# eraTW / eraOCG 接入：已知问题清单

记录时间：2026-11-15　分支：`4.0幻想乡适配`

引擎：EraCore `6e14de0` + `era-engine/patches/eracore-mistbell.patch`（19 文件，+508/−64）
前端：`mistbell-v3/.../feature/era/`（Compose，横向全屏）

---

## 一、已修复（附证据）

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | `.als` 别名表不加载，688 条“无法解析的标识符” | `SafCompat.FileExists` 对本地路径绕过 `DirAccessor`，Android 区分大小写，`Talent.als` 查 `TALENT.als` 未命中 | `SafCompat` 的 Exists/ReadAllText/ReadAllBytes/ReadAllLines/OpenRead 全部回退 accessor |
| 2 | `TOINT(变动值 * 1.2)` 报「意外符号 `.`」 | EraCore 无浮点字面量 | 词法+算子+函数全套 double 支持；小数后向边界避免 `2.5次元的怪盗` 误判 |
| 3 | 小数结果赋给整数变量报「无法将字符串赋值」 | `SET` 类型校验只认 `IsInteger` | 允许 `IsFloat` 源，运行期 `(long)` 截断 |
| 4 | `HTML_PRINT ... <font size=...>` 报「属性名 size 无法解析」 | EraCore `<font>` 只支持 color/bcolor/face | 新增 `size` 属性解析（12 / 12px / 12.0） |
| 5 | 横屏切换即闪退 | `MainActivity` 无 `configChanges`，转屏重建 Activity | Manifest 增加 `configChanges`；并补 `decorFitsSystemWindows(false)` 实现沉浸式 |
| 6 | 首帧后前端断开（疑似立绘） | 立绘层解码与大图 OOM | 4 层上限、64MB 文件上限、200 万像素降采样、RGB_565 |
| 7 | eraOCG `TITLE.ERB:179` NullReferenceException | `BINPUT` 可跳过分支对可选参数 `Mouse`/`Def` 用了 null-forgiving | 先判空再取值 |
| 8 | 自动滚屏失效 | `followTail` 的 effect 挂在 `lines.size`，新行触发重启并把跟随误置 false | 改为监听 `isScrollInProgress` |
| 9 | 解析缓存复用旧结果 | `ErbIrCache` 版本未变 | `FormatVersion` 2 → 3 |
| 10 | 循环外 `CONTINUE` 直接报错 | 严格模式 | 降为 Lv1 告警 + 运行期空跳转保护 |

## 二、待处理

### P1 `#DIMF` / `LOCALF` 没有真正的 double 存储

- 现状：`#DIMF` 被当作整数变量接受（能解析、不再告警），`倍率 = 1.0` 截断为 `1`，`*= 1.2` 恒等于不变
- 影响：`DLC\魔法DLC\魔法相关函数.ERB` 的倍率恒为 1；`使用魔法.ERB` 的 `LOCALF` 同理
- 需要：`VariableCode.__FLOAT__` + `VariableData.double[]` + `VariableToken.IsInteger` 收窄 + `#DIMF`/`LOCALF` 建表

### P2 多游戏并存

- 现状：仓库按单游戏存储（`preferences` 里一组 KEY），导入新包会替换当前
- 目标：列表 + 切换；引擎按游戏名解包到 `files/games/<名>`（已支持），方言 profile 需随游戏区分（eraTW→`eratw`，eraOCG→`v24pure`）

### P3 eraOCG 启动 `INIT_FAILED`

- 现状：BINPUT 空引用已修，待真机验证
- 若仍失败：读游戏目录 `emuera.log`（不是 app 的 `era-engine.log`）

### P4 布局（等重做）

- 顶栏已移除（沉浸式全屏）
- 按钮目前是行内点击 + 底部快捷区；后期由新布局接管（协议已单独下发 `TurnButton`，无需从文本解析）
- 自动滚屏已修，但新布局下可能改为“新内容提示条”

## 三、环境与构建事实

| 项 | 值 |
|---|---|
| 设备堆上限 | `class=256MB large=512MB`（`EraEngineService` 启动时记录） |
| eraTW 加载峰值 | Mono 堆 526MB（`LoadPendingParallel window=8`），曾撞上限被杀 |
| eraOCG 加载峰值 | 449MB（10240/11383 处） |
| 测试结论 | `window` 8→3 **不降峰值**（反而 544MB、慢 2.5 倍）→ 内存来自 AST 常驻，非并发 |
| 包体差异 | `Era Merged` ≈91.6MB（**不含 QNN 库**）；`Qualcomm HTP V75 Runtime Package` ≈130MB（含 NPU，`mistbell-dev.jks` 签名，需先卸载） |
| 打包完整性 | 依赖 `AndroidLinkMode=None`；`SdkOnly` 会裁到 60.9MB 并有反射误删风险 |

## 四、复现与取证入口

- 宿主日志：`/sdcard/Android/data/com.mistbell.tavern.v3/files/era-engine.log`
  - 关键行：`heap limit` / `awaiting GameHost.StartAsync...` / `GameHost started successfully` / `Front-end disconnected`
- 引擎日志：游戏目录 `agent.log` 与 `emuera.log`（**注意 buffered，末尾进度可能不可信**）
- Java 崩溃：`/sdcard/Android/data/com.mistbell.tavern.v3/files/era-crash.log`
- 原生崩溃：`/data/tombstones/`（需 root）
