#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""collaboration-gate.py — Mistbell-Tavern 协作契约结构门禁

设计范围（重要，不要误读本脚本的能力）
--------------------------------------------------
本门禁当前是 **结构化元数据门禁**，只做两类事：

  1. METADATA 档：契约文件自身的完整性、封闭性、引用完整性、状态枚举合法性、
     冻结字段（--freeze 提供时）与契约基线的一致性。
  2. REPO 档（--mode repo）：对本地 git 仓库做 **只读** 查询，
     核对基线提交是否存在并与契约声明一致、受保护路径是否真实存在、
     schema 导出版本文件是否落在契约声明的范围内。

本门禁 **不会**：
  * 运行任何构建、打包、单测、迁移或真机验收；
  * 访问网络或 GitHub API；
  * 证明任何行为类约束（NEEDS_TEST / NEEDS_DEVICE / NEEDS_HUMAN）成立。

因此：字段齐全 != 行为通过。哪些约束属于"只做结构检查"，
由契约里的 `adjudication` 字段逐条声明，本脚本按其分类报告，绝不升级。

用法
----
  python3 lint/collaboration-gate.py --mode metadata
  python3 lint/collaboration-gate.py --mode repo --repo .
  python3 lint/collaboration-gate.py --mode all --phase pre-review --freeze tasks/task-freeze.yaml
  python3 lint/collaboration-gate.py --json   # 额外输出机器可读结果

退出码
------
  0 = 通过
  1 = 存在失败项
  2 = 用法错误 / 契约无法加载（不视为"约束被满足"）
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

DEFAULT_CONTRACT = os.path.join(".cq", "collaboration-contract.yaml")

# 契约必须覆盖的约束总数（阶段 1 提取：MT-001 … MT-052）
EXPECTED_MT_COUNT = 52

MT_ID_RE = re.compile(r"^MT-\d{3}$")
RC_ID_RE = re.compile(r"^RC-\d{3}$")

REQUIRED_CONSTRAINT_FIELDS = (
    "id",
    "category",
    "source_document",
    "source_quote",
    "rule",
    "trigger",
    "action",
    "enforcement",
    "adjudication",
    "mechanism",
    "owner",
    "related",
)

# 各 adjudication 档位能给出的证据上限。用于报告，不允许越档。
ADJUDICATION_EVIDENCE = {
    "METADATA_AUTOMATED": "结构+冻结字段一致性（本门禁可判定）",
    "METADATA_STRUCTURAL_ONLY": "仅字段/记录齐全，不代表行为成立",
    "REPO_AUTOMATED": "本地 git 只读状态（需 --mode repo）",
    "NEEDS_TEST": "必须由自动化测试/故障注入提供证据",
    "NEEDS_DEVICE": "必须由真机或真实服务提供证据",
    "NEEDS_HUMAN": "必须人工裁决",
}

ACTION_VOCAB = {"BLOCK", "REJECT"}


# ---------------------------------------------------------------------------
# 结果收集
# ---------------------------------------------------------------------------
@dataclass
class Findings:
    failures: List[Tuple[str, str]] = field(default_factory=list)
    notes: List[Tuple[str, str]] = field(default_factory=list)
    checks_run: List[str] = field(default_factory=list)
    skipped: List[Tuple[str, str]] = field(default_factory=list)

    def fail(self, scope: str, message: str) -> None:
        self.failures.append((scope, message))

    def note(self, scope: str, message: str) -> None:
        self.notes.append((scope, message))

    def skip(self, scope: str, message: str) -> None:
        self.skipped.append((scope, message))

    def ran(self, name: str) -> None:
        self.checks_run.append(name)

    @property
    def ok(self) -> bool:
        return not self.failures


# ---------------------------------------------------------------------------
# 最小 YAML 子集解析器
#
# 支持：块映射、块序列、行内流序列 [a, b]、带引号与不带引号的标量、
#       `#` 注释（不在引号内时）、空值。不支持：锚点、多行标量、复杂嵌套折叠。
# 契约文件被限制在该子集内，因此无需第三方依赖。
# ---------------------------------------------------------------------------
class YamlSubsetError(ValueError):
    pass


def _strip_comment(line: str) -> str:
    out = []
    quote: Optional[str] = None
    i = 0
    while i < len(line):
        ch = line[i]
        if quote:
            out.append(ch)
            if ch == "\\" and i + 1 < len(line):
                out.append(line[i + 1])
                i += 2
                continue
            if ch == quote:
                quote = None
        else:
            if ch in "\"'":
                quote = ch
                out.append(ch)
            elif ch == "#":
                break
            else:
                out.append(ch)
        i += 1
    return "".join(out).rstrip()


def _parse_scalar(raw: str) -> Any:
    text = raw.strip()
    if text == "":
        return None
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'":
        body = text[1:-1]
        if text[0] == '"':
            return body.replace('\\"', '"').replace("\\\\", "\\")
        return body
    if text.startswith("[") and text.endswith("]"):
        inner = text[1:-1].strip()
        if not inner:
            return []
        return [_parse_scalar(part) for part in _split_flow(inner)]
    if text in ("true", "True"):
        return True
    if text in ("false", "False"):
        return False
    if text in ("null", "~"):
        return None
    if re.fullmatch(r"-?\d+", text):
        return int(text)
    if re.fullmatch(r"-?\d+\.\d+", text):
        return float(text)
    return text


def _split_flow(inner: str) -> List[str]:
    parts, buf, quote = [], [], None
    for ch in inner:
        if quote:
            buf.append(ch)
            if ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
            buf.append(ch)
        elif ch == ",":
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(ch)
    if buf:
        parts.append("".join(buf))
    return [p.strip() for p in parts if p.strip() != ""]


def _tokenize(text: str) -> List[Tuple[int, str]]:
    tokens: List[Tuple[int, str]] = []
    for raw_line in text.splitlines():
        if not raw_line.strip():
            continue
        stripped = _strip_comment(raw_line)
        if not stripped.strip():
            continue
        indent = len(stripped) - len(stripped.lstrip(" "))
        if "\t" in stripped[:indent]:
            raise YamlSubsetError("缩进中不允许使用制表符")
        tokens.append((indent, stripped.strip()))
    return tokens


def load_yaml_subset(text: str) -> Any:
    tokens = _tokenize(text)
    if not tokens:
        return {}
    value, index = _parse_block(tokens, 0, tokens[0][0])
    if index != len(tokens):
        raise YamlSubsetError("存在无法归约的剩余行，可能是缩进不一致")
    return value


def _parse_block(tokens: List[Tuple[int, str]], index: int, indent: int) -> Tuple[Any, int]:
    if index >= len(tokens):
        return None, index
    if tokens[index][1].startswith("- "):
        return _parse_sequence(tokens, index, indent)
    return _parse_mapping(tokens, index, indent)


def _parse_sequence(tokens: List[Tuple[int, str]], index: int, indent: int) -> Tuple[List[Any], int]:
    items: List[Any] = []
    while index < len(tokens):
        cur_indent, body = tokens[index]
        if cur_indent < indent or not body.startswith("- "):
            break
        if cur_indent > indent:
            raise YamlSubsetError(f"序列项缩进异常：{body!r}")
        payload = body[2:].strip()
        index += 1
        if payload == "":
            child, index = _parse_block(tokens, index, indent + 2)
            items.append(child)
            continue
        if ":" in payload and not payload.startswith("["):
            key, _, rest = payload.partition(":")
            item: Dict[str, Any] = {}
            rest = rest.strip()
            if rest == "":
                # 可能是嵌套块，也可能是空值
                if index < len(tokens) and tokens[index][0] > indent:
                    child, index = _parse_block(tokens, index, tokens[index][0])
                    item[key.strip()] = child
                else:
                    item[key.strip()] = None
            else:
                item[key.strip()] = _parse_scalar(rest)
            # 同一条目的后续键
            while index < len(tokens):
                nxt_indent, nxt_body = tokens[index]
                if nxt_indent <= indent or nxt_body.startswith("- "):
                    break
                if ":" not in nxt_body:
                    raise YamlSubsetError(f"映射项缺少 ':'：{nxt_body!r}")
                sub_key, _, sub_rest = nxt_body.partition(":")
                sub_key, sub_rest = sub_key.strip(), sub_rest.strip()
                index += 1
                if sub_rest == "":
                    if index < len(tokens) and tokens[index][0] > nxt_indent:
                        child, index = _parse_block(tokens, index, tokens[index][0])
                        item[sub_key] = child
                    else:
                        item[sub_key] = None
                else:
                    item[sub_key] = _parse_scalar(sub_rest)
            items.append(item)
        else:
            items.append(_parse_scalar(payload))
    return items, index


def _parse_mapping(tokens: List[Tuple[int, str]], index: int, indent: int) -> Tuple[Dict[str, Any], int]:
    result: Dict[str, Any] = {}
    while index < len(tokens):
        cur_indent, body = tokens[index]
        if cur_indent < indent:
            break
        if cur_indent > indent:
            raise YamlSubsetError(f"映射缩进跳级：{body!r}")
        if body.startswith("- "):
            break
        if ":" not in body:
            raise YamlSubsetError(f"映射项缺少 ':'：{body!r}")
        key, _, rest = body.partition(":")
        key, rest = key.strip(), rest.strip()
        index += 1
        if rest == "":
            if index < len(tokens) and tokens[index][0] > cur_indent:
                child, index = _parse_block(tokens, index, tokens[index][0])
                result[key] = child
            elif index < len(tokens) and tokens[index][0] == cur_indent and tokens[index][1].startswith("- "):
                child, index = _parse_sequence(tokens, index, cur_indent)
                result[key] = child
            else:
                result[key] = None
        else:
            result[key] = _parse_scalar(rest)
    return result, index


# ---------------------------------------------------------------------------
# git 只读辅助
# ---------------------------------------------------------------------------
def git(repo: str, *args: str) -> Tuple[int, str]:
    try:
        proc = subprocess.run(
            ["git", "-C", repo, *args],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as exc:  # pragma: no cover
        return 127, str(exc)
    return proc.returncode, (proc.stdout or proc.stderr).strip()


# ---------------------------------------------------------------------------
# 检查项
# ---------------------------------------------------------------------------
def check_contract_metadata(contract: Dict[str, Any], contract_path: str, found: Findings) -> None:
    scope = "contract"

    # 1. 顶层字段
    found.ran("contract.top_level_fields")
    for key in ("contract_version", "baseline", "enforcement_types", "adjudication_levels", "constraints"):
        if key not in contract or contract[key] in (None, "", [], {}):
            found.fail(scope, f"缺少顶层字段或字段为空：{key}")

    # 2. adjudication 词表与声明一致
    found.ran("contract.adjudication_vocabulary")
    declared_levels = contract.get("adjudication_levels") or []
    if set(declared_levels) != set(ADJUDICATION_EVIDENCE):
        found.fail(
            scope,
            "adjudication_levels 与脚本内置档位不一致："
            f"契约={sorted(declared_levels)} 脚本={sorted(ADJUDICATION_EVIDENCE)}",
        )

    # 3. enforcement 类型词表必须覆盖阶段 1 的五个类型
    found.ran("contract.enforcement_types")
    enforcement_types = contract.get("enforcement_types") or {}
    expected_enforcement = {"METADATA_GATE", "REPOSITORY_CHECK", "AUTOMATED_TEST", "DEVICE_EVIDENCE", "HUMAN_REVIEW"}
    missing_enforcement = expected_enforcement - set(enforcement_types)
    if missing_enforcement:
        found.fail(scope, f"enforcement_types 缺少定义：{sorted(missing_enforcement)}")

    constraints = contract.get("constraints") or []
    if not isinstance(constraints, list):
        found.fail(scope, "constraints 必须是序列")
        return

    # 4. 数量与 ID 覆盖
    found.ran("contract.constraint_count_and_ids")
    ids = [c.get("id") for c in constraints if isinstance(c, dict)]
    if len(constraints) != EXPECTED_MT_COUNT:
        found.fail(scope, f"约束条数为 {len(constraints)}，期望 {EXPECTED_MT_COUNT}")
    expected_ids = [f"MT-{i:03d}" for i in range(1, EXPECTED_MT_COUNT + 1)]
    missing_ids = [i for i in expected_ids if i not in ids]
    extra_ids = [i for i in ids if i not in expected_ids]
    if missing_ids:
        found.fail(scope, f"缺少约束 ID：{missing_ids}")
    if extra_ids:
        found.fail(scope, f"出现未登记的约束 ID：{extra_ids}")
    if len(set(ids)) != len(ids):
        found.fail(scope, "存在重复的约束 ID")

    # 5. 逐条字段与取值
    found.ran("contract.constraint_fields")
    rc_referenced = set((contract.get("rc_registry") or {}).get("referenced_ids") or [])
    for item in constraints:
        if not isinstance(item, dict):
            found.fail(scope, "constraints 中存在非映射条目")
            continue
        cid = str(item.get("id") or "<无 id>")
        for field_name in REQUIRED_CONSTRAINT_FIELDS:
            if field_name not in item or item[field_name] in (None, "", [], {}):
                found.fail(cid, f"缺少必填字段：{field_name}")
        if not MT_ID_RE.match(cid):
            found.fail(cid, "ID 形式不符合 MT-NNN")
        if item.get("action") not in ACTION_VOCAB:
            found.fail(cid, f"action 取值非法：{item.get('action')!r}（允许 {sorted(ACTION_VOCAB)}）")
        enforcement = item.get("enforcement") or []
        if not isinstance(enforcement, list) or not enforcement:
            found.fail(cid, "enforcement 必须是非空序列")
        else:
            unknown = [e for e in enforcement if e not in expected_enforcement]
            if unknown:
                found.fail(cid, f"enforcement 含未定义类型：{unknown}")
        if item.get("adjudication") not in ADJUDICATION_EVIDENCE:
            found.fail(cid, f"adjudication 取值非法：{item.get('adjudication')!r}")
        for ref in item.get("related") or []:
            if MT_ID_RE.match(str(ref)):
                if str(ref) not in expected_ids:
                    found.fail(cid, f"引用了不存在的 MT 约束：{ref}")
            elif RC_ID_RE.match(str(ref)):
                if rc_referenced and str(ref) not in rc_referenced:
                    found.fail(cid, f"引用的 RC 约束不在 rc_registry.referenced_ids 中：{ref}")
            else:
                found.fail(cid, f"related 含非法约束编号：{ref!r}")

    # 6. RC 登记表必须如实标注正文缺失
    found.ran("contract.rc_registry_status")
    rc_registry = contract.get("rc_registry") or {}
    if rc_registry.get("status") != "TEXT_UNAVAILABLE_IN_REPO":
        found.note(scope, "rc_registry.status 未标注为 TEXT_UNAVAILABLE_IN_REPO；请确认 RC 正文来源。")
    if not rc_registry.get("referenced_ids"):
        found.fail(scope, "rc_registry.referenced_ids 为空，无法校验 RC 引用完整性")
    if not rc_registry.get("evidence"):
        found.fail(scope, "rc_registry 缺少 evidence 字段（无法追溯 RC 正文缺失的核查依据）")

    # 7. 状态枚举
    found.ran("contract.feature_status_enum")
    statuses = contract.get("feature_status_enum") or []
    required_statuses = {
        "NOT_IMPLEMENTED",
        "IN_PROGRESS",
        "CODE_COMPLETE_PENDING_VERIFICATION",
        "AUTOMATED_TEST_PASSED",
        "DEVICE_VERIFIED",
    }
    if not required_statuses.issubset(set(statuses)):
        found.fail(scope, f"feature_status_enum 缺少档位：{sorted(required_statuses - set(statuses))}")

    # 8. 日常轮次不得默认要求完整 APK / 真机验收（MT-011）
    found.ran("contract.stage_policy_daily")
    daily = ((contract.get("stage_policy") or {}).get("DAILY") or {})
    forbidden = set(daily.get("forbidden_by_default") or [])
    if not {"full_apk_packaging", "final_device_acceptance"}.issubset(forbidden):
        found.fail(scope, "stage_policy.DAILY 未把 full_apk_packaging / final_device_acceptance 列入默认禁止项")

    # 9. 接入点必须如实标注尚未接入
    found.ran("contract.gate_hooks_status")
    hooks = contract.get("gate_hooks") or {}
    if not hooks:
        found.fail(scope, "缺少 gate_hooks 段")
    for hook_name, hook in hooks.items():
        if not isinstance(hook, dict):
            continue
        status = hook.get("status")
        if status is None:
            found.fail(scope, f"gate_hooks.{hook_name} 缺少 status")
        elif status != "WIRED" and hook_name != "ci_integration" and "intended_command" not in hook:
            found.note(scope, f"gate_hooks.{hook_name} 未接入且未声明 intended_command")

    # 10. 基线字段完整性
    found.ran("contract.baseline_fields")
    baseline = contract.get("baseline") or {}
    for key in ("branch", "commit", "source_pr", "source_pr_head_sha", "verified_at"):
        if not baseline.get(key):
            found.fail(scope, f"baseline 缺少字段：{key}")
    commit = str(baseline.get("commit") or "")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        found.fail(scope, f"baseline.commit 不是 40 位 SHA：{commit!r}")
    hashsha = str(baseline.get("source_pr_head_sha") or "")
    if not re.fullmatch(r"[0-9a-f]{40}", hashsha):
        found.fail(scope, f"baseline.source_pr_head_sha 不是 40 位 SHA：{hashsha!r}")
    if commit and hashsha and commit != hashsha:
        found.note(
            scope,
            "3.0 基线与 PR #29 HEAD 当前不是同一提交；这是允许的状态，但需按 MT-002 重新登记派生关系。",
        )


def check_freeze(freeze: Dict[str, Any], contract: Dict[str, Any], found: Findings) -> None:
    scope = "freeze"
    found.ran("freeze.fields")
    baseline = contract.get("baseline") or {}

    for key in ("task_id", "stage", "baseline_branch", "baseline_commit"):
        if not freeze.get(key):
            found.fail(scope, f"冻结文件缺少字段：{key}")

    stage = freeze.get("stage")
    if stage not in ("DAILY", "FINAL"):
        found.fail(scope, f"stage 取值非法：{stage!r}（允许 DAILY / FINAL）")

    # MT-001：3.0 开发任务必须绑定指定分支，且用提交身份而非分支名
    if freeze.get("baseline_branch") and freeze["baseline_branch"] != baseline.get("branch"):
        found.fail(
            scope,
            f"MT-001 触发：baseline_branch={freeze['baseline_branch']!r} 与契约基线 {baseline.get('branch')!r} 不一致",
        )
    frozen_commit = str(freeze.get("baseline_commit") or "")
    if frozen_commit and not re.fullmatch(r"[0-9a-f]{40}", frozen_commit):
        found.fail(scope, f"baseline_commit 不是 40 位 SHA：{frozen_commit!r}")

    # MT-008：创建 PR 必须记录工作线或隔离理由
    if freeze.get("creates_pr") and not (freeze.get("workline") or freeze.get("isolation_rationale")):
        found.fail(scope, "MT-008 触发：creates_pr=true 但未记录 workline / isolation_rationale")

    # MT-011：日常轮次不得默认附加完整 APK 打包或最终真机验收
    if stage == "DAILY":
        if freeze.get("requires_full_apk") is True and not freeze.get("explicit_scope_authorization"):
            found.fail(scope, "MT-011 触发：DAILY 轮次要求完整 APK 打包且无显式授权范围")
        if freeze.get("requires_final_device_acceptance") is True and not freeze.get("explicit_scope_authorization"):
            found.fail(scope, "MT-011 触发：DAILY 轮次要求最终真机验收且无显式授权范围")

    # MT-007：不得把双模型审核加入 App 功能完成条件
    if freeze.get("requires_independent_dual_model_review") is True:
        found.fail(scope, "MT-007 触发：把独立双模型审核列为 App 功能完成条件")

    # MT-013：状态声明不得越过证据
    claimed = freeze.get("claimed_feature_status")
    if claimed is not None:
        statuses = contract.get("feature_status_enum") or []
        if claimed not in statuses:
            found.fail(scope, f"claimed_feature_status 不在枚举内：{claimed!r}")
        else:
            evidence = freeze.get("evidence") or {}
            if not isinstance(evidence, dict):
                found.fail(scope, "evidence 必须是映射")
            else:
                if claimed in ("CODE_COMPLETE_PENDING_VERIFICATION", "AUTOMATED_TEST_PASSED", "DEVICE_VERIFIED"):
                    if not freeze.get("source_sha"):
                        found.fail(
                            scope,
                            f"MT-049 触发：声明 {claimed} 但未绑定 source_sha（旧结果不得作为新版本证据）",
                        )
                if claimed == "AUTOMATED_TEST_PASSED":
                    if not evidence.get("automated_test"):
                        found.fail(scope, "MT-013 触发：声明 AUTOMATED_TEST_PASSED 但 evidence.automated_test 为空")
                if claimed == "DEVICE_VERIFIED":
                    if not evidence.get("device"):
                        found.fail(scope, "MT-013 触发：声明 DEVICE_VERIFIED 但 evidence.device 为空")
                    if not evidence.get("automated_test"):
                        found.fail(scope, "MT-013 触发：声明 DEVICE_VERIFIED 但缺少自动化测试证据")

    # 未覆盖的档位显式报告，避免"字段齐全"被误读为通过
    for level, description in ADJUDICATION_EVIDENCE.items():
        if level in ("NEEDS_TEST", "NEEDS_DEVICE", "NEEDS_HUMAN"):
            found.note(scope, f"{level} 不由本门禁判定（{description}）")


def check_repo(contract: Dict[str, Any], repo: str, found: Findings) -> None:
    scope = "repo"
    if not os.path.isdir(os.path.join(repo, ".git")) and not os.path.isfile(os.path.join(repo, ".git")):
        found.fail(scope, f"不是 git 工作区：{repo}")
        return

    baseline = contract.get("baseline") or {}
    declared_commit = str(baseline.get("commit") or "")

    # 1. 声明的基线提交必须真实存在
    found.ran("repo.baseline_commit_exists")
    code, out = git(repo, "cat-file", "-e", f"{declared_commit}^{{commit}}")
    if code != 0:
        found.fail(scope, f"声明的基线提交在本地仓库不可解析：{declared_commit}")
    else:
        found.note(scope, f"基线提交可解析：{declared_commit[:12]}")

    # 2. 受保护路径真实存在（用实际路径核实，不用模式名推测）
    found.ran("repo.protected_paths")
    protected = contract.get("protected_paths") or {}
    schema_dir = protected.get("schema_export_dir")
    if not schema_dir:
        found.fail(scope, "protected_paths.schema_export_dir 缺失")
    else:
        abs_dir = os.path.join(repo, schema_dir)
        if not os.path.isdir(abs_dir):
            found.fail(scope, f"契约声明的 schema 导出目录不存在：{schema_dir}")
        else:
            found.note(scope, f"schema 导出目录存在：{schema_dir}")

    # 3. schema 版本文件必须落在声明范围内
    found.ran("repo.schema_versions")
    declared_versions = protected.get("schema_versions_present") or []
    if schema_dir and os.path.isdir(os.path.join(repo, schema_dir)):
        actual_versions = sorted(
            int(m.group(1))
            for m in (
                re.fullmatch(r"(\d+)\.json", name)
                for name in os.listdir(os.path.join(repo, schema_dir))
            )
            if m
        )
        if not actual_versions:
            found.fail(scope, f"schema 目录中没有版本化 JSON：{schema_dir}")
        else:
            undeclared = [v for v in actual_versions if v not in declared_versions]
            missing = [v for v in declared_versions if v not in actual_versions]
            if undeclared:
                found.fail(scope, f"仓库存在契约未声明的 schema 版本：{undeclared}")
            if missing:
                found.fail(scope, f"契约声明的 schema 版本在仓库中缺失：{missing}")
            if not undeclared and not missing:
                found.note(scope, f"schema 版本一致：{actual_versions}")

    # 4. 对齐测试文件存在
    found.ran("repo.schema_alignment_test")
    test_path = protected.get("schema_alignment_test")
    if test_path and not os.path.isfile(os.path.join(repo, test_path)):
        found.fail(scope, f"契约声明的 schema 对齐测试不存在：{test_path}")

    # 5. 当前 HEAD 与契约基线的关系：差异只报告，不阻断
    found.ran("repo.head_relation")
    code, head = git(repo, "rev-parse", "HEAD")
    if code == 0:
        if head.startswith(declared_commit[:12]) or declared_commit.startswith(head[:12]):
            found.note(scope, f"当前 HEAD 与契约基线一致：{head[:12]}")
        else:
            found.note(
                scope,
                f"当前 HEAD={head[:12]} 与契约基线 {declared_commit[:12]} 不同；"
                "本门禁不判定对错，按 MT-002 需在冻结文件中登记实际使用的提交。",
            )


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def load_contract(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as handle:
        data = load_yaml_subset(handle.read())
    if not isinstance(data, dict):
        raise YamlSubsetError("契约顶层必须是映射")
    return data


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="collaboration-gate.py",
        description="Mistbell-Tavern 协作契约结构门禁（只做结构化元数据与只读仓库检查）",
    )
    parser.add_argument("--contract", default=DEFAULT_CONTRACT, help=f"契约路径（默认 {DEFAULT_CONTRACT}）")
    parser.add_argument("--mode", choices=["metadata", "repo", "all"], default="metadata", help="检查档位")
    parser.add_argument("--phase", choices=["pre-review", "post-review", "standalone"], default="standalone")
    parser.add_argument("--freeze", help="冻结文件（YAML）路径；提供时校验冻结字段")
    parser.add_argument("--repo", default=".", help="git 工作区路径（--mode repo/all 时使用）")
    parser.add_argument("--json", action="store_true", help="额外输出机器可读 JSON 结果")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    found = Findings()
    try:
        contract = load_contract(args.contract)
    except (OSError, YamlSubsetError) as exc:
        print(f"[FATAL] 无法加载契约 {args.contract}: {exc}")
        return 2

    check_contract_metadata(contract, args.contract, found)

    if args.freeze:
        try:
            with open(args.freeze, "r", encoding="utf-8") as handle:
                freeze = load_yaml_subset(handle.read())
            if not isinstance(freeze, dict):
                raise YamlSubsetError("冻结文件顶层必须是映射")
            check_freeze(freeze, contract, found)
        except (OSError, YamlSubsetError) as exc:
            found.fail("freeze", f"无法加载冻结文件 {args.freeze}: {exc}")
    else:
        found.skip("freeze", "未提供 --freeze；本轮不校验冻结字段（不等于已通过）")

    if args.mode in ("repo", "all"):
        check_repo(contract, args.repo, found)
    else:
        found.skip("repo", "--mode metadata；未执行仓库检查（不等于已通过）")

    # MT-007：审核基础设施必须与 App 功能工作线分离
    found.ran("contract.review_tooling_separation")
    if (contract.get("pull_request_policy") or {}).get("required_independent_dual_model_review") is True:
        found.fail("contract", "MT-007 触发：契约把独立双模型审核设为必需门槛")

    # 报告
    adjudication_counts: Dict[str, int] = {}
    for item in contract.get("constraints") or []:
        if isinstance(item, dict):
            key = str(item.get("adjudication"))
            adjudication_counts[key] = adjudication_counts.get(key, 0) + 1

    print(f"契约：{args.contract}")
    print(f"阶段：{args.phase}    模式：{args.mode}")
    print(f"约束条数：{len(contract.get('constraints') or [])}（期望 {EXPECTED_MT_COUNT}）")
    print("判定档位分布（这些档位能给出的证据上限见下）：")
    for level in ADJUDICATION_EVIDENCE:
        if level in adjudication_counts:
            print(f"  - {level:<26} {adjudication_counts[level]:>3} 条   {ADJUDICATION_EVIDENCE[level]}")
    print(f"已执行检查：{len(found.checks_run)} 项 -> {', '.join(found.checks_run)}")

    if found.skipped:
        print("\n未执行的检查（不代表通过）：")
        for scope, message in found.skipped:
            print(f"  [SKIP] {scope}: {message}")

    if found.notes:
        print("\n说明（不构成通过，只是状态登记）：")
        for scope, message in found.notes:
            print(f"  [NOTE] {scope}: {message}")

    if found.failures:
        print("\n失败项：")
        for scope, message in found.failures:
            print(f"  [FAIL] {scope}: {message}")
    print("\n结论：" + ("结构门禁通过（仅限已执行检查的档位）" if found.ok else "结构门禁未通过"))
    print("提醒：本门禁不运行构建/测试/真机，NEEDS_TEST、NEEDS_DEVICE、NEEDS_HUMAN 档位未被验证。")

    if args.json:
        print("\n" + json.dumps(
            {
                "contract": args.contract,
                "phase": args.phase,
                "mode": args.mode,
                "ok": found.ok,
                "constraint_count": len(contract.get("constraints") or []),
                "adjudication_distribution": adjudication_counts,
                "checks_run": found.checks_run,
                "failures": [{"scope": s, "message": m} for s, m in found.failures],
                "notes": [{"scope": s, "message": m} for s, m in found.notes],
                "skipped": [{"scope": s, "message": m} for s, m in found.skipped],
            },
            ensure_ascii=False,
            indent=2,
        ))

    return 0 if found.ok else 1


if __name__ == "__main__":
    sys.exit(main())
