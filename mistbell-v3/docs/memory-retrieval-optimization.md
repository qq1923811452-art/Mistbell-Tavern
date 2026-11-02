# 记忆检索与索引维护优化（v3 4.0 分支）

本轮只改**向量生成之后的数据使用方式**，不改变嵌入模型、量化格式、检索语义与持久化格式。
所有改动都在 `mistbell-v3` 内，未触碰 NPU 运行时、世界书、角色卡与 UI。

## 1. 量化向量单趟打分（scale 消元）

存储向量是 `v = scale · b`，同一向量所有维度共用一个正数 `scale`。余弦相似度中 `scale`
在分子分母同时出现，直接抵消：

```
cos(u, v) = (u · b) / (|u| · |b|)
```

因此可以直接从 mmap 的字节上算，不需要反量化成 `FloatArray`，也不需要每条记录两次遍历
（旧实现：`fillInto` 填数组 → 点积）。查询向量保持浮点，不做量化，避免引入新的排名误差。

- 新增 `QuantizedVectorView`（`core/memory/VectorSnapshot.kt`），由 `MappedVectorValues` 实现；
  legacy float32 快照的 `isQuantized` 为 `false`，继续走原来的反量化路径。
- **模长只算一次并缓存**：`byteNorm()` 首次调用时遍历字节算 `|b|`，结果存在该映射向量的字段里，
  之后每次查询只累加点积。所以「点积与范数融合」只发生在第一次，不是每次查询都重算。
  legacy 快照继续用 `VectorSnapshot.norms`，两条路径都不在查询里做重复遍历。
- `scale` 完全不参与打分运算，但仍参与 `get()`/`fillInto()` 的反量化，并在解码时校验
  （有限、正数、不超过 `Float.MAX_VALUE / 127`）——坏 scale 在读锁时就拒绝，不留给查询期。

**顺带消除的一个隐患**：旧路径对每个元素做 `byte * scale` 后校验 `isFinite`。解码器允许读到
`-128`，而 `scale` 上限是 `Float.MAX_VALUE / 127`，`-128 * scale` 会溢出为 `Infinity` 并抛异常。
新路径不乘 `scale`，这个溢出面自然消失；字节本身恒为有限值，因此快速路径不需要逐元素 `isFinite`
（查询向量仍在入口校验有限性）。

## 2. 有界 Top-K

`search()` 原来先构造全部命中再全量排序后 `take(limit)`。改为大小为 `limit` 的小顶堆，
堆头是"最差"命中，插入前先比较。排序键保持 `score 降序 + id 升序`，与原实现完全一致。

## 3. 文本库进程级缓存

一次发送路径会读两次文本库：一次判断索引是否过期，一次注入记忆。每次读都是整文件读取 +
逐条 SHA-256 校验，成本与总条数成正比。

新增 `MemoryTextMemoryCache`（`core/storage/`）：

- 键 = 规范路径 + 进程内写代数 + 长度 + mtime。写入时**推进代数**，因此同毫秒、同长度的重写
  也能立即失效；跨 Store 实例同样生效。
- 只缓存解码且校验通过的结果；缺失与损坏始终回盘读取，损坏不会被记成有效数据。
- 缓存值是不可修改视图，且首次加载与后续命中返回**同一个实例**，调用方观察不到差异。
- 所有同路径 Store 实例共用一个锁；`update()` 的读-改-写整体在锁内完成，消除了跨实例
  读改写互相覆盖的窗口（这是原有行为里的一个真实竞态）。

## 4. 增量重建 + 批量嵌入

`MemoryIndexRebuilder.rebuild(records, previous)` 新增 `previous` 参数：

- 复用条件：`modelId`、`dimensions`、`id`、`sourceTextSha256` 四项全部匹配。模型或维度变化时
  全部重算——只看文本哈希是不够的。
- 复用值会被**拷贝**成新的 `FloatArray`，避免新快照继续持有旧 mmap 缓冲区。
- 复用的记录保留原 `createdAtEpochMillis`，让"重建"不再无意义地刷新全部时间戳。
- 待嵌入记录按 `runtime.passageBatchSize` 分批调用 `embedPassages`；默认实现逐条回退，
  不支持批量的运行时行为不变。
- `MemoryIndexRebuildResult.Success` 结构未变；复用/嵌入/批次数通过 `onProgress` 回调上报，
  由 `ChatMemoryIndexRebuildCoordinator` 写入诊断日志 `memory.index.rebuild_work`。

## 实测（JVM，768 维，limit=100，每次 10 个查询，5 轮取最好）

同一份 mmap 快照上，旧实现（反量化 + 点积，模长来自 `snapshot.norms`）与新实现对比：

| 记录数 | 旧实现 | 新实现 | 加速 |
|---|---|---|---|
| 1,000 | 2.02–2.32 ms | 1.12–1.25 ms | 1.80×–1.87× |
| 10,000 | 21.95–24.24 ms | 11.71–12.30 ms | 1.88×–1.97× |
| 50,000 | 111.63–125.67 ms | 56.60–59.74 ms | 1.97×–2.10× |

这些数比上一版（融合单趟、不缓存模长：1.24×–1.69×，区间抖动到 0.85×–2.08×）稳定得多，
因为每条记录每查询的乘法从 2·d 降到 1·d。**仍然只是桌面 JVM 的观测**，手机端倍数要真机复测，
不能把上表当作设备承诺。

等价性（同一 mmap 快照、60 次随机查询、limit=100）：`max |Δscore| = 3.7e-9`，排名变化 0/60。

## 召回质量（A/B，答案已知）

「与旧算法排位一致」只证明回归兼容，不证明检索质量。为此新增 `VectorSearchRecallQualityTest`：
20 个簇 × 20 条、768 维、查询为目标向量加噪（σ=0.12），**真值由量化前的浮点向量精确计算**，
200 次查询：

- `recall@5 = 0.994`，`rank-1 = 1.000`，最高分误差 `8.6e-4`

即 int8 存储本身在这个夹具上丢掉约 0.6% 的 top-5 邻居；新打分器与旧的余弦实现完全一致，
所以这部分损失来自量化格式，不是本轮改动引入的。真实模型召回率仍需真机、真实记忆样本评估。

## 未做（刻意）

- **查询向量量化**：会引入新的排名误差，需要先做准确率对照，本轮不做。
- **`textByHash` 预建索引**：`VectorMemoryRetriever` 每次调用要 `groupBy` 一遍文本记录。
  实测 200/1000/5000 条分别为 0.46/0.70/2.22 ms，发送路径一次会调用两遍。这个量级低于
  一次嵌入或一次网络往返，先不引入跨层缓存状态；若后续文本量级上万再补。
- **复用键不含「嵌入输入策略版本」**：复用条件是 `modelId + dimensions + id + sourceTextSha256`。
  记忆路径嵌入的是 `formatDocument(text)`（无标题），所以文本哈希等价于嵌入输入哈希**的前提**是
  `EmbeddingGemma2TextPolicy.POLICY_VERSION` 与模型文件不变。策略或模型文件换了但 `modelId`
  仍相同，旧向量会被错误复用。修这个要动快照身份（`modelId` 加策略版本或新增字段），
  会让现有索引全量重建一次，留给下一轮决定。
- **十六进制查表**：`sourceTextSha256` 已 `by lazy`，叠加文本缓存后每进程每条只算一次，收益趋近于零。
- **流式编码 / 32 字节定长哈希 / 新快照格式**：属于格式迁移，单独评估。

## 验证

- `VectorSimilaritySearchQuantizedTest`：量化路径 vs 反量化路径等价、稳定并列、有界选择前缀一致。
- `VectorSearchRecallQualityTest`：量化快照的 ground-truth `recall@5` 与 `rank-1`、重复查询分数不漂移。
- `MemoryIndexRebuilderIncrementalTest`：复用计数、模型/维度失效、批大小、删除、分数不变。
- `MemoryTextStoreCacheTest`（androidTest）：跨实例命中与失效、同长度重写、损坏不缓存、`update` 可见性。
