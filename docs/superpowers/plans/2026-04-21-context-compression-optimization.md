# RepoPilot 上下文压缩优化计划

## 背景

当前 RepoPilot 已实现基础上下文压缩与 `context-cost` 评测命令，能够通过本地估算和真实模型 usage 对比压缩前后的输入 token 消耗。现有真实 usage 评测在 `long-file-read` 场景下显示累计输入 token 降低 20%+，但压缩策略仍偏基础，后续优化重点应从“能压缩”升级为“按预算压缩、优先压工具输出、保留关键事实、可验证效果”。

## 关键假设

- Coding Agent 的上下文膨胀主要来自工具输出，而不是用户消息本身。
- 完整工具原文应保存在 Trace 中，模型 prompt 侧只保留继续任务所需的压缩视图。
- 压缩策略必须保证 OpenAI-compatible API 的消息协议合法，不能留下孤立的 `tool_result`。
- 压缩收益不能只看 token 降低，还要验证用户目标、计划阶段、关键文件、失败命令等核心状态没有丢失。

## 成功标准

- 支持基于 token budget 的压缩触发，而不是只依赖固定消息窗口。
- 支持规则化 microcompact，优先压缩旧工具输出并保留结构化证据。
- `context-cost` 报告能展示压缩触发原因、压缩层级、输入 token 降低比例。
- 新增关键事实保真评测，能够检查压缩后核心任务状态是否保留。
- PostgreSQL Trace 与模型可见上下文解耦：Trace 保存完整历史，prompt 只注入压缩后的工作状态。

## 阶段一：Token Budget 触发压缩

目标：把压缩触发从固定窗口升级为 token 预算判断。

实现思路：

- 为模型上下文配置 `contextWindowTokens`、`reservedOutputTokens`、`safetyBufferTokens`。
- 每次模型调用前估算完整输入 token，包括 system prompt、tool schema、Skill 内容、working memory、context summary、最近消息和工具结果。
- 当估算 token 超过阈值时触发压缩。
- 在 Trace 或评测报告中记录触发原因、压缩前 token、阈值和压缩后 token。

验收标准：

- 单元测试覆盖未超阈值不压缩、超过阈值触发压缩、压缩后消息协议仍合法。
- `context-cost` 报告能区分 `MESSAGE_WINDOW` 与 `TOKEN_BUDGET` 触发原因。

## 阶段二：Microcompact 工具输出压缩

目标：优先用规则压缩旧工具输出，减少不必要的模型摘要调用。

压缩对象：

- `read_file`：保留文件路径、读取范围、关键片段或摘要。
- `grep_files`：保留匹配文件、行号和命中数量。
- `run_command`：保留命令、退出码、关键错误、尾部输出。
- `apply_patch`：保留修改文件、成功状态和 diff 摘要。

实现思路：

- 引入可压缩工具白名单。
- 最近若干条工具结果保留原文，旧工具结果替换为结构化压缩块。
- 工具原始输出继续写入 Trace，prompt 侧只保留压缩视图。
- 压缩时必须保留 assistant tool call 与 tool result 的配对关系。

验收标准：

- 对长文件读取、长命令日志、批量搜索结果构造测试场景。
- 评测报告能展示 microcompact 单独节省的 token。
- 真实模型调用不再出现孤立 `tool_result` 或协议顺序错误。

## 阶段三：结构化模型摘要

目标：当规则压缩不足时，引入模型生成结构化上下文摘要。

摘要字段：

```json
{
  "user_goal": "用户当前目标",
  "current_phase": "PLAN 或 EXECUTE",
  "plan_state": "当前计划状态",
  "touched_files": ["已读取或修改的文件"],
  "important_findings": ["关键发现"],
  "failed_commands": ["失败命令和错误原因"],
  "decisions": ["已确认的设计决策"],
  "next_actions": ["下一步动作"]
}
```

实现思路：

- 压缩模型只输出结构化 JSON，不允许调用工具。
- 摘要结果解析失败时暴露错误，不静默吞掉。
- 摘要通过校验后写入 context summary，并在后续 prompt 中替代完整历史。

验收标准：

- 测试覆盖结构化摘要解析、字段缺失校验、非法 JSON 暴露错误。
- 摘要内容能被后续上下文构建逻辑稳定消费。

## 阶段四：关键事实保真评测

目标：补足“压缩后是否丢状态”的验证能力。

评测字段：

- 用户目标是否保留。
- 当前 Plan / Execute 阶段是否保留。
- 已读文件和已改文件是否保留。
- 失败命令、退出码和关键错误是否保留。
- 已确认决策和下一步动作是否保留。

实现思路：

- 在 `context-cost` 场景中为每个任务定义 expected facts。
- 压缩后从 prompt 视图中提取 retained facts。
- 报告输出 token reduction 与 fact retention 两类指标。

验收标准：

- 报告中展示关键事实保留率。
- 至少覆盖长文件读取、测试失败排查、Plan/Execute 切换三个场景。

## 阶段五：Trace 与 Prompt 上下文解耦

目标：让 PostgreSQL Trace 成为完整历史来源，prompt 只承载当前执行所需状态。

实现思路：

- Trace 保存完整模型调用、工具参数、工具输出和阶段流转。
- Prompt 构建只读取工作记忆、结构化摘要、最近消息和必要工具摘要。
- 回放、审计和失败定位通过 Trace 查询完成，不依赖 prompt 中保留完整历史。

验收标准：

- 单次会话可以通过 Trace 回放完整执行过程。
- 模型 prompt 中不再长期携带旧工具原文。
- 文档说明 Trace 与 prompt 上下文的职责边界。

## 暂不纳入范围

- 不实现 Claude Code 风格的 `cache_edits`，该能力依赖特定模型服务端缓存 API。
- 不引入 GrowthBook 或远程特性开关，避免增加与核心链路无关的复杂度。
- 不实现多层 fallback 或静默降级；压缩失败应暴露明确错误。
- 不优先实现 Fork Agent full compact，当前阶段先用单次结构化摘要满足需求。

## 推荐实现顺序

1. Token Budget 触发压缩。
2. Microcompact 工具输出压缩。
3. 扩展 `context-cost` 报告，拆分 microcompact 与完整压缩收益。
4. 结构化模型摘要。
5. 关键事实保真评测。
6. Trace 与 Prompt 上下文解耦文档和回放验证。

## 简历落点

如果完成阶段一到阶段四，可以将简历中的上下文管理条目升级为：

```text
设计基于 token budget 的分层上下文压缩策略，先通过规则化 microcompact 清理旧工具输出，再将历史对话压缩为工作记忆、上下文摘要与最近消息窗口；结合真实 usage 与关键事实保真评测，在长轮次编码任务中将输入 token 降低 20%+，并保留用户目标、计划阶段、关键文件和失败命令等核心状态。
```
