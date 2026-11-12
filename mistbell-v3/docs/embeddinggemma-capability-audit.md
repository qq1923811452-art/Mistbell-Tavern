# EmbeddingGemma 2 能力核对报告（对照仓库当前源码）

核对基线：`codex/mistbell-ui-nocturne` @ `cb7318a2`（PR #228，CI 全绿）。
结论口径：**已完整实现 / 部分实现（含偏差）/ 未实现**。

| # | 功能 | 状态 |
| --- | --- | --- |
| 1 | 官方任务指令前缀路由 | ⚠️ 部分实现，**且有实质偏差** |
| 2 | MRL 动态维度截断 + 粗排精排 | ⚠️ 截断已做，双级检索未做 |
| 3 | 8192 tokens 场景级长切片 | ❌ 未实现 |
| 4 | 世界书混合语义门控 | ❌ 未实现（纯字符串/正则） |
| 5 | 检索前置意图门控 | ❌ 未实现（只有空串短路） |
| 6 | 流式立绘/表情差分切换 | ❌ 未实现 |
| 7 | 写入前语义查重与合并 | ❌ 未实现（只有精确哈希去重） |
| 8 | 后台"记忆梦境"聚类压缩 | ❌ 未实现 |
| 9 | 角色红线 / 防 OOC 事实校验 | ❌ 未实现 |
| 10 | 跨模态图文记忆召回 | ❌ 未实现（管线纯文本） |
| 11 | 音频氛围 BGM 自适应 | ❌ 未实现（无音频模块） |

---

## 1. 任务指令前缀 —— 部分实现，**前缀字符串是错的**

现状（`feature/embedding/EmbeddingGemma2TextPolicy.kt:32-44`）：

```kotlin
fun formatSearchQuery(query: String) = "task: search query | text: ${query.trim()}"
fun formatDocument(text: String, title: String?) =
    "task: search result | title: ${title ?: "none"} | text: ${text.trim()}"
```

官方 EmbeddingGemma 的非对称检索模板是：

- 查询端：`task: search result | query: {query}`
- 文档端：`title: {title} | text: {content}`

**偏差有两处**：查询端自造了 `task: search query | text:`（官方没有这个模板）；文档端把 `task: search result` 挪到了文档上（官方它属于查询端）。
后果是查询向量与训练时的目标分布不一致，**直接压低召回率**，而且这种劣化是静默的——相似度照样算出数字，只是排序变差。
另外：对称任务（Classification / Clustering / SentenceSimilarity）三个前缀**一个都没有**，所以清单里第 6、7、8、9 项就算想实现，也缺这个入口。

**改动量**：两行字符串 + 现有 `EmbeddingGemma2TextPolicyTest` 补断言。**这是全清单里性价比最高的一项。**

## 2. MRL 维度截断 —— 已实现；粗排+精排 —— 未实现

已做（`EmbeddingGemma2TextPolicy.truncateAndNormalize` / `EmbeddingGemma2RuntimeAdapter:150-160`）：
- `supportedDimensions = {128, 256, 512, 768}`，超出集合直接 `require` 拒绝
- 截断后**重新做 L2 归一化**并校验 `isFinite`，注释也写明"截断不归一化会改变余弦排序"
- 向量缓存键含维度（`EmbeddingVectorCache.key(..., dimensions, ...)`）

未做：
- **没有两级漏斗**。`VectorMemoryRetriever` 是把候选全量展开、逐条算点积再做 RRF 融合，没有"内存常驻 128d 粗排 Top-K → 回表取 768d 精排"的路径。
- 缓存按维度分键的副作用：用户在设置里切 768/512/256 做对比时，缓存全部 miss，**同一段文本会重复跑一遍 NPU 前向**。

## 3. 场景级长切片 —— 未实现

现状：
- `MAX_EMBED_CHARS = 2_000`（UTF-16 码元）单段上限，超出按代理对安全截断
- 记忆注入侧 `MAX_MEMORY_SECTION_CHARS = 1_200`（`VectorMemoryPromptComposer`）
- 每条记忆是一个独立向量，**没有"整幕戏"级别的宏观向量**，也没有细节/场景双路召回

顺带提醒：截断按字符、校验按 UTF-8 字节（`EmbeddingGemma2RuntimeAdapter:112-122`），**中文超过约 1350 字会直接抛 `EmbeddingOutputException`**，这条是现存 P0（见上一轮审计）。

## 4. 世界书混合语义门控 —— 未实现

`feature/worldbook/core/WorldbookPlanner.kt` 的匹配只有三种：`lowercase().contains()`、`*` 通配转正则、用户自定义正则（`Regex(expression, IGNORE_CASE)`）。
`feature/worldbook` 整个包**没有任何 embedding 引用**。所以"换个说法就漏触发"的问题目前仍然存在。

## 5. 检索前置意图门控 —— 未实现

`VectorMemoryContextService.retrieveForQuery` 的唯一短路是 `if (query.isBlank())`。
"早安""嗯嗯"这类消息照样会走完整检索链路。
另外注意：当前 `VectorSnapshotReadResult.Missing` 会**直接返回 `Unavailable(SNAPSHOT_MISSING)`**，连关键词分支一起停掉——这条不修，第 4、5 项加起来也没意义。

## 6. 流式表情/立绘切换 —— 未实现

立绘目前只是角色卡上传的一张图（`CharacterArtStore` + `ui/CharacterAvatar.kt`），**没有表情差分概念**，也没有分类向量的路由。
要实现需要先加：表情锚点表 → `task: classification` 前缀 → 取点积最大者 → 与流式 token 节流联动。

## 7. 写入前语义查重 —— 未实现

`TemporalFactIdentity` 的 `stableId` 是对 `(conversationId, subject, predicate, value)` 做的**精确哈希**，只能挡住完全相同的重复；"同一件事换个说法"会各存一条。
没有 SentenceSimilarity 比对，也没有合并/覆盖策略。

## 8. 后台记忆聚类压缩 —— 未实现

全仓库 grep 不到 `cluster` 相关实现（唯一命中是 UI 里的角色 Chip 命名）。后台只有抽取队列 `MemoryExtractionQueueWorker`，没有空闲态聚类归档。

## 9. 防 OOC 事实校验 —— 未实现

没有 `fact checking` 前缀、没有红线 Evidence 库、没有"撞线就把铁律置顶"的注入路径。

## 10 / 11. 多模态 —— 未实现，且当前管线是纯文本

- 模型标识固定 `MODEL_ID = "google/embeddinggemma-2"`
- 编码入口只有 `formatSearchQuery` / `formatDocument` 两个文本函数，**没有图像/音频输入接口**
- 打包的模型资产是文本核心（270M），没有 170M 视觉编码器 / 300M 音频编码器
- 整个 app **没有音频/BGM 模块**

---

## 落地优先级（按改动成本从低到高）

| 优先级 | 事项 | 理由 |
| --- | --- | --- |
| **P0** | 修正查询/文档前缀（第 1 项） | 两行改动，直接修复召回质量；现有测试可加断言 |
| **P0** | 修 `SNAPSHOT_MISSING` 短路 + 提取后触发向量重建 | 否则后面所有「向量门控」都拿不到向量（上一轮审计 MEM-01） |
| **P1** | 世界书向量软门控（第 4 项） | 复用现有 retriever + 快照即可，无需新模型；对游玩体验提升最明显 |
| **P1** | 轻量意图门控（第 5 项） | 先用"长度 + 停用词 + 是否含疑问/名词"判定，不必上向量，成本更低 |
| **P2** | 写入前语义去重（第 7 项） | 用现有 768d 向量即可，防记忆库膨胀 |
| **P2** | 双级检索（128d 粗排 + 768d 精排，第 2 项） | 记忆量大时才见效，属于性能优化 |
| **P3** | 场景级切片 + 双路召回（第 3 项） | 需要先定"一幕"的边界规则 |
| **P3** | 防 OOC 校验（第 9 项） | 需要角色卡新增"红线"字段与作者约定 |
| **P4** | 表情路由（第 6 项） | 依赖表情差分素材 |
| **P4** | 聚类整理（第 8 项） | 需要 WorkManager 空闲约束 + 质量评估手段 |
| **P5** | 图文 / 音频跨模态（第 10、11 项） | 要换模型资产，APK 体积和 NPU 兼容性都要重测 |

**一句话结论**：底座（MRL 截断、前缀机制、RRF 融合、向量快照）已经铺好，但**只落地了"文本检索"这一条主干**；清单里真正发挥 EmbeddingGemma 2 差异化的部分——语义门控、语义去重、防 OOC、多模态——基本都还没接。而现有前缀字符串是错的，属于"地基里埋了一颗雷"，建议先修它。
