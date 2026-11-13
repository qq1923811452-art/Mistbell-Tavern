using Android.Util;
using MinorShift.Emuera;
using MinorShift.Emuera.GameView;
using MinorShift.Emuera.Runtime.Utils;
using MinorShift.Emuera.UI.Ewp;
using MinorShift.Emuera.UI.Headless;
using EuConfig = MinorShift.Emuera.Runtime.Config.Config;
using System;
using System.Threading;

namespace Mistbell.Era.Host;

/// <summary>
/// 无窗宿主（Android 版）。与上游 HeadlessHost 的差别只有两点：
/// 1. 由我们自己持有 <see cref="HeadlessSurface"/>，服务销毁时能真正结束泵循环；
/// 2. 启动前按前端上报的视口调整虚拟画布（Android 屏幕比例与桌面不同）。
/// </summary>
internal static class EraHeadlessHost
{
	private const string Tag = "EraEngine";

	private static readonly object Gate = new();
	private static HeadlessSurface surface;
	private static bool running;

	public static int Run()
	{
		lock (Gate)
		{
			if (running)
				return 0;
			running = true;
		}

		try
		{
			var ctx = new HeadlessSynchronizationContext();
			SynchronizationContext.SetSynchronizationContext(ctx);
			PlatformInterop.DoEvents = () => ctx.PumpOnce(false);

			var w = Math.Max(320, EuConfig.WindowX);
			var h = Math.Max(240, EuConfig.WindowY);
			var hostSurface = new HeadlessSurface(ctx, w, h);
			lock (Gate) { surface = hostSurface; }

			var console = new EmueraConsole(hostSurface);
			hostSurface.Attach(console);

			var ewp = new EwpHost(hostSurface, Program.EwpPort);
			// TCP 模式：必须先握手再开游戏，否则首帧事件会早于订阅丢失。
			if (Program.EwpPort > 0)
				ewp.AttachConsole(console);
			ewp.Start();

			if (Program.EwpPort > 0)
			{
				long deadline = Environment.TickCount64 + 30000;
				while (!ewp.GameStartReady && Environment.TickCount64 < deadline)
					ctx.PumpOnce(true);
				if (!ewp.GameStartReady)
					Log.Warn(Tag, "front-end handshake timeout (30s); starting anyway");
			}

			var init = console.Initialize();
			while (!init.IsCompleted)
				ctx.PumpOnce(true);
			init.GetAwaiter().GetResult();

			while (!hostSurface.QuitRequested)
				ctx.PumpOnce(true);

			ewp.NotifyProcessExit();
			return 0;
		}
		catch (Exception ex)
		{
			EraBootstrap.LastError = "host failed: " + ex.GetType().Name + ": " + ex.Message;
			EraBootstrap.Trace(EraBootstrap.LastError);
			EraBootstrap.Trace(ex.StackTrace ?? "");
			return 1;
		}
		finally
		{
			lock (Gate)
			{
				running = false;
				surface = null;
			}
		}
	}

	/// <summary>服务销毁时调用：让泵循环退出，等待引擎收尾。</summary>
	public static void RequestStop()
	{
		HeadlessSurface target;
		lock (Gate) { target = surface; }
		if (target == null)
			return;
		try { target.Close(); } catch (Exception ex) { Log.Warn(Tag, "close failed: " + ex.Message); }
	}
}
