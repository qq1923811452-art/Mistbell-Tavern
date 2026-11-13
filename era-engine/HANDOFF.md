# era 引擎接入 · 交接状态（2026-11-14）

> 结论：**玩法可用性未验证通过，暂停**。下面是已经跑通的部分、卡住的地方、以及下次怎么接着做。

## 已跑通（有真机证据）

1. **引擎能在 Android 上跑起来**：`com.mistbell.era` 服务绑定成功，Mono 初始化、EWP/0.3 握手、脚本加载都发生过。
2. **单包注入可行**：现有 `merge_apks.py` 把 `assemblies/` + `lib/arm64-v8a/*.so` + `classesN.dex` 注入主 APK，
   真机上出现「单包 · 游戏已启动」，`:era` 子进程与 `MonoRuntimeProvider` 都工作。
3. **EWP 文本链路可用**：`legacy.line` / `input.request` / `frame.commit` 都收到过，Compose 能渲染、
   能回 `input.submit`、`view.ack`。
4. **CI 流水线稳定**：NPU 单包（`qualcomm-npu-runtime.yml` 末尾的合并步骤）、`era-engine-build.yml`、
   `era-merged-apk.yml` 都能出包；`sys.click` / `sys.enter` 补丁在 CI 里对上游打（`patch_upstream.py`）。

## 卡住的地方（下次从这里继续）

1. **脚本加载 ~171 秒**（游戏自己打印「文件读取完成。耗时：171.53 秒」）。
   - 原因：包的 `emuera.config` 有 `USELAZYLOADING:YES`，但**没有 `lazyloading.cfg`**，引擎只能全量解析 16601 个 ERB。
   - 我生成的 cfg 让引擎跳过这些目录 → 大量「无法解析的标识符」，已回滚（`DropBrokenLazyConfig`）。
   - ✅ 正确的提速方向：**引擎进程常驻**（退出界面只断 socket、不结束进程），不碰脚本语义。
2. **警告规模未收敛**：`cflag.csv 中没有 "X" 的定义` 曾在多个角色卡重复出现。
   - 已做四层修复但**未在真机验证**：CSV 文件名大小写归一、字段 trim（含全角空格）、CFLAG 缺名补全、
     引擎侧写入/查询双侧归一化（`NameTable.NormalizeKey`，NFKC）。
   - 下次先看 `era-engine.log` 里的 `csv whitespace normalized` / `cflag table` / `cflag repair appended`。
3. **`相性,112/113 超出数组范围`**：包的角色数超过 `CHARA_NUM`，属包数据问题，未处理。
4. **引擎曾异常退出（exit=1）**：已加异常回传（`FATAL ...`）但未拿到最终栈。

## 关键位置

- 引擎宿主：`era-engine/EraHost`（net8.0-android，只编内核 + EWP 无头宿主）
- 注入脚本：`era-engine/scripts/merge_apks.py`
- 上游补丁：`era-engine/scripts/patch_upstream.py`（`sys.click` / `sys.enter` / 名字表归一）
- 客户端：`mistbell-v3/.../feature/era/engine/`（EWP 客户端、会话、打包）
- 游戏日志：`/storage/emulated/0/Android/data/com.mistbell.tavern.v3/files/era-engine.log`
- 游戏目录：`/data/user/0/com.mistbell.tavern.v3/files/games/<游戏>/`

## 分支状态

- 所有实验都在 `4.0幻想乡适配`，**未合并**到 `codex/mistbell-development-4.0`；PR #234 保持开启。
- 主线用户包不受影响。

## 已排除的路线

- **AAR 打包**：.NET 8 类库产物只有 156 字节，运行时不进 AAR；根级 `assemblies/` AGP 打不出。
- **跨应用 bindService + FD**：MIUI 直接拒绝（`Not allowed to write file descriptors here`）。
- **换 EraCore**：它的原生客户端缺 `DialectProfile` 设置，跑 eraTW 必然 `INIT_FAILED`；
  要换得自己写宿主并设 `CoreSessionOptions { DialectProfileId = "eratw" }`（2–3 天）。
