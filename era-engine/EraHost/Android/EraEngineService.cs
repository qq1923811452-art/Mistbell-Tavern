using Android.App;
using Android.Content;
using Android.OS;
using Android.Util;
using System;
using System.Threading;

namespace Mistbell.Era.Host;

/// <summary>
/// 给 Mistbell 绑定的引擎服务（无界面）。生命周期＝绑定生命周期：
/// 客户端 bind 时带「ZIP 文件描述符 + 端口 + 包名」，服务解包后在本进程里跑 EWP，
/// 前端连 127.0.0.1:port 读事件、回输入。解绑即停。
/// </summary>
[Service(Name = "com.mistbell.era.EraEngineService", Exported = true)]
public sealed class EraEngineService : Service
{
	private const string Tag = "EraEngine";
	public const int MsgStatus = 1;
	public const int MsgStop = 2;

	private readonly object gate = new();
	private Messenger replyTo;
	private Thread worker;
	private bool running;
	private Messenger inbound;

	public override IBinder OnBind(Intent intent)
	{
		replyTo = intent?.GetParcelableExtra("replyTo") as Messenger;
		var port = intent?.GetIntExtra("port", 0) ?? 0;
		var gameName = intent?.GetStringExtra("gameName");
		var packVersion = intent?.GetStringExtra("packVersion");
		var zipPath = intent?.GetStringExtra("zipPath");
		var zipFd = intent?.GetParcelableExtra("zipFd") as ParcelFileDescriptor;

		if (inbound == null)
			inbound = new Messenger(new Handler(Looper.MainLooper, new StopCallback(this)));

		bool alreadyRunning;
		lock (gate) { alreadyRunning = running; }

		if (!alreadyRunning)
		{
			lock (gate) { running = true; }
			worker = new Thread(() => Work(gameName, packVersion, port, zipFd, zipPath))
			{
				IsBackground = true,
				Name = "era-engine",
			};
			worker.Start();
		}
		else
		{
			SendStatus("running", null);
		}

		return inbound.Binder;
	}

	private sealed class StopCallback : Java.Lang.Object, Handler.ICallback
	{
		private readonly EraEngineService owner;
		public StopCallback(EraEngineService owner) => this.owner = owner;

		public bool HandleMessage(Message msg)
		{
			if (msg.What == MsgStop)
			{
				EraHeadlessHost.RequestStop();
				return true;
			}
			return false;
		}
	}

	private void Work(string gameName, string packVersion, int port, ParcelFileDescriptor zipFd, string zipPath)
	{
		try
		{
			if (port <= 0)
				port = 5177;

			SendStatus("preparing", null);

			string gameDir;
			if (zipFd != null)
			{
				SendStatus("importing", null);
				gameDir = EraGameStore.Install(this, zipFd, gameName, packVersion);
				SendStatus("imported", gameDir);
			}
			else if (!string.IsNullOrEmpty(zipPath))
			{
				SendStatus("importing", null);
				gameDir = EraGameStore.InstallFromPath(this, zipPath, gameName, packVersion);
				SendStatus("imported", gameDir);
			}
			else
			{
				gameDir = EraGameStore.LastGameDir(this);
			}

			if (string.IsNullOrEmpty(gameDir) || !System.IO.Directory.Exists(gameDir))
			{
				SendStatus("error", "no_game_installed");
				return;
			}

			EraBootstrap.InitLog(this);
			SendStatus("starting", gameDir);
			var code = EraBootstrap.Run(gameDir, port);
			EraBootstrap.Trace("engine exit code " + code);
			SendStatus("stopped", "exit=" + code);
		}
		catch (Exception ex)
		{
			EraBootstrap.InitLog(this);
			EraBootstrap.Trace("engine failed: " + ex);
			Log.Error(Tag, "engine failed: " + ex);
			SendStatus("error", ex.GetType().Name + ": " + ex.Message);
		}
		finally
		{
			lock (gate) { running = false; }
		}
	}

	private void SendStatus(string state, string detail)
	{
		var messenger = replyTo;
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
		EraHeadlessHost.RequestStop();
		base.OnDestroy();
		// 引擎收尾（关闭 EWP 写手/像素流）可能阻塞，交给系统回收进程，避免主线程卡死。
		new Handler(Looper.MainLooper).PostDelayed(() =>
		{
			try { Android.OS.Process.KillProcess(Android.OS.Process.MyPid()); } catch { }
		}, 1200);
	}
}
