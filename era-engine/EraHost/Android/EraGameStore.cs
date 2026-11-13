using Android.Content;
using Android.OS;
using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Globalization;
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
			// 已有安装也要跑一遍；这些修复是幂等的，老用户不该因为「版本没变」错过。
			NormalizeCsvFileNames(lastDir);
			NormalizeCsvWhitespace(lastDir);
			RepairMissingCflags(lastDir);
			DropBrokenLazyConfig(lastDir);
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
		NormalizeCsvFileNames(gameDir);
		NormalizeCsvWhitespace(gameDir);
		RepairMissingCflags(gameDir);
		DropBrokenLazyConfig(gameDir);
		prefs?.Edit()?.PutString(PrefLastGame, gameDir)?.PutString(PrefLastVersion, version)?.Apply();
		return gameDir;
	}

	/// <summary>同一 APK 内的调用方直接给路径：不解 FD，避开部分 ROM 的跨应用 FD 限制。</summary>
	public static string InstallFromPath(Context ctx, string zipPath, string displayName, string version)
	{
		if (string.IsNullOrEmpty(zipPath) || !File.Exists(zipPath))
			throw new FileNotFoundException("game zip not found: " + zipPath);

		var name = Sanitize(string.IsNullOrWhiteSpace(displayName) ? "era" : displayName);
		var root = GamesRoot(ctx);
		Directory.CreateDirectory(root);
		var target = Path.Combine(root, name);

		var prefs = ctx.GetSharedPreferences(PrefsName, FileCreationMode.Private);
		var lastVersion = prefs?.GetString(PrefLastVersion, null);
		var lastDir = prefs?.GetString(PrefLastGame, null);
		if (!string.IsNullOrEmpty(version) && version == lastVersion
			&& !string.IsNullOrEmpty(lastDir) && Directory.Exists(lastDir))
		{
			// 已有安装也要跑一遍；这些修复是幂等的，老用户不该因为「版本没变」错过。
			NormalizeCsvFileNames(lastDir);
			NormalizeCsvWhitespace(lastDir);
			RepairMissingCflags(lastDir);
			DropBrokenLazyConfig(lastDir);
			return lastDir;
		}

		Extract(zipPath, target);
		NormalizeTopLevelDirs(target);
		var gameDir = ResolveGameDir(target);
		NormalizeCsvFileNames(gameDir);
		NormalizeCsvWhitespace(gameDir);
		RepairMissingCflags(gameDir);
		DropBrokenLazyConfig(gameDir);
		prefs?.Edit()?.PutString(PrefLastGame, gameDir)?.PutString(PrefLastVersion, version)?.Apply();
		return gameDir;
	}

	/// <summary>
	/// 引擎的懒加载只有在 lazyloading.cfg 存在时才启用：
	/// 缺 cfg ⇒ 每次启动全量解析 16601 个 ERB（实测 171 秒），且从不写缓存。
	/// 这里按 erb/ 下的实际目录生成 cfg，让首次加载后能写出 lazyloading*.bin 供下次复用。
	/// </summary>
	// 引擎用 File.Exists 按字面名加载这些 CSV；Android 私有目录区分大小写，
	// 包里若是小写（cflag.csv）就会整张表读不到，进而报「没有 xx 的定义」。
	private static readonly string[] ExpectedCsvNames =
	{
		"ABL.CSV", "BASE.CSV", "CFLAG.CSV", "CSTR.CSV", "DAY.CSV", "EQUIP.CSV", "EX.CSV",
		"EXP.CSV", "FLAG.CSV", "GAMEBASE.CSV", "GLOBAL.CSV", "GLOBALS.CSV", "ITEM.CSV",
		"MARK.CSV", "MONEY.CSV", "PALAM.CSV", "SAVESTR.CSV", "SOURCE.CSV", "STAIN.CSV",
		"STR.CSV", "STRNAME.CSV", "TALENT.CSV", "TCVAR.CSV", "TEQUIP.CSV", "TFLAG.CSV",
		"TIME.CSV", "TRAIN.CSV", "TSTR.CSV", "VariableSize.CSV",
	};

	private static void NormalizeCsvFileNames(string gameDir)
	{
		try
		{
			if (!TryFindChild(gameDir, "csv", out var csvDir))
				return;
			var files = Directory.GetFiles(csvDir);
			int renamed = 0;
			foreach (var expected in ExpectedCsvNames)
			{
				var exact = Path.Combine(csvDir, expected);
				if (File.Exists(exact))
					continue;
				foreach (var file in files)
				{
					if (!string.Equals(Path.GetFileName(file), expected, StringComparison.OrdinalIgnoreCase))
						continue;
					try
					{
						File.Move(file, exact);
						renamed++;
						EraBootstrap.Trace("csv renamed: " + Path.GetFileName(file) + " -> " + expected);
					}
					catch (Exception ex)
					{
						EraBootstrap.Trace("csv rename failed: " + ex.Message);
					}
					break;
				}
			}
			if (renamed > 0)
				EraBootstrap.Trace("csv renamed total: " + renamed);
		}
		catch (Exception ex)
		{
			EraBootstrap.Trace("csv normalize failed: " + ex.Message);
		}
	}

	/// <summary>
	/// 包里 Chara/*.csv 引用了 CFLAG.CSV 中不存在的 flag 名时，引擎会把赋值丢掉并报警告。
	/// 这里按「索引,名字」追加缺失项（沿用原文件编码），让这些 flag 真正生效而不是被忽略。
	/// </summary>
	private static void RepairMissingCflags(string gameDir)
	{
		try
		{
			if (!TryFindChild(gameDir, "csv", out var csvDir))
				return;
			var cflagPath = Path.Combine(csvDir, "CFLAG.CSV");
			if (!File.Exists(cflagPath))
				return;

			var (encoding, text) = ReadTextAuto(cflagPath);
			var names = new HashSet<string>(StringComparer.Ordinal);
			var maxIndex = -1;
			foreach (var line in text.Split('\n'))
			{
				var tokens = line.Trim().Split(',');
				if (tokens.Length < 2)
					continue;
				if (int.TryParse(tokens[0].Trim(), out var index))
					maxIndex = Math.Max(maxIndex, index);
				names.Add(NormalizeName(tokens[1]));
			}
			EraBootstrap.Trace("cflag table: " + names.Count + " names, max index " + maxIndex
				+ ", file " + new FileInfo(cflagPath).Length + " bytes");
			if (names.Count == 0)
			{
				EraBootstrap.Trace("cflag repair skipped: empty name table");
				return;
			}

			var missing = new SortedSet<string>(StringComparer.Ordinal);
			foreach (var file in Directory.GetFiles(gameDir, "*.csv", SearchOption.AllDirectories))
			{
				var normalized = file.Replace('\\', '/');
				if (normalized.IndexOf("/Chara/", StringComparison.OrdinalIgnoreCase) < 0)
					continue;
				var (_, body) = ReadTextAuto(file);
				foreach (var line in body.Split('\n'))
				{
					var tokens = line.Trim().Split(',');
					if (tokens.Length < 2)
						continue;
					var key = tokens[0].Trim();
					if (!string.Equals(key, "フラグ", StringComparison.Ordinal)
						&& !string.Equals(key, "CFLAG", StringComparison.OrdinalIgnoreCase))
						continue;
					var rawName = tokens[1];
					var name = NormalizeName(rawName);
					if (name.Length == 0 || int.TryParse(name, out _))
						continue;
					if (!names.Contains(name))
						missing.Add(rawName.Trim());
				}
			}

			if (missing.Count == 0)
			{
				EraBootstrap.Trace("cflag repair: nothing missing");
				return;
			}

			var builder = new StringBuilder(text);
			if (!text.EndsWith("\n", StringComparison.Ordinal))
				builder.Append('\n');
			foreach (var name in missing)
			{
				maxIndex++;
				builder.Append(maxIndex).Append(',').Append(name).Append('\n');
			}
			File.WriteAllText(cflagPath, builder.ToString(), encoding);
			EraBootstrap.Trace("cflag repair appended " + missing.Count + ": " + string.Join(",", missing));
		}
		catch (Exception ex)
		{
			EraBootstrap.Trace("cflag repair failed: " + ex.Message);
		}
	}

	/// <summary>
	/// 引擎建名字表时<b>不 Trim</b> 字段，查表时字段也不 Trim：
	/// 包里若写成 `0, 口調`（逗号后带空格），表里的键是 " 口調"，Chara 里查 "口調" 就全部落空。
	/// 这里把常量表每行按逗号切分后逐字段 trim，仅在确有变化时回写。
	/// </summary>
	private static void NormalizeCsvWhitespace(string gameDir)
	{
		try
		{
			if (!TryFindChild(gameDir, "csv", out var csvDir))
				return;
			int changedFiles = 0, changedLines = 0;
			foreach (var expected in ExpectedCsvNames)
			{
				var path = Path.Combine(csvDir, expected);
				if (!File.Exists(path))
					continue;
				var (encoding, text) = ReadTextAuto(path);
				var newline = text.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : "\n";
				var lines = text.Replace("\r\n", "\n").Split('\n');
				var dirty = false;
				for (int i = 0; i < lines.Length; i++)
				{
					var line = lines[i];
					var trimmed = line.Trim();
					if (trimmed.Length == 0 || trimmed.StartsWith(";", StringComparison.Ordinal))
					{
						if (line != line.TrimEnd())
						{
							lines[i] = line.TrimEnd();
							dirty = true;
							changedLines++;
						}
						continue;
					}
					var parts = line.Split(',');
					var rebuilt = string.Join(",", parts.Select(p => p.Trim()));
					if (rebuilt != line)
					{
						lines[i] = rebuilt;
						dirty = true;
						changedLines++;
					}
				}
				if (!dirty)
					continue;
				File.WriteAllText(path, string.Join(newline, lines), encoding);
				changedFiles++;
			}
			if (changedFiles > 0)
				EraBootstrap.Trace("csv whitespace normalized: " + changedFiles + " files, " + changedLines + " lines");
			else
				EraBootstrap.Trace("csv whitespace: clean");
		}
		catch (Exception ex)
		{
			EraBootstrap.Trace("csv whitespace failed: " + ex.Message);
		}
	}

	/// <summary>CSV 可能是 GBK(CHINESE_HANS) 或 UTF-8；按内容猜，回写保持原编码。</summary>
	/// <summary>与引擎补丁一致的键归一化：去全角空格 + NFKC。</summary>
	private static string NormalizeName(string raw) =>
		string.IsNullOrEmpty(raw)
			? string.Empty
			: raw.Trim(' ', '\t', '\u3000', '\u00A0').Normalize(NormalizationForm.FormKC).Trim();

	private static (Encoding Encoding, string Text) ReadTextAuto(string path)
	{
		var bytes = File.ReadAllBytes(path);
		try
		{
			var utf8 = new UTF8Encoding(false, true);
			return (utf8, utf8.GetString(bytes));
		}
		catch
		{
			var gbk = Encoding.GetEncoding(936);
			return (gbk, gbk.GetString(bytes));
		}
	}

	/// <summary>
	/// 我们自己生成过 lazyloading.cfg，但那会让引擎跳过这些目录的 ERB，
	/// 脚本在加载期引用其中函数就会报「无法解析的标识符」。没有 bins 说明缓存从未建成，
	/// 直接把这份 cfg 删掉，恢复完整加载。
	/// </summary>
	private static void DropBrokenLazyConfig(string gameDir)
	{
		try
		{
			var cfg = Path.Combine(gameDir, "lazyloading.cfg");
			if (!File.Exists(cfg))
				return;
			var bins = File.Exists(Path.Combine(gameDir, "lazyloading.bin"))
				&& File.Exists(Path.Combine(gameDir, "lazyloadingfiles.bin"));
			if (bins)
				return;
			File.Delete(cfg);
			EraBootstrap.Trace("dropped lazyloading.cfg (no cache bins; full load restored)");
		}
		catch (Exception ex)
		{
			EraBootstrap.Trace("drop lazyloading.cfg failed: " + ex.Message);
		}
	}

	private static void EnsureLazyLoadingConfig(string gameDir)
	{
		try
		{
			var cfg = Path.Combine(gameDir, "lazyloading.cfg");
			if (File.Exists(cfg))
				return;
			if (!TryFindChild(gameDir, "erb", out var erbDir))
				return;

			var dirs = new List<string> { erbDir };
			dirs.AddRange(Directory.GetDirectories(erbDir, "*", SearchOption.AllDirectories));
			var lines = new List<string>(dirs.Count);
			foreach (var dir in dirs)
			{
				var rel = Path.GetRelativePath(gameDir, dir);
				if (rel == ".")
					continue;
				lines.Add(rel + Path.DirectorySeparatorChar);
			}
			if (lines.Count == 0)
				return;
			lines.Sort(StringComparer.Ordinal);
			File.WriteAllLines(cfg, lines, new UTF8Encoding(false));
			EraBootstrap.Trace("lazyloading.cfg generated: " + lines.Count + " folders");
		}
		catch (Exception ex)
		{
			EraBootstrap.Trace("lazyloading.cfg failed: " + ex.Message);
		}
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

		// 有的包会套两层目录（eraTW/<game>/csv），限制在两层内查找。
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

	/// <summary>era 系目录名在 Windows 上大小写不敏感，Android 私有目录区分大小写；统一成小写。</summary>
	private static readonly string[] KnownDirs = { "csv", "erb", "sav", "font", "sound", "resources", "dat", "debug" };

	private static void NormalizeTopLevelDirs(string root)
	{
		try
		{
			foreach (var dir in Directory.GetDirectories(root))
			{
				var name = Path.GetFileName(dir);
				foreach (var expected in KnownDirs)
				{
					if (!string.Equals(name, expected, StringComparison.OrdinalIgnoreCase) || name == expected)
						continue;
					var target = Path.Combine(root, expected);
					if (Directory.Exists(target))
						continue;
					try { Directory.Move(dir, target); } catch { }
					break;
				}
			}
		}
		catch { }
	}

	/// <summary>只在根目录下按大小写不敏感找同名子目录。</summary>
	private static bool TryFindChild(string parent, string name, out string found)
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

	private static bool HasGameData(string dir) => TryFindChild(dir, "csv", out _) || TryFindChild(dir, "erb", out _);

	/// <summary>把根目录前两层的目录名拼成一段说明，失败时回传前端。</summary>
	public static string DescribeLayout(string root)
	{
		try
		{
			var first = Directory.GetDirectories(root).Select(Path.GetFileName).Take(12).ToArray();
			var builder = new StringBuilder();
			builder.Append("dirs=").Append(string.Join(",", first));
			foreach (var dir in Directory.GetDirectories(root).Take(4))
			{
				var name = Path.GetFileName(dir);
				var second = Directory.GetDirectories(dir).Select(Path.GetFileName).Take(6).ToArray();
				if (second.Length > 0)
					builder.Append(" | ").Append(name).Append("/{").Append(string.Join(",", second)).Append('}');
			}
			return builder.ToString();
		}
		catch (Exception ex)
		{
			return "layout error: " + ex.Message;
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
