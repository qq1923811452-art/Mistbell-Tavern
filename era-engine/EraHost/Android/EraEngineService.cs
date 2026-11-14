using Android.App;
using Android.Content;
using Android.OS;
using Android.Util;
using System;
using System.Threading;
using System.Threading.Tasks;

namespace Mistbell.Era.Host;

[Service(Name = "com.mistbell.era.EraEngineService", Exported = true)]
public sealed class EraEngineService : Service
{
    private const string Tag = "EraEngine";
    public const int MsgStatus = 1;
    public const int MsgStop = 2;

    private readonly object gate = new();
    private readonly SemaphoreSlim sessionMutex = new(1, 1);

    private Messenger? replyTo;
    private Messenger? inbound;
    private EraSessionHost? activeHost;
    private CancellationTokenSource? serviceCts;
    private Task? runningTask;

    public override void OnCreate()
    {
        base.OnCreate();
        EraBootstrap.InitLog(this);
        EraBootstrap.Trace("EraEngineService.OnCreate");
    }

    public override IBinder? OnBind(Intent? intent)
    {
        return HandleIntent(intent);
    }

    public override void OnRebind(Intent? intent)
    {
        base.OnRebind(intent);
        HandleIntent(intent);
    }

    public override bool OnUnbind(Intent? intent)
    {
        // 返回 true 允许进程存活期间后续触发 OnRebind
        return true;
    }

    private IBinder? HandleIntent(Intent? intent)
    {
        lock (gate)
        {
            replyTo = intent?.GetParcelableExtra("replyTo") as Messenger ?? replyTo;
            inbound ??= new Messenger(new Handler(Looper.MainLooper!, new StopCallback(this)));
        }

        var port = intent?.GetIntExtra("port", 0) ?? 0;
        var gameName = intent?.GetStringExtra("gameName") ?? "era";
        var packVersion = intent?.GetStringExtra("packVersion") ?? string.Empty;
        var zipPath = intent?.GetStringExtra("zipPath");
        var zipFd = intent?.GetParcelableExtra("zipFd") as ParcelFileDescriptor;
        var forceRestart = intent?.GetBooleanExtra("forceRestart", false) ?? false;

        var packKey = $"{gameName}::{packVersion}";

        lock (gate)
        {
            var host = activeHost;
            if (!forceRestart && host != null && runningTask != null && !runningTask.IsCompleted && host.PackKey == packKey)
            {
                if (port > 0)
                    host.RebindPort(port);
                else
                    SendStatus("running", $"port={host.Port}");
                zipFd?.Close();
                return inbound?.Binder;
            }
        }

        _ = Task.Run(() => StartSessionAsync(gameName, packVersion, packKey, port, zipFd, zipPath));
        return inbound?.Binder;
    }

    private async Task StartSessionAsync(
        string gameName,
        string packVersion,
        string packKey,
        int port,
        ParcelFileDescriptor? zipFd,
        string? zipPath)
    {
        await sessionMutex.WaitAsync().ConfigureAwait(false);
        try
        {
            EraBootstrap.InitLog(this);

            // 若已有旧会话（换包或强制重启），先干净销毁
            await DisposeCurrentHostLockedAsync().ConfigureAwait(false);

            if (port <= 0)
                port = 5177;

            SendStatus("preparing", null);

            string? gameDir;
            try
            {
                if (zipFd != null)
                {
                    using (zipFd)
                    {
                        SendStatus("importing", null);
                        gameDir = EraGameStore.Install(this, zipFd, gameName, packVersion);
                    }
                }
                else if (!string.IsNullOrEmpty(zipPath))
                {
                    SendStatus("importing", null);
                    gameDir = EraGameStore.InstallFromPath(this, zipPath, gameName, packVersion);
                }
                else
                {
                    gameDir = EraGameStore.LastGameDir(this);
                }

                // 无论新装还是复用，都要把最终游戏目录回报给前端：
                // 立绘用 file(gameDir)/<src> 取图，拿不到路径就永远不显示。
                if (!string.IsNullOrEmpty(gameDir))
                    SendStatus("imported", gameDir);
            }
            catch (Exception ex)
            {
                EraBootstrap.Trace($"Import failed: {ex}");
                SendStatus("error", $"import_failed: {ex.Message}");
                return;
            }

            if (string.IsNullOrEmpty(gameDir) || !System.IO.Directory.Exists(gameDir))
            {
                SendStatus("error", "no_game_installed");
                return;
            }

            var cts = new CancellationTokenSource();
            var host = new EraSessionHost(gameDir, packKey, SendStatus);
            lock (gate)
            {
                serviceCts = cts;
                activeHost = host;
                runningTask = RunHostAsync(host, port, cts.Token);
            }
        }
        finally
        {
            sessionMutex.Release();
        }
    }

    private async Task RunHostAsync(EraSessionHost host, int port, CancellationToken ct)
    {
        try
        {
            await host.StartAsync(port, ct).ConfigureAwait(false);
            SendStatus("stopped", host.WasExplicitStopRequested ? "exit=0 user_stop" : "exit=0");
        }
        catch (System.OperationCanceledException)
        {
            SendStatus("stopped", "exit=0 cancelled");
        }
        catch (Exception ex)
        {
            var detail = $"exit=1 FATAL {ex.GetType().Name}: {ex.Message}";
            EraBootstrap.Trace(detail);
            EraBootstrap.Trace(ex.StackTrace ?? string.Empty);
            SendStatus("stopped", detail);
        }
    }

    private async Task DisposeCurrentHostLockedAsync()
    {
        EraSessionHost? oldHost;
        CancellationTokenSource? oldCts;
        Task? oldTask;
        lock (gate)
        {
            oldHost = activeHost;
            oldCts = serviceCts;
            oldTask = runningTask;
            activeHost = null;
            serviceCts = null;
            runningTask = null;
        }

        if (oldCts != null)
        {
            try { await oldCts.CancelAsync().ConfigureAwait(false); } catch { }
        }
        if (oldHost != null)
        {
            await oldHost.DisposeAsync().ConfigureAwait(false);
        }
        if (oldTask != null)
        {
            try { await oldTask.WaitAsync(TimeSpan.FromSeconds(3)).ConfigureAwait(false); } catch { }
        }
        oldCts?.Dispose();
    }

    private sealed class StopCallback : Java.Lang.Object, Handler.ICallback
    {
        private readonly EraEngineService owner;
        public StopCallback(EraEngineService owner) => this.owner = owner;

        public bool HandleMessage(Message msg)
        {
            if (msg.What == MsgStop)
            {
                _ = Task.Run(async () =>
                {
                    await owner.sessionMutex.WaitAsync().ConfigureAwait(false);
                    try { await owner.DisposeCurrentHostLockedAsync().ConfigureAwait(false); }
                    finally { owner.sessionMutex.Release(); }
                });
                return true;
            }
            return false;
        }
    }

    private void SendStatus(string state, string? detail)
    {
        Messenger? messenger;
        lock (gate) { messenger = replyTo; }
        if (messenger == null)
            return;
        try
        {
            var bundle = new Bundle();
            bundle.PutString("state", state);
            if (detail != null)
                bundle.PutString("detail", detail);
            var msg = Message.Obtain(null, MsgStatus);
            msg.Data = bundle;
            messenger.Send(msg);
        }
        catch (Exception ex)
        {
            Log.Warn(Tag, "status send failed: " + ex.Message);
        }
    }

    public override void OnDestroy()
    {
        EraBootstrap.Trace("EraEngineService.OnDestroy");
        try
        {
            Task.Run(async () =>
            {
                await sessionMutex.WaitAsync().ConfigureAwait(false);
                try { await DisposeCurrentHostLockedAsync().ConfigureAwait(false); }
                finally { sessionMutex.Release(); }
            }).Wait(TimeSpan.FromSeconds(4));
        }
        catch (Exception ex)
        {
            EraBootstrap.Trace("OnDestroy cleanup exception: " + ex.Message);
        }
        base.OnDestroy();
    }
}
