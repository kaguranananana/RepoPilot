# RepoPilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个采用“本地执行 Runtime + 服务端 Control Plane”架构的 Java Coding Agent 平台，先跑通最小可验证主链路，并补上静态/动态 prompt 边界、结构化 short-term memory、渐进式 skill loading、可替换执行后端抽象、显式模型路由 / handoff 预留与最小评估链路。

**Architecture:** `repopilot-protocol` 提供共享协议，`repopilot-core` 提供最小 ReAct Runtime、system prompt 组装、working memory、渐进式 skill loading、模型路由 / handoff 结构与受治理的工具执行链路，`repopilot-cli` 负责本地入口、HTTP 客户端、执行后端装配、未来 ACP adapter 边界与评估运行器，`repopilot-server` 提供 session / trace 控制面。先用内存存储打通链路，再逐步替换为 JPA 与 PostgreSQL，并保持命令执行后端可从本地实现演进到真实 sandbox。

**Tech Stack:** Java 17, Maven, Spring Boot 3, Jackson, Picocli, JDK HttpClient, JUnit 5

**Task Completion Contract:** 自当前执行阶段起，任何未完成 task 在勾选完成前，都必须先跑完对应自动化验证，再提供一组可由用户在交互式终端亲自执行的验收命令或操作步骤，写清预期现象，并在该检查点暂停等待人工确认；如果 task 本身是内部整理、单独不可见，就继续补到最小可见 smoke path 后再算完成。

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

- [ ] 定义 `working_memory` 与 `context_summary` 在运行时消息模型中的表示方式。
- [ ] 固定保留 system prompt、任务目标、已确认事实、最近关键工具结果、当前阻塞、产出物引用与最近若干轮高保真消息。
- [ ] 把更早的工具执行轨迹压缩为结构化摘要，而不是普通聊天总结。
- [ ] 给压缩动作预留 trace 钩子，便于后续回放和调试。
- [ ] 给后续 append-only 历史归档、会话恢复和 idle session auto-compact 预留数据结构接口。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

### Task 12: Skill 渐进式加载

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillSummary.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillDescriptor.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillContent.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillLoader.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillIndex.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/skill/SkillLoaderTest.java`

- [ ] 扫描项目级和用户级 Skill 元信息，建立稳定排序的 `skill index`。
- [ ] 默认只把 skill 摘要暴露给 system prompt，并给后续 `allowed-tools` 约束预留字段。
- [ ] 提供按名称加载完整 Skill 正文的能力。
- [ ] 提供 Skill 附属脚本、模板、示例文档的按需递进加载能力。
- [ ] 为后续 `global policy ∩ skill allowed-tools` 的工具约束模型预留扩展点。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

### Task 13: 命令执行后端抽象与 Sandbox 预留

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
- [ ] 在 CLI/bootstrap 层明确选择执行 backend，并保证默认行为是显式配置而不是隐式 fallback。
- [ ] 为后续 `run_code` 与真实云端 sandbox backend（如 `E2B`）预留接口，但一期不接入真实远程服务。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

### Task 14: Background Task

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/background/BackgroundTaskManager.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/background/BackgroundTaskHandle.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/background/BackgroundTaskManagerTest.java`

- [ ] 支持长命令异步执行。
- [ ] 支持下一轮推理前读取后台完成结果。
- [ ] 复用命令执行后端抽象，避免后台任务再维护一套独立进程管理逻辑。
- [ ] 为后续 task system 预留扩展点。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

### Task 15: 最小评估链路

**Files:**
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/RepoPilotCliCommand.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/command/EvalCommand.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/eval/EvalScenario.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/eval/EvalResult.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/eval/EvalRunner.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/eval/EvalReportWriter.java`
- Create: `repopilot-cli/src/test/java/com/repopilot/cli/eval/EvalRunnerTest.java`
- Create: `docs/eval/repopilot-eval-scenarios.md`

- [ ] 先定义一组固定的最小任务集，避免“准确率”只停留在主观描述。
- [ ] 统计至少 `tool_call_valid_rate`、`patch_apply_success_rate`、`build_or_test_pass_rate`、`task_success_rate`、`avg_steps`、`avg_duration`。
- [ ] 提供命令行评估入口，能够重复执行同一组任务并输出结构化报告。
- [ ] 保持评估链路独立于主 runtime，避免为了评估污染正常执行路径。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

### Task 16: 持久化替换

**Files:**
- Modify: `repopilot-server/pom.xml`
- Create: `repopilot-server/src/main/java/com/repopilot/server/session/persistence/...`
- Create: `repopilot-server/src/main/resources/application.yml`
- Create: `repopilot-server/src/main/resources/db/migration/...`
- Create: `repopilot-server/src/test/java/com/repopilot/server/session/persistence/...`

- [ ] 把内存 session / trace 存储替换为 JPA。
- [ ] 接入 PostgreSQL 与 Flyway。
- [ ] 保证 controller 层接口不变。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

### Task 17: SSE 推送与实时事件流

**Files:**
- Modify: `repopilot-server/src/main/java/com/repopilot/server/session/SessionController.java`
- Modify: `repopilot-server/src/main/java/com/repopilot/server/session/SessionApplicationService.java`
- Create: `repopilot-server/src/main/java/com/repopilot/server/session/TraceEventStreamBroadcaster.java`
- Create: `repopilot-server/src/test/java/com/repopilot/server/session/SessionTraceSseTests.java`

- [ ] 提供基于 session 的最小 SSE 订阅入口，让控制面可以实时接收 trace 事件。
- [ ] 保持 SSE 事件模型复用现有 `TraceEventRecord` 语义，不再发明第二套事件协议。
- [ ] 保证已有 HTTP 查询接口仍然可用，SSE 只作为增量实时通道存在。
- [ ] 为后续 Web 控制台或 IDE 订阅实时事件预留稳定入口。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

### Task 18: 模型路由、Handoff 与 ACP 接入边界预留

**Files:**
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/runtime/CliRuntimeBootstrap.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/modelrouting/ModelRouteRequest.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/modelrouting/ModelRouteDecision.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/modelrouting/ModelRouter.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/modelrouting/StaticPolicyModelRouter.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/handoff/HandoffPacket.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/handoff/HandoffBuilder.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/runtime/CliModelRoutingConfig.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/acp/AcpAgentAdapter.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/acp/AcpSessionBridge.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/modelrouting/StaticPolicyModelRouterTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/handoff/HandoffBuilderTest.java`
- Create: `repopilot-cli/src/test/java/com/repopilot/cli/runtime/CliModelRoutingConfigTest.java`
- Create: `repopilot-cli/src/test/java/com/repopilot/cli/acp/AcpSessionBridgeTest.java`

- [ ] 抽象 `ModelRouter`，让模型选择基于显式策略输入，而不是散落在 bootstrap 或 adapter 里的隐式判断。
- [ ] 固定 `ModelRouteRequest / ModelRouteDecision` 结构，至少覆盖任务模式、工具需求、上下文体积、预算和所选模型。
- [ ] 提供最小 `StaticPolicyModelRouter`，先实现可解释、可测试的静态策略，不做黑盒自动路由。
- [ ] 定义 `HandoffPacket`，把任务目标、用户约束、`working_memory` 快照、关键证据、允许工具和剩余预算收敛成统一交接结构。
- [ ] 让 `CliRuntimeBootstrap` 能消费路由决策并生成 handoff 数据，但不在一期引入复杂多模型协作。
- [ ] 为后续 `Agent Client Protocol (ACP)` 接入预留 adapter 边界，把协议映射留在 CLI 接入层，不让 `AgentLoop` 直接依赖协议细节。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

### Task 19: 演示与收尾

**Files:**
- Create: `README.md`
- Create: `docs/demo/repopilot-demo.md`
- Modify: `docs/superpowers/specs/2026-04-15-repopilot-design.md`
- Modify: `docs/superpowers/plans/2026-04-15-repopilot-implementation.md`

- [ ] 补一份项目说明文档。
- [ ] 补一份演示脚本。
- [ ] 把当前进度和后续里程碑同步回 spec / plan。
- [ ] 保证每次阶段结束都能通过 `mvn test`。
- [ ] 提供本 task 的交互式终端验收命令、预期现象与观察重点，并在人工确认后再勾选完成。

### Phase 2 Preview: Web 控制台、云端 Sandbox 与多 Agent

**方向，不纳入一期关键路径：**
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
