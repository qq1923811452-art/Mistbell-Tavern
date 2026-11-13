# era 引擎宿主（Android）

把 Emuera（eraTW 内核）跑在 Android 上，并以 EWP/0.3 把「文本 + 选项 + 输入」交给 Mistbell 前端。

## 为什么是独立 APK

- Emuera 是 C#（.NET 8）。要嵌进 Kotlin APK，等于把 .NET 运行时塞进一个非 .NET 的 Android 应用——
  Android 侧没有官方支持路径，风险远大于收益。
- 上游的 Android 移植是完整的 Xamarin.Forms 应用（自带 UI、依赖 Xamarin.Forms / 旧 AndroidX），
  直接当 AAR 塞进 Compose 应用会带进一整套冲突依赖。
- 所以第一版把引擎做成**无界面服务 APK**：Mistbell 绑定它，它只负责跑剧本 + 出事件。
  进程边界天然满足「AI 崩溃不影响游戏 / 游戏脚本崩溃不影响 AI」的隔离要求。

`EraEngineSession` 只依赖 `com.mistbell.era` 的绑定契约与 EWP，将来如果真能把引擎收进主进程，
前端一行不用改。

## 组成

| 文件 | 作用 |
|---|---|
| `EraHost/EraHost.csproj` | `net8.0-android`，只编内核 `Emuera/Runtime` + 共享控制台 + `UI/Ewp` + `UI/Headless` |
| `EraHost/Android/EraBootstrap.cs` | 与 `Emuera.Headless` 同源的启动序列，跑在服务线程上 |
| `EraHost/Android/EraHeadlessHost.cs` | 自持 `HeadlessSurface` 的泵循环，服务销毁时能干净退出 |
| `EraHost/Android/EraGameStore.cs` | 从文件描述符读 ZIP，解到 `filesDir/games/<name>`，按包版本复用 |
| `EraHost/Android/EraEngineService.cs` | 绑定服务：收「ZIP fd + 端口」，回状态 Messenger |

上游源码不进本仓库：CI 按固定提交拉取到 `era-engine/upstream`（已 gitignore），
本工程以相对路径引用，只增不改。

## 绑定契约

Mistbell → 引擎（`Intent` extras）：

| key | 类型 | 说明 |
|---|---|---|
| `replyTo` | `Messenger` | 引擎回状态用 |
| `port` | `int` | 引擎在 `127.0.0.1` 监听的 EWP 端口 |
| `gameName` | `String` | 显示名，同时作为目录名 |
| `packVersion` | `String` | 包版本；相同则跳过解包 |
| `zipFd` | `ParcelFileDescriptor` | 游戏 ZIP（Mistbell 私有目录，按 fd 传） |

引擎 → Mistbell：`Messenger` 消息 `what=1`，`data.state ∈ {preparing, importing, imported,
starting, running, stopped, error}`，`data.detail` 为附加说明。

## EWP 面

引擎出：`hello` / `ready` / `legacy.line` / `legacy.clear` / `input.request` / `frame.commit` /
`dialog.request` / `error` / `exit`。

前端回：`hello.ok`（caps + viewport）/ `input.submit`（`via` = text / button / enter / skip）/
`view.ack` / `sys.quit`。

前端只声明自己真的会渲染的 caps，引擎会自动降级（例如不声明 `legacy.spans` 就不发 spans）。

## 构建

`.github/workflows/era-engine-build.yml`（模板见 `ci/era-engine-build.yml`）：

1. 拉取上游固定提交；
2. `dotnet workload install android`；
3. `dotnet publish era-engine/EraHost/EraHost.csproj -f net8.0-android`；
4. `apksigner` 签名并上传 artifact。

> 注意：`android` workload 只支持 linux-x64 / win / mac，本机若是 arm64 无法编译，
> 必须在 CI（x64 runner）上出包。

## 为什么不是单包（AAR 探针结论）

试过把同一套源码按 `AndroidApplication=false` 发布成 AAR：产出的 `MistbellEra.aar` 只有
`res/values/strings.xml` 与 `.net/__res_name_case_map.txt`（156 字节），**不含运行时、程序集与
`MonoRuntimeProvider`**。

即使按「从 APK 重组 AAR」的做法手工搬，也过不去 AGP 这一关：.NET 8 的程序集在 APK 根目录
`assemblies/assemblies.blob`（外加 `lib/<abi>/libxamarin-app.so`），而 Gradle 只能打
`lib/ assets/ res/ META-INF/` 四个顶层目录，**生成不了根级 `assemblies/`**；`libxamarin-app.so`
又是按 applicationId 生成并带校验的，跨包名直接搬会失败。

真要单包，只能在 Gradle 产出 APK 之后做后处理（解包塞 `assemblies/`、改二进制 manifest 加
provider、对齐、重签），等于自养一个打包器。结论：**两包（引擎组件 + 应用）是当前工具链下的正解**，
两者用同一 dev keystore 签名，更新可直接覆盖。

## 许可

引擎源码沿用 Emuera 许可（zlib 式，允许商用，但修改需注明、保留声明）。
本目录新增代码随 Mistbell 仓库。
