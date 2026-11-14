"""给 EraCore 的 EncodingHandler 打一个幂等补丁：无 BOM 且非 UTF-8 时，先按「当前语言配置的
ANSI 编码」解码（CHINESE_HANS=GBK/936、JAPANESE=SJIS/932、KOREAN=949、CHINESE_HANT=950），
失败再退回 Shift-JIS。

背景：EraCore 原本只有「严格 UTF-8 → Shift-JIS」两条路。中文汉化包（eraTW 画蛇添足 CH 版）
的 csv/*.CSV 是 GBK，被当成 Shift-JIS 解码后名字全是乱码，于是 TALENT/MARK 这类按名字查表
全部落空，运行期报「无法解析的标识符」。

用法：python3 patch_eracore.py <era-core 根目录>
"""

import sys
from pathlib import Path

OLD = """\t\tcatch (DecoderFallbackException)
\t\t{
\t\t\t// UTF-8 失败 → 假设 SHIFT-JIS（CP 932 对绝大多数日文文件可解码）
\t\t\treturn SplitLines(shiftjisEncoding.GetString(bytes));
\t\t}
"""

NEW = """\t\tcatch (DecoderFallbackException)
\t\t{
\t\t\t// MISTBELL_PATCH_LANG_ENCODING：先按当前语言配置的 ANSI 编码做「严格」解码。
\t\t\t// 中文包（useLanguage=CHINESE_HANS→GBK/936）的 csv/*.CSV 被当 SJIS 解会整片乱码，
\t\t\t// 导致 TALENT/MARK 等按名字查表全部失败。
\t\t\t// 注意：Encoding.GetEncoding(936) 默认是 ReplacementFallback，非法字节约等于沉默；
\t\t\t// 这里显式包一层 ExceptionFallback，解不了再退回 Shift-JIS（混编包里的日文残留文件靠它）。
\t\t\t// 日文包 lang==932，与下面的兜底同编码，行为不变。
\t\t\ttry
\t\t\t{
\t\t\t\tvar langEncoding = LangManager.lang;
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


def patch(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if "MISTBELL_PATCH_LANG_ENCODING" in text:
        return False
    if OLD not in text:
        raise SystemExit("anchor not found in " + str(path))
    path.write_text(text.replace(OLD, NEW, 1), encoding="utf-8")
    return True


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "era-engine/upstream")
    target = root / "EraCore.Core/Shared/Runtime/Utils/EncodingHandler.cs"
    if not target.is_file():
        raise SystemExit("EncodingHandler.cs not found: " + str(target))
    changed = patch(target)
    print(("patched " if changed else "already patched ") + str(target))
    return 0


if __name__ == "__main__":
    sys.exit(main())
