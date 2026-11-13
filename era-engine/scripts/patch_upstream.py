"""给上游 EwpHost / ConstantData 打幂等补丁。

1. sys.click：手指点击 → 引擎鼠标事件；等待的不是鼠标时退化为 Enter。
2. sys.enter：直接按 Enter（eraTW 的「按 Enter 键或点击鼠标继续」用得上）。
3. ConstantData：名字表写入与查询都 Trim（包里 `0, 口調` 这种空格会让整张表查不到）。

用法：python3 patch_upstream.py <upstream 根目录>
"""

import re
import sys
from pathlib import Path

CLICK_CASE = '''                case "sys.click":
                    Dispatch(() => DoClick(root.Clone()));
                    break;
                case "sys.enter":
                    Dispatch(() => DoEnter());
                    break;
'''

CLICK_METHOD = '''    // MISTBELL_PATCH_BLOCK_BEGIN
    /// <summary>手指点击 → 引擎鼠标事件；不是鼠标等待时退化为 Enter，避免卡在"点击继续"。</summary>
    private void DoClick(JsonElement root)
    {
        var console = GameConsole;
        if (console == null)
        {
            EmitResult(false, "internal", "console not ready");
            return;
        }
        int x = 0, y = 0;
        if (root.TryGetProperty("x", out var xe) && xe.ValueKind == JsonValueKind.Number)
            xe.TryGetInt32(out x);
        if (root.TryGetProperty("y", out var ye) && ye.ValueKind == JsonValueKind.Number)
            ye.TryGetInt32(out y);
        try
        {
            if (console.IsWaintingInputWithMouse)
            {
                console.MouseDown(new Point(x, y), 0);
                EmitResult(true, null, "click " + x + "," + y);
            }
            else if (console.IsWaitInputState)
            {
                console.PressEnterKey(false, "", false);
                EmitResult(true, null, "click->enter");
            }
            else
            {
                EmitResult(false, "not_waiting", "console is not waiting input");
            }
        }
        catch (Exception ex)
        {
            EmitResult(false, "click_failed", ex.Message);
        }
    }

    /// <summary>直接按 Enter：用于"按 Enter 键或点击鼠标继续"这类等待。</summary>
    private void DoEnter()
    {
        var console = GameConsole;
        if (console == null)
        {
            EmitResult(false, "internal", "console not ready");
            return;
        }
        try
        {
            console.PressEnterKey(false, "", false);
            EmitResult(true, null, "enter");
        }
        catch (Exception ex)
        {
            EmitResult(false, "enter_failed", ex.Message);
        }
    }
    // MISTBELL_PATCH_BLOCK_END

'''

CONSTDATA_WRITE_OLD = "\t\t\t\ttarget[index] = ros[dest[1]].ToString();"
CONSTDATA_WRITE_NEW = "\t\t\t\ttarget[index] = ros[dest[1]].ToString().NormalizeKey();"
CONSTDATA_LOOKUP_OLD = "if (!namearray.TryGetValue(tokens[1], out index))"
CONSTDATA_LOOKUP_NEW = "if (!NameTable.TryLookup(namearray, tokens[1], out index))"

NAME_HELPER_BLOCK = """// MISTBELL_NAME_BLOCK_BEGIN
internal static class NameTable
{
    internal static string NormalizeKey(this string raw)
    {
        if (string.IsNullOrEmpty(raw))
            return string.Empty;
        // 全角空格/不换行空格都不算语义空格；再做 NFKC 把全角字母数字折成半角。
        return raw.Trim(' ', '\\t', '\\u3000', '\\u00A0').Normalize(System.Text.NormalizationForm.FormKC).Trim();
    }

    internal static bool TryLookup(System.Collections.Generic.Dictionary<string, int> table, string raw, out int index)
    {
        index = 0;
        if (table == null)
            return false;
        var key = raw.NormalizeKey();
        if (table.TryGetValue(key, out index))
            return true;
        if (table.TryGetValue(raw, out index))
            return true;
        foreach (var pair in table)
        {
            if (string.Equals(pair.Key.NormalizeKey(), key, System.StringComparison.Ordinal))
            {
                index = pair.Value;
                return true;
            }
        }
        index = 0;
        return false;
    }
}
// MISTBELL_NAME_BLOCK_END

"""


def patch_name_helper(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    block_re = re.compile(r"// MISTBELL_NAME_BLOCK_BEGIN.*?// MISTBELL_NAME_BLOCK_END\n\n", re.S)
    if block_re.search(text):
        new_text = block_re.sub(lambda _m: NAME_HELPER_BLOCK.strip() + "\n\n", text, count=1)
        if new_text != text:
            path.write_text(new_text, encoding="utf-8")
            return True
        return False
    anchor = "namespace MinorShift.Emuera.Runtime.Script.Loader;"
    if anchor in text:
        text = text.replace(anchor, anchor + "\n\n" + NAME_HELPER_BLOCK.strip(), 1)
        path.write_text(text, encoding="utf-8")
        return True
    raise SystemExit("namespace anchor missing in " + str(path))


def patch_ewphost(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    changed = False

    if CLICK_CASE not in text:
        old_case = '                case "sys.click":\n                    Dispatch(() => DoClick(root.Clone()));\n                    break;\n'
        if old_case in text:
            text = text.replace(old_case, "", 1)
            changed = True
        anchor = '                default:\n                    EmitResult(false, "protocol_violation", "unknown or missing \\"t\\"");\n'
        if anchor not in text:
            raise SystemExit("case anchor missing in " + str(path))
        text = text.replace(anchor, CLICK_CASE + anchor, 1)
        changed = True

    block_re = re.compile(r"    // MISTBELL_PATCH_BLOCK_BEGIN.*?// MISTBELL_PATCH_BLOCK_END\n\n", re.S)
    if block_re.search(text):
        new_text = block_re.sub(CLICK_METHOD, text, count=1)
        if new_text != text:
            text = new_text
            changed = True
    else:
        anchor = "    private void Dispatch(Action action)\n"
        if anchor not in text:
            raise SystemExit("method anchor missing in " + str(path))
        text = text.replace(anchor, CLICK_METHOD + anchor, 1)
        changed = True

    if changed:
        path.write_text(text, encoding="utf-8")
    return changed


def patch_constantdata(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    changed = False
    if CONSTDATA_WRITE_NEW not in text and CONSTDATA_WRITE_OLD in text:
        text = text.replace(CONSTDATA_WRITE_OLD, CONSTDATA_WRITE_NEW, 1)
        changed = True
    if CONSTDATA_LOOKUP_NEW not in text and CONSTDATA_LOOKUP_OLD in text:
        text = text.replace(CONSTDATA_LOOKUP_OLD, CONSTDATA_LOOKUP_NEW, 1)
        changed = True
    if changed:
        path.write_text(text, encoding="utf-8")
    return changed


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "era-engine/upstream")
    ewphost = root / "Emuera/UI/Ewp/EwpHost.cs"
    if not ewphost.is_file():
        raise SystemExit("EwpHost.cs not found: " + str(ewphost))
    print(("patched " if patch_ewphost(ewphost) else "already patched ") + str(ewphost))

    constdata = root / "Emuera/Runtime/Script/Data/ConstantData.cs"
    if constdata.is_file():
        print(("patched " if patch_constantdata(constdata) else "already patched ") + str(constdata))
        print(("patched " if patch_name_helper(constdata) else "already patched ") + "NameTable helper")
    return 0


if __name__ == "__main__":
    sys.exit(main())
