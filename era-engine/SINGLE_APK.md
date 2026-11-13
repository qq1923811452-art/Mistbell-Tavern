# 问题说明：把 .NET 8 引擎收进 Kotlin/Compose 主 APK

> 给外部技术评审用。所有结论都附了实测证据，不是推测。

## 1. 现状

- 主应用：Kotlin + Jetpack Compose，包名 `com.mistbell.tavern.v3`，`compileSdk 37`、`minSdk 26`，Gradle 构建。
- 引擎：C# / .NET 8，Emuera（eraTW 的 ERB 解释器）+ SkiaSharp，`net8.0-android34.0`，由 `dotnet publish` 出 APK。
- 目前是**两个 APK**：
  - 引擎 APK `com.mistbell.era`，无图标、无 Activity，只暴露一个绑定服务；
  - 主应用 bind 该服务，通过 `ParcelFileDescriptor` 把游戏 ZIP 传给引擎，引擎在 `127.0.0.1` 上开 EWP/0.3（JSON-Lines over TCP）把文本/选项/输入交给 Compose 渲染。
  - 两者用同一 dev keystore 签名，引擎 APK 58 MB，主应用 146 MB。

## 2. 想解决的：合并成一个 APK

用户侧只有一个安装动作，且引擎 bug 不需要跨两个产物修。

## 3. 已实测的硬限制

### 3.1 .NET 8 Android 类库产物不含运行时

把同一套源码按 `-p:AndroidApplication=false -p:OutputType=Library` 发布：

```
MistbellEra.aar
├── res/values/strings.xml            (80 B)
└── .net/__res_name_case_map.txt      (76 B)
```

**没有 `classes.jar`、没有 `lib/`、没有 `AndroidManifest.xml`、没有 `MonoRuntimeProvider`。**
即：库模式产出的 AAR 是资源包，运行时不会被 MSBuild 打进去。

### 3.2 运行时在 APK 里的真实布局（从产物解包得到）

```
assemblies/assemblies.blob                   23 MB
assemblies/assemblies.arm64_v8a.blob
assemblies/assemblies.x86_64.blob
assemblies/assemblies.manifest
lib/arm64-v8a/libmonodroid.so
lib/arm64-v8a/libmonosgen-2.0.so
lib/arm64-v8a/libxamarin-app.so
lib/arm64-v8a/libSkiaSharp.so
lib/arm64-v8a/libe_sqlite3.so
AndroidManifest.xml 里含 mono.MonoRuntimeProvider
```

注意：程序集在 **APK 根目录 `assemblies/`**，不是 `libassemblies.*.blob.so`（那是 .NET 6 早期 fast-deploy 的命名）。

### 3.3 AGP 无法生成根级目录

Gradle/AGP 只允许往 `lib/`、`assets/`、`res/`、`META-INF/` 放文件，**没有任何配置能在 APK 根目录生成 `assemblies/`**。
而 Mono 的 assembly store 是按 APK 根相对路径查找的。

### 3.4 `libxamarin-app.so` 与 applicationId 绑定

该 .so 内嵌 applicationId 与 assembly store 的路径/哈希。把 `com.mistbell.era` 的产物直接搬进
`com.mistbell.tavern.v3` 会校验失败；要重新生成就得回到 .NET 工程改 `ApplicationId`。

## 4. 已经排除的做法

| 做法 | 结果 |
|---|---|
| 引擎打成 AAR，`implementation(files("...aar"))` 进主工程 | AAR 是空资源包，运行时不进包 |
| 从引擎 APK 里搬 `libmonodroid.so`/`libmonosgen-2.0.so` 进主工程 jniLibs | 缺 `assemblies/`，MonoVM 起得来但找不到托管代码 |
| 手工重组 AAR（合并 mono.android.jar + JCW + 原生库 + 文本 manifest） | 仍卡在 3.3；且 `libxamarin-app.so` 包名/哈希不匹配（3.4） |
| 用 `[Register]` 静态方法做 JNI 桥 | 静态方法默认不生成 JCW 导出，Kotlin 侧会 `NoSuchMethodError` |

## 5. 想请评审回答的问题

1. **官方路径**：.NET 8/9/10 for Android 有没有「把运行时 + 程序集嵌入一个已有的 Java/Kotlin 应用」的受支持机制？（不是另一个 .NET 应用消费 AAR，而是纯 Java 宿主绑定。）
2. **APK 后处理可行性**：如果只能在 Gradle 产出 APK 之后做二次处理（注入 `assemblies/`、改二进制 manifest 加 `MonoRuntimeProvider`、`zipalign`、重签），需要满足哪些前提？
   - `libxamarin-app.so` 是否必须按目标 applicationId 重新生成？有没有生成工具/参数？
   - assembly store 的哈希/`assemblies.manifest` 是否需要与 .so 同步？
   - Android 15/16 的 16 KB page size 对齐、`extractNativeLibs=false` 会不会成为硬门槛？
3. **NativeAOT 替代**：能不能把这套 C# 直接编成 `linux-bionic-arm64` 的 NativeAOT 可执行文件或 `.so`，用 `RuntimeIdentifier=android-arm64` 走 no-Mono 路线，从而绕开整个 assembly store？.NET 8 是否支持？如果不支持，.NET 9/10 呢？
4. **进程边界**：如果最终仍要独立进程，`android:process=":era"` 让 `MonoRuntimeProvider` 只在子进程初始化是否可靠？provider 的 `authorities` 用 `${applicationId}` 占位符在 library manifest 合并时是否安全？
5. **是否有更省事的三方方案**：有没有成熟的「Kotlin 直接解释 ERB/era 脚本」的 JVM 实现，可以完全不依赖 .NET？（如果有，代价可能比搬运行时更低。）

## 6. 约束

- 开发容器是 aarch64，`dotnet workload install android` **不支持 arm64 宿主**，所以 .NET 侧只能在 x64 CI（GitHub Actions ubuntu-latest）上构建，迭代一轮约 5–10 分钟。
- 现有 CI 出包正常，两包方案已可装机验证；单包是体验优化，不是阻塞项。
