# RepoPilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已跑通最小主链路的基础上，把 RepoPilot 收束成一个真正面向“本地代码仓编码任务”的 Java Coding Agent 项目；当前阶段必须先补齐最小编码任务闭环，再把成果收束成 5 个可直接写进简历的亮点。

**Architecture:** `repopilot-protocol` 继续承载共享协议，`repopilot-core` 作为最小 ReAct Runtime 保留工具治理、上下文压缩、Skill 加载，并补齐“搜索 / 阅读 / 补丁式修改 / 验证执行”这条编码任务主链路；`repopilot-cli` 负责交互入口、评测运行器、Plan / Execute 模式入口与执行后端装配，`repopilot-server` 保持 session / trace 最小控制面。当前阶段不继续横向铺控制面功能，而是优先把最小业务闭环跑通、结果可量化、运行时空转可检测、状态锚点可回放、亮点可收束。

**Tech Stack:** Java 17, Maven, Spring Boot 3, Jackson, Picocli, JDK HttpClient, JUnit 5

**Task Completion Contract:** 自当前执行阶段起，任何未完成 task 在勾选完成前，都必须先跑完对应自动化验证，再提供一组可由用户在交互式终端亲自执行的验收命令或操作步骤，写清预期现象，并在该检查点暂停等待人工确认；如果 task 本身是内部整理、单独不可见，就继续补到最小可见 smoke path 后再算完成。

**Real Model E2E Gate:** 每完成一个用户可见功能，都必须用真实模型 provider 跑通至少一条端到端路径后才能勾选完成。验收必须启动真实控制面与 CLI，发起真实模型调用，记录输入、关键 trace、工具调用/工具结果、最终输出和通过条件；mock、scripted adapter、本地 `bootstrap` 假模型和单元测试都不能替代这条门禁。如果当前环境缺少真实模型凭据、网络或控制面依赖，功能状态必须保持 blocked，不能用“自动化测试已通过”代替完成。

**Current Business Acceptance:**
1. 用户输入一个明确编码任务。
2. agent 能完成代码搜索与文件读取。
3. agent 能做最小代码修改，而不是只依赖整文件覆盖。
4. agent 能执行验证命令并读取真实结果。
5. agent 能根据验证结果继续修正，或显式暴露真实失败。
6. agent 最终能输出变更结果、验证结果和关键 trace / diff summary。

如果这条业务闭环没有跑通，当前阶段即使有 Skill、评测或控制面增强，也不能算完成。

**Current Capability Boundary:**
- 当前只承诺“小型、明确、受控”的本地编码任务。
- 支持目标优先限定为单文件或少量文件局部修改、明确报错定位、小功能补齐和命令验证。
- 当前不承诺稳定完成跨模块重构、长时间调试、多阶段产品功能、模糊需求拆解或大范围架构迁移。
- scripted `ModelAdapter` 只能证明 runtime 路径；真实 coding agent 能力必须由真实模型 provider 的端到端验收和固定任务集评测证明。

**Minimum Product Gate:**
1. 真实模型 provider 跑通一次小型编码任务端到端链路，包含搜索、读取、补丁、命令验证和最终回答。
2. 至少 10 到 20 个固定本地任务可重复执行，并输出 `task_success_rate`、`tool_call_valid_rate`、`avg_steps`、`avg_duration`。
3. Plan / Execute 只读阶段进入 prompt 和 runtime 双重约束。
4. 确定性循环检测能中断连续重复工具调用，并在 trace 中留下原因。
5. Skill `allowed-tools` 进入 prompt 可见工具和 runtime 可执行工具的双重约束。
6. README、demo 脚本、baseline report 和 spec / plan 对当前支持范围、非目标和指标口径保持一致。

只要上面 6 项没有全部满足，项目状态只能标记为“最小 runtime 闭环已具备，最小产品证据链未完成”，不能标记为“coding agent 产品完成”。

**Current Resume Highlights:**
1. 编码任务端到端闭环
2. 工具治理与安全边界
3. 结构化上下文与 Skill 机制
4. 离线评测与量化指标
5. 会话追踪与可观测控制面

**Current Focus:**
1. `P0` 补丁式代码编辑与最小编码任务闭环：先把业务主链路补完整。
2. `P1` 最小离线评测链路：把当前 Runtime 能力转成可重复执行的量化指标。
3. `P2` Plan / Execute 只读阶段：把成熟 coding agent 的“先探索再执行”落成显式权限模式。
4. `P3` 确定性循环检测：在 `maxSteps` 之外识别连续重复工具调用，并把真实循环原因暴露给用户和 trace。
5. `P4` Skill `allowed-tools` 运行时治理：把 Skill 从上下文注入推进到有效工具子集约束。
6. `P5` 命令执行后端抽象：收敛 `run_command` 的协议边界与真实执行承载。
7. `P6` 轻量 checkpoint 与 trace checkpoint：为长回合压缩、失败定位和评测报告提供状态锚点，不做文件系统自动回滚。
8. `P7` README / demo / 基线报告收尾：把已有能力沉淀成可以直接支撑简历表述的证据。

**Scope Guardrail:** 只要 `P0 / P1 / P2 / P3 / P4 / P5 / P6 / P7` 还没完成，就不继续推进 `background task`、持久化替换、SSE、模型路由、handoff、ACP、可写型 sub-agent 等横向扩展项；任何不能直接强化 5 条简历亮点之一的任务，一律延后。

**Current Delivery Status (2026-04-20):**
- 当前项目已经具备“最小 runtime 闭环”，但尚未收口为“可对外宣称完成的 coding agent 产品”。
- 代码与验证层面已经确认：`mvn test` 全绿；scripted 评测可重复执行；交互式 CLI、session / trace 控制面、`apply_patch` 主链路、Skill 激活与上下文压缩都已落地。
- scripted 基线报告当前结果为：`scenarioCount=10`、`taskSuccessRate=1.0`、`toolCallValidRate=1.0`、`avgSteps=2.2`、`avgDurationMillis=5.5`。
- real-model 基线报告当前结果为：`scenarioCount=5`、`taskSuccessRate=1.0`、`toolCallValidRate=1.0`、`avgSteps=3.2`、`avgDurationMillis=14246.2`；其中已包含一条严格验收的端到端编码场景。
- 当前最准确的外部口径应为：`Java coding agent runtime`、`本地 coding agent 雏形`、`最小 runtime 闭环已具备，产品证据链未完成`。
- 当前最主要的缺口不是继续扩工具，而是补齐 `Plan / Execute`、确定性循环检测、Skill `allowed-tools` 运行时约束，以及更复杂开放式提示下的补丁精度与回归验证约束。

**Resume-First Closeout Order:**
1. `P7` 与真实模型最小门槛证据已收口，后续只做增量同步，不再作为收尾阻塞项。
2. 按 `P2 -> P3 -> P4` 顺序补齐最影响产品可信度的运行时约束。
3. 继续补更复杂开放式提示下的真实交互验收，验证补丁精度与回归约束。
4. `P5 / P6` 保留为工程完整度增强项，不再阻塞简历收尾。

---

### Task 1: 多模块工程骨架

**Files:**
- Create: `pom.xml`
- Create: `repopilot-protocol/pom.xml`
- Create: `repopilot-core/pom.xml`
- Create: `repopilot-cli/pom.xml`
- Create: `repopilot-server/pom.xml`
- Create: `.gitignore`

- [x] 建立父工程与 4 个模块的 Maven 结构。
- [x] 固定 Java 版本、测试插件版本和依赖管理。
- [x] 验证 reactor 构建顺序正常。

### Task 2: 协议层最小模型

**Files:**
- Create: `repopilot-protocol/src/main/java/com/repopilot/protocol/events/SessionEvent.java`
- Create: `repopilot-protocol/src/main/java/com/repopilot/protocol/events/SessionCreatedEvent.java`
- Create: `repopilot-protocol/src/main/java/com/repopilot/protocol/session/SessionStatus.java`
- Create: `repopilot-protocol/src/main/java/com/repopilot/protocol/session/SessionSummary.java`
- Create: `repopilot-protocol/src/main/java/com/repopilot/protocol/session/CreateSessionRequest.java`
- Create: `repopilot-protocol/src/main/java/com/repopilot/protocol/trace/TraceEventType.java`
- Create: `repopilot-protocol/src/main/java/com/repopilot/protocol/trace/AppendTraceEventRequest.java`
- Create: `repopilot-protocol/src/main/java/com/repopilot/protocol/trace/TraceEventRecord.java`
- Create: `repopilot-protocol/src/main/java/com/repopilot/protocol/json/ProtocolObjectMapperFactory.java`
- Test: `repopilot-protocol/src/test/java/com/repopilot/protocol/events/SessionCreatedEventJacksonTest.java`
- Test: `repopilot-protocol/src/test/java/com/repopilot/protocol/session/SessionSummaryJacksonTest.java`

- [x] 先用测试锁定事件与会话摘要的序列化行为。
- [x] 统一 `ObjectMapper` 的时间处理规则。
- [x] 保证 CLI、core、server 三边对 JSON 协议的理解一致。

### Task 3: Server 最小控制面

**Files:**
- Create: `repopilot-server/src/main/java/com/repopilot/server/RepoPilotServerApplication.java`
- Create: `repopilot-server/src/main/java/com/repopilot/server/config/TimeConfiguration.java`
- Create: `repopilot-server/src/main/java/com/repopilot/server/session/SessionApplicationService.java`
- Create: `repopilot-server/src/main/java/com/repopilot/server/session/SessionController.java`
- Create: `repopilot-server/src/main/java/com/repopilot/server/session/SessionNotFoundException.java`
- Create: `repopilot-server/src/main/java/com/repopilot/server/session/SessionExceptionHandler.java`
- Test: `repopilot-server/src/test/java/com/repopilot/server/RepoPilotServerApplicationTests.java`
- Test: `repopilot-server/src/test/java/com/repopilot/server/session/SessionControllerTests.java`

- [x] 打通 `POST /api/sessions`。
- [x] 打通 `GET /api/sessions/{sessionId}`。
- [x] 打通 `POST /api/sessions/{sessionId}/trace-events`。
- [x] 打通 `GET /api/sessions/{sessionId}/trace-events`。
- [x] 用内存存储先验证控制面语义，再考虑持久化。

### Task 4: Core 最小 ReAct Runtime

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/model/MessageRole.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/model/ConversationMessage.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/model/ToolCall.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/model/ModelResponse.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/model/FinalModelResponse.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/model/ToolCallModelResponse.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/model/ModelAdapter.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/ToolExecutionResult.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/ToolHandler.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/ToolDefinition.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/ToolNotFoundException.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/ToolRegistry.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoopRequest.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoopResult.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoopLimitExceededException.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoop.java`
- Test: `repopilot-core/src/test/java/com/repopilot/core/tool/ToolRegistryTest.java`
- Test: `repopilot-core/src/test/java/com/repopilot/core/agent/AgentLoopTest.java`

- [x] 先实现最小 Tool Registry。
- [x] 实现“模型请求工具 -> 执行工具 -> 注入 TOOL 消息 -> 模型给最终回答”的最小主循环。
- [x] 加入 `maxSteps` 限制，作为第一层防死循环保护。
- [x] 把工具结果从二元 `success` 升级为显式状态，区分 `SUCCESS / RECOVERABLE_ERROR / FATAL_ERROR`。
- [x] 在 `AgentLoop` 中对致命工具错误执行 fail-fast，避免把系统级失败伪装成普通 `TOOL` 消息继续运行。
- [ ] 后续把当前最小 `AgentLoop` 向“纯 Runner + 外围 Orchestrator”方向收敛，避免把 CLI/server 逻辑混进运行时内核。

### Task 5: CLI 到 Server 的 HTTP 协作

**Files:**
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/RepoPilotCliCommand.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/RepoPilotCliApplication.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/session/SessionApiClient.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/session/DefaultHttpSessionApiClient.java`
- Test: `repopilot-cli/src/test/java/com/repopilot/cli/session/DefaultHttpSessionApiClientTest.java`

- [x] 先建立 CLI 根命令。
- [x] 打通创建 session 的 HTTP 客户端。
- [x] 打通查询 session 的 HTTP 客户端。

### Task 6: 静态 Prompt 与动态边界

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/prompt/SystemPromptBoundary.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/prompt/DynamicPromptContext.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/prompt/SystemPromptBuilder.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/prompt/SystemPromptBuilderTest.java`

- [x] 固定 system prompt 中静态宪法与动态政策的边界，避免用户和工作区数据混入稳定前缀。
- [x] 动态段先支持 session preamble、工作区信息、Skill 摘要、预算提示与可用工具子集。
- [x] 把高频 runtime metadata 设计成独立 runtime context 块，不直接写进稳定 system prompt。
- [x] 保证静态段内容稳定、动态段内容可替换，为后续缓存和成本优化打基础。

### Task 7: CLI `run` 主命令

**Files:**
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/RepoPilotCliCommand.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/command/RunCommand.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/runtime/CliRuntimeBootstrap.java`
- Create: `repopilot-cli/src/test/java/com/repopilot/cli/command/RunCommandTest.java`

- [x] 把“创建 session -> 启动本地 runtime”的入口挂到 CLI。
- [x] 先支持最小参数：`workspaceId`、`serverBaseUrl`、`prompt`。
- [x] 让 CLI 能把一次用户任务送进 core。

### Task 8: 第一批真实工具

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/ReadFileTool.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/GrepFilesTool.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/RunCommandTool.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/BuiltinToolRegistrar.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/tool/builtin/ReadFileToolTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/tool/builtin/GrepFilesToolTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/tool/builtin/RunCommandToolTest.java`

- [x] 先补 `read_file`。
- [x] 再补 `grep_files`。
- [x] 最后补 `run_command`。
- [x] 保证三者都能被 Tool Registry 注册和执行。

### Task 9: 运行时 Trace 上报

**Files:**
- Modify: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoop.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/trace/TracePublisher.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/session/TraceApiClient.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/session/DefaultHttpTraceApiClient.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/trace/TracePublishingAgentLoopTest.java`

- [x] 在模型调用前后发 trace。
- [x] 在工具调用前后发 trace。
- [x] 让工具 trace 明确记录 `SUCCESS / RECOVERABLE_ERROR / FATAL_ERROR` 三态，并保证 `FATAL_ERROR` 在中断主链路前也能被上报。
- [x] 把 trace 从 core 经 CLI 同步到 server。
- [x] 为后续 Hook 生命周期预留最小扩展点，不把 trace 上报硬编码成唯一实现。

### Task 10: 工具治理流水线与权限策略

**Files:**
- Modify: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoop.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/tool/ToolRegistry.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/governance/GovernedToolExecutor.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/permission/PermissionPolicy.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/permission/WorkspacePermissionPolicy.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/review/DiffReviewService.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/tool/governance/GovernedToolExecutorTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/permission/WorkspacePermissionPolicyTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/review/DiffReviewServiceTest.java`

- [x] 把工具执行从“直接调用 handler”升级为“校验 -> 权限 -> 执行 -> 结构化结果”的受治理流水线。
- [x] 权限决策遵循 `deny -> ask -> allow` 顺序，并默认 fail-closed。
- [x] 统一工具失败出口：由治理层把校验失败、权限拒绝、执行异常收敛成 `RECOVERABLE_ERROR` 或 `FATAL_ERROR`，避免 `AgentLoop` 同时消费多套错误协议。
- [x] 限制工具只能操作当前工作区，并给危险命令留出审批钩子。
- [x] 在写文件前生成 diff 摘要，防止模型直接写盘绕过审查。
- [x] 保持工具定义输出顺序稳定，避免动态工具集导致 prompt 抖动。

### Task 11: 结构化 Short-Term Memory 与上下文压缩

**Files:**
- Modify: `repopilot-core/src/main/java/com/repopilot/core/model/MessageRole.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/model/ConversationMessage.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoop.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/context/ContextCompactionPolicy.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/context/ContextCompactor.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/context/WorkingMemory.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/context/WorkingMemorySnapshot.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/context/ContextCompactorTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/context/WorkingMemoryTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/agent/AgentLoopContextCompactionTest.java`

- [x] 定义 `working_memory` 与 `context_summary` 在运行时消息模型中的表示方式。
- [x] 固定保留 system prompt、任务目标、已确认事实、最近关键工具结果、当前阻塞、产出物引用与最近若干轮高保真消息。
- [x] 把更早的工具执行轨迹压缩为结构化摘要，而不是普通聊天总结。
- [x] 给压缩动作预留 trace 钩子，便于后续回放和调试。
- [x] 给后续 append-only 历史归档、会话恢复和 idle session auto-compact 预留数据结构接口。
- [x] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

**Acceptance:**
- Command: `mvn -pl repopilot-core,repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WorkingMemoryTest,ContextCompactorTest,AgentLoopContextCompactionTest,DeepSeekChatModelAdapterTest test`
- Expected: `WorkingMemoryTest`、`ContextCompactorTest`、`AgentLoopContextCompactionTest`、`DeepSeekChatModelAdapterTest` 全部通过。
- Observe: 第二轮模型调用前会注入 `WORKING_MEMORY` 与 `CONTEXT_SUMMARY`；CLI 适配层会把两类结构化消息序列化进真实模型请求。

### Task 12: Skill 渐进式加载

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillSummary.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillDescriptor.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillContent.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillLoader.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillIndex.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/skill/SkillLoaderTest.java`

- [x] 扫描项目级和用户级 Skill 元信息，建立稳定排序的 `skill index`。
- [x] 默认只把 skill 摘要暴露给 system prompt，并给后续 `allowed-tools` 约束预留字段。
- [x] 提供按名称加载完整 Skill 正文的能力。
- [x] 提供 Skill 附属脚本、模板、示例文档的按需递进加载能力。
- [x] 为后续 `global policy ∩ skill allowed-tools` 的工具约束模型预留扩展点。
- [x] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

**Acceptance:**
- Command: `mvn -pl repopilot-core,repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SkillLoaderTest,SystemPromptBuilderTest,CliRuntimeBootstrapTest,InteractiveRuntimeRunnerTest,InteractiveCliSessionTest,DeepSeekChatModelAdapterTest test`
- Expected: `SkillLoaderTest`、`SystemPromptBuilderTest`、`CliRuntimeBootstrapTest`、`InteractiveRuntimeRunnerTest`、`InteractiveCliSessionTest`、`DeepSeekChatModelAdapterTest` 全部通过。
- Observe: 项目级与用户级 Skill 会被扫描进稳定排序的 `skill index`；system prompt 默认只出现 Skill 摘要而不展开正文与 `allowed-tools`；CLI/bootstrap 与交互式 runtime 会注入 Skill 摘要；命中 Skill 后可按名称读取完整 `SKILL.md`，并继续按相对路径加载脚本、模板和示例附件；重复 Skill 名称会直接报错而不是静默覆盖。

### Task 13: 补丁式代码编辑与最小编码任务闭环

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/edit/PatchApplyRequest.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/edit/PatchApplyResult.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/edit/PatchApplyService.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/ApplyPatchTool.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/BuiltinToolRegistrar.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/permission/WorkspacePermissionPolicy.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/review/DiffReviewService.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/ConsoleTraceObserver.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/edit/PatchApplyServiceTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/tool/builtin/ApplyPatchToolTest.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/tool/governance/GovernedToolExecutorTest.java`

- [x] 新增补丁式编辑原语，支持在工作区内对单文件执行最小文本补丁，而不是只能整文件覆盖写入。
- [x] 让 `apply_patch` 进入现有工具治理链路，复用路径边界、审批挂点与 diff review，而不是绕开现有安全边界。
- [x] 明确补丁应用失败时的真实错误语义：上下文不匹配、目标文件不存在、补丁格式非法都直接暴露，不做静默猜测或 heuristics。
- [x] 让 Console 侧能展示补丁式修改的核心摘要，便于用户观察“本轮到底改了什么”。
- [x] 补一条最小 smoke path：用户给出编码任务后，agent 至少能完成“搜索 / 阅读 / 补丁修改 / 命令验证 / 返回结果”这条主链路。
- [x] 补一条真实模型端到端验收记录，明确输入、工具调用序列、补丁摘要、验证命令、最终回答和失败时真实错误。
- [x] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

**Acceptance:**
- Command: `mvn -pl repopilot-core,repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PatchApplyServiceTest,ApplyPatchToolTest,DiffReviewServiceTest,WorkspacePermissionPolicyTest,BuiltinToolRegistrarTest,GovernedToolExecutorTest,AgentLoopCodingTaskSmokeTest,ConsoleTraceObserverTest,CliRuntimeBootstrapTest test`
- Expected: 补丁服务、`apply_patch` 工具、权限审批、diff review、内置工具注册、Console 摘要、CLI prompt 工具暴露和最小编码任务 smoke path 全部通过。
- Observe: `AgentLoopCodingTaskSmokeTest` 会按顺序走过 `grep_files -> read_file -> apply_patch -> run_command -> final answer`，并验证目标文件确实被补丁式修改；`ConsoleTraceObserverTest` 会展示 `apply_patch path=... changeType=... +N/-N` 摘要；`GovernedToolExecutorTest` 会证明 `apply_patch` 进入审批与 diff review 后才执行。
- Real Model E2E Gate: 启动真实控制面和交互式 CLI，使用真实模型 provider 发起一个小型编码任务，观察 trace 中出现搜索、读取、补丁、命令验证和最终回答；如果当前环境缺少真实模型凭据或网络，该 task 自动化已通过但整体完成状态仍保持 blocked，不能勾选完成。

### Task 14: 最小评估链路

**Files:**
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/RepoPilotCliCommand.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/command/EvalCommand.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/eval/EvalScenario.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/eval/EvalResult.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/eval/EvalRunner.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/eval/EvalReportWriter.java`
- Create: `repopilot-cli/src/test/java/com/repopilot/cli/eval/EvalRunnerTest.java`
- Create: `docs/eval/repopilot-eval-scenarios.md`

- [x] 先定义 10 到 20 个固定本地任务集，优先覆盖最小编码任务闭环，而不是扩成大而全 benchmark。
- [x] 基线任务至少覆盖：代码搜索、文件读取、补丁修改、命令验证、Skill 激活、工具失败暴露与上下文压缩。
- [x] 先输出 4 个核心指标：`tool_call_valid_rate`、`task_success_rate`、`avg_steps`、`avg_duration`。
- [x] 评测报告必须区分 scripted runtime 验证和真实模型 provider 验证，不能把 scripted 成功率写成真实 agent 成功率。
- [x] 每个失败任务至少记录失败阶段、最近工具调用、最终错误和最近 trace / checkpoint 引用。
- [x] 提供命令行评测入口，能够重复执行同一组任务并输出结构化报告，支持前后版本对比。
- [x] 保持评测链路独立于主 runtime，避免为了打分改写主循环语义。
- [x] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

**Acceptance:**
- Command 1: `mvn -q -DskipTests install`
- Command 2: `mvn -q -pl repopilot-cli -am -DskipTests dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime`
- Command 3: `CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)" && java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication eval`
- Expected: 终端输出 `scenario_count=10 task_success_rate=1.0000 tool_call_valid_rate=1.0000`，并生成 `target/repopilot-eval-report.json`。
- Command 4: `CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)" && java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication eval --runtime-kind REAL_MODEL_PROVIDER`
- Expected: 终端输出 `scenario_count=5 task_success_rate=1.0000 tool_call_valid_rate=1.0000`，并生成 `target/repopilot-real-model-eval-report.json`。
- Observe: scripted 报告中应看到 `SCRIPTED_RUNTIME`、`10` 个固定场景，以及 `avgSteps=2.2`、`avgDurationMillis=5.5` 的当前 scripted 基线；real-model 报告中应看到 `REAL_MODEL_PROVIDER`、`5` 个固定场景，以及新增的 `search-read-patch-command` 端到端编码任务场景；两套口径必须分离，不能混算。

### Task 15: Plan / Execute 只读阶段

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentRunMode.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoopRequest.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/prompt/SystemPromptBuilder.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/tool/governance/GovernedToolExecutor.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/permission/WorkspacePermissionPolicy.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/InteractionMode.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/InteractiveCliSession.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/ConsoleTraceObserver.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/DefaultInteractiveRuntimeRunner.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/agent/AgentRunModeTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/agent/PlanModeToolGovernanceTest.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/prompt/SystemPromptBuilderTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/InteractiveCliSessionTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/ConsoleTraceObserverTest.java`

- [ ] 新增显式运行模式，至少区分 `PLAN` 与 `EXECUTE`，默认模式必须是 `EXECUTE`。
- [ ] 在交互式 CLI 中提供 `/plan` 显式入口，进入 Plan 模式后打印当前模式和只读边界。
- [ ] Plan 模式只暴露只读工具子集，例如 `grep_files`、`read_file`；写入型工具不进入 prompt 的可用工具列表。
- [ ] 运行时治理层在 Plan 模式下硬拒 `apply_patch`、`write_file` 等写入工具，不能通过审批绕过只读边界。
- [ ] Plan 模式输出实施计划和证据摘要，用户确认或显式退出后才进入 Execute 模式执行修改。
- [ ] 明确不做复杂 planner、多层任务树或自然语言自动切换模式；模式切换必须来自显式命令。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

**Acceptance:**
- Command: `mvn -pl repopilot-core,repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=AgentRunModeTest,PlanModeToolGovernanceTest,SystemPromptBuilderTest,InteractiveCliSessionTest,ConsoleTraceObserverTest test`
- Expected: Plan 模式枚举、prompt 工具子集、运行时硬拒写工具、CLI `/plan` 入口和 Console 模式摘要全部通过。
- Observe: 进入 `/plan` 后，终端明确显示只读模式；模型只能看到只读工具；如果模型仍请求 `apply_patch` 或 `write_file`，治理层返回明确拒绝原因，而不是进入审批或执行。
- Real Model E2E Gate: 启动真实控制面和交互式 CLI，输入 `/plan <一个小型改动需求>`，观察模型先搜索/读取并输出计划，不出现写入工具调用；再显式进入执行阶段完成修改。

### Task 16: 确定性循环检测

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/agent/loop/ToolCallLoopDetector.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/agent/loop/ToolCallLoopDetectionResult.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoop.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoopRequest.java`
- Modify: `repopilot-protocol/src/main/java/com/repopilot/protocol/trace/TraceEventType.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/ConsoleTraceObserver.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/agent/loop/ToolCallLoopDetectorTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/agent/AgentLoopLoopDetectionTest.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/trace/TracePublishingAgentLoopTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/ConsoleTraceObserverTest.java`

- [ ] 为工具调用生成稳定 key，规则固定为 `toolName + canonicalArguments`，参数顺序必须稳定。
- [ ] 只检测连续重复的同一工具调用 key，达到显式阈值后中断当前回合。
- [ ] 循环检测结果必须写入 trace，至少包含 step、toolName、重复次数和参数摘要。
- [ ] 用户侧错误必须明确说明“连续重复工具调用导致终止”，不能表现成普通工具失败或成功回答。
- [ ] 默认阈值写进 `AgentLoopRequest` 或明确配置对象，测试中必须能固定阈值。
- [ ] 一期不做 LLM 语义循环判断、不做内容 chanting 检测、不做自动换策略的启发式修复。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

**Acceptance:**
- Command: `mvn -pl repopilot-core,repopilot-cli,repopilot-protocol -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ToolCallLoopDetectorTest,AgentLoopLoopDetectionTest,TracePublishingAgentLoopTest,ConsoleTraceObserverTest test`
- Expected: 稳定 key、连续重复计数、阈值中断、trace 事件和 CLI 摘要全部通过。
- Observe: scripted model 连续请求同一 `read_file` 或 `grep_files` 参数时，Runtime 在阈值触发点终止，并输出可读的 loop detected 摘要。

### Task 17: Skill `allowed-tools` 运行时治理

**Files:**
- Modify: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillSummary.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillDescriptor.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillIndex.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/prompt/SystemPromptBuilder.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/tool/governance/GovernedToolExecutor.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/runtime/CliRuntimeBootstrap.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/DefaultInteractiveRuntimeRunner.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/skill/SkillAllowedToolsIntegrationTest.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/skill/SkillLoaderTest.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/prompt/SystemPromptBuilderTest.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/tool/governance/GovernedToolExecutorTest.java`

- [ ] 计算激活 Skill 后的有效工具集合，规则固定为 `global policy ∩ skill allowed-tools`。
- [ ] 让 prompt 中的“可用工具子集”只暴露有效工具集合，避免模型继续看到超出 Skill 边界的工具。
- [ ] 让运行时治理层也按同一集合做强约束，避免 Skill 只影响提示词、不影响真实执行。
- [ ] 明确缺失 `allowed-tools` 时的行为：默认继续沿用全局工具集，而不是猜测或自动缩放。
- [ ] 补充端到端测试，覆盖“只读 Skill 无法触发补丁修改 / 跑命令”“重复激活不改变结果”“缺失 Skill 继续显式报错”。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

### Task 18: 命令执行后端抽象与验证链路

**Files:**
- Modify: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/RunCommandTool.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/BuiltinToolRegistrar.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/runtime/CliRuntimeBootstrap.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/execution/CommandExecutionBackend.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/execution/CommandExecutionRequest.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/execution/CommandExecutionResult.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/execution/LocalProcessCommandExecutionBackend.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/runtime/CliExecutionConfig.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/execution/LocalProcessCommandExecutionBackendTest.java`
- Create: `repopilot-cli/src/test/java/com/repopilot/cli/runtime/CliExecutionConfigTest.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/tool/builtin/RunCommandToolTest.java`

- [ ] 抽象 `CommandExecutionBackend`，把 `ProcessBuilder` 从 `RunCommandTool` 中收敛到本地 backend 实现内部。
- [ ] 统一命令执行结果模型，至少显式返回 `exitCode`、`stdout`、`stderr`、`durationMillis`、`backendKind`。
- [ ] 让 `RunCommandTool` 只负责工具协议和结果封装，不再直接管理本地进程生命周期。
- [ ] 明确“编码任务验证链路”的目标能力：运行测试 / 构建命令、读取真实结果、失败时可继续修正或显式中断。
- [ ] 在 CLI/bootstrap 层明确选择执行 backend，并保证默认行为是显式配置而不是隐式 fallback。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

### Task 19: 轻量 Checkpoint 与 Trace Checkpoint

**Files:**
- Modify: `repopilot-protocol/src/main/java/com/repopilot/protocol/trace/TraceEventType.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/checkpoint/RuntimeCheckpoint.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/checkpoint/RuntimeCheckpointRecorder.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoop.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/context/WorkingMemorySnapshot.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/context/ContextCompactor.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/eval/EvalResult.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/eval/EvalReportWriter.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/checkpoint/RuntimeCheckpointRecorderTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/agent/AgentLoopCheckpointTraceTest.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/context/ContextCompactorTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/eval/EvalReportWriterTest.java`

- [ ] 在关键 step 前创建轻量 checkpoint id，并写入 trace。
- [ ] checkpoint 只绑定运行时状态：step、消息数量、`working_memory` 快照摘要、最近关键工具结果和 trace 位置。
- [ ] 上下文压缩完成后必须能引用最近 checkpoint id，便于回放时定位压缩前状态。
- [ ] 评测报告中保留失败任务的最近 checkpoint id，方便后续复现。
- [ ] 明确不做文件系统自动回滚、不做命令级 undo/redo、不做 D-Mail 式时间旅行。
- [ ] checkpoint 记录失败必须暴露真实错误，不能静默跳过并继续宣称回放可用。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

**Acceptance:**
- Command: `mvn -pl repopilot-core,repopilot-cli,repopilot-protocol -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RuntimeCheckpointRecorderTest,AgentLoopCheckpointTraceTest,ContextCompactorTest,EvalReportWriterTest test`
- Expected: checkpoint id 生成、trace 记录、上下文压缩引用和评测报告失败定位全部通过。
- Observe: 长回合触发压缩或失败时，trace 中能看到最近 checkpoint，报告中能定位失败发生前的运行时状态锚点。

### Task 20: 演示与简历证据收尾

**Files:**
- Create: `README.md`
- Create: `docs/demo/repopilot-demo.md`
- Modify: `docs/superpowers/specs/2026-04-15-repopilot-design.md`
- Modify: `docs/superpowers/plans/2026-04-15-repopilot-implementation.md`
- Create: `docs/eval/reports/repopilot-baseline.md`

- [x] 补一份面向外部阅读的项目说明，重点说明 RepoPilot 解决的业务问题、最小编码任务闭环与 5 条简历亮点。
- [x] README 必须包含当前能力边界：支持小型明确任务，不承诺跨模块重构、长时间调试、模糊需求拆解或产品级全自动交付。
- [x] 补一份可重复执行的 demo 脚本，至少演示一次“搜索 / 阅读 / 补丁修改 / 命令验证 / 返回结果”的完整链路。
- [x] 产出一份基线评测报告，沉淀当前版本的核心指标，作为后续优化对比基准。
- [x] demo 和 baseline report 必须分别标明 scripted adapter 结果和真实模型 provider 结果，避免把测试夹具能力包装成真实模型能力。
- [x] 在 README 中明确最小产品 6 项门槛的完成状态，并把未完成项列为后续路线，而不是隐藏在实现细节里。
- [x] 把当前进度、完成项与后续里程碑同步回 spec / plan，保证文档和实际代码一致。
- [x] 保证阶段收尾时 `mvn test` 通过，并且 README / demo / eval report / 简历表述之间的口径一致。
- [x] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

**Acceptance:**
- Command 1: `mvn test`
- Command 2: `cd repopilot-server && mvn spring-boot:run`
- Command 3: `cd .. && mvn -q -DskipTests install && mvn -q -pl repopilot-cli -am -DskipTests dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime`
- Command 4: `CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)" && java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication eval`
- Command 5: `CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)" && java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication eval --runtime-kind REAL_MODEL_PROVIDER`
- Expected: `mvn test` 全绿；scripted 与 real-model 两套评测都能生成报告；README、demo、baseline 三份文档对当前能力、边界和未完成项的表述一致。
- Observe: demo 文档既包含通过的真实交互 smoke path，也包含暴露过宽补丁风险的失败样例；口径上不再把 scripted 能力包装成真实产品能力。

### Phase 2 Preview: 后续扩展项（不占当前阶段关键路径）

**方向，不纳入当前阶段关键路径：**
- background task
- session / trace 持久化替换（JPA / PostgreSQL / Flyway）
- SSE 推送与实时事件流
- 模型路由、handoff 与 ACP 接入边界
- 真实云端 sandbox backend：通过执行后端接口接入 `E2B` 或等价能力，不改变上层工具协议
- 薄 Web Session Console：优先展示 session / trace / approval / diff summary，不做 IDE clone
- 显式模型自动路由：优先做可解释策略，再考虑动态成本 / 质量权衡
- 结构化 handoff packet：跨模型、跨角色和会话恢复共用同一份交接语义
- ACP adapter：优先支持 editor / IDE ↔ agent 的接入，不把协议逻辑侵入 runtime 核心
- 工具手册按需加载：把单工具长说明从基础 prompt 中移出，仅在需要时注入
- 文档型 Agentic RAG：只检索项目文档与规范，不替代源码工具
- 多 Agent 角色分工：`Explore / Plan / Worker / Verification`
- 单平面调度与防递归：仅父级持有派工权，子 Agent 不再生成子 Agent
- 结构化历史归档：把运行摘要持久化为 append-only 历史
- 耐久记忆：只保留仓库级稳定事实，且要求可审计、可恢复
- idle session auto-compact：对空闲会话做后台压缩，降低长会话成本
- Hook 体系扩展：把 trace、checkpoint、telemetry、response finalize 挂到统一生命周期

**进入 Phase 2 的前提：**
- 已有基线评测报告
- Plan / Execute 只读阶段已进入 prompt 与 runtime 双重约束
- 确定性循环检测已能在 trace 中暴露空转原因
- Skill `allowed-tools` 已经进入 prompt 与 runtime 双重约束
- 轻量 checkpoint 已能支撑失败定位和压缩回放
- README / demo / 主计划与代码状态保持一致

- [ ] 二期实现工具手册时，优先做 `tool_search` 或等价的按需加载机制，不把所有工具说明全文塞进 system prompt。
- [ ] 二期接入真实云端 sandbox 时，坚持只通过 execution backend 接入，不允许在 `RunCommandTool` 内部私接 SDK，也不允许失败后静默回退到本地执行。
- [ ] 二期若补 Web 控制台，优先消费现有 session / trace / approval / diff summary 数据，不让 server 变成远程执行器。
- [ ] 二期做模型自动路由时，坚持先落可解释静态策略和可观测 trace，再考虑动态学习或复杂启发式。
- [ ] 二期做 handoff 时，优先复用统一 `HandoffPacket`，不允许靠拷贝整段原始历史实现“交接”。
- [ ] 二期若接 ACP，优先接入 editor / IDE 场景，不让协议适配层拥有独立运行时语义。
- [ ] 二期引入多 Agent 时，先落只读角色与 verification，再考虑可写 worker。
- [ ] 保持多 Agent 编排扁平化，避免递归派工导致成本、责任和调试复杂度失控。
- [ ] 二期若引入耐久记忆，优先做 append-only 历史和可审计恢复，不做人格化大而全记忆系统。
- [ ] 二期若引入入口扩展，优先保持事件边界稳定，不让 Web/IDE 接入直接侵入 runtime 主循环。
