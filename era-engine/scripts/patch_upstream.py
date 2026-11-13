"""给上游 EwpHost 打一个幂等补丁：新增 `sys.click` 命令（手指点击 → 引擎鼠标点击）。

上游 EWP 只有 input.submit（文本/按钮/Enter），没有鼠标事件；eraTW 的
「按 Enter 键或点击鼠标继续」这类等待需要真正的鼠标点击才能推进。

用法：python3 patch_upstream.py <upstream 根目录>
"""

import sys
from pathlib import Path

CASE_ANCHOR = '                default:\n                    EmitResult(false, "protocol_violation", "unknown or missing \\"t\\"");\n'
CASE_INSERT = (
    '                case "sys.click":\n'
    '                    Dispatch(() => DoClick(root.Clone()));\n'
    '                    break;\n'
)

METHOD_ANCHOR = '    private void Dispatch(Action action)\n'
METHOD_INSERT = '''    /// <summary>手指点击 → 引擎鼠标事件。坐标与 EWP 的 render 逻辑像素一致（左上原点）。</summary>
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
            console.MouseDown(new Point(x, y), 0);
            EmitResult(true, null, "click " + x + "," + y);
        }
        catch (Exception ex)
        {
            EmitResult(false, "click_failed", ex.Message);
        }
    }

'''


CONSTDATA_ANCHOR_WRITE = "\t\t\t\ttarget[index] = ros[dest[1]].ToString();"
CONSTDATA_WRITE = "\t\t\t\ttarget[index] = ros[dest[1]].ToString().Trim();"

CONSTDATA_ANCHOR_LOOKUP = "if (!namearray.TryGetValue(tokens[1], out index))"
CONSTDATA_LOOKUP = "if (!namearray.TryGetValue(tokens[1].Trim(), out index))"


def patch_constantdata(path: Path) -> bool:
    """名字表的写入与查表都补 Trim。

    引擎原本两边都不 Trim：包里写成 `0, 口調` / `フラグ, 口調, 0` 时，
    表里键带空格、查表带空格，逐字符不等 → 每个名字每条都报「没有定义」。
    """
    text = path.read_text(encoding="utf-8")
    changed = False
    if CONSTDATA_WRITE not in text and CONSTDATA_ANCHOR_WRITE in text:
        text = text.replace(CONSTDATA_ANCHOR_WRITE, CONSTDATA_WRITE, 1)
        changed = True
    if CONSTDATA_LOOKUP not in text and CONSTDATA_ANCHOR_LOOKUP in text:
        text = text.replace(CONSTDATA_ANCHOR_LOOKUP, CONSTDATA_LOOKUP, 1)
        changed = True
    if changed:
        path.write_text(text, encoding="utf-8")
    return changed


def patch(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    changed = False
    if 'case "sys.click"' not in text:
        if CASE_ANCHOR not in text:
            raise SystemExit("anchor for case not found: " + str(path))
        text = text.replace(CASE_ANCHOR, CASE_INSERT + CASE_ANCHOR, 1)
        changed = True
    if "private void DoClick(JsonElement root)" not in text:
        if METHOD_ANCHOR not in text:
            raise SystemExit("anchor for method not found: " + str(path))
        text = text.replace(METHOD_ANCHOR, METHOD_INSERT + METHOD_ANCHOR, 1)
        changed = True
    if changed:
        path.write_text(text, encoding="utf-8")
    return changed


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "era-engine/upstream")
    target = root / "Emuera/UI/Ewp/EwpHost.cs"
    if not target.is_file():
        raise SystemExit("EwpHost.cs not found: " + str(target))
    changed = patch(target)
    print(("patched " if changed else "already patched ") + str(target))

    constdata = root / "Emuera/Runtime/Script/Data/ConstantData.cs"
    if constdata.is_file():
        changed2 = patch_constantdata(constdata)
        print(("patched " if changed2 else "already patched ") + str(constdata))
    return 0


if __name__ == "__main__":
    sys.exit(main())
