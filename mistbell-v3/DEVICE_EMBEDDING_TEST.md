# Mistbell Tavern 3.0 — 本地 Embedding 设备实测

这份清单用于最终在真实 Android 设备上验证 EmbeddingGemma 2。CI/JVM 单元测试不能替代真机测试。

## 测试步骤

1. 安装专门的 3.0 开发 APK，并保持网络可用。
2. 在“本地模型与实测”区域先二选一：通用版 LiteRT-LM 模型（约 485 MB），或 Qualcomm SM8650 专用模型（约 563 MB）。两个版本固定到各自已核对的模型版本并分别校验 SHA-256。选择专用模型不代表 NPU 已可用；加速库和 dispatch ABI 仍需独立验证。请预留充足存储空间并使用稳定网络。
3. 下载结束后，已校验模型会发布到手机公共目录 `/storage/emulated/0/Download/向量模型/`，模型文件和 `.sha256` 摘要文件都应能在文件管理器中看到。应用通过 Android MediaStore 发布文件，不需要授予“所有文件访问”权限；运行时使用应用私有的已校验副本。重新安装或清除应用数据后，若公共目录中的模型仍在，应用会尝试恢复所选版本。
4. 下载结束后点击“初始化后执行真实向量测试”。测试使用应用内固定的诊断短句，不会调用聊天 API。
5. 检查“本地 Embedding / NPU 状态”和操作结果。当前 APK 若没有打包完整、经过版本匹配验证的加速库，会自动跳过对应后端并使用可用的安全后备（CPU 优先保证测试可运行），同时在日志中写明缺少哪些文件；不会仅因芯片型号或模型名称就尝试 NPU。
   - **已就绪 / 真实本地推理成功**：模型初始化并成功返回向量。
   - **已回退**：首选后端初始化或推理失败，另一个配置后端成功返回向量；查看回退原因。
   - **初始化/推理失败**：检查错误摘要并导出日志。
   - **配置后端 NPU** 不等于已证明硬件 NPU 实际执行。LiteRT-LM 当前公开的 Embedding Kotlin API 没有向应用提供可独立核验的逐次硬件执行遥测，因此应用会明确标记“仅配置成功，未独立确认实际硬件调用”。
6. 点击“导出诊断日志”，将 `mistbell-diagnostics.txt` 保存后发回项目对话。日志包括阶段、后端尝试、回退原因、错误摘要、向量维度、耗时和模型 SHA-256。

## 隐私与校验说明

- 诊断日志不应包含聊天正文、完整提示词、API 密钥或 HTTP 请求/响应正文。
- 模型下载后会保存 SHA-256 sidecar，用于检测本地文件之后是否被改动。该 sidecar 是下载时生成的本地摘要，不是模型发布者签名或独立可信校验和。
- LiteRT-LM 0.18.0 Android AAR 本身不包含独立 GPU accelerator 插件。设备测试 APK 工作流现在会从官方 LiteRT-LM `v0.18.0` 源码标签获取匹配的 `prebuilt/android_arm64/*.so`，放入 APK 的 `lib/arm64-v8a`，并记录库文件 SHA-256 与上游许可证。此举只完成 GPU 运行库打包，仍需在 Redmi K70 Pro 上验证初始化和真实推理；系统还必须提供兼容的 OpenCL 驱动。NPU 的 Qualcomm dispatch 与 QAIRT/QNN 运行库尚未打包，需先确认精确兼容版本及再分发许可，再做设备验证。
- 启用 SM8650 NPU 还必须把与该 LiteRT-LM 版本兼容的 `libLiteRtDispatch_Qualcomm.so`、QAIRT/QNN HTP 运行库和 Hexagon V75 组件放入 `app/src/main/jniLibs/arm64-v8a/`，并完成设备验证。只有实际验证后才能加入 `app/src/main/assets/native-runtime/qualcomm-sm8650.properties`，内容须包含 `status=verified`、`target_soc=SM8650`、`litertlm_android=0.18.0`、`qairt_abi=verified` 和 `dispatch_abi=verified`；不能只因文件存在就标记已验证。
- 若要确认 Redmi K70 Pro 的 NPU 是否真正执行，还需要结合设备/系统级运行日志或可观测的后端遥测；不能只依据手机型号、后端配置或“初始化成功”下结论。
- 最终用户实测需记录设备型号、Android 版本、结果状态、耗时、是否回退和导出的诊断日志。
## 原生运行库完整清单

GPU、Qualcomm SM8650 NPU、CPU 运行时、系统提供的驱动、条件依赖、ELF 动态依赖、版本固定、许可证和打包验收门槛统一记录在 [NATIVE_RUNTIME_DEPENDENCY_MATRIX.md](NATIVE_RUNTIME_DEPENDENCY_MATRIX.md)。添加或更新任何 native library 前必须先更新该清单；不能以“文件存在”作为后端可用的证明。

## APK 说明

最终设备测试 APK 由 `Mistbell 3.0 Final Device-Test APK` 工作流生成。工作流会先对 `mistbell-v3` 执行 Kotlin 编译和 JVM 单元测试，只有两者通过后才会执行 `assembleDebug` 并上传 APK、SHA-256 和源代码提交号。该 APK 用于你的真实设备测试，不代表 NPU 已通过实测或应用已完成最终验收。
