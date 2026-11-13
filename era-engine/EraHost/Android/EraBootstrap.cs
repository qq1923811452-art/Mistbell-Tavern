using Android.Util;
using MinorShift.Emuera;
using MinorShift.Emuera.Runtime.Config;
using MinorShift.Emuera.Runtime.Config.JSON;
using MinorShift.Emuera.Runtime.Utils;
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

	public static int Run(string gameDir, int port)
	{
		if (string.IsNullOrEmpty(gameDir) || !Directory.Exists(gameDir))
		{
			Log.Error(Tag, "game directory missing: " + gameDir);
			return 2;
		}

		// 内核里 WorkingDir 取自 CWD；Android 进程 CWD 是 "/"，不可写，先切到游戏目录。
		try { Directory.SetCurrentDirectory(gameDir); } catch (Exception ex) { Log.Warn(Tag, "chdir failed: " + ex.Message); }

		SegmentCulture();

		try { Console.OutputEncoding = new System.Text.UTF8Encoding(false); } catch { }

		Program.InitHeadless(gameDir);
		Program.EwpMode = true;
		Program.EwpHeadlessMode = true;
		Program.EwpPort = port;
		Program.EwpMsgpack = false;

		if (!Directory.Exists(Program.CsvDir) || !Directory.Exists(Program.ErbDir))
		{
			Log.Error(Tag, "missing_game_data csv=" + Program.CsvDir + " erb=" + Program.ErbDir);
			return 2;
		}

		ConfigData.Instance.LoadConfig();
		JSONConfig.Load();
		Lang.LoadLanguageFiles();
		Lang.SetLanguage();

		LoadFonts();

		EwpOut.InitIO(deferWriter: true, msgpack: false);
		InitPlatformInterop();

		Log.Info(Tag, "engine starting, port=" + port + ", dir=" + gameDir);
		return EraHeadlessHost.Run();
	}

	private static void SegmentCulture()
	{
		CultureInfo.CurrentCulture = CultureInfo.InvariantCulture;
		CultureInfo.DefaultThreadCurrentCulture = CultureInfo.InvariantCulture;
	}

	/// <summary>游戏包 font/ 目录里的字体优先注册；缺字体时内核会退回内置 BIZ UDGothic。</summary>
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
			var tf = FontFactory.GetFallbackTypefaceForChar(' ', fontName) ?? SKTypeface.Default;
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
