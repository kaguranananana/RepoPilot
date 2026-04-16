# RepoPilot 设计说明

**项目目标**

RepoPilot 是一个面向本地代码仓研发场景的 Java Coding Agent 平台，采用“本地执行 Runtime + 服务端 Control Plane”的双层架构。

它需要同时满足两类目标：

1. 学习目标：把 Agent Runtime、工具调用、权限治理、会话追踪这些核心机制真正做出来，而不是只包一层框架。
2. 简历目标：让项目既能体现 Java 后端工程能力，也能体现 Agent Runtime / Harness 工程能力。

---

**核心设计哲学**

RepoPilot 参考终端 Coding Agent 的通用设计方向，但不会机械复刻产品细节。项目采用以下核心哲学：

- `Less scaffolding, more model`
  模型负责规划、解释、选择工具；Runtime 负责工具边界、权限门控、可验证反馈与可审计执行。
- `Loop-first architecture`
  架构围绕 `model -> tool -> model` 主循环组织，而不是围绕聊天 UI 或框架入口组织。
- `Fail-closed`
  对工具参数、权限、文件修改、上下文压缩等关键动作优先暴露真实错误，不用模糊兜底掩盖主链路问题。
- `Precision-first`
  无论是记忆、Skill 还是后续文档检索，都遵循“宁缺毋滥”的注入原则，避免上下文被弱相关信息污染。

这意味着 RepoPilot 的目标不是做一个“功能很多”的 AI CLI，而是做一个“执行路径清晰、边界明确、可验证与可审计”的 Agent Runtime。

---

**核心边界**

RepoPilot 不是一个单纯聊天机器人，也不是一个只会调模型接口的 Spring AI Demo。

RepoPilot 的边界定义如下：

- `repopilot-core`
  负责 Agent Runtime。包含消息模型、`model -> tool -> model` 主循环、Tool Registry、权限策略、diff review、background task、skill loading。
- `repopilot-cli`
  负责本地执行入口。包含用户输入、本地审批交互、流式输出展示、对 server 的事件上报。
- `repopilot-server`
  负责 Control Plane。包含 session、task、trace、approval、diff summary 的存储、查询、回放、审计与 SSE 推送。
- `repopilot-protocol`
  负责共享协议。包含 CLI、core、server 共同依赖的 DTO、事件模型、JSON 序列化约定。

这里有一个关键设计原则：

- 本地代码仓的真实读写和命令执行发生在 `cli + core`
- `server` 不直接操作本地仓库，只做控制面

这样做是为了避免把“coding agent 的真实执行”错误地做成“远程服务端代执行”。

---

**运行时内部分层**

除了模块级边界，`repopilot-core` 内部也需要保持清晰分层，避免产品接入逻辑污染纯运行时内核。

推荐的内部职责拆分是：

- `AgentRunner`
  负责纯运行时闭环：消息准备、模型调用、工具执行、上下文治理、预算检查、终止条件。
- `AgentOrchestrator`
  负责把 session、approval、trace、checkpoint、server 上报这类产品层动作挂接到运行时。
- `SystemPromptBuilder / ContextCompactor / GovernedToolExecutor`
  作为独立协作者存在，不直接被 CLI 或 server 反向侵入。

这样做的价值是：

- CLI、server、未来 Web/IDE 接入不需要改动纯循环语义
- 评估链路可以直接复用同一套 runtime 内核
- 后续加 Hook、subagent、idle compaction 时不会让 `AgentLoop` 继续膨胀

一期不需要立刻把所有类名都定死，但必须按这个分层方向演进。

---

**Prompt 与动态边界**

RepoPilot 的 system prompt 不应是一个没有分层的大字符串，而应分成两个区域：

- 静态宪法
  包含身份、工具纪律、权限总则、行为约束、输出原则。这部分尽量稳定，便于长期维护和缓存。
- 动态政策
  包含 session preamble、当前工作区信息、Skill 摘要、预算提示、可用工具子集、后续记忆或文档检索结果。

除此之外，高频变化的 runtime metadata 也应与稳定 system prompt 隔离，例如：

- 当前时间
- session id
- 临时 checkpoint
- 恢复会话摘要
- 运行通道或入口元数据

这类内容更适合作为独立 runtime context 块，在当前 user turn 附近注入，而不是直接污染静态 system prompt。

两者之间应有显式边界，避免把工作区路径、用户偏好、临时上下文错误地混入静态前缀。这样做的价值在于：

- 便于调试 prompt 组装过程
- 便于未来做缓存和成本优化
- 便于控制哪些信息可以跨会话稳定复用，哪些只能按当前运行时注入

一期不做复杂 Prompt 缓存系统，但会先把静态/动态边界和拼装职责设计正确。

---

**生命周期 Hook**

RepoPilot 需要一组轻量但稳定的生命周期 Hook，用来承载运行时之外的横切能力，而不是把这些逻辑全部写死在主循环里。

一期建议预留这些阶段：

- `before_model_call`
- `after_model_response`
- `before_tool_execution`
- `after_tool_execution`
- `before_compaction`
- `after_compaction`
- `finalize_response`

这类 Hook 未来可承载：

- trace 上报
- SSE 推送
- 审批记录
- checkpoint
- telemetry
- 最终输出整形

它们应默认遵循 fail-closed 原则：关键 Hook 失败时暴露错误；非关键 Hook 则可选择隔离错误并记录日志，但不能悄悄改变主链路语义。

对于工具执行中的致命错误，还需要额外保证一件事：

- 如果本轮工具结果会直接终止主链路，那么 trace / telemetry 的关键记录必须在中断前就能落地，不能因为异常提前抛出而把最关键的一次失败丢掉。

---

**一期保留能力**

一期必须保留这些能力：

- 多轮 `model -> tool -> model` 执行闭环
- Tool Registry 与统一工具协议
- session / trace 基础控制面
- CLI 到 server 的最小 HTTP 协议
- 本地代码分析与工具调用主链路
- 命令执行与文件修改的安全边界
- diff review
- 结构化上下文压缩
- background task
- skill 按需加载
- 最小评估链路
- 静态 prompt 与动态 prompt 边界
- 工具治理流水线

---

**一期明确不做**

为了避免项目范围失控，一期明确不做：

- MCP 协议深水区
- 完整全屏 TUI
- 可写型 sub-agent
- worktree isolation
- skill marketplace
- 远程执行器
- Web 管理台
- 向量数据库长期记忆
- 大规模 benchmark 平台
- 产品级 IDE Bridge
- 产品级全量工具手册懒加载系统

这些能力不是“不重要”，而是放到二期后更合理，因为它们依赖更稳定的任务系统、隔离机制和控制面。

---

**运行时设计**

RepoPilot 一期的运行时主范式是：

- `ReAct` 作为主循环骨架
- 显式任务对象作为后续 `Plan-and-Execute` 的扩展点
- 事件驱动的反思与审查作为后续 `Reflection` 的扩展点

这意味着一期不会做一个复杂 planner，也不会每轮都让模型自我反思，而是先把最核心的执行闭环打牢。

RepoPilot 一期的主循环采用“八步闭环”的方式组织：

1. 准备消息
   组装 system prompt、历史消息与必要的动态上下文；如有需要先触发上下文压缩。
2. 调用模型
   把可发送的消息窗口交给模型适配层。
3. 收集响应
   解析模型输出，区分最终回答与工具调用意图。
4. 处理错误
   对可恢复错误做有限重试，对不可恢复错误显式终止，不做静默掩盖。
5. 执行工具
   通过统一的工具治理流水线执行工具，而不是直接把调用透传给工具实现。
6. 检查预算
   检查 `maxSteps`、上下文预算与后续可扩展的成本预算。
7. 注入结果继续
   把工具结果写回消息历史，继续下一轮推理。
8. 无工具则退出
   当模型不再请求工具时结束当前回合，并返回最终输出。

为了避免把真实错误伪装成普通工具反馈，工具执行结果在运行时语义上至少要区分三类：

- `SUCCESS`
  工具成功完成，结果可以直接回注给模型继续推理。
- `RECOVERABLE_ERROR`
  工具失败，但失败信息仍然适合暴露给模型，让模型修正参数、路径或策略后继续尝试。
- `FATAL_ERROR`
  工具失败且已经说明主链路语义失真，必须立刻终止当前回合，不能再伪装成普通 `TOOL` 消息继续运行。

这条边界应该尽早固定下来。否则后续把权限拒绝、schema 错误、执行器异常和普通业务失败混在一起时，运行时很容易在“该继续”与“该终止”之间失去一致性。

执行过程中的 session、trace、approval、diff summary 会同步到 server，以便后续回放与审计。

---

**工具治理与权限管道**

RepoPilot 的工具系统不是“按名字找到处理器然后直接执行”这么简单，而是要逐步演进成一条受治理的流水线。对于一次工具调用，一期至少要覆盖这些环节：

1. 输入解析与基础校验
2. Tool schema 校验
3. 业务级补充校验
4. 权限评估
5. 真正执行
6. 结构化结果封装
7. trace 与审计记录

权限评估遵循明确顺序：

- `deny`
- `ask`
- `allow`

也就是说，先判断硬拒，再判断是否需要人工确认，最后才允许执行。对路径访问、危险命令和文件写入都遵循这一原则。写文件还必须经过 diff review，不能让模型直接绕过审查落盘。

同样，工具治理层还应统一错误出口，而不是让运行时同时消费多套失败协议。无论失败来自：

- schema 校验
- 业务级校验
- 权限拒绝
- 工具实现自身异常

都应在治理层被收敛为结构化结果，至少明确落到 `RECOVERABLE_ERROR` 或 `FATAL_ERROR` 之一。这样 `AgentLoop` 只需要消费统一语义，而不用同时猜测“这是可恢复失败、硬拒绝，还是系统故障”。

一期不实现复杂的投机分类器或大量 Hook，但会预留 `pre-execution` / `post-execution` 扩展点，便于二期继续演化。

---

**上下文管理与评估**

一期把“上下文压缩”和“编码准确率”都纳入主线，但都做成受控版本。

上下文压缩的目标不是做一个复杂记忆系统，而是解决多轮工具调用后的上下文膨胀问题。RepoPilot 一期采用确定性的三层上下文结构：

- 固定上下文：system prompt、权限边界、当前任务目标
- 近期高保真上下文：最近若干轮 user / assistant / tool 交互
- 历史结构化摘要：把更早的执行轨迹压缩为 `context_summary`

`context_summary` 不做泛化聊天总结，而是保留运行时真正关键的信息：

- 已读取过的关键文件
- 已执行过的重要工具调用
- 已出现过的命令或工具错误
- 用户明确提出的约束
- 已确认的修改结论或审查结论

压缩触发条件采用显式阈值，而不是模糊启发式规则。这样在调试、回放和面试解释时都更可控。

对于后续记忆和文档检索，也遵循 `precision-first` 原则：

- 不确定相关则不注入
- 少而准优于多而杂
- 注入内容和消息历史共用同一份上下文预算

因此 RepoPilot 一期仍然是 `tool-first` 的 coding agent，而不是 `RAG-first` 的系统。源码检索优先依赖 `grep_files / read_file` 这类确定性工具；文档检索或长期记忆只会在后续阶段以受控方式引入。

除了运行时窗口内的 `context_summary`，RepoPilot 后续还应预留“结构化历史”和“耐久记忆”的分层空间：

- 会话内消息：当前活跃执行上下文
- 结构化历史：append-only 的运行摘要，用于归档和后续评估
- 耐久记忆：仓库级稳定事实或团队规范，必须可审计、可恢复、可版本化

对于 coding agent 来说，这类耐久记忆的重点不是人格设定，而是：

- 仓库约定
- 已确认的工程决策
- 长期有效的工作区事实

这部分更适合二期逐步引入，不应在一期抢占主链路。

编码准确率一期不把它定义成“模型主观上有多聪明”，而是落成一个最小评估链路。系统需要基于固定任务集输出至少这些指标：

- `tool_call_valid_rate`
- `patch_apply_success_rate`
- `build_or_test_pass_rate`
- `task_success_rate`
- `avg_steps`
- `avg_duration`

这样 RepoPilot 的效果评估不是主观印象，而是可重复执行、可对比的工程指标。

---

**Skills 与工具知识**

RepoPilot 保留 Skill 机制，但 Skill 的定位是“轻量工作流注入”，不是重插件平台。每个 Skill 应该描述：

- 适用场景
- 推荐步骤
- 工具使用边界
- 必要时的 `allowed-tools` 约束

Skill 生效后的有效工具集合应遵循：

`global policy ∩ skill allowed-tools`

这样 Skill 不会绕过全局权限，也不会把所有工具都默认打开。

单个工具的长说明不应一次性塞进基础 system prompt。RepoPilot 二期可进一步引入“工具手册按需加载”或 `tool_search` 式机制，让模型在需要时再读工具说明，而不是在每轮请求中背上全部工具文档。

同样，工具定义的输出顺序也应保持稳定。内置工具、动态工具和后续 MCP 工具都需要稳定排序，以便：

- 降低 prompt 抖动
- 提高缓存友好性
- 让调试和测试输出更稳定

---

**事件边界与入口适配**

虽然 RepoPilot 一期只做 CLI + server，但架构上应保留“入口适配层”和“runtime 核心”之间的事件边界。它不一定需要完整消息总线，但至少要让这些事件成为显式结构：

- 用户输入事件
- 工具开始/结束事件
- 审批请求/结果事件
- checkpoint 事件
- 最终输出事件

这样后续如果加 Web UI、IDE bridge 或评估 runner，就不需要直接侵入 runtime 主循环。

---

**控制面设计**

`repopilot-server` 的作用不是“帮模型改代码”，而是把运行过程从终端文本升级成可管理的数据。

一期控制面要承载的最小对象有：

- `Session`
  表示一次运行的全局容器。
- `TraceEvent`
  表示一次运行中的结构化步骤事件。
- `Task`
  先作为预留概念存在，后续接 planning、background task、sub-agent。
- `Approval`
  后续接入命令审批、文件写入审批。
- `DiffSummary`
  后续接入补丁审查和改动概览。

控制面的价值是：

- 可查询
- 可回放
- 可审计
- 可统计
- 可扩展

---

**实现顺序**

为了兼顾学习和稳定性，项目按这个顺序推进：

1. 多模块骨架与共享协议
2. server 最小 session / trace 控制面
3. core 最小 Tool Registry 与 Agent Loop
4. CLI 到 server 的 HTTP 客户端
5. 静态 prompt / 动态 prompt 边界
6. CLI `run` 主命令
7. 本地文件 / 搜索 / 命令工具
8. 运行时 trace 上报
9. 工具治理流水线与权限策略
10. 结构化上下文压缩
11. JPA + PostgreSQL 持久化
12. SSE 推送
13. background task
14. skill loading
15. 最小评估链路

二期优先演进这些方向：

- 工具手册按需加载
- 文档型 Agentic RAG
- 多 Agent 角色分工
- 单平面调度与防递归
- 结构化历史归档与耐久记忆
- idle session auto-compact

其中多 Agent 会遵循明确角色边界：

- `Explore` / `Plan` 只读
- `Worker` 负责实现
- `Verification` 与实现路径隔离
- 调度权只保留给父级或 Coordinator，不允许子 Agent 继续派生子 Agent

---

**当前进度**

截至当前，已经完成这些内容：

- 多模块 Maven 工程骨架
- 协议层首批模型与统一 JSON 序列化工厂
- server 最小 session / trace API
- core 最小 Tool Registry 与 Agent Loop
- CLI 到 server 的 Session HTTP 客户端
- 设计文档与实现计划已显式纳入 prompt 边界、工具治理、上下文压缩和评估方向

也就是说，当前项目已经不是空架子，而是具备了“协议层 + 最小控制面 + 最小运行时 + 最小客户端”的第一条通路。

---

**下一阶段目标**

下一阶段要把“模块存在”升级成“真正能用”：

- 在 CLI 中加入 `run` 命令
- 把本地 runtime 和 server session 打通
- 先把静态 prompt 与动态 prompt 的边界立住
- 引入第一批真实工具：`read_file`、`grep_files`、`run_command`
- 给工具执行链路接上 trace 上报
- 给工具执行补上受治理的权限和审查管道
- 给长回合补上结构化上下文压缩
- 给项目补上一组可重复执行的评估任务和效果指标

这一步完成后，RepoPilot 就会第一次具备“从用户输入到本地代码分析”的真实 Agent 行为。
