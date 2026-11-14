using Android.Content;
using Android.Util;
using System;
using System.IO;
using System.Text;

namespace Mistbell.Era.Host;

/// <summary>
/// 进程级诊断日志：只由 :era 进程单方写入，带锁和大小轮转，避免无限膨胀或双进程交错写入。
/// </summary>
internal static class EraBootstrap
{
    private const string Tag = "EraEngine";
    private const long MaxLogBytes = 1024 * 1024; // 1 MiB
    private static readonly object LogGate = new();
    private static string? logPath;

    public static void InitLog(Context ctx)
    {
        try
        {
            var dir = ctx.GetExternalFilesDir(null)?.AbsolutePath ?? ctx.FilesDir?.AbsolutePath;
            if (string.IsNullOrEmpty(dir))
                return;

            var path = Path.Combine(dir, "era-engine.log");
            lock (LogGate)
            {
                logPath = path;
                RotateIfNeededLocked(path);
            }
        }
        catch
        {
        }
    }

    public static void Trace(string line)
    {
        Log.Info(Tag, line);
        lock (LogGate)
        {
            if (logPath == null)
                return;
            try
            {
                RotateIfNeededLocked(logPath);
                var stamped = $"{DateTime.Now:HH:mm:ss.fff} {line}\n";
                File.AppendAllText(logPath, stamped, new UTF8Encoding(false));
            }
            catch
            {
            }
        }
    }

    private static void RotateIfNeededLocked(string path)
    {
        if (!File.Exists(path))
            return;
        var info = new FileInfo(path);
        if (info.Length <= MaxLogBytes)
            return;
        var backup = path + ".prev";
        try
        {
            if (File.Exists(backup))
                File.Delete(backup);
            File.Move(path, backup);
        }
        catch
        {
            try { File.WriteAllText(path, string.Empty); } catch { }
        }
    }
}
