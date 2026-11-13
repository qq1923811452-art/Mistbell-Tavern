using Android.Util;
using MinorShift.Emuera;
using MinorShift.Emuera.Runtime.Config;
using MinorShift.Emuera.Runtime.Config.JSON;
using MinorShift.Emuera.Runtime.Utils;
using MinorShift.Emuera.Runtime.Utils.EvilMask;
using MinorShift.Emuera.UI;
using MinorShift.Emuera.UI.Ewp;
using SkiaSharp;
using System;
using System.Drawing;
using System.Globalization;
using System.IO;

namespace Mistbell.Era.Host;

/// <summary>
/// 无窗引擎引导：与 Emuera.Headless 的启动序列同源，只把「进程入口」换成 Android 服务。
/// 引擎只做两件事：跑剧本、把 legacy.line / input.request / frame.commit 通过 EWP 写给前端。
/// </summary>
internal static class EraBootstrap
{
	private const string Tag = "EraEngine";
	private static string logPath;

	/// <summary>最近一次失败的结构化说明，随退出码一起回传前端。</summary>
	public static string LastError { get; internal set; } = string.Empty;

	/// <summary>把启动过程写到外部私有目录，便于在没有 logcat 时远程排查。</summary>
	public static void InitLog(Android.Content.Context ctx)
	{
		try
		{
			var dir = ctx.GetExternalFilesDir(null)?.AbsolutePath ?? ctx.FilesDir?.AbsolutePath;
			if (!string.IsNullOrEmpty(dir))
				logPath = Path.Combine(dir, "era-engine.log");
		}
		catch { }
	}

	public static void Trace(string line)
	{
		Log.Info(Tag, line);
		if (logPath == null)
			return;
		try
		{
			File.AppendAllText(logPath, DateTime.Now.ToString("HH:mm:ss.fff") + " " + line + "\n");
		}
		catch { }
	}

	public static int Run(string gameDir, int port)
	{
		try
		{
			return RunInner(gameDir, port);
		}
		catch (Exception ex)
		{
			LastError = "FATAL " + ex.GetType().Name + ": " + ex.Message;
			Trace(LastError);
			Trace(ex.StackTrace ?? "");
			return 1;
		}
	}

	private static int RunInner(string gameDir, int port)
	{
		Trace("run: dir=" + gameDir + " port=" + port);
		if (string.IsNullOrEmpty(gameDir) || !Directory.Exists(gameDir))
		{
			LastError = "game dir missing: " + gameDir;
			Trace(LastError);
			return 2;
		}

		// 内核里 WorkingDir 取自 CWD；Android 进程 CWD 是 "/"，不可写，先切到游戏目录。
		try { Directory.SetCurrentDirectory(gameDir); } catch (Exception ex) { Log.Warn(Tag, "chdir failed: " + ex.Message); }

		SegmentCulture();
		// eraTW 的 csv/erb 大量是 Shift-JIS（CP932），必须先注册代码页提供程序。
		System.Text.Encoding.RegisterProvider(System.Text.CodePagesEncodingProvider.Instance);

		try { Console.OutputEncoding = new System.Text.UTF8Encoding(false); } catch { }

		Program.InitHeadless(gameDir);
		Program.EwpMode = true;
		Program.EwpHeadlessMode = true;
		Program.EwpPort = port;
		Program.EwpMsgpack = false;

		if (!Directory.Exists(Program.CsvDir) || !Directory.Exists(Program.ErbDir))
		{
			LastError = "missing csv/erb; " + EraGameStore.DescribeLayout(gameDir);
			Trace("missing_game_data " + LastError);
			return 2;
		}

		Trace("loading config");
		ConfigData.Instance.LoadConfig();
		JSONConfig.Load();
		Lang.LoadLanguageFiles();
		Lang.SetLanguage();

		ApplyAndroidOverrides();
		Trace("loading fonts from " + Program.FontDir);
		LoadFonts();

		EwpOut.InitIO(deferWriter: true, msgpack: false);
		InitPlatformInterop();

		Trace("entering headless host");
		var code = EraHeadlessHost.Run();
		Trace("headless host returned " + code);
		return code;
	}

	private static void SegmentCulture()
	{
		CultureInfo.CurrentCulture = CultureInfo.InvariantCulture;
		CultureInfo.DefaultThreadCurrentCulture = CultureInfo.InvariantCulture;
	}

	/// <summary>游戏包 font/ 目录里的字体优先注册；缺字体时内核会退回内置 BIZ UDGothic。</summary>
	/// <summary>
	/// 包里的 emuera.config 是给 Windows 配的（1800x973 横屏、字体黑体、TEXTRENDERER），
	/// 在 Android 上要按手机实际能力改三处，否则图像层被禁用、字体全部走回退、布局按横屏算。
	/// </summary>
	private static void ApplyAndroidOverrides()
	{
		try
		{
			Trace("overrides: drawing=" + Config.TextDrawingMode + " win=" + Config.WindowX + "x" + Config.WindowY + " font=" + Config.FontName);
			Config.TextDrawingMode = TextDrawingMode.SKIASHARP;
			if (Config.WindowX <= 0 || Config.WindowY <= 0 || Config.WindowX > Config.WindowY)
			{
				Config.WindowX = 900;
				Config.WindowY = 1600;
			}
			if (RegisterEmbeddedFont())
				Config.FontName = FallbackFontName;
			Trace("overrides applied: drawing=" + Config.TextDrawingMode + " win=" + Config.WindowX + "x" + Config.WindowY + " font=" + Config.FontName);
		}
		catch (Exception ex)
		{
			Trace("overrides failed: " + ex.Message);
		}
	}

	private const string FallbackFontName = "BIZ UDGothic";

	private static bool RegisterEmbeddedFont()
	{
		try
		{
			var assembly = typeof(EraBootstrap).Assembly;
			using var stream = assembly.GetManifestResourceStream("MinorShift.Emuera.Properties.fonts.BIZUDGothic-Regular.ttf");
			if (stream == null)
			{
				Trace("embedded font missing");
				return false;
			}
			var path = Path.Combine(Path.GetTempPath(), "BIZUDGothic-Regular.ttf");
			if (!File.Exists(path))
			{
				using var file = new FileStream(path, FileMode.Create, FileAccess.Write);
				stream.CopyTo(file);
			}
			GlobalStatic.Pfc.AddFontFile(path);
			Trace("embedded font registered: " + path);
			return GlobalStatic.Pfc.Contains(FallbackFontName);
		}
		catch (Exception ex)
		{
			Trace("embedded font failed: " + ex.Message);
			return false;
		}
	}

	private static void LoadFonts()
	{
		try
		{
			if (!Directory.Exists(Program.FontDir))
				return;
			foreach (var pattern in new[] { "*.ttf", "*.otf", "*.ttc" })
			{
				foreach (var file in Directory.GetFiles(Program.FontDir, pattern, SearchOption.AllDirectories))
				{
					try { GlobalStatic.Pfc.AddFontFile(file); } catch { }
				}
			}
		}
		catch (Exception ex)
		{
			Log.Warn(Tag, "load fonts failed: " + ex.Message);
		}
	}

	private static void InitPlatformInterop()
	{
		PlatformInterop.ShowMessage = text => EwpHost.EmitDialogEvent("message", text, "");
		PlatformInterop.ShowMessageWithTitle = (text, title) => EwpHost.EmitDialogEvent("message", text, title);
		PlatformInterop.ShowQuestion = (text, title) =>
		{
			EwpHost.EmitDialogEvent("question", text, title);
			return false;
		};
		PlatformInterop.GetProductVersion = () =>
			typeof(EraBootstrap).Assembly.GetName().Version?.ToString() ?? "0.0.0.0";
		PlatformInterop.GetMousePosition = () => Point.Empty;
		PlatformInterop.SetClipboardText = _ => { };
		PlatformInterop.DebugLog = msg => Log.Debug(Tag, msg);
		PlatformInterop.MeasureText = MeasureText;
		PlatformInterop.MeasureTextWidth = (text, fontName, fontSize, style) => MeasureText(text, fontName, fontSize, style).Width;

		MinorShift.Emuera.Runtime.Utils.Sound.Factory = () => new MinorShift.Emuera.Runtime.Utils.Sound();
		GlobalStatic.Bgm = MinorShift.Emuera.Runtime.Utils.Sound.Factory();
	}

	private static (float Width, float Height) MeasureText(string text, string fontName, float fontSize, FontStyle style)
	{
		try
		{
			var tf = FontFactory.GetFallbackTypefaceForChar(' ') ?? SKTypeface.Default;
			using var font = new SKFont(tf, fontSize);
			using var paint = new SKPaint { Typeface = tf, TextSize = fontSize };
			var metrics = font.Metrics;
			return (paint.MeasureText(text ?? ""), -metrics.Ascent + metrics.Descent);
		}
		catch
		{
			return (0f, 0f);
		}
	}
}
