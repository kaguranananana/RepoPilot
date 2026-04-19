# Skill Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 让 RepoPilot 在真实对话主链路中支持按需激活 Skill：用户可通过 `/skill-name` 或 `$skill-name` 显式激活，模型可通过显式 `activate_skill` 工具调用激活，激活后的 `SKILL.md` 正文会稳定进入后续上下文。

**Architecture:** 在 `repopilot-core` 中新增 `SkillActivationService`、`ActivatedSkillSet` 与 `activate_skill` 内置工具，并把工具执行链路升级为可携带“附加消息”的结果模型，从而让模型激活 Skill 后能把新的 `SYSTEM` 消息直接插入当前回合历史。在 `repopilot-cli` 交互层增加显式 Skill 命令解析与用户触发桥接，复用同一个激活服务完成注入，不引入普通自然语言匹配 heuristics。

**Tech Stack:** Java 17, Maven, JUnit 5, Jackson, Picocli

**Status:** 本计划覆盖的是“Skill 激活正文注入主链路”。当前阶段的后续高优先级不再写在这里继续横向追加，而是转入主计划中的两个收尾项：`Skill allowed-tools` 运行时治理与离线评测覆盖。

**Real Model E2E Gate:** 本功能不能只靠单元测试、scripted `ModelAdapter` 或本地 `bootstrap` 假模型验收。完成前必须启动真实 `repopilot-server` 与交互 CLI，使用真实模型 provider 发起真实模型调用，并在 verbose trace 中确认 `activate_skill` 工具调用成功、工具结果回注成功、下一轮消息历史出现 `# Activated Skill`。如果真实模型验收失败，必须先修复根因并重新验收，不能把 task 标记为完成。

---

### Task 1: Skill 激活核心模型与服务

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/ActivatedSkillSet.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillActivationResult.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/skill/SkillActivationService.java`
- Test: `repopilot-core/src/test/java/com/repopilot/core/skill/SkillActivationServiceTest.java`

- [x] **Step 1: 写失败测试，锁定存在 Skill、缺失 Skill、重复激活与注入消息格式**

```java
@Test
void shouldActivateExistingSkillIntoSystemMessage() {
    SkillActivationResult result = activationService.activate(ActivatedSkillSet.empty(), "debug");
    assertTrue(result.activatedNow());
    assertEquals("debug", result.skillName());
    assertEquals(MessageRole.SYSTEM, result.appendedMessages().get(0).role());
    assertTrue(result.appendedMessages().get(0).content().contains("# Activated Skill"));
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `mvn -pl repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SkillActivationServiceTest test`
Expected: FAIL，提示 `SkillActivationService` / `ActivatedSkillSet` / `SkillActivationResult` 不存在。

- [x] **Step 3: 实现 `ActivatedSkillSet`，支持从历史消息重建激活状态**

```java
public static ActivatedSkillSet fromMessages(List<ConversationMessage> messages)
public boolean contains(String skillName)
public ActivatedSkillSet append(String skillName, ConversationMessage message)
```

- [x] **Step 4: 实现 `SkillActivationResult`，显式返回“是否新激活 + 追加消息 + 用户可见结果文本”**

```java
public record SkillActivationResult(
        String skillName,
        boolean activatedNow,
        List<ConversationMessage> appendedMessages,
        String output
) {}
```

- [x] **Step 5: 实现 `SkillActivationService`，复用 `SkillLoader` 构造固定格式的 `SYSTEM` 消息**

```java
ConversationMessage activatedMessage = new ConversationMessage(
        MessageRole.SYSTEM,
        "# Activated Skill\nname: " + descriptor.name() + "\nsource: " + descriptor.source() + "\n\n" + content.body()
);
```

- [x] **Step 6: 重新运行定向测试确认通过**

Run: `mvn -pl repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SkillActivationServiceTest test`
Expected: PASS，覆盖存在 Skill、缺失 Skill、重复激活与消息格式。

### Task 2: 升级工具执行链路以支持“附加消息”

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/ToolExecutionContext.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/tool/ToolExecutionResult.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/tool/ToolHandler.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/tool/ToolRegistry.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/tool/governance/GovernedToolExecutor.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/agent/AgentLoop.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/tool/ToolRegistryTest.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/tool/governance/GovernedToolExecutorTest.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/agent/AgentLoopTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/agent/AgentLoopSkillActivationTest.java`

- [x] **Step 1: 写失败测试，锁定工具结果可携带附加消息并在下一轮模型调用前进入历史**

```java
@Test
void shouldAppendAdditionalSystemMessagesReturnedByToolExecution() {
    // 第一步模型调用 activate_skill，第二步应能看到新增 SYSTEM skill 正文。
}
```

- [x] **Step 2: 运行定向测试确认失败**

Run: `mvn -pl repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=AgentLoopSkillActivationTest,AgentLoopTest,GovernedToolExecutorTest,ToolRegistryTest test`
Expected: FAIL，提示当前工具链路无法提供执行上下文与附加消息。

- [x] **Step 3: 引入 `ToolExecutionContext`，把当前消息历史显式传给工具**

```java
public record ToolExecutionContext(List<ConversationMessage> messages) {}
```

- [x] **Step 4: 扩展 `ToolHandler` / `ToolRegistry` / `GovernedToolExecutor`，让工具执行签名统一变为“参数 + 上下文”**

```java
ToolExecutionResult execute(ToolExecutionContext context, Map<String, String> arguments);
```

- [x] **Step 5: 扩展 `ToolExecutionResult`，允许成功或可恢复错误同时携带 `appendedMessages`**

```java
public static ToolExecutionResult success(String output, List<ConversationMessage> appendedMessages)
public List<ConversationMessage> appendedMessages()
```

- [x] **Step 6: 修改 `AgentLoop`，在追加标准 `TOOL` 消息后，继续把 `executionResult.appendedMessages()` 追加到当前历史**

```java
messages.add(toolMessage);
messages.addAll(executionResult.appendedMessages());
```

- [x] **Step 7: 重新运行定向测试确认通过**

Run: `mvn -pl repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=AgentLoopSkillActivationTest,AgentLoopTest,GovernedToolExecutorTest,ToolRegistryTest test`
Expected: PASS，原有工具测试不回归，新测试能观察到 Skill 正文进入下一轮历史。

### Task 3: 接入 `activate_skill` 内置工具与模型触发主链路

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/ActivateSkillTool.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/tool/builtin/BuiltinToolRegistrar.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/runtime/CliRuntimeBootstrap.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/DefaultInteractiveRuntimeRunner.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/tool/builtin/ActivateSkillToolTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/runtime/CliRuntimeBootstrapTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/runtime/DeepSeekChatModelAdapterTest.java`

- [x] **Step 1: 写失败测试，锁定 `activate_skill` 工具注册、存在 Skill 激活、缺失 Skill 返回 `RECOVERABLE_ERROR`**

```java
@Test
void shouldActivateSkillAndReturnAdditionalSystemMessage() {
    ToolExecutionResult result = tool.execute(context, Map.of("name", "debug"));
    assertEquals(Status.SUCCESS, result.status());
    assertEquals(1, result.appendedMessages().size());
}
```

- [x] **Step 2: 运行定向测试确认失败**

Run: `mvn -pl repopilot-core,repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ActivateSkillToolTest,CliRuntimeBootstrapTest,DeepSeekChatModelAdapterTest test`
Expected: FAIL，提示 `activate_skill` 未注册或行为不符。

- [x] **Step 3: 实现 `ActivateSkillTool`，内部调用 `SkillActivationService.activate(ActivatedSkillSet.fromMessages(context.messages()), name)`**

```java
SkillActivationResult result = activationService.activate(ActivatedSkillSet.fromMessages(context.messages()), skillName);
return ToolExecutionResult.success(result.output(), result.appendedMessages());
```

- [x] **Step 4: 修改 `BuiltinToolRegistrar`，固定把 `activate_skill` 注册到工具列表中**

```java
toolRegistry.register("activate_skill", "按名称激活单个 Skill", schema, activateSkillTool);
```

- [x] **Step 5: 修改 CLI/bootstrap 与交互 runtime 的工具装配，把现有 `skillLoader` 注入到 `BuiltinToolRegistrar` / `ActivateSkillTool`**

```java
BuiltinToolRegistrar.registerAll(toolRegistry, workspaceRoot, skillLoader);
```

- [x] **Step 6: 重新运行定向测试确认通过**

Run: `mvn -pl repopilot-core,repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ActivateSkillToolTest,CliRuntimeBootstrapTest,DeepSeekChatModelAdapterTest,AgentLoopSkillActivationTest test`
Expected: PASS，模型能看到并调用 `activate_skill`，激活后的 Skill 正文进入下一轮上下文。

### Task 4: 接入用户显式 Skill 命令解析与交互桥接

**Files:**
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/UserSkillCommand.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/UserSkillCommandParser.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/InteractiveRuntimeRunner.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/DefaultInteractiveRuntimeRunner.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/InteractiveCliSession.java`
- Create: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/UserSkillCommandParserTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/InteractiveRuntimeRunnerTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/InteractiveCliSessionTest.java`

- [x] **Step 1: 写失败测试，锁定 `/debug`、`$debug`、`/debug 修复这个测试`、非法多 Skill 输入与普通文本不匹配**

```java
@Test
void shouldParseSkillCommandWithRemainingPrompt() {
    UserSkillCommand command = parser.parse("/debug 修复这个测试").orElseThrow();
    assertEquals("debug", command.skillName());
    assertEquals("修复这个测试", command.remainingPrompt());
}
```

- [x] **Step 2: 写失败测试，锁定只有 `/debug` 时只激活不跑模型，`/debug 修复这个测试` 时激活后继续执行**

```java
assertEquals(0, runtimeRunner.runTurnCount.get()); // 只有 /debug
assertEquals(1, runtimeRunner.runTurnCount.get()); // /debug 修复这个测试
```

- [x] **Step 3: 运行定向测试确认失败**

Run: `mvn -pl repopilot-cli,repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UserSkillCommandParserTest,InteractiveRuntimeRunnerTest,InteractiveCliSessionTest test`
Expected: FAIL，提示当前没有显式 Skill 命令解析与激活入口。

- [x] **Step 4: 实现 `UserSkillCommandParser`，仅支持 `/skill-name` 与 `$skill-name`，拒绝多 Skill 与空名字**

```java
Optional<UserSkillCommand> parse(String input)
```

- [x] **Step 5: 在 `DefaultInteractiveRuntimeRunner` 中增加用户激活入口，复用 `SkillActivationService` 更新历史**

```java
List<ConversationMessage> activateSkill(List<ConversationMessage> history, String skillName)
```

- [x] **Step 6: 修改 `InteractiveCliSession`，在普通 turn 前优先解析 Skill 命令，并按“仅激活”或“激活后继续跑”两种路径分流**

```java
if (skillCommand.remainingPrompt() == null) {
    history = runtimeRunner.activateSkill(...).messages();
    traceObserver.onAssistantAnswer(result.output());
    return;
}
```

- [x] **Step 7: 重新运行定向测试确认通过**

Run: `mvn -pl repopilot-cli,repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UserSkillCommandParserTest,InteractiveRuntimeRunnerTest,InteractiveCliSessionTest test`
Expected: PASS，显式命令解析准确，用户触发路径不依赖普通自然语言匹配。

### Task 5: 上下文压缩、回归验证与人工验收

**Files:**
- Modify: `repopilot-core/src/test/java/com/repopilot/core/context/ContextCompactorTest.java`
- Modify: `docs/superpowers/plans/2026-04-19-skill-activation-implementation.md`

- [x] **Step 1: 写或补 `ContextCompactorTest`，锁定激活后的 Skill `SYSTEM` 消息在压缩后仍被保留**

```java
assertTrue(result.messages().stream()
        .filter(message -> message.role() == MessageRole.SYSTEM)
        .anyMatch(message -> message.content().contains("# Activated Skill")));
```

- [x] **Step 2: 运行上下文压缩与 Skill 激活相关测试确认失败**

Run: `mvn -pl repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ContextCompactorTest,AgentLoopSkillActivationTest test`
Expected: 如果压缩链路遗漏激活正文，测试应 FAIL。

- [x] **Step 3: 修正压缩或历史组装细节，确保激活 Skill 的 `SYSTEM` 消息不会被压缩吞掉**

```java
// 保持所有 SYSTEM 消息完整保留，Activated Skill 与基础 prompt 共用同一保留语义。
```

- [x] **Step 4: 运行本 feature 的完整自动化验证**

Run: `mvn -pl repopilot-core,repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SkillActivationServiceTest,ActivateSkillToolTest,AgentLoopSkillActivationTest,UserSkillCommandParserTest,InteractiveRuntimeRunnerTest,InteractiveCliSessionTest,ContextCompactorTest,CliRuntimeBootstrapTest,DeepSeekChatModelAdapterTest test`
Expected: 全部 PASS，无回归失败。

- [x] **Step 5: 更新本计划勾选状态，并补人工交互式验收说明**

Manual acceptance:
- 准备真实 Skill 文件，例如用户级 `~/.repopilot/skills/debug/SKILL.md`。
- 启动真实控制面：`mvn -f repopilot-server/pom.xml spring-boot:run`。
- 启动交互 CLI，并设置 `REPOPILOT_TRACE_LEVEL=verbose`。
- 用户显式触发路径：输入 `/debug`，预期终端返回 “Skill debug 已激活” 类确认文本，不发起模型回合。
- 用户后续回合路径：再输入 `修复这个测试`，预期下一轮模型请求中带有 `# Activated Skill` 对应正文。
- 同轮显式触发路径：输入 `/debug 修复这个测试`，预期同一轮先出现 `# Activated Skill`，再把 `修复这个测试` 作为真实 USER prompt。
- 真实模型工具触发路径：切到真实模型 provider，输入“请先调用 activate_skill 工具激活 debug Skill，再继续回答”，预期 trace 中先出现 `activate_skill(name=debug)` 工具调用成功，再在下一轮上下文中看到激活后的 Skill 正文。

**Acceptance:**
- Command: `mvn -pl repopilot-core,repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SkillActivationServiceTest,ActivateSkillToolTest,AgentLoopSkillActivationTest,UserSkillCommandParserTest,InteractiveRuntimeRunnerTest,InteractiveCliSessionTest,ContextCompactorTest,CliRuntimeBootstrapTest,DeepSeekChatModelAdapterTest test`
- Expected: `SkillActivationServiceTest`、`ActivateSkillToolTest`、`AgentLoopSkillActivationTest`、`UserSkillCommandParserTest`、`InteractiveRuntimeRunnerTest`、`InteractiveCliSessionTest`、`ContextCompactorTest`、`CliRuntimeBootstrapTest`、`DeepSeekChatModelAdapterTest` 全部通过。
- Observe: 用户通过 `/skill-name` 或 `$skill-name` 可显式激活 Skill；模型可通过 `activate_skill` 工具显式请求激活；激活后的 `SKILL.md` 正文会以新的 `SYSTEM` 消息进入后续轮次；重复激活不会重复注入正文；压缩后仍保留已激活 Skill 的 `SYSTEM` 消息；不存在 Skill 时用户路径直接报错，模型路径收到 `RECOVERABLE_ERROR`。
- Real Model E2E: 必须使用真实模型 provider 跑通上述真实模型工具触发路径；如果 trace 中没有出现成功的 `activate_skill` 工具调用和下一轮 `# Activated Skill`，本功能不算完成。

**Execution Result:**
- 2026-04-19 已按计划完成 Task 1-5。
- 自动化验证已执行，上述 Acceptance 命令返回 `BUILD SUCCESS`。
- 真实端到端验收已补跑：真实 server + 真实交互 CLI + 真实用户级 `debug` Skill + 真实 DeepSeek 模型调用。验收过程中发现 `activate_skill` 被 `WorkspacePermissionPolicy` fail-closed 拒绝，已通过 `fix: allow skill activation tool in permission policy` 修复并重新验收通过。
- 真实模型显式工具触发已通过：trace 中出现 `activate_skill(name=debug)` 工具调用成功，下一轮消息历史出现 `# Activated Skill`。
- “模型弱提示下自主优先选择 Skill”不是当前稳定验收点；真实模型可能先选择其他工具。当前完成标准固定为显式真实模型工具触发链路成功。
