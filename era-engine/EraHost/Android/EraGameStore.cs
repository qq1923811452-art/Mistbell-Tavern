using Android.Content;
using Android.OS;
using System;
using System.IO;
using System.IO.Compression;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace Mistbell.Era.Host;

/// <summary>
/// 游戏包落盘：Mistbell 把用户导入的 ZIP 以文件描述符传进来，引擎解到自己的私有目录。
/// 不做哈希校验（游戏包随时会更新），只做路径穿越与体积上限的硬防护。
/// </summary>
internal static class EraGameStore
{
	private const int MaxEntries = 60000;
	private const long MaxBytes = 1500000000L;
	private const string PrefsName = "mistbell-era-host";
	private const string PrefLastGame = "last_game_dir";
	private const string PrefLastVersion = "last_game_version";

	public static string GamesRoot(Context ctx) => Path.Combine(ctx.FilesDir!.AbsolutePath, "games");

	public static string LastGameDir(Context ctx)
	{
		var prefs = ctx.GetSharedPreferences(PrefsName, FileCreationMode.Private);
		return prefs?.GetString(PrefLastGame, null);
	}

	/// <summary>把 ZIP 解到 games/&lt;name&gt;，返回真正的游戏根目录（会自动剥掉单层根目录）。</summary>
	public static string Install(Context ctx, ParcelFileDescriptor fd, string displayName, string version)
	{
		if (fd == null)
			throw new ArgumentNullException(nameof(fd));

		var name = Sanitize(string.IsNullOrWhiteSpace(displayName) ? "era" : displayName);
		var root = GamesRoot(ctx);
		Directory.CreateDirectory(root);
		var target = Path.Combine(root, name);

		// 前端每次绑定都会带包版本号；版本没变就直接复用上次解好的目录，避免反复解 16k 文件。
		var prefs = ctx.GetSharedPreferences(PrefsName, FileCreationMode.Private);
		var lastVersion = prefs?.GetString(PrefLastVersion, null);
		var lastDir = prefs?.GetString(PrefLastGame, null);
		if (!string.IsNullOrEmpty(version) && version == lastVersion
			&& !string.IsNullOrEmpty(lastDir) && Directory.Exists(lastDir))
		{
			return lastDir;
		}

		var tempZip = Path.Combine(ctx.CacheDir!.AbsolutePath, "era-import.zip");
		// Java 的 AutoCloseInputStream 在 .NET 侧不是 Stream；干脆拿底层 fd 自己包 FileStream。
		int rawFd;
		using (var cloned = fd.Dup())
			rawFd = cloned.DetachFd();
		using (var input = new FileStream(new SafeFileHandle((IntPtr)rawFd, ownsHandle: true), FileAccess.Read))
		using (var output = File.Create(tempZip))
			input.CopyTo(output);

		try
		{
			Extract(tempZip, target);
		}
		finally
		{
			try { File.Delete(tempZip); } catch { }
		}

		var gameDir = ResolveGameDir(target);
		prefs?.Edit()?.PutString(PrefLastGame, gameDir)?.PutString(PrefLastVersion, version)?.Apply();
		return gameDir;
	}

	private static void Extract(string zipPath, string target)
	{
		var encoding = NeedsLegacyEncoding(zipPath) ? Encoding.GetEncoding(932) : new UTF8Encoding(false);
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
			if (rel.Contains("..", StringComparison.Ordinal) || rel.Contains(':', StringComparison.Ordinal))
				continue;

			var dest = Path.GetFullPath(Path.Combine(targetFull, rel));
			if (!dest.StartsWith(prefix, StringComparison.Ordinal) || dest.Length > prefix.Length + 400)
				continue;

			// 更新包时保留存档
			if (IsSavePath(rel) && File.Exists(dest))
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

	private static bool IsSavePath(string rel)
	{
		var lower = rel.ToLowerInvariant();
		return lower.StartsWith("sav/", StringComparison.Ordinal)
			|| (lower.IndexOf('/') < 0 && lower.EndsWith(".sav", StringComparison.Ordinal));
	}

	private static bool NeedsLegacyEncoding(string zipPath)
	{
		using var zip = ZipFile.OpenRead(zipPath);
		foreach (var entry in zip.Entries)
		{
			if (entry.FullName.IndexOf('\uFFFD') >= 0)
				return true;
		}
		return false;
	}

	/// <summary>很多包会多套一层目录（eraTW/erb、eraTW/csv）。这里剥到含 csv+erb 的那层。</summary>
	private static string ResolveGameDir(string target)
	{
		if (HasGameData(target))
			return target;

		var dirs = Directory.GetDirectories(target);
		if (dirs.Length == 1 && HasGameData(dirs[0]))
			return dirs[0];

		foreach (var dir in dirs)
		{
			if (HasGameData(dir))
				return dir;
		}
		return target;
	}

	private static bool HasGameData(string dir)
	{
		try
		{
			return Directory.Exists(Path.Combine(dir, "csv")) || Directory.Exists(Path.Combine(dir, "erb"));
		}
		catch
		{
			return false;
		}
	}

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
