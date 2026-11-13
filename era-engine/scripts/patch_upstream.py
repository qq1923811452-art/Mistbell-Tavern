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
    return 0


if __name__ == "__main__":
    sys.exit(main())
