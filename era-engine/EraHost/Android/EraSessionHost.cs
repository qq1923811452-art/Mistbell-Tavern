using EraCore.Hosting;
using MinorShift.Emuera.Files;
using MinorShift.Emuera.Input;
using MinorShift.Emuera.Output;
using MinorShift.Emuera.Sessions;
using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;

namespace Mistbell.Era.Host;

[JsonSerializable(typeof(HostClientCmd))]
[JsonSerializable(typeof(HostStatusMsg))]
[JsonSerializable(typeof(HostErrorMsg))]
[JsonSerializable(typeof(HostStoppedMsg))]
[JsonSourceGenerationOptions(DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull)]
internal partial class HostWireContext : JsonSerializerContext
{
}

internal sealed record HostClientCmd(string? type, string? value, long? generation);
internal sealed record HostStatusMsg(string type, string state, string? detail);
internal sealed record HostErrorMsg(string type, string code, string message);
internal sealed record HostStoppedMsg(string type, int code, string? reason);

internal sealed class EraSessionHost : IAsyncDisposable
{
    private readonly string gameDir;
    private readonly Action<string, string?> notifyStatus;
    private readonly CancellationTokenSource lifetimeCts = new();
    private readonly object listenerGate = new();

    private TcpListener? listener;
    private GameHost? gameHost;
    private HostStream? startupStream;
    private Task<CoreSessionStartResult>? startTask;
    private long latestGeneration;
    private int shutdownRequested;

    public int Port { get; private set; }
    public string GameDir => gameDir;
    public string PackKey { get; }

    public EraSessionHost(string gameDir, string packKey, Action<string, string?> notifyStatus)
    {
        this.gameDir = gameDir;
        PackKey = packKey;
        this.notifyStatus = notifyStatus;
    }

    public async Task StartAsync(int port, CancellationToken serviceToken)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(lifetimeCts.Token, serviceToken);
        var ct = linked.Token;

        BindListener(port);

        var options = new CoreSessionOptions(gameDir, new FileSystemGameDirAccessor())
        {
            DialectProfileId = "eratw",
            FullDiffOnFirstTurn = false,
            TeardownTimeout = TimeSpan.FromSeconds(4),
        };
        var host = new GameHost(options);
        gameHost = host;

        notifyStatus("starting", gameDir);
        EraBootstrap.Trace($"GameHost.StartAsync dir={gameDir} profile=eratw port={Port}");

        startTask = host.StartAsync(ct);

        // 后台持续接受前端连接：支持首次连接 + 切设置/返回后的秒重连
        var acceptLoop = AcceptLoopAsync(ct);
        var startResult = await startTask.ConfigureAwait(false);

        if (!startResult.Succeeded)
        {
            var err = $"{startResult.ErrorCode ?? "START_FAILED"}: {startResult.ErrorMessage ?? "unknown"}";
            EraBootstrap.Trace($"GameHost.StartAsync failed: {err}");
            DumpPostMortem(host);
            notifyStatus("error", err);
            await acceptLoop.ConfigureAwait(false);
            return;
        }

        // 输出轴（Snapshot/OpenStream）只有在 StartAsync 成功后才可用；
        // OpenStream 内部是「先订阅、后取快照」，所以此处打开不会漏帧。
        startupStream = host.OpenStream();
        EraBootstrap.Trace("GameHost started successfully");
        notifyStatus("running", $"port={Port}");
        await acceptLoop.ConfigureAwait(false);
    }

    /// <summary>
    /// 启动失败时把控制台最后若干行与游戏目录里的 emuera.log 尾部写进我们的日志，
    /// 否则只能看到兜底的「游戏初始化失败。」。
    /// </summary>
    private void DumpPostMortem(GameHost host)
    {
        try
        {
            var snapshot = host.Snapshot;
            var texts = snapshot.lines
                .Select(line => string.Concat(line.entries.SelectMany(e => e.segments).Select(seg => seg.text)))
                .Where(text => !string.IsNullOrWhiteSpace(text))
                .TakeLast(15);
            foreach (var text in texts)
                EraBootstrap.Trace("console: " + text);
        }
        catch (Exception ex)
        {
            EraBootstrap.Trace("snapshot dump failed: " + ex.Message);
        }

        try
        {
            var logPath = Path.Combine(gameDir, "emuera.log");
            if (File.Exists(logPath))
            {
                foreach (var line in File.ReadAllLines(logPath).TakeLast(25))
                    EraBootstrap.Trace("emuera.log: " + line);
            }
            else
            {
                EraBootstrap.Trace("emuera.log not found at " + logPath);
            }
        }
        catch (Exception ex)
        {
            EraBootstrap.Trace("emuera.log read failed: " + ex.Message);
        }
    }

    public void RebindPort(int newPort)
    {
        if (newPort <= 0 || newPort == Port)
            return;
        BindListener(newPort);
        notifyStatus("running", $"port={Port}");
    }

    private void BindListener(int port)
    {
        lock (listenerGate)
        {
            try { listener?.Stop(); } catch { }
            var tcp = new TcpListener(IPAddress.Loopback, port);
            tcp.Start();
            listener = tcp;
            Port = ((IPEndPoint)tcp.LocalEndpoint).Port;
            EraBootstrap.Trace($"TCP listening on 127.0.0.1:{Port}");
        }
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpListener? current;
            lock (listenerGate) { current = listener; }
            if (current == null)
                break;

            TcpClient client;
            try
            {
                client = await current.AcceptTcpClientAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                // RebindPort 关闭了旧 listener：循环回去取新 listener
                if (!ct.IsCancellationRequested)
                    continue;
                break;
            }
            catch (SocketException ex)
            {
                if (ct.IsCancellationRequested)
                    break;
                EraBootstrap.Trace($"Accept SocketException: {ex.SocketErrorCode}");
                await Task.Delay(100, ct).ConfigureAwait(false);
                continue;
            }

            await HandleClientAsync(client, ct).ConfigureAwait(false);
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken ct)
    {
        client.NoDelay = true;
        EraBootstrap.Trace("Front-end connected");

        using var _ = client;
        using var netStream = client.GetStream();
        using var writer = new StreamWriter(netStream, new UTF8Encoding(false), leaveOpen: true) { AutoFlush = true };
        using var reader = new StreamReader(netStream, Encoding.UTF8, leaveOpen: true);
        var writeGate = new SemaphoreSlim(1, 1);

        async Task SendLineAsync(string json)
        {
            await writeGate.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                await writer.WriteLineAsync(json.AsMemory(), ct).ConfigureAwait(false);
            }
            finally
            {
                writeGate.Release();
            }
        }

        try
        {
            // 若还在加载期间连入，先告知状态并等待启动结果
            var pendingStart = startTask;
            if (pendingStart != null && !pendingStart.IsCompleted)
            {
                var statusJson = JsonSerializer.Serialize(
                    new HostStatusMsg("status", "starting", gameDir),
                    HostWireContext.Default.HostStatusMsg);
                await SendLineAsync(statusJson).ConfigureAwait(false);
            }

            if (pendingStart != null)
            {
                EraBootstrap.Trace("awaiting GameHost.StartAsync...");
                // 启动期心跳：进程被杀时最后一行心跳就是死前状态（内存 + 进度）。
                using var heartbeatCts = new CancellationTokenSource();
                _ = Task.Run(async () =>
                {
                    try
                    {
                    while (!heartbeatCts.IsCancellationRequested)
                    {
                        await Task.Delay(2000, heartbeatCts.Token).ConfigureAwait(false);
                        try
                        {
                            long managedMb = GC.GetTotalMemory(false) / (1024 * 1024);
                            long rssMb = 0;
                            using (var proc = System.Diagnostics.Process.GetCurrentProcess())
                            {
                                rssMb = proc.WorkingSet64 / (1024 * 1024);
                            }
                            EraBootstrap.Trace($"starting... managed={managedMb}MB rss={rssMb}MB");
                        }
                        catch
                        {
                        }
                    }
                    }
                    catch
                    {
                    }
                });
                var startResult = await pendingStart.ConfigureAwait(false);
                EraBootstrap.Trace($"GameHost.StartAsync returned: ok={startResult.Succeeded} code={startResult.ErrorCode}");
                if (!startResult.Succeeded)
                {
                    var errJson = JsonSerializer.Serialize(
                        new HostErrorMsg("error", startResult.ErrorCode ?? "INIT_FAILED", startResult.ErrorMessage ?? "start failed"),
                        HostWireContext.Default.HostErrorMsg);
                    await SendLineAsync(errJson).ConfigureAwait(false);
                    return;
                }
            }

            var host = gameHost;
            if (host == null)
                return;

            // 首次连接接管预订阅的 startupStream；后续重连开新流并先推 Snapshot
            HostStream? ownedInitial = Interlocked.Exchange(ref startupStream, null);
            using var stream = ownedInitial ?? host.OpenStream();

            var snapshot = stream.InitialSnapshot;
            Volatile.Write(ref latestGeneration, snapshot.generation);
            EraBootstrap.Trace($"snapshot: lines={snapshot.lines.Count} gen={snapshot.generation} rev={snapshot.revision} bgImages={snapshot.bgImages?.Count ?? 0}");
            string envelope;
            try
            {
                envelope = SnapshotJsonWriter.SerializeEnvelope(snapshot);
            }
            catch (Exception ex)
            {
                EraBootstrap.Trace("snapshot serialize failed: " + ex);
                throw;
            }
            EraBootstrap.Trace($"snapshot envelope bytes={envelope.Length}");
            await SendLineAsync(envelope).ConfigureAwait(false);
            EraBootstrap.Trace("snapshot sent");

            using var clientCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            var readTask = ReadClientCommandsAsync(reader, host, clientCts.Token);

            var turns = stream.Turns;
            if (turns != null)
            {
                await foreach (var turn in turns.ReadAllAsync(clientCts.Token).ConfigureAwait(false))
                {
                    Volatile.Write(ref latestGeneration, turn.Record.generation);
                    await SendLineAsync($"{{\"type\":\"turn\",\"payload\":{turn.Wire}}}").ConfigureAwait(false);
                }
            }

            await clientCts.CancelAsync().ConfigureAwait(false);
            await readTask.ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
        }
        catch (IOException)
        {
            // 前端关闭 Socket（比如切去设置或聊天），保留 GameHost 常驻
        }
        catch (Exception ex)
        {
            EraBootstrap.Trace($"Client loop error: {ex}");
            try
            {
                var errJson = JsonSerializer.Serialize(
                    new HostErrorMsg("error", ex.GetType().Name, ex.Message),
                    HostWireContext.Default.HostErrorMsg);
                await SendLineAsync(errJson).ConfigureAwait(false);
            }
            catch { }
        }
        finally
        {
            EraBootstrap.Trace("Front-end disconnected (host stays warm unless stopped)");
        }
    }

    private async Task ReadClientCommandsAsync(StreamReader reader, GameHost host, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
                if (line == null)
                    break;
                if (string.IsNullOrWhiteSpace(line))
                    continue;

                var cmd = JsonSerializer.Deserialize(line, HostWireContext.Default.HostClientCmd);
                if (cmd == null)
                    continue;

                switch (cmd.type)
                {
                    case "input":
                    {
                        var currentGen = Volatile.Read(ref latestGeneration);
                        if (cmd.generation.HasValue && cmd.generation.Value != currentGen)
                        {
                            EraBootstrap.Trace($"Drop stale input: clientGen={cmd.generation} currentGen={currentGen}");
                            continue;
                        }
                        host.Submit(new InputFrame(Value: cmd.value ?? string.Empty));
                        break;
                    }
                    case "stop":
                    {
                        EraBootstrap.Trace("Received explicit stop command");
                        Interlocked.Exchange(ref shutdownRequested, 1);
                        await StopAsync().ConfigureAwait(false);
                        return;
                    }
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (IOException)
        {
        }
        catch (Exception ex)
        {
            EraBootstrap.Trace($"Command read error: {ex.Message}");
        }
    }

    public bool WasExplicitStopRequested => Volatile.Read(ref shutdownRequested) == 1;

    public async Task StopAsync()
    {
        try { await lifetimeCts.CancelAsync().ConfigureAwait(false); } catch { }
        lock (listenerGate)
        {
            try { listener?.Stop(); } catch { }
            listener = null;
        }
        var host = gameHost;
        if (host != null)
        {
            try { await host.StopAsync().ConfigureAwait(false); } catch { }
        }
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        Interlocked.Exchange(ref startupStream, null)?.Dispose();
        var host = Interlocked.Exchange(ref gameHost, null);
        if (host != null)
        {
            try { await host.DisposeAsync().ConfigureAwait(false); } catch { }
        }
        lifetimeCts.Dispose();
    }
}
