# RepoPilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个采用“本地执行 Runtime + 服务端 Control Plane”架构的 Java Coding Agent 平台，先跑通最小可验证主链路，并补上静态/动态 prompt 边界、结构化上下文压缩与最小评估链路。

**Architecture:** `repopilot-protocol` 提供共享协议，`repopilot-core` 提供最小 ReAct Runtime、system prompt 组装、Hook 生命周期、上下文压缩和受治理的工具执行链路，`repopilot-cli` 负责本地入口、HTTP 客户端与评估运行器，`repopilot-server` 提供 session / trace 控制面。先用内存存储打通链路，再逐步替换为 JPA 与 PostgreSQL。

**Tech Stack:** Java 17, Maven, Spring Boot 3, Jackson, Picocli, JDK HttpClient, JUnit 5

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

- [ ] 固定 system prompt 中静态宪法与动态政策的边界，避免用户和工作区数据混入稳定前缀。
- [ ] 动态段先支持 session preamble、工作区信息、Skill 摘要、预算提示与可用工具子集。
- [ ] 把高频 runtime metadata 设计成独立 runtime context 块，不直接写进稳定 system prompt。
- [ ] 保证静态段内容稳定、动态段内容可替换，为后续缓存和成本优化打基础。

### Task 7: CLI `run` 主命令

**Files:**
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/RepoPilotCliCommand.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/command/RunCommand.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/runtime/CliRuntimeBootstrap.java`
- Create: `repopilot-cli/src/test/java/com/repopilot/cli/command/RunCommandTest.java`

- [ ] 把“创建 session -> 启动本地 runtime”的入口挂到 CLI。
- [ ] 先支持最小参数：`workspaceId`、`serverBaseUrl`、`prompt`。
- [ ] 让 CLI 能把一次用户任务送进 core。

### Task 8: 第一批真实工具

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/ReadFileTool.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/GrepFilesTool.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/RunCommandTool.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/BuiltinToolRegistrar.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/tool/builtin/ReadFileToolTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/tool/builtin/GrepFilesToolTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/tool/builtin/RunCommandToolTest.java`

- [ ] 先补 `read_file`。
- [ ] 再补 `grep_files`。
- [ ] 最后补 `run_command`。
- [ ] 保证三者都能被 Tool Registry 注册和执行。

### Task 9: 运行时 Trace 上报

**Files:**
- Modify: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoop.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/trace/TracePublisher.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/session/TraceApiClient.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/session/DefaultHttpTraceApiClient.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/trace/TracePublishingAgentLoopTest.java`

- [ ] 在模型调用前后发 trace。
- [ ] 在工具调用前后发 trace。
- [ ] 让工具 trace 明确记录 `SUCCESS / RECOVERABLE_ERROR / FATAL_ERROR` 三态，并保证 `FATAL_ERROR` 在中断主链路前也能被上报。
- [ ] 把 trace 从 core 经 CLI 同步到 server。
- [ ] 为后续 Hook 生命周期预留最小扩展点，不把 trace 上报硬编码成唯一实现。

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

- [ ] 把工具执行从“直接调用 handler”升级为“校验 -> 权限 -> 执行 -> 结构化结果”的受治理流水线。
- [ ] 权限决策遵循 `deny -> ask -> allow` 顺序，并默认 fail-closed。
- [ ] 统一工具失败出口：由治理层把校验失败、权限拒绝、执行异常收敛成 `RECOVERABLE_ERROR` 或 `FATAL_ERROR`，避免 `AgentLoop` 同时消费多套错误协议。
- [ ] 限制工具只能操作当前工作区，并给危险命令留出审批钩子。
- [ ] 在写文件前生成 diff 摘要，防止模型直接写盘绕过审查。
- [ ] 保持工具定义输出顺序稳定，避免动态工具集导致 prompt 抖动。

### Task 11: 结构化上下文压缩

**Files:**
- Modify: `repopilot-core/src/main/java/com/repopilot/core/model/MessageRole.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/model/ConversationMessage.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoop.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/context/ContextCompactionPolicy.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/context/ContextCompactor.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/context/ContextCompactorTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/agent/AgentLoopContextCompactionTest.java`

- [ ] 定义 `context_summary` 在运行时消息模型中的表示方式。
- [ ] 固定保留 system prompt、任务目标与最近若干轮高保真消息。
- [ ] 把更早的工具执行轨迹压缩为结构化摘要，而不是普通聊天总结。
- [ ] 给压缩动作预留 trace 钩子，便于后续回放和调试。
- [ ] 给后续 append-only 历史归档和 idle session auto-compact 预留数据结构接口。

### Task 12: 持久化替换

**Files:**
- Modify: `repopilot-server/pom.xml`
- Create: `repopilot-server/src/main/java/com/repopilot/server/session/persistence/...`
- Create: `repopilot-server/src/main/resources/application.yml`
- Create: `repopilot-server/src/main/resources/db/migration/...`
- Create: `repopilot-server/src/test/java/com/repopilot/server/session/persistence/...`

- [ ] 把内存 session / trace 存储替换为 JPA。
- [ ] 接入 PostgreSQL 与 Flyway。
- [ ] 保证 controller 层接口不变。

### Task 13: Background Task

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/background/BackgroundTaskManager.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/background/BackgroundTaskHandle.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/background/BackgroundTaskManagerTest.java`

- [ ] 支持长命令异步执行。
- [ ] 支持下一轮推理前读取后台完成结果。
- [ ] 为后续 task system 预留扩展点。

### Task 14: Skill Loading

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillSummary.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillLoader.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/skill/SkillLoaderTest.java`

- [ ] 扫描项目级和用户级 `SKILL.md`。
- [ ] 暴露 skill 摘要给 system prompt，并给后续 `allowed-tools` 约束预留字段。
- [ ] 提供按名称加载完整 skill 内容的能力。
- [ ] 为后续 `global policy ∩ skill allowed-tools` 的工具约束模型预留扩展点。

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

### Task 16: 演示与收尾

**Files:**
- Create: `README.md`
- Create: `docs/demo/repopilot-demo.md`
- Modify: `docs/superpowers/specs/2026-04-15-repopilot-design.md`
- Modify: `docs/superpowers/plans/2026-04-15-repopilot-implementation.md`

- [ ] 补一份项目说明文档。
- [ ] 补一份演示脚本。
- [ ] 把当前进度和后续里程碑同步回 spec / plan。
- [ ] 保证每次阶段结束都能通过 `mvn test`。

### Phase 2 Preview: 工具知识与多 Agent

**方向，不纳入一期关键路径：**
- 工具手册按需加载：把单工具长说明从基础 prompt 中移出，仅在需要时注入
- 文档型 Agentic RAG：只检索项目文档与规范，不替代源码工具
- 多 Agent 角色分工：`Explore / Plan / Worker / Verification`
- 单平面调度与防递归：仅父级持有派工权，子 Agent 不再生成子 Agent
- 结构化历史归档：把运行摘要持久化为 append-only 历史
- 耐久记忆：只保留仓库级稳定事实，且要求可审计、可恢复
- idle session auto-compact：对空闲会话做后台压缩，降低长会话成本
- Hook 体系扩展：把 trace、checkpoint、telemetry、response finalize 挂到统一生命周期

- [ ] 二期实现工具手册时，优先做 `tool_search` 或等价的按需加载机制，不把所有工具说明全文塞进 system prompt。
- [ ] 二期引入多 Agent 时，先落只读角色与 verification，再考虑可写 worker。
- [ ] 保持多 Agent 编排扁平化，避免递归派工导致成本、责任和调试复杂度失控。
- [ ] 二期若引入耐久记忆，优先做 append-only 历史和可审计恢复，不做人格化大而全记忆系统。
- [ ] 二期若引入入口扩展，优先保持事件边界稳定，不让 Web/IDE 接入直接侵入 runtime 主循环。
