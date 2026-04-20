# RepoPilot 设计说明

**项目目标**

RepoPilot 是一个面向本地代码仓研发场景的 Java Coding Agent 平台，采用“本地执行 Runtime + 服务端 Control Plane”的双层架构。

它需要同时满足两类目标：

1. 学习目标：把 Agent Runtime、工具调用、权限治理、会话追踪这些核心机制真正做出来，而不是只包一层框架。
2. 简历目标：让项目既能体现 Java 后端工程能力，也能体现 Agent Runtime / Harness 工程能力。

**当前阶段目标（简历导向收尾）**

截至当前阶段，RepoPilot 的最小主链路已经基本成型：多轮 `model -> tool -> model` 闭环、工具治理、上下文压缩、Skill 激活、交互式 CLI 与 session / trace 控制面都已落地。

因此接下来的目标不再是继续横向铺大量功能，而是把项目收束成一个“可解释、可验证、可量化、可对外讲清楚”的 Agent Runtime 项目。当前阶段优先级固定为：

1. 最小编码任务闭环补齐：先把“搜索 / 阅读 / 最小修改 / 验证 / 结果回传”这条业务主链路做完整。
2. 最小离线评测链路：把主链路能力转成可重复执行的量化指标。
3. Plan / Execute 只读阶段：把“先探索再修改”的成熟 coding agent 工作流落成显式权限模式，而不是让模型在理解代码时直接写文件。
4. 确定性循环检测：在 `maxSteps` 之外补齐工具调用重复检测，避免 agent 无意义空转并把循环原因写进 trace。
5. Skill `allowed-tools` 真正落地：让 Skill 从“上下文注入”升级为“运行时可约束的平台能力”。
6. 命令执行后端抽象：把 `run_command` 的工具协议和本地进程执行边界彻底拆开，补齐 Runtime 工程完整度。
7. 轻量 checkpoint 与演示材料：把关键 step、working memory 和 trace checkpoint 结构化，并沉淀 README、demo 脚本与可直接支撑简历表述的证据。

这也意味着，凡是不能直接提升“业务闭环完整度”“量化结果”“Skill 平台能力”或“运行时边界清晰度”的任务，都不再占用当前阶段关键路径。

**截至 2026-04-20 的阶段判定**

基于当前代码实现和本地验证结果，RepoPilot 已经越过“概念验证 / 框架拼装”阶段，进入“最小 runtime 闭环已具备，但产品证据链未收口”的状态。

当前已经确认的事实如下：

- 多模块工程、CLI、core、server、protocol 的最小主链路代码都已落地，而不是停留在设计文档。
- 本地 `mvn test` 已通过全部模块测试，能够证明最小 runtime、交互式 CLI、session / trace 控制面与 scripted 评测链路整体健康。
- scripted 评测已能稳定输出结构化报告；当前基线是 `10` 个固定场景、`taskSuccessRate=1.0`、`toolCallValidRate=1.0`、`avgSteps=2.2`、`avgDurationMillis=5.5`。
- real-model 评测已能稳定输出独立结构化报告；当前基线是 `5` 个固定场景、`taskSuccessRate=1.0`、`toolCallValidRate=1.0`、`avgSteps=3.2`、`avgDurationMillis=14246.2`，并包含一条严格验收的端到端编码任务场景。

因此，当前最准确的对外口径不是“产品级 coding agent 已完成”，而是：

- `Java coding agent runtime`
- `本地 coding agent 雏形`
- `具备最小 runtime 闭环、正在补齐产品证据链的 coding agent 项目`

不建议当前对外直接表述为：

- `通用 coding agent 产品`
- `产品级 coding agent 已完成`
- `稳定支持复杂多模块开发任务的 agent`

当前最主要的差距不是“还缺很多功能”，而是“还缺把已有能力讲成产品所需的最后一段证据链与约束能力”。

**当前对外表述风险**

当前阶段最容易被面试官追问击穿的点，不是代码量不够，而是 scripted 能力、假模型验证和真实模型产品能力之间的边界如果说不清楚，就会被理解成“把测试夹具说成真实产品能力”。

需要显式说明的边界包括：

- `bootstrap` 假模型只能证明 prompt 和 runtime 接线正确，不能当作真实 agent 能力证据。
- scripted 评测只能证明 runtime 在固定场景下可重复运行；真实模型端到端编码任务验收需要由独立 real-model 场景集提供证据。
- 当前 server 仍是最小控制面实现，重点是 session / trace 语义，而不是持久化平台或远程执行器。

**简历导向的最快收尾顺序**

如果目标是尽快进入“能在面试官面前把它讲成产品雏形”的状态，当前最短关键路径应收敛为：

1. 先补 README / demo / baseline report / 术语口径，把 scripted 能力、真实模型能力和未完成门槛写清楚。
2. 再补一条真实模型 provider 的端到端验收记录，形成“不是只在测试里成立”的直接证据。
3. 然后补 `Plan / Execute`、确定性循环检测、Skill `allowed-tools` 三个最影响产品可信度的运行时约束。
4. `run_command` 执行后端抽象、checkpoint 等工程增强保留在上述事项之后，不再占用简历收尾关键路径。

**当前阶段要解决的业务问题**

RepoPilot 当前阶段要解决的核心业务，不是“做一个会聊天的 AI CLI”，而是“让开发者把一个本地代码仓里的真实编码任务交给 agent 后，agent 能在受控边界内完成定位、修改、验证和结果回传”。

这个业务问题面向的是典型研发场景，例如：

- 修复一个已有失败测试
- 在现有模块里补一个小功能
- 根据报错定位问题并提交最小修改
- 在不离开当前工作区的前提下完成一次可验证的代码改动

如果连这条业务链路都没跑通，那么即使项目里已经有 Skill、记忆、控制面和 trace，也不能把当前阶段视为“coding agent 产品雏形完成”。

**当前阶段最小业务闭环**

RepoPilot 当前阶段至少要稳定跑通下面这条最小业务闭环：

1. 用户输入一个明确的编码任务。
2. agent 使用仓库工具完成代码搜索、文件读取和证据收集。
3. agent 对目标文件做最小代码修改。
4. agent 执行 build / test / command 验证修改结果。
5. agent 根据验证结果继续修正，或显式暴露真实失败，而不是伪装成功。
6. agent 输出变更文件、验证结果与关键 trace / diff summary。

当前阶段对“代码修改”这一步的目标形态也要收敛清楚：

- `write_file` 可以保留为独立工具能力
- 但面向 coding task 主链路，优先补齐“补丁式最小修改”能力，而不是继续把整文件覆盖写入当成主要实现形态

**当前实现能力边界**

截至当前实现，RepoPilot 已经具备最小 coding agent runtime 的关键零件：多轮 Agent Loop、真实工具注册、工具治理、补丁式编辑、命令验证、上下文压缩、Skill 激活、交互式 CLI 和 DeepSeek 兼容模型适配器。

但当前能力边界必须说清楚：

- 已经适合支撑“小型、明确、受控”的本地编码任务。
- 主要目标任务是单文件或少量文件的局部修改、明确报错定位、小功能补齐和命令验证。
- 当前不承诺稳定完成跨模块重构、长时间调试、多阶段产品功能、模糊需求拆解或大范围架构迁移。
- 当前自动化测试能证明 runtime 路径和 scripted coding task 闭环，但不能替代真实模型端到端验收。

因此，RepoPilot 当前不是“通用产品级 coding agent”，而是“最小产品雏形前的 runtime 闭环”。下一阶段的重点不是继续扩大工具数量，而是补齐最小产品必须具备的证据链。

**最小 Coding Agent 产品门槛**

RepoPilot 要被视为“最小 coding agent 产品雏形”，至少必须满足下面 6 个门槛：

1. 真实模型端到端编码任务跑通
   使用真实模型 provider，不依赖 scripted adapter，让 agent 完成一次“搜索 / 阅读 / 补丁修改 / 命令验证 / 最终回答”的小型任务。

2. 固定任务集评测可重复执行
   至少有 10 到 20 个本地固定任务，能输出成功率、工具调用有效率、平均步数和平均耗时。

3. Plan / Execute 只读阶段落地
   agent 在探索和制定计划时不能写文件，必须等用户确认后再进入执行阶段。

4. 循环检测能显式中断空转
   连续重复同一工具调用时，runtime 必须中断并写入 trace，不能只依赖 `maxSteps`。

5. Skill 工具约束进入运行时
   Skill 的 `allowed-tools` 必须同时影响 prompt 可见工具和 runtime 可执行工具。

6. README / demo / baseline report 口径一致
   外部读者能直接看到当前支持什么、不支持什么、如何运行 demo、基线指标是多少。

只要这些门槛没有满足，RepoPilot 就不应对外宣称“产品级 coding agent 已完成”。可以宣称的是“具备最小 runtime 闭环、正在补齐最小产品证据链的 Java coding agent 项目”。

按截至 2026-04-20 的状态判断：

- 第 1 项“真实模型端到端编码任务跑通”已经满足，并已有 real-model 固定场景基线。
- 第 2 项“固定任务集评测可重复执行”已经满足，并已有 scripted / real-model 双口径报告。
- 第 6 项“README / demo / baseline report 口径一致”已完成收口。
- 第 3、4、5 项仍未满足，因此项目整体仍处于“产品雏形前收尾阶段”。

**当前阶段简历亮点收束（控制在 5 条）**

为了避免项目亮点过散、难以表述，当前阶段的简历亮点固定收束为 5 条：

1. 编码任务端到端闭环：搜索 / 阅读 / 修改 / 验证 / 结果回传。
2. 工具治理与安全边界：参数校验、权限判定、审批、diff review、fail-closed。
3. 结构化上下文与 Skill 机制：working memory、context summary、Skill 激活与工具约束。
4. 离线评测与量化指标：固定任务集、成功率、步数、时延等可重复指标。
5. 会话追踪与可观测控制面：session / trace / replay / demo。

后续任何新增任务，如果不能直接强化这 5 条中的至少一条，就不进入当前阶段关键路径。

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
  负责 Agent Runtime。包含消息模型、`model -> tool -> model` 主循环、Tool Registry、权限策略、diff review、上下文压缩、Skill loading / activation 与后续 `allowed-tools` 交集治理。
- `repopilot-cli`
  负责本地执行入口。包含用户输入、本地审批交互、流式输出展示、对 server 的事件上报。
- `repopilot-server`
  负责 Control Plane。当前阶段先聚焦 session / trace 的存储、查询、回放与审计，不把 server 扩展成远程执行器或实时事件平台。
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

**工具执行后端与 Sandbox 边界**

RepoPilot 需要区分“工具协议”和“执行后端”两个层面，避免把本地进程启动逻辑直接写死在工具实现里。

- `ToolHandler / GovernedToolExecutor`
  定义 agent 视角下的工具语义、治理顺序和统一错误边界。
- `CommandExecutionBackend / CodeExecutionBackend`
  定义命令执行和代码执行的底层承载接口，负责真实的进程或 sandbox 生命周期。

这种拆分的目标是：

- 上层 `AgentLoop`、tool schema 和 trace 协议不感知底层到底是本地执行还是云端 sandbox
- `run_command`、后续 `run_code` 共享统一执行结果模型，而不是各自发明一套返回格式
- 本地 backend、未来云端 sandbox backend 可以替换，但不改变上层运行时语义

一期先把“执行后端抽象 + 本地 backend”设计正确，把 `ProcessBuilder` 这类本地执行细节收敛到 backend 内部。二期再在不改变工具协议的前提下接入 `E2B` 这类真实云端 sandbox SDK。

这里有两条强约束：

- 不允许上层工具在 backend 失败后静默切换到另一种执行方式
- 不允许把云端 sandbox 接入写成“仅在失败时兜底”的隐藏分支

RepoPilot 只接受显式配置、显式失败和统一结果模型，不接受掩盖主链路语义的 fallback。

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

**当前阶段必须保留与补齐的能力**

当前阶段必须稳定保留这些能力，并优先补齐最能支撑简历亮点的收尾项：

- 多轮 `model -> tool -> model` 执行闭环
- 编码任务端到端闭环（搜索 / 阅读 / 修改 / 验证 / 回传）
- Tool Registry 与统一工具协议
- session / trace 基础控制面
- CLI 到 server 的最小 HTTP 协议
- 本地代码分析与工具调用主链路
- 补丁式最小代码修改能力
- 命令执行与文件修改的安全边界
- diff review
- 结构化 short-term memory 与上下文压缩
- skill 渐进式按需加载与显式激活
- 最小评估链路
- Plan / Execute 只读阶段
- 确定性循环检测
- Skill `allowed-tools` 运行时交集治理
- 命令执行后端抽象
- 轻量 checkpoint 与 trace checkpoint
- README / demo / 简历证据化材料
- 静态 prompt 与动态 prompt 边界
- 工具治理流水线

其中：

- `最小评估链路` 负责回答“这套 Runtime 到底跑得怎么样”。
- `Plan / Execute` 负责回答“agent 是否能先只读取证，再在用户确认后修改”。
- `确定性循环检测` 负责回答“agent 空转时 Runtime 能否及时暴露真实问题”。
- `Skill allowed-tools` 负责回答“Skill 到底是提示词，还是已经变成平台能力”。
- `命令执行后端抽象` 负责回答“工具协议和真实执行边界是否已经拆清楚”。
- `轻量 checkpoint` 负责回答“长回合失败、压缩和回放时是否有可定位的状态锚点”。

只有当“编码任务端到端闭环”稳定成立后，后面的 Skill、评测和控制面增强才有业务意义。

---

**阶段性验收约束**

为了保证实现过程可观察、可验证，一期执行额外遵循以下约束：

- 计划中的每个 task 在宣告完成前，都必须落到一个用户可在交互式终端亲自操作的检查点。
- 每个检查点至少要包含精确命令、必要输入、预期屏幕现象和失败时应直接暴露的真实错误。
- 如果某个 task 主要是内部重构、单独看不到效果，就不能只靠“代码已改完”或“单测已通过”宣告完成；必须继续收敛到最小可见 smoke path，或把 task 边界改成能产生可见结果的形态。
- 自动化测试仍然是完成条件的一部分，但不能替代用户亲自观察一次主链路效果。
- 每完成一个用户可见功能，都必须补一条真实模型端到端验收路径：启动真实控制面与 CLI，使用真实模型 provider 发起至少一轮真实模型调用，观察模型请求、工具调用、工具结果和最终输出是否符合该功能预期。
- mock / scripted `ModelAdapter` / 本地假模型只能证明运行时代码路径，不能替代真实模型端到端验收；如果当前环境缺少真实模型凭据、网络或控制面依赖，该功能只能标记为 blocked，不能勾选完成。
- 真实模型端到端验收必须记录具体输入、关键 trace 证据和通过条件；如果真实模型暴露出权限、prompt、工具协议或上下文注入问题，应先修复根因并重新验收。

---

**一期明确不做**

为了避免项目范围再次失控，当前阶段明确不做：

- MCP 协议深水区
- 完整全屏 TUI
- background task
- 可写型 sub-agent
- worktree isolation
- skill marketplace
- 真实云端 sandbox 集成（如 `E2B`）
- 复杂 Web 管理台
- session / trace 持久化替换（JPA / PostgreSQL / Flyway）
- SSE 推送与实时事件流
- 模型自动路由、handoff 与 ACP 接入
- 向量数据库长期记忆
- 自主进化或自修改 runtime
- 大规模 benchmark 平台
- 产品级 IDE Bridge
- 产品级全量工具手册懒加载系统
- 与最小编码任务闭环无直接关系的额外横向能力

这些能力不是“不重要”，而是对当前阶段的简历增益不够直接。它们放到后续阶段更合理，因为当前最缺的不是功能清单，而是结果证明、Skill 平台化和演示闭环。

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

**成熟产品对照后的运行时补强**

`agent-base` 中多套成熟 coding agent 反复出现的高价值机制是：先计划后执行、循环检测、checkpoint、可量化评测和执行后端隔离。RepoPilot 当前阶段只吸收其中能直接强化主链路的部分，不把项目扩成复杂多 Agent 或协议平台。

### Plan / Execute 只读阶段

RepoPilot 需要补一个显式的 Plan 模式，用来承载“只读探索 -> 计划确认 -> 修改执行”的工作流。

一期采用最小设计：

- 通过 `/plan` 或等价显式入口进入 Plan 模式。
- Plan 模式只暴露只读工具集合，例如 `grep_files`、`read_file`，以及后续明确标记为只读的验证型工具。
- Plan 模式禁止 `apply_patch`、`write_file` 等写入工具，也不允许通过审批绕过只读边界。
- 模型在 Plan 模式只输出实施计划和证据摘要，不直接修改工作区。
- 用户确认后才切回 Execute 模式，Execute 模式继续复用现有工具治理、审批和 diff review。

这里不做复杂 planner、不做多层任务树，也不让模型靠自然语言暗示自动切换模式。模式切换必须是显式状态，权限边界必须同时进入 prompt 暴露和 runtime 执行约束。

### 确定性循环检测

`maxSteps` 只能限制总步数，不能解释 agent 为什么空转。RepoPilot 需要在 Agent Loop 内补一层确定性循环检测，先覆盖最容易验证的重复工具调用场景。

一期规则固定为：

- 对每次工具调用生成稳定 key：`toolName + canonicalArguments`。
- 只检测连续重复的同一 key，超过显式阈值后终止当前回合。
- 检测结果写入 trace，至少包含 step、toolName、重复次数和参数摘要。
- 终止时暴露真实循环原因，不继续把失败伪装成普通工具结果。

一期不做 LLM 语义循环判断、不做内容 chanting 检测，也不做“自动换一种策略”的启发式修复。后续如果需要扩展，也必须先有评测样例证明误报和漏报边界。

### 轻量 Checkpoint

RepoPilot 需要 checkpoint，但当前阶段只做运行时状态锚点，不做文件系统自动回滚。

一期 checkpoint 的职责是：

- 在关键 step 前生成 checkpoint id。
- 绑定当前消息窗口、`working_memory` 快照、最近关键工具结果和 trace 位置。
- 让上下文压缩、错误回放、评测报告可以引用同一个状态锚点。
- 失败时帮助用户定位“在哪个 step、基于什么状态失败”，而不是尝试自动恢复文件。

一期明确不做 D-Mail 式时间旅行、不做命令级 undo/redo、不做 Git 或文件系统快照回滚。文件修改后的恢复必须保持显式，由用户或后续明确工具决定。

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

**短期记忆、上下文管理与评估**

一期把“short-term memory”“上下文压缩”和“编码准确率”都纳入主线，但都做成受控版本。

对 RepoPilot 来说，short-term memory 不是把整段原始聊天历史无限保留，而是把继续推进任务真正需要的信息固定成一份结构化 `working_memory`。一期至少保留这些信息：

- 当前任务目标
- 已确认事实
- 最近关键工具结果
- 当前阻塞点
- 已产出文件或补丁引用
- 下一步待执行动作

上下文压缩的目标不是做一个复杂记忆系统，而是解决多轮工具调用后的上下文膨胀问题，同时保证 `working_memory` 仍然保持稳定可读。RepoPilot 一期采用确定性的三层上下文结构：

- 固定上下文：system prompt、权限边界、当前任务目标
- 近期高保真上下文：最近若干轮 user / assistant / tool 交互
- 历史结构化摘要：把更早的执行轨迹压缩为 `context_summary`

`context_summary` 不做泛化聊天总结，而是保留运行时真正关键的信息，并和 `working_memory` 相互对齐：

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

编码准确率当前阶段不把它定义成“模型主观上有多聪明”，而是落成一个最小评估链路。系统先基于固定任务集输出 4 个核心指标：

- `tool_call_valid_rate`
- `task_success_rate`
- `avg_steps`
- `avg_duration`

当 `write_file` 与更完整的 e2e 任务夹具稳定后，再补充：

- `patch_apply_success_rate`
- `build_or_test_pass_rate`

这样 RepoPilot 的效果评估不是主观印象，而是可重复执行、可对比的工程指标。

---

**Skills 与工具知识**

RepoPilot 保留 Skill 机制，但 Skill 的定位是“轻量工作流注入”，不是重插件平台。每个 Skill 应该描述：

- 适用场景
- 推荐步骤
- 工具使用边界
- 必要时的 `allowed-tools` 约束

Skill 的主链路采用“渐进式加载”而不是“全量注入”：

1. `skill index`
   启动时只扫描元信息，例如名称、摘要、来源、`allowed-tools`。
2. `skill body`
   当用户请求、系统命中或调度器明确选择某个 Skill 时，再加载完整 `SKILL.md`。
3. `skill attachments`
   当 Skill 正文继续引用模板、脚本、示例或附加文档时，再按需递进加载。

这意味着默认 system prompt 里只出现 Skill 摘要，而不是把全部 Skill 正文一次性塞进上下文窗口。

Skill 生效后的有效工具集合应遵循：

`global policy ∩ skill allowed-tools`

这个交集约束不应只停留在元信息或 prompt 摘要阶段，而应在当前阶段真正进入运行时：

- prompt 中只展示有效工具子集
- runtime 执行时也只允许有效工具子集

这样 Skill 才不会停留在“注入一段正文”的层面，而会真正成为可审计、可测试、可量化的平台能力。

单个工具的长说明不应一次性塞进基础 system prompt。RepoPilot 二期可进一步引入“工具手册按需加载”或 `tool_search` 式机制，让模型在需要时再读工具说明，而不是在每轮请求中背上全部工具文档。

同样，工具定义的输出顺序也应保持稳定。内置工具、动态工具和后续 MCP 工具都需要稳定排序，以便：

- 降低 prompt 抖动
- 提高缓存友好性
- 让调试和测试输出更稳定

---

**模型路由、Handoff 与协议适配**

RepoPilot 后续若从“单模型 coding agent”继续演进，优先补的是显式模型路由与结构化交接，而不是直接跳到复杂多 Agent 编排。

模型路由层的职责应保持单一：

- `ModelRouter`
  只负责根据显式策略选择本轮模型，不负责 prompt 拼装、工具执行或会话存储。
- `ModelRouteDecision`
  结构化描述本轮选择了哪个模型、原因是什么、是否允许工具调用、预算约束是什么。

这里坚持两个原则：

- 路由必须是显式策略，不做不可解释的黑盒自动切换
- 路由失败必须暴露真实错误，不允许静默 fallback 到另一个模型继续执行

一期不追求“自动把所有问题都送给最优模型”，而是先把路由输入和输出边界固定下来。典型输入包括：

- 当前任务模式
- 是否需要工具调用
- 当前上下文体积
- 成本预算
- 是否需要长输出或高推理强度

当系统需要在模型之间、角色之间或执行阶段之间交接时，不应直接把整段原始历史粗暴复制过去，而应通过结构化 `HandoffPacket` 完成交接。这个交接包至少要包含：

- 当前任务目标
- 用户约束
- `working_memory` 快照
- 最近关键证据与工具结果
- 已完成步骤
- 未完成步骤
- 允许工具集合
- 剩余预算

这样做的价值是：

- 模型切换时保持语义连续
- 角色切换时保持证据链完整
- 会话恢复与评估时可复用同一份交接结构

对于协议层，RepoPilot 后续优先考虑的是 `Agent Client Protocol (ACP)` 这一类“编辑器 / IDE ↔ coding agent”接入协议，而不是一开始就把 agent-to-agent 通信协议直接混入 runtime 内核。协议适配层应放在入口边界，而不是放进 `AgentLoop`。

也就是说：

- runtime 核心保持协议无关
- CLI / Web / IDE / ACP client 都通过 adapter 接入
- ACP adapter 只做协议映射、会话桥接和事件转发，不拥有独立运行时语义

这样后续如果要支持 stdio / TCP 这类 ACP 入口，也只是多一个 adapter，而不是推倒 runtime 主循环重写。

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

**Web 控制台预留边界**

RepoPilot 后续若补前端，不以 IDE clone 为目标，而是先做一个薄控制面 Web 控制台。它只消费 `server` 已经持有的控制面数据，不直接接触本地仓库执行。

这个 Web 控制台的最小价值是：

- 展示 session 列表与状态
- 展示 trace 时间线
- 展示当前 step / tool 调用状态
- 展示最终回答与错误状态
- 展示 approval / diff summary 这类审计对象

它的边界必须保持清晰：

- 真正执行仍然发生在 `cli + core`
- Web 前端只读控制面数据，不代替本地 runtime
- 如果未来支持从 Web 发起任务，也只是创建控制面对象和触发本地入口，不把 server 变成远程执行器

这样 RepoPilot 后续就能自然形成“前端展示层 -> server 控制面 -> 本地 agent runtime”的清晰链路。

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
10. 结构化 short-term memory 与上下文压缩
11. skill 渐进式加载
12. 补丁式代码编辑与最小编码任务闭环
13. 最小评估链路
14. Plan / Execute 只读阶段
15. 确定性循环检测
16. Skill `allowed-tools` 运行时交集治理
17. 命令执行后端抽象与本地 sandbox 语义固定
18. 轻量 checkpoint 与 trace checkpoint
19. README / demo / 基线报告收尾

二期优先演进这些方向：

- background task
- session / trace 持久化替换（JPA / PostgreSQL / Flyway）
- SSE 推送与实时事件流
- 真实云端 sandbox backend（如 `E2B`）
- 薄 Web Session Console
- 显式模型自动路由策略
- 结构化 handoff packet 跨模型 / 跨角色复用
- ACP adapter（editor / IDE ↔ agent）
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
