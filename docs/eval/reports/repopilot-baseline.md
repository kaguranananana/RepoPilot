# RepoPilot Baseline Report

**日期：** 2026-04-20

**目的：** 沉淀当前 RepoPilot 的 scripted runtime 基线与真实模型最小产品门槛证据，作为后续收尾和优化的对照参考。

**结论：** 当前项目已经具备最小 runtime 闭环，并已补齐“真实模型端到端编码任务”这道最小产品门槛；但还不能对外宣称“产品级 coding agent 已完成”。更准确的表述是“Java coding agent runtime / 本地 coding agent 雏形”。

---

## 本次验证范围

本次基线覆盖 3 类已经实际执行过的验证：

1. 全量自动化测试：`mvn test`
2. scripted 评测链路：`RepoPilotCliApplication eval`
3. real-model 评测链路：`RepoPilotCliApplication eval --runtime-kind REAL_MODEL_PROVIDER`

## 已确认事实

- 多模块工程 `repopilot-protocol`、`repopilot-core`、`repopilot-cli`、`repopilot-server` 均可通过测试。
- scripted 评测能够重复执行，并输出结构化 JSON 报告。
- real-model 评测能够重复执行，并输出独立结构化 JSON 报告。
- real-model 固定任务集现在已经包含一条严格验收的端到端编码场景：`grep_files -> read_file -> apply_patch -> run_command -> final`。
- 当前 scripted 场景已经覆盖：
  - 代码搜索
  - 文件读取
  - 补丁修改
  - 命令验证
  - Skill 激活
  - 工具失败暴露
  - 上下文压缩
  - 整文件写入
  - 单轮多工具
  - 命令失败暴露

## 当前 scripted 基线指标

数据来源：`target/repopilot-eval-report.json`

- `runtimeKind`: `SCRIPTED_RUNTIME`
- `scenarioCount`: `10`
- `toolCallCount`: `13`
- `validToolCallCount`: `13`
- `toolCallValidRate`: `1.0`
- `taskSuccessRate`: `1.0`
- `avgSteps`: `2.2`
- `avgDurationMillis`: `5.5`

## 当前 real-model 基线指标

数据来源：`target/repopilot-real-model-eval-report.json`

- `runtimeKind`: `REAL_MODEL_PROVIDER`
- `scenarioCount`: `5`
- `toolCallCount`: `11`
- `validToolCallCount`: `11`
- `toolCallValidRate`: `1.0`
- `taskSuccessRate`: `1.0`
- `avgSteps`: `3.2`
- `avgDurationMillis`: `14246.2`

其中新增的 `search-read-patch-command` 场景会同时验证：

- 工具顺序必须是 `grep_files -> read_file -> apply_patch -> run_command`
- 关键工具参数必须符合预期
- 最终文件内容必须精确保持无关行不变
- 最终回答必须存在

## 当前阶段判定

从工程完成度看，RepoPilot 已经不是“概念验证”或“只会调模型接口的 Demo”：

- 已有真实的 Agent Loop、工具治理、补丁式编辑、命令验证、上下文压缩、Skill 激活、交互式 CLI 和 session / trace 控制面。
- scripted 评测已能证明 runtime 主链路在固定场景下可重复运行。

但从产品证据链看，RepoPilot 仍有明确缺口：

1. 缺 `Plan / Execute` 只读阶段。
2. 缺确定性循环检测。
3. 缺 Skill `allowed-tools` 的 prompt + runtime 双重约束。
4. 更复杂开放式提示下的补丁精度与回归验证约束仍需增强。

截至当前文档版本，README / demo / baseline 的口径已经完成收口；剩余缺口集中在运行时约束和更复杂提示下的鲁棒性。

因此，当前项目状态应定义为：

- `最小 runtime 闭环已具备`
- `最小产品门槛的第一道硬证据已完成`

## 面试可讲口径

当前可以安全表述为：

- `Java coding agent runtime`
- `本地 coding agent 雏形`
- `具备搜索 / 阅读 / 补丁修改 / 命令验证闭环的 Agent Runtime`

当前不应直接表述为：

- `产品级 coding agent 已完成`
- `通用 coding agent 产品`
- `稳定支持复杂多模块开发任务的编码代理`

## 下一步最短关键路径

1. 补 README、demo 和统一对外口径，避免把 scripted 能力讲成真实模型能力。
2. 按顺序补 `Plan / Execute`、确定性循环检测、Skill `allowed-tools` 运行时治理。
3. 继续补更复杂开放式提示下的真实交互验收，强化补丁精度与回归验证约束。
