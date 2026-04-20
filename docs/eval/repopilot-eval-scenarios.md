# RepoPilot 最小评估任务集

本文档定义 Task 14 的最小评估链路。当前评估入口支持两类运行口径：

- `SCRIPTED_RUNTIME`：用确定性脚本模型驱动真实 `AgentLoop`、工具注册、权限策略、审批处理、diff review 和 trace 发布链路，作为稳定回归基线。
- `REAL_MODEL_PROVIDER`：在独立场景集上使用真实 OpenAI 兼容模型 provider，评估真实模型与 runtime 主链路的联动效果。

两类口径必须使用独立场景和独立报告，不能混算成功率。

## 运行入口

```bash
mvn -pl repopilot-cli -am test
```

当前仓库还没有配置可直接分发的 CLI fat jar 或模块级 exec 入口；`eval` 子命令已接入 `RepoPilotCliCommand`，命令级链路由 `EvalCommandTest` 覆盖。后续如果补 CLI 打包任务，可以直接通过 `repopilot eval` 暴露同一入口。

`SCRIPTED_RUNTIME` 默认输出：

- 工作区根目录：`target/repopilot-eval-workspaces`
- 结构化报告：`target/repopilot-eval-report.json`
- 运行类型：`SCRIPTED_RUNTIME`

`REAL_MODEL_PROVIDER` 默认输出：

- 工作区根目录：`target/repopilot-real-model-eval-workspaces`
- 结构化报告：`target/repopilot-real-model-eval-report.json`
- 运行类型：`REAL_MODEL_PROVIDER`

真实模型评估前需要显式设置：

```bash
REPOPILOT_MODEL_PROVIDER=openai-compatible
OPENAI_COMPATIBLE_API_KEY=your-api-key
OPENAI_COMPATIBLE_BASE_URL=https://your-openai-compatible-endpoint/v1
OPENAI_COMPATIBLE_MODEL=your-model-id
```

如果希望直接运行真实模型评估，可显式执行：

```bash
java -cp "<cli-classpath>" com.repopilot.cli.RepoPilotCliApplication eval --runtime-kind REAL_MODEL_PROVIDER
```

真实模型评估场景自身的 `maxSteps` 已在场景定义里单独设置；交互式 CLI 的 `REPOPILOT_MAX_STEPS` 配置不会覆盖 eval 场景的固定步数。非法值会直接报错，不会静默回退。

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

## 固定 real-model 场景

| 场景 ID | 覆盖能力 | 验收重点 |
| --- | --- | --- |
| `code-search` | 代码搜索 | 真实模型按提示选择 `grep_files`，并找到 `status=draft`。 |
| `file-read` | 文件读取 | 真实模型按提示选择 `read_file`，并读到真实文件内容。 |
| `patch-edit` | 补丁修改 | 真实模型构造 `apply_patch` 补丁，把目标文件从 `draft` 改成 `ready`，再运行命令验证。 |
| `command-validation` | 命令验证 | 真实模型按提示运行 `grep -n 'status=ready'`，且不修改目标文件。 |
| `search-read-patch-command` | 端到端编码任务 | 真实模型在单一场景里按顺序完成 `grep_files -> read_file -> apply_patch -> run_command -> final`，并通过工具参数、最终文件内容和最终回答三重验收。 |

## 设计边界

- 评估链路复用主 runtime，不改写 `AgentLoop` 语义。
- 每个场景运行前都会重建自己的子工作区，避免上次评估残留影响结果。
- 评估场景里的写文件、补丁和命令执行使用固定审批通过，因为这些场景运行在受控临时工作区内。
- `REAL_MODEL_PROVIDER` 当前只支持 `openai-compatible` 配置；如果 `REPOPILOT_MODEL_PROVIDER` 不是该值，命令会直接报错暴露配置问题。
- 真实模型评估仍然不是交互式 REPL 端到端体验测试；它验证的是“真实模型 + 固定审批通过的 eval runtime”。
- 报告使用 JSON，是为了支持不同版本之间做结构化对比。
