# Mistbell 3.0 独立功能重建清单

> 本文以 `codex/mistbell-development-3.0` 的基线提交 `ba2ae9b3099fa7282ab58b35b2128f9bca89b1ee` 为起点。
>
> **隔离规则：** PR29 及其后续支线只提供产品需求和经验，不作为本支线的代码来源。不得从旧支线 cherry-pick、合并或直接移植实现；不得把旧支线报告的问题未经验证就认定为本基线的缺陷。所有实现与测试均针对 3.0 基线重新设计。

## 不可丢失的功能目标

1. **向量记忆**：可靠的语义存储、检索、命名空间隔离、缓存、并发与错误恢复；向量空间身份必须明确，不能混用不同模型产生的向量。
2. **本地 Embedding**：设备端推理与可配置的本地模型方案；清楚报告实际使用的后端。NPU/GPU/CPU 状态必须来自真实执行结果，不得用装饰性提示冒充成功。
3. **独立记忆整理模型**：聊天模型与记忆抽取/整理模型配置可独立设置，支持各自的 Base URL、密钥、模型、超时和失败处理。
4. **事实历史**：事实变化时保留可追溯的历史记录，明确当前事实、旧事实、时间和来源；避免静默覆盖或错误地同时注入互相矛盾的事实。
5. **世界书递归扫描**：可控制递归扫描开关、深度、扫描文本范围与预算；正确处理常量条目、关键词、递归激活、去重和循环引用。
6. **生图 API**：独立的图像生成配置与调用链，安全处理密钥、请求/响应、下载大小、文件类型、预览与失败重试。
7. **Token 与缓存优化**：减少无效上下文与重复信息，同时稳定可缓存的提示词前缀；动态内容与静态内容分层。节省量和缓存命中改善必须通过可重复测量证明，不预先承诺数字。
8. **Mistbell 专属内容格式引擎**：为世界书和角色卡定义版本化的原生格式与语义，兼容读取常见旧格式；保留未知字段和原始导入内容，提供可解释的导入诊断，避免无意破坏用户文件。
9. **验证与交叉审核**：常规 CI 以编译、单元测试、静态检查为主，不例行打包 APK 或启动昂贵的模拟器流程；最终集成阶段才执行完整 Android/Room/打包验证。两个独立模型必须审查同一个不可变 SHA，并记录模型实际调用、SHA 和结果；任何一方未执行或失败都不能宣称双审通过。

## 3.0 基线初步盘点（待逐模块验证，不是缺陷结论）

已在基线树中发现相关实现或测试入口：

- 向量记忆与 Embedding：`data/vector/EmbeddingService.kt`、`LocalEmbeddingService.kt`、`OpenAIEmbeddingService.kt`、`VectorMemoryService.kt`，以及对应单元/仪器测试。
- 结构化记忆与抽取：`StructuredMemoryEntity.kt`、`StructuredMemoryDao.kt`、`StructuredMemoryRepository.kt`、`service/MemoryExtractionService.kt`。
- 世界书：`util/WorldBookParser.kt`、`service/LocalWorldBookService.kt`、`data/repository/WorldBookRepository.kt`。
- 角色卡导入：`util/CharacterImporter.kt`、`util/CharxImporter.kt`。
- 提示词构建：`data/prompt/PromptBuilder.kt`、`data/prompt/WorldBookPlacement.kt`。
- CI：`.github/workflows/ci.yml` 当前包含例行 APK 构建和仪器测试相关 job，需按目标重新设计。

以上仅表示文件/入口存在，不代表目标已完整满足，也不表示其存在旧支线报告过的缺陷。下一步应逐模块阅读实现、追踪真实调用链、检查测试覆盖和数据迁移约束，再形成依赖图与实现顺序。

## 实施原则

- 先验证基线现状，再决定保留、重构或新增；不得仅凭文件名判断功能完成。
- 先定义模块契约和数据模型，再并行开发低耦合模块。
- 每个功能以用户可观察的行为和回归测试验收，而不是照搬旧支线修复列表。
- 数据迁移必须非破坏性；无法证明旧数据属于哪个 Embedding 空间时，不得静默混用或删除。
- PR 保持 Draft，直到功能、测试、双模型交叉审核和最终验证均有证据；未获明确授权不得合并。
