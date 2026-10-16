# 任务冻结模板（Mistbell-Tavern 3.0）

> 用法：把本文件复制为 `tasks/<任务ID>.yaml`（例如 `tasks/T-2026-10-16-cq-01.yaml`），
> 填齐 YAML 部分，正文说明写在 `---` 之后。
> 门禁命令：
>
> ```bash
> python3 lint/collaboration-gate.py --phase pre-review --mode repo \
>   --repo . --freeze tasks/<任务ID>.yaml
> ```

```yaml
# ---- 身份 ----
task_id: T-YYYY-MM-DD-<短名>-NN
stage: DAILY            # DAILY | FINAL（MT-011：DAILY 默认不做完整打包/真机验收）

# ---- 基线（MT-001 / MT-002：按提交身份绑定，不按分支名）----
baseline_branch: codex/mistbell-development-3.0
baseline_commit: ba2ae9b3099fa7282ab58b35b2128f9bca89b1ee
baseline_source_pr: 29

# ---- 范围 ----
workline: collaboration-infrastructure   # 工作线；与 App 功能线分开（MT-007）
scope_included:
  - <本轮唯一目标，写具体交付物>
scope_excluded:
  - <明确排除项，例如：不推进 PR #30、不做完整 APK 打包>

# ---- PR（MT-008：创建 PR 必须记录工作线或隔离理由）----
creates_pr: false
isolation_rationale: null     # creates_pr=true 时必填

# ---- 阶段门禁开关（MT-011）----
requires_full_apk: false
requires_final_device_acceptance: false
explicit_scope_authorization: false   # true 时必须写出授权来源与理由
requires_independent_dual_model_review: false   # MT-007 禁止置 true

# ---- 状态声明（MT-013：不得越过证据升级）----
claimed_feature_status: NOT_IMPLEMENTED
# 可选：NOT_IMPLEMENTED | IN_PROGRESS | CODE_COMPLETE_PENDING_VERIFICATION
#       | AUTOMATED_TEST_PASSED | DEVICE_VERIFIED
source_sha: null              # 声明后三档时必填，40 位 SHA
evidence:
  automated_test: null        # 声明 AUTOMATED_TEST_PASSED 时必填
  device: null                # 声明 DEVICE_VERIFIED 时必填（含机型/系统/模型/后端）

# ---- 约束适用性（逐项表态；不适用要写理由，不得留空）----
constraints_applicability:
  # 只列本轮**涉及**的 MT 条目；其余不适用需在正文说明理由
  - id: MT-000
    applies: false
    note: <理由>
```

---

## 填写说明（正文，人工阅读）

### 1. 本轮唯一目标

用一句话写清交付物。若一句话写不下，说明范围过大，应拆分（但 MT-008：拆不等于就该新建 PR）。

### 2. 已确定但不属于本轮的目标

列出仍然存在、本轮不做的目标。这些目标**不因实现被删而消失**（MT-010）。

### 3. 证据计划

| 要证明的事 | 档位 | 具体手段 | 预计产出 |
|---|---|---|---|
| 例：结构字段一致 | METADATA_AUTOMATED | 门禁脚本 | 门禁输出 |
| 例：整理队列可恢复 | NEEDS_TEST | 故障注入单测 | 用例与结果 |
| 例：NPU 实际后端 | NEEDS_DEVICE | 真机日志 | 机型/后端证据 |

### 4. 本轮明确不做的事

逐条列出，并写明是"范围外"还是"被阻塞"。被阻塞项必须指出依赖对象，且不得牵连无依赖模块（MT-014）。

### 5. 收工时的自检

- [ ] 提交 SHA 已记录，且与 `source_sha` 一致
- [ ] 状态档位没有超过证据
- [ ] 未删测试、未吞异常、未做破坏性迁移
- [ ] 无凭据进入仓库 / 日志 / 快照
- [ ] 后置门禁已运行，`SKIP` 与 `NOTE` 原样保留
- [ ] 未实测项已显式标注为未验证
