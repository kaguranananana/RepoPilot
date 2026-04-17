# RepoPilot 交互式 CLI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 RepoPilot CLI 根命令升级为默认进入交互式 REPL，会话内复用同一个 session 和消息历史，并在终端输出关键链路摘要。

**Architecture:** 在 `repopilot-cli` 中新增交互配置、REPL 会话、单轮运行器与终端摘要观察器；在 `repopilot-core` 中为 `AgentLoop` 增加可选观察器接口，用于把模型 step 和工具执行过程暴露给 CLI。保留现有 `run` 子命令，根命令直接进入交互模式。

**Tech Stack:** Java 17, Picocli, JDK IO, JDK HttpClient, JUnit 5

---

### Task 1: 规格化交互配置与根命令入口

**Files:**
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/InteractiveCliConfig.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/RepoPilotCliCommand.java`
- Test: `repopilot-cli/src/test/java/com/repopilot/cli/RepoPilotCliCommandTest.java`

- [ ] 写 `RepoPilotCliCommandTest`，锁定根命令启动交互入口而不是打印 skeleton 文案。
- [ ] 运行定向测试，确认当前行为失败。
- [ ] 实现 `InteractiveCliConfig`，从 `.env.local + 环境变量` 解析 `REPOPILOT_SERVER_BASE_URL` 与 `REPOPILOT_WORKSPACE_ID`。
- [ ] 修改 `RepoPilotCliCommand`，默认启动交互入口，同时保留 `run` 子命令。
- [ ] 重新运行定向测试，确认通过。

### Task 2: 实现交互式会话循环

**Files:**
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/InteractiveCliSession.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/InteractiveTurnResult.java`
- Test: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/InteractiveCliSessionTest.java`

- [ ] 写 `InteractiveCliSessionTest`，覆盖启动创建 session、普通 prompt、`/help`、`/reset`、`/exit`。
- [ ] 运行定向测试，确认缺少实现时失败。
- [ ] 实现 `InteractiveCliSession`，用注入的输入输出流驱动 REPL，并显式处理 `/help`、`/reset`、`/exit`。
- [ ] 让 `/reset` 清空消息历史并重新创建 session。
- [ ] 重新运行会话层测试，确认通过。

### Task 3: 实现多轮消息历史运行器

**Files:**
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/InteractiveRuntimeRunner.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/DefaultInteractiveRuntimeRunner.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/runtime/CliRuntimeBootstrap.java`
- Test: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/InteractiveRuntimeRunnerTest.java`

- [ ] 写 `InteractiveRuntimeRunnerTest`，锁定系统消息初始化、多轮历史复用、本轮 `USER` 追加与结果历史回写。
- [ ] 运行定向测试，确认当前缺少多轮运行能力时失败。
- [ ] 从 `CliRuntimeBootstrap` 中抽取可复用的系统消息构建与模型适配装配逻辑。
- [ ] 实现 `DefaultInteractiveRuntimeRunner`，让它基于已有消息历史驱动 `AgentLoop`。
- [ ] 重新运行运行器测试，确认通过。

### Task 4: 为 AgentLoop 增加观察器并输出摘要 trace

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoopObserver.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoop.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/ConsoleTraceObserver.java`
- Test: `repopilot-core/src/test/java/com/repopilot/core/agent/AgentLoopTest.java`
- Test: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/ConsoleTraceObserverTest.java`

- [ ] 先给 `AgentLoop` 观察器补测试，锁定 step、tool start、tool finish、final 的事件顺序。
- [ ] 运行相关测试，确认当前没有观察器能力时失败。
- [ ] 实现 `AgentLoopObserver` 与 `AgentLoop` 的可选通知点。
- [ ] 实现 `ConsoleTraceObserver`，把 step/tool/final 格式化成确定性的终端摘要。
- [ ] 重新运行观察器测试，确认通过。

### Task 5: 组装交互主链路并完成回归验证

**Files:**
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/RepoPilotCliCommand.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/command/RunCommandTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/runtime/CliRuntimeBootstrapTest.java`

- [ ] 把 `InteractiveCliConfig`、`InteractiveCliSession`、`DefaultInteractiveRuntimeRunner`、`ConsoleTraceObserver` 真正接到根命令。
- [ ] 保证现有 `run` 子命令不回归。
- [ ] 运行 `mvn -pl repopilot-cli,repopilot-core test`，修正交互模式引入的回归问题。
- [ ] 运行 `mvn test` 做全仓验证。
- [ ] 准备提交说明，记录交互模式入口和调试方式。
