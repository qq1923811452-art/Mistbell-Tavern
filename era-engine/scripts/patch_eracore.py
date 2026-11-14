"""给 EraCore 打三个幂等补丁（Mistbell 的 Android 单宿主需要）：

1. EncodingHandler：无 BOM 且非 UTF-8 时，先按当前语言编码做「严格」解码，失败再落回 Shift-JIS。
   中文汉化包的 csv 若为 GBK，被当 SJIS 解会整片乱码。
2. LangManager：暴露 internal CurrentEncoding（原字段 private，外部不可见）。
3. SafCompat：本地路径的存在性判断回退到 IGameDirAccessor 的逐段大小写容错。
   引擎用硬编码大写名推导别名表（TALENT.CSV → TALENT.als），而 eraTW 包里真实名是
   Talent.als；Android 私有目录区分大小写，裸 File.Exists 恒 false → .als 别名表不加载，
   「音楽知識/愛欲/反発刻印」等中文关键字全部报「无法解析的标识符」。

用法：python3 patch_eracore.py <era-core 根目录>
"""

import sys
from pathlib import Path

# ---------- 1. EncodingHandler ----------
ENC_OLD = """\t\tcatch (DecoderFallbackException)
\t\t{
\t\t\t// UTF-8 失败 → 假设 SHIFT-JIS（CP 932 对绝大多数日文文件可解码）
\t\t\treturn SplitLines(shiftjisEncoding.GetString(bytes));
\t\t}
"""

ENC_NEW = """\t\tcatch (DecoderFallbackException)
\t\t{
\t\t\t// MISTBELL_PATCH_LANG_ENCODING：先按当前语言配置的 ANSI 编码做「严格」解码。
\t\t\t// 注意 Encoding.GetEncoding(936) 默认 ReplacementFallback 不抛异常，
\t\t\t// 必须显式装 DecoderFallback.ExceptionFallback，否则 SJIS 兜底永远走不到。
\t\t\ttry
\t\t\t{
\t\t\t\tvar langEncoding = LangManager.CurrentEncoding;
\t\t\t\tif (langEncoding != null && langEncoding.CodePage != shiftjisEncoding.CodePage)
\t\t\t\t{
\t\t\t\t\tvar strictLang = Encoding.GetEncoding(
\t\t\t\t\t\tlangEncoding.CodePage,
\t\t\t\t\t\tEncoderFallback.ReplacementFallback,
\t\t\t\t\t\tDecoderFallback.ExceptionFallback);
\t\t\t\t\ttry
\t\t\t\t\t{
\t\t\t\t\t\treturn SplitLines(strictLang.GetString(bytes));
\t\t\t\t\t}
\t\t\t\t\tcatch (DecoderFallbackException)
\t\t\t\t\t{
\t\t\t\t\t}
\t\t\t\t}
\t\t\t}
\t\t\tcatch
\t\t\t{
\t\t\t}
\t\t\t// UTF-8 失败 → 假设 SHIFT-JIS（CP 932 对绝大多数日文文件可解码）
\t\t\treturn SplitLines(shiftjisEncoding.GetString(bytes));
\t\t}
"""

# ---------- 2. LangManager ----------
LANG_OLD = "\tstatic Encoding lang = null!;"
LANG_NEW = (
    "\tstatic Encoding lang = null!;"
    "\n\n\t// MISTBELL_PATCH_LANG_ENCODING：给 EncodingHandler 暴露当前语言编码。"
    "\n\tinternal static Encoding? CurrentEncoding => lang;"
)

# ---------- 3. SafCompat ----------
SAF_OLD = """    internal static bool FileExists(string path)
    {
        if (!IsContentUri(path)) return File.Exists(path);
        return Program.DirAccessor.FileExists(path);
    }

    internal static bool DirectoryExists(string path)
    {
        if (!IsContentUri(path)) return Directory.Exists(path);
        return Program.DirAccessor.DirectoryExists(path);
    }"""

SAF_NEW = """    // MISTBELL_PATCH_CASE_INSENSITIVE：本地路径也回退到 accessor 的逐段大小写容错。
    // 引擎对固定名资源用硬编码大写（TALENT.CSV 推导 TALENT.als），而 eraTW 包里真实名是
    // Talent.als；Android 私有目录区分大小写，裸 File.Exists 恒 false → 别名表不加载
    // → 「音楽知識/愛欲/反発刻印」等中文关键字全部报「无法解析的标识符」。
    internal static bool FileExists(string path)
    {
        if (IsContentUri(path)) return Program.DirAccessor.FileExists(path);
        if (File.Exists(path)) return true;
        return Program.DirAccessor?.FileExists(path) ?? false;
    }

    internal static bool DirectoryExists(string path)
    {
        if (IsContentUri(path)) return Program.DirAccessor.DirectoryExists(path);
        if (Directory.Exists(path)) return true;
        return Program.DirAccessor?.DirectoryExists(path) ?? false;
    }"""


def patch(path: Path, marker: str, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print("already patched " + label)
        return
    if old not in text:
        raise SystemExit("anchor not found: " + label)
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print("patched " + label)


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "era-engine/upstream")
    patch(
        root / "EraCore.Core/Shared/Runtime/Utils/LangManager.cs",
        "CurrentEncoding",
        LANG_OLD,
        LANG_NEW,
        "LangManager.cs",
    )
    patch(
        root / "EraCore.Core/Shared/Runtime/Utils/EncodingHandler.cs",
        "MISTBELL_PATCH_LANG_ENCODING",
        ENC_OLD,
        ENC_NEW,
        "EncodingHandler.cs",
    )
    patch(
        root / "EraCore.Core/Shared/Runtime/Utils/SafCompat.cs",
        "MISTBELL_PATCH_CASE_INSENSITIVE",
        SAF_OLD,
        SAF_NEW,
        "SafCompat.cs",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
