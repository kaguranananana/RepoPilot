# RepoPilot 最小评估任务集

本文档定义 Task 14 的最小评估链路。当前评估入口只运行 `SCRIPTED_RUNTIME` 场景，用确定性脚本模型驱动真实 `AgentLoop`、工具注册、权限策略、审批处理、diff review 和 trace 发布链路。它不能代表真实模型 provider 的成功率。

## 运行入口

```bash
mvn -pl repopilot-cli -am test
```

当前仓库还没有配置可直接分发的 CLI fat jar 或模块级 exec 入口；`eval` 子命令已接入 `RepoPilotCliCommand`，命令级链路由 `EvalCommandTest` 覆盖。后续如果补 CLI 打包任务，可以直接通过 `repopilot eval` 暴露同一入口。

默认输出：

- 工作区根目录：`target/repopilot-eval-workspaces`
- 结构化报告：`target/repopilot-eval-report.json`
- 运行类型：`SCRIPTED_RUNTIME`

真实模型 E2E 建议显式设置：

```bash
REPOPILOT_MODEL_PROVIDER=openai-compatible
OPENAI_COMPATIBLE_API_KEY=your-api-key
OPENAI_COMPATIBLE_BASE_URL=https://your-openai-compatible-endpoint/v1
OPENAI_COMPATIBLE_MODEL=your-model-id
REPOPILOT_MAX_STEPS=16
```

交互式 CLI 默认使用 12 步；真实模型编码任务如果涉及审批拒绝后重试、补丁修正或额外验证，可以把该值调到 16。非法值会直接报错，不会静默回退。

如果后续接入真实模型 provider，必须用独立场景和独立报告标记 `REAL_MODEL_PROVIDER`，不能把 scripted runtime 的成功率复用成真实模型成功率。

## 核心指标

- `toolCallValidRate`：通过工具注册表和必填参数校验的工具调用数 / 模型尝试发起的工具调用数。
- `taskSuccessRate`：通过场景验收的任务数 / 总任务数。
- `avgSteps`：每个任务实际进入模型循环的平均 step 数。
- `avgDurationMillis`：每个任务从夹具重建到验收结束的平均耗时。

报告同时记录逐场景诊断字段：

- `failureStage`
- `recentToolCall`
- `finalError`
- `recentTraceRef`

## 固定 scripted 场景

| 场景 ID | 覆盖能力 | 验收重点 |
| --- | --- | --- |
| `code-search` | 代码搜索 | `grep_files` 能在固定夹具里找到目标状态。 |
| `file-read` | 文件读取 | `read_file` 返回真实文件内容。 |
| `patch-edit` | 补丁修改 | `apply_patch` 后目标文件从 `draft` 变成 `ready`。 |
| `command-validation` | 命令验证 | `run_command` 返回 `exitCode: 0`。 |
| `skill-activation` | Skill 激活 | `activate_skill` 能加载场景内 `.repopilot/skills`。 |
| `read-failure-exposure` | 工具失败暴露 | 不存在文件错误被回注给模型，而不是被吞掉。 |
| `context-compaction` | 上下文压缩 | 多轮读取触发 `CONTEXT_COMPACTION_COMPLETED` trace。 |
| `write-file` | 整文件写入 | `write_file` 创建新文件并写入预期内容。 |
| `multi-tool-round` | 单轮多工具 | 同一轮模型响应里的多个工具调用按顺序执行。 |
| `command-failure-exposure` | 命令失败暴露 | 非零退出码以结构化工具结果返回。 |

## 设计边界

- 评估链路复用主 runtime，不改写 `AgentLoop` 语义。
- 每个场景运行前都会重建自己的子工作区，避免上次评估残留影响结果。
- 评估场景里的写文件、补丁和命令执行使用固定审批通过，因为这些场景运行在受控临时工作区内。
- 当前不做真实模型 provider 评估；如果用户显式选择 `REAL_MODEL_PROVIDER`，命令会直接报错暴露未接入状态。
- 报告使用 JSON，是为了支持不同版本之间做结构化对比。
