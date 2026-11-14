using Android.Content;
using Android.OS;
using Microsoft.Win32.SafeHandles;
using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;

namespace Mistbell.Era.Host;

/// <summary>
/// 干净、原子的游戏包解包管理：不改任何 CSV/ERB 原文件内容（EraCore 的 FileSystemGameDirAccessor 已内置大小写容错）。
/// </summary>
internal static class EraGameStore
{
    private const int MaxEntries = 60000;
    private const long MaxBytes = 1500000000L;
    private const string PrefsName = "mistbell-era-host";
    private const string PrefLastGame = "last_game_dir";
    private const string PrefLastName = "last_game_name";
    private const string PrefLastVersion = "last_game_version";

    static EraGameStore()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
    }

    public static string GamesRoot(Context ctx) => Path.Combine(ctx.FilesDir!.AbsolutePath, "games");

    public static string? LastGameDir(Context ctx)
    {
        var prefs = ctx.GetSharedPreferences(PrefsName, FileCreationMode.Private);
        return prefs?.GetString(PrefLastGame, null);
    }

    public static string Install(Context ctx, ParcelFileDescriptor fd, string displayName, string version)
    {
        if (fd == null)
            throw new ArgumentNullException(nameof(fd));

        var name = Sanitize(string.IsNullOrWhiteSpace(displayName) ? "era" : displayName);
        if (TryReuseExisting(ctx, name, version, out var existingDir))
            return existingDir;

        var tempZip = Path.Combine(ctx.CacheDir!.AbsolutePath, $"era-import-{Guid.NewGuid():N}.zip");
        try
        {
            int rawFd;
            using (var cloned = fd.Dup())
                rawFd = cloned.DetachFd();
            using (var input = new FileStream(new SafeFileHandle((IntPtr)rawFd, ownsHandle: true), FileAccess.Read))
            using (var output = File.Create(tempZip))
                input.CopyTo(output);

            return InstallFromZipInternal(ctx, tempZip, name, version);
        }
        finally
        {
            try { File.Delete(tempZip); } catch { }
        }
    }

    public static string InstallFromPath(Context ctx, string zipPath, string displayName, string version)
    {
        if (string.IsNullOrEmpty(zipPath) || !File.Exists(zipPath))
            throw new FileNotFoundException("game zip not found: " + zipPath);

        var name = Sanitize(string.IsNullOrWhiteSpace(displayName) ? "era" : displayName);
        if (TryReuseExisting(ctx, name, version, out var existingDir))
            return existingDir;

        return InstallFromZipInternal(ctx, zipPath, name, version);
    }

    private static bool TryReuseExisting(Context ctx, string name, string version, out string existingDir)
    {
        existingDir = string.Empty;
        if (string.IsNullOrEmpty(version))
            return false;

        var prefs = ctx.GetSharedPreferences(PrefsName, FileCreationMode.Private);
        var lastName = prefs?.GetString(PrefLastName, null);
        var lastVersion = prefs?.GetString(PrefLastVersion, null);
        var lastDir = prefs?.GetString(PrefLastGame, null);
        if (lastName == name && lastVersion == version && !string.IsNullOrEmpty(lastDir) && Directory.Exists(lastDir))
        {
            EraBootstrap.Trace($"Reusing installed game: {lastDir} (version={version})");
            existingDir = lastDir;
            return true;
        }
        return false;
    }

    private static string InstallFromZipInternal(Context ctx, string zipPath, string name, string version)
    {
        var root = GamesRoot(ctx);
        Directory.CreateDirectory(root);

        var target = Path.Combine(root, name);
        var token = Guid.NewGuid().ToString("N");
        var staging = Path.Combine(root, $".staging-{token}");
        var backup = Path.Combine(root, $".backup-{token}");

        var backupCreated = false;
        var targetReplaced = false;

        try
        {
            Extract(zipPath, staging);
            var resolved = ResolveGameDir(staging);

            // 若已有旧版安装，保留玩家存档（sav/ 与根目录 save*.sav）并移到新版根目录
            if (Directory.Exists(target))
            {
                CopySaves(target, resolved);
                Directory.Move(target, backup);
                backupCreated = true;
            }

            if (string.Equals(resolved, staging, StringComparison.Ordinal))
            {
                Directory.Move(staging, target);
            }
            else
            {
                Directory.Move(resolved, target);
                try { Directory.Delete(staging, true); } catch { }
            }
            targetReplaced = true;

            if (backupCreated)
            {
                try { Directory.Delete(backup, true); } catch { }
            }

            var prefs = ctx.GetSharedPreferences(PrefsName, FileCreationMode.Private);
            prefs?.Edit()
                ?.PutString(PrefLastName, name)
                ?.PutString(PrefLastGame, target)
                ?.PutString(PrefLastVersion, version)
                ?.Apply();

            EraBootstrap.Trace($"Installed game to {target} (version={version})");
            return target;
        }
        catch
        {
            if (targetReplaced && Directory.Exists(target))
            {
                try { Directory.Delete(target, true); } catch { }
            }
            if (backupCreated && Directory.Exists(backup) && !Directory.Exists(target))
            {
                try { Directory.Move(backup, target); } catch { }
            }
            throw;
        }
        finally
        {
            if (Directory.Exists(staging))
            {
                try { Directory.Delete(staging, true); } catch { }
            }
        }
    }

    private static void Extract(string zipPath, string target)
    {
        var encoding = DetectLegacyEntryEncoding(zipPath);
        var targetFull = Path.GetFullPath(target);
        Directory.CreateDirectory(targetFull);
        var sep = Path.DirectorySeparatorChar;
        var prefix = targetFull.EndsWith(sep.ToString(), StringComparison.Ordinal) ? targetFull : targetFull + sep;

        using var zip = ZipFile.Open(zipPath, ZipArchiveMode.Read, encoding);
        if (zip.Entries.Count > MaxEntries)
            throw new InvalidDataException("too many entries: " + zip.Entries.Count);

        long total = 0;
        int written = 0;
        foreach (var entry in zip.Entries)
        {
            var rel = entry.FullName.Replace('\\', '/').TrimStart('/');
            if (rel.Length == 0 || rel.EndsWith("/", StringComparison.Ordinal))
                continue;
            if (rel.StartsWith("__MACOSX/", StringComparison.OrdinalIgnoreCase)
                || rel.EndsWith("/.DS_Store", StringComparison.OrdinalIgnoreCase)
                || string.Equals(rel, ".DS_Store", StringComparison.OrdinalIgnoreCase))
                continue;

            var segments = rel.Split('/', StringSplitOptions.RemoveEmptyEntries);
            if (segments.Length == 0 || segments.Any(s => s == "..") || rel.Contains(':', StringComparison.Ordinal))
                continue;

            var cleanRel = string.Join('/', segments);
            var dest = Path.GetFullPath(Path.Combine(targetFull, cleanRel));
            if (!dest.StartsWith(prefix, StringComparison.Ordinal) || dest.Length > prefix.Length + 400)
                continue;

            total += entry.Length;
            if (total > MaxBytes)
                throw new InvalidDataException("archive too large");

            var parent = Path.GetDirectoryName(dest);
            if (!string.IsNullOrEmpty(parent))
                Directory.CreateDirectory(parent);
            entry.ExtractToFile(dest, true);
            written++;
        }

        if (written == 0)
            throw new InvalidDataException("archive has no files");
    }

    /// <summary>
    /// UTF-8 标志位开启的条目会由 ZipArchive 自动按 UTF-8 解码；
    /// 对未打 UTF-8 标志的旧条目，在 CP932（日文 Windows）和 CP936（中文 Windows）间精准判定。
    /// </summary>
    private static Encoding DetectLegacyEntryEncoding(string zipPath)
    {
        var utf8 = new UTF8Encoding(false, true);
        if (AllEntriesClean(zipPath, utf8, out _))
            return utf8;

        var cp932 = Encoding.GetEncoding(932, EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);
        var cp936 = Encoding.GetEncoding(936, EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);

        var ok932 = AllEntriesClean(zipPath, cp932, out var hasKana932);
        var ok936 = AllEntriesClean(zipPath, cp936, out _);

        if (ok932 && !ok936)
            return cp932;
        if (ok936 && !ok932)
            return cp936;
        if (ok932 && ok936)
            return hasKana932 ? cp932 : cp936;

        return Encoding.GetEncoding(932);
    }

    private static bool AllEntriesClean(string zipPath, Encoding encoding, out bool hasKana)
    {
        hasKana = false;
        try
        {
            using var zip = ZipFile.Open(zipPath, ZipArchiveMode.Read, encoding);
            foreach (var entry in zip.Entries)
            {
                foreach (var ch in entry.FullName)
                {
                    if (ch == '\uFFFD')
                        return false;
                    if ((ch >= '\u3040' && ch <= '\u30FF') || (ch >= '\uFF66' && ch <= '\uFF9F'))
                        hasKana = true;
                }
            }
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static void CopySaves(string oldRoot, string newRoot)
    {
        try
        {
            if (TryFindChild(oldRoot, "sav", out var oldSav))
            {
                var destSav = TryFindChild(newRoot, "sav", out var existingSav)
                    ? existingSav!
                    : Path.Combine(newRoot, "sav");
                Directory.CreateDirectory(destSav);
                foreach (var file in Directory.GetFiles(oldSav, "*", SearchOption.AllDirectories))
                {
                    var rel = Path.GetRelativePath(oldSav, file);
                    var dest = Path.Combine(destSav, rel);
                    Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                    File.Copy(file, dest, overwrite: true);
                }
            }

            foreach (var file in Directory.GetFiles(oldRoot, "*.sav", SearchOption.TopDirectoryOnly))
            {
                var name = Path.GetFileName(file);
                if (!name.StartsWith("save", StringComparison.OrdinalIgnoreCase))
                    continue;
                File.Copy(file, Path.Combine(newRoot, name), overwrite: true);
            }
        }
        catch (Exception ex)
        {
            EraBootstrap.Trace("Preserve saves warning: " + ex.Message);
        }
    }

    private static string ResolveGameDir(string target)
    {
        if (HasGameData(target))
            return target;

        foreach (var first in Directory.GetDirectories(target))
        {
            if (HasGameData(first))
                return first;
            foreach (var second in Directory.GetDirectories(first))
            {
                if (HasGameData(second))
                    return second;
            }
        }
        return target;
    }

    private static bool TryFindChild(string parent, string name, out string? found)
    {
        found = null;
        try
        {
            var exact = Path.Combine(parent, name);
            if (Directory.Exists(exact))
            {
                found = exact;
                return true;
            }
            foreach (var dir in Directory.GetDirectories(parent))
            {
                if (string.Equals(Path.GetFileName(dir), name, StringComparison.OrdinalIgnoreCase))
                {
                    found = dir;
                    return true;
                }
            }
        }
        catch { }
        return false;
    }

    private static bool HasGameData(string dir) =>
        TryFindChild(dir, "csv", out _) || TryFindChild(dir, "erb", out _);

    private static string Sanitize(string raw)
    {
        var sb = new StringBuilder(raw.Length);
        foreach (var ch in raw)
        {
            if (char.IsLetterOrDigit(ch) || ch == '-' || ch == '_')
                sb.Append(ch);
            else if (ch == ' ' || ch == '.')
                sb.Append('_');
        }
        var name = sb.ToString().Trim('_');
        if (name.Length == 0)
            name = "era";
        return name.Length > 64 ? name.Substring(0, 64) : name;
    }
}
