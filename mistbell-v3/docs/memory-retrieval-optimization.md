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
- 单趟循环同时累加点积与平方和，`scale` 完全不参与运算。

**顺带消除的一个隐患**：旧路径对每个元素做 `byte * scale` 后校验 `isFinite`。解码器允许读到
`-128`，而 `scale` 上限是 `Float.MAX_VALUE / 127`，`-128 * scale` 会溢出为 `Infinity` 并抛异常。
新路径不乘 `scale`，这个溢出面自然消失。

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

## 实测（JVM，768 维，limit=100，每次 30 个查询取均值）

同一份 mmap 快照上，新旧实现各跑 3 轮：

| 记录数 | 旧实现 | 单趟 | 观察到的加速区间 |
|---|---|---|---|
| 1,000 | 1.55–3.49 ms | 0.92–2.81 ms | 1.24×–1.69× |
| 10,000 | 19.06–29.34 ms | 9.15–34.70 ms | 0.85×–2.08× |
| 50,000 | 111.66–139.00 ms | 69.88–152.99 ms | 0.91×–1.62× |

**区间很宽，因为桌面 JVM 的 GC 与 JIT 抖动远大于被测差异。** 能确定的只有：结果等价，
且没有证据显示新路径更慢；不能据此承诺手机上的具体倍数。真机收益需在设备上复测。

等价性（同一 mmap 快照、160 次随机查询）：`max |Δscore| = 8.9e-9`，超出 `1e-9` 的排名变化为 0。

## 未做（刻意）

- **查询向量量化**：会引入新的排名误差，需要先做准确率对照，本轮不做。
- **`textByHash` 预建索引**：当前库规模下 `groupBy` 可忽略，先不引入额外缓存状态。
- **十六进制查表**：`sourceTextSha256` 已 `by lazy`，叠加文本缓存后每进程每条只算一次，收益趋近于零。
- **流式编码 / 32 字节定长哈希 / 新快照格式**：属于格式迁移，单独评估。

## 验证

- `VectorSimilaritySearchQuantizedTest`：量化路径 vs 反量化路径等价、稳定并列、有界选择前缀一致。
- `MemoryIndexRebuilderIncrementalTest`：复用计数、模型/维度失效、批大小、删除、分数不变。
- `MemoryTextStoreCacheTest`（androidTest）：跨实例命中与失效、同长度重写、损坏不缓存、`update` 可见性。
