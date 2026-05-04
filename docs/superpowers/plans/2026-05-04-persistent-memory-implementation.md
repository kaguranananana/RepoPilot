# Persistent Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 RepoPilot 落地“手动记忆 + 自动召回”的最小持久记忆系统：用户可显式创建、查看、列出、删除记忆；runtime 会在新任务开始前自动召回少量相关记忆，并继续要求模型用真实工具验证。

**Architecture:** 在 `repopilot-core` 新增独立 `memory` 子包，采用工作区文件化存储 `(.repopilot/memory/)`，通过 `MEMORY.md` 做轻量索引，单条 Markdown 文件承载完整正文。交互式 CLI 通过显式命令直接管理记忆，不经过模型；普通任务入口在 `DefaultInteractiveRuntimeRunner` 与 `CliRuntimeBootstrap` 中调用无工具的 recall selector 选出少量相关记忆，并以临时 `SYSTEM` 边界消息注入当前轮次。

**Tech Stack:** Java 17, Maven, JUnit 5, Jackson, Picocli

**Spec:** `docs/superpowers/specs/2026-05-04-persistent-memory-design.md`

**Real Model E2E Gate:** 自动召回包含一次真实模型 selector 调用，因此验收不能只看单元测试和 `bootstrap` 假模型。完成前至少要用真实 provider 跑通一次交互式 CLI：先 `/remember` 写入一条 `project` 记忆，再发起相关任务，确认本轮请求中出现 `# Recalled Memories`，并且最终回答仍通过 `read_file` / `grep_files` 等工具重新验证相关事实。

---

### Task 1: 持久记忆文件模型与索引存储

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/MemoryType.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/MemoryRecord.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/MemoryIndexEntry.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/PersistentMemoryStore.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/MemoryIndexRenderer.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/FilePersistentMemoryStore.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/memory/MemoryIndexRendererTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/memory/FilePersistentMemoryStoreTest.java`

- [ ] **Step 1: 先写 `MemoryIndexRendererTest`，锁定索引渲染、索引解析、重复 id 报错与非法格式报错**

```java
@Test
void shouldRenderAndParseStableMemoryIndex() {
    List<MemoryIndexEntry> entries = List.of(
            new MemoryIndexEntry(
                    "project-plan-execute-boundary",
                    MemoryType.PROJECT,
                    "Plan 与 Execute 必须分阶段",
                    "该仓库要求先只读取证，再进入修改与验证。",
                    Instant.parse("2026-05-04T10:00:00Z")
            )
    );

    String markdown = renderer.render(entries);
    assertTrue(markdown.contains("# Memory Index"));
    assertTrue(markdown.contains("project-plan-execute-boundary"));
    assertEquals(entries, renderer.parse(markdown));
}
```

- [ ] **Step 2: 再写 `FilePersistentMemoryStoreTest`，锁定 save/get/list/delete、front matter 缺字段报错和路径越界拒绝**

```java
@Test
void shouldSaveMemoryWriteRecordFileAndRewriteIndex() {
    MemoryRecord record = new MemoryRecord(
            "project-plan-execute-boundary",
            MemoryType.PROJECT,
            "Plan 与 Execute 必须分阶段",
            "该仓库要求先只读取证，再进入修改与验证。",
            "在 RepoPilot 中，PLAN 阶段只允许只读工具。",
            Instant.parse("2026-05-04T10:00:00Z"),
            Instant.parse("2026-05-04T10:00:00Z"),
            List.of("workflow", "runtime")
    );

    store.save(record);

    assertEquals(record, store.get("project-plan-execute-boundary").orElseThrow());
    assertTrue(Files.exists(memoryRoot.resolve("project/project-plan-execute-boundary.md")));
    assertTrue(Files.readString(memoryRoot.resolve("MEMORY.md")).contains("project-plan-execute-boundary"));
}
```

- [ ] **Step 3: 运行定向测试确认失败**

Run: `mvn -pl repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MemoryIndexRendererTest,FilePersistentMemoryStoreTest test`
Expected: FAIL，提示 `com.repopilot.core.memory` 相关类型尚不存在。

- [ ] **Step 4: 实现 `MemoryType`、`MemoryRecord`、`MemoryIndexEntry` 与 `PersistentMemoryStore` 的最小类型边界**

```java
public enum MemoryType {
    USER, PROJECT, FEEDBACK, REFERENCE
}
```

```java
public interface PersistentMemoryStore {
    void save(MemoryRecord record);
    Optional<MemoryRecord> get(String id);
    List<MemoryIndexEntry> list();
    void delete(String id);
}
```

- [ ] **Step 5: 实现 `MemoryIndexRenderer`，固定 `MEMORY.md` 的 Markdown 格式与反向解析规则**

```java
public String render(List<MemoryIndexEntry> entries) {
    StringBuilder builder = new StringBuilder("# Memory Index").append(System.lineSeparator());
    for (MemoryIndexEntry entry : entries) {
        builder.append("- id: ").append(entry.id()).append(System.lineSeparator())
                .append("  type: ").append(entry.type().name().toLowerCase()).append(System.lineSeparator())
                .append("  title: ").append(entry.title()).append(System.lineSeparator())
                .append("  summary: ").append(entry.summary()).append(System.lineSeparator())
                .append("  updated_at: ").append(entry.updatedAt()).append(System.lineSeparator());
    }
    return builder.toString().stripTrailing();
}
```

- [ ] **Step 6: 实现 `FilePersistentMemoryStore`，限定根目录为 `.repopilot/memory/`，写单条文件并重写索引**

```java
Path recordPath = memoryRoot
        .resolve(record.type().name().toLowerCase())
        .resolve(record.id() + ".md")
        .normalize();
if (!recordPath.startsWith(memoryRoot)) {
    throw new IllegalArgumentException("Memory record path 越界: " + record.id());
}
```

- [ ] **Step 7: 重新运行定向测试确认通过**

Run: `mvn -pl repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MemoryIndexRendererTest,FilePersistentMemoryStoreTest test`
Expected: PASS，覆盖稳定索引格式、front matter 读写与路径安全边界。

### Task 2: CLI 显式记忆命令与交互帮助

**Files:**
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/UserMemoryCommand.java`
- Create: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/UserMemoryCommandParser.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/InteractiveCliSession.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/ConsoleTraceObserver.java`
- Create: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/UserMemoryCommandParserTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/InteractiveCliSessionTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/ConsoleTraceObserverTest.java`

- [ ] **Step 1: 写 `UserMemoryCommandParserTest`，锁定 `/remember`、`/memories`、`/memory <id>`、`/forget <id>` 与普通文本不匹配**

```java
@Test
void shouldParseForgetCommandWithId() {
    UserMemoryCommand command = parser.parse("/forget project-plan-execute-boundary").orElseThrow();
    assertEquals(UserMemoryCommand.Type.FORGET, command.type());
    assertEquals("project-plan-execute-boundary", command.id());
}
```

- [ ] **Step 2: 写 `InteractiveCliSessionTest`，锁定显式记忆命令不进入 `runTurn`，而是直接走本地 store**

```java
assertEquals(0, runtimeRunner.runTurnCount());
assertTrue(output.contains("已保存记忆"));
assertTrue(store.list().stream().anyMatch(entry -> entry.id().equals("project-plan-execute-boundary")));
```

- [ ] **Step 3: 运行定向测试确认失败**

Run: `mvn -pl repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UserMemoryCommandParserTest,InteractiveCliSessionTest,ConsoleTraceObserverTest test`
Expected: FAIL，提示当前 CLI 不识别 memory 命令，也不会展示帮助文案。

- [ ] **Step 4: 实现 `UserMemoryCommand` 与 `UserMemoryCommandParser`，只接受明确命令语法，不做自然语言猜测**

```java
public enum Type {
    REMEMBER, LIST, SHOW, FORGET
}
```

```java
Optional<UserMemoryCommand> parse(String input)
```

- [ ] **Step 5: 在 `InteractiveCliSession` 中新增 memory 命令分支，先处理 `/memories`、`/memory`、`/forget`，最后处理 `/remember` 的交互录入**

```java
if (memoryCommand != null) {
    runMemoryCommand(memoryCommand);
    return;
}
```

- [ ] **Step 6: 更新 `ConsoleTraceObserver` 帮助输出，并在测试中锁定四个新命令都能展示出来**

Run: `mvn -pl repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UserMemoryCommandParserTest,InteractiveCliSessionTest,ConsoleTraceObserverTest test`
Expected: PASS，memory 命令可解析、可执行，并且不会误触发普通模型推理。

### Task 3: 无工具 recall selector 与相关记忆选择服务

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/MemoryRecallSelectionRequest.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/MemoryRecallSelection.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/MemoryRecallSelector.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/ModelMemoryRecallSelector.java`
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/MemoryRecallService.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/memory/ModelMemoryRecallSelectorTest.java`
- Create: `repopilot-core/src/test/java/com/repopilot/core/memory/MemoryRecallServiceTest.java`

- [ ] **Step 1: 写 `ModelMemoryRecallSelectorTest`，锁定 selector 只接受 JSON、拒绝工具调用、拒绝未知 id、最多返回 3 条**

```java
@Test
void shouldRejectUnknownMemoryIdsReturnedByModel() {
    ModelAdapter adapter = scriptedFinal("""
            {"selected_ids":["unknown-id"]}
            """);

    IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> selector.select(request)
    );

    assertTrue(exception.getMessage().contains("unknown-id"));
}
```

- [ ] **Step 2: 写 `MemoryRecallServiceTest`，锁定空索引短路、不调用模型；非空索引时只返回有限结果**

```java
@Test
void shouldShortCircuitWhenIndexIsEmpty() {
    MemoryRecallSelection selection = service.recall("先分析方案", AgentRunMode.PLAN, List.of());
    assertTrue(selection.selectedIds().isEmpty());
    assertEquals(0, adapter.callCount());
}
```

- [ ] **Step 3: 运行定向测试确认失败**

Run: `mvn -pl repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ModelMemoryRecallSelectorTest,MemoryRecallServiceTest test`
Expected: FAIL，提示 recall selector 相关类型不存在。

- [ ] **Step 4: 实现 `MemoryRecallSelectionRequest`、`MemoryRecallSelection` 与 `MemoryRecallSelector` 接口**

```java
public record MemoryRecallSelectionRequest(
        String prompt,
        AgentRunMode runMode,
        List<MemoryIndexEntry> candidates
) {}
```

```java
public interface MemoryRecallSelector {
    MemoryRecallSelection select(MemoryRecallSelectionRequest request);
}
```

- [ ] **Step 5: 实现 `ModelMemoryRecallSelector`，复用 `ModelStructuredContextSummaryGenerator` 的思路，要求模型只输出结构化 JSON**

```java
private static final String SYSTEM_PROMPT = """
        你是 RepoPilot 的 memory_recall_selector。
        你只能输出一个 JSON object，不允许输出 Markdown、解释文字或代码块。
        你不能调用任何工具。
        JSON 必须包含 selected_ids 字段，值是字符串数组，长度不能超过 3。
        """;
```

- [ ] **Step 6: 实现 `MemoryRecallService`，对空索引先短路，对非空索引只把 selector 结果转换成最终 recall 选择**

```java
if (candidates.isEmpty()) {
    return MemoryRecallSelection.empty();
}
return selector.select(new MemoryRecallSelectionRequest(prompt, runMode, candidates));
```

- [ ] **Step 7: 重新运行定向测试确认通过**

Run: `mvn -pl repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ModelMemoryRecallSelectorTest,MemoryRecallServiceTest test`
Expected: PASS，覆盖短路逻辑、非法 JSON 暴露和 recall 数量上限。

### Task 4: 将 recalled memory 作为临时边界消息接入交互式 runtime

**Files:**
- Create: `repopilot-core/src/main/java/com/repopilot/core/memory/RecalledMemoryPromptRenderer.java`
- Modify: `repopilot-core/src/main/java/com/repopilot/core/prompt/SystemPromptBuilder.java`
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/interactive/DefaultInteractiveRuntimeRunner.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/interactive/InteractiveRuntimeRunnerTest.java`
- Modify: `repopilot-core/src/test/java/com/repopilot/core/prompt/SystemPromptBuilderTest.java`

- [ ] **Step 1: 先写 `InteractiveRuntimeRunnerTest`，锁定每轮会移除旧 recalled block，再按当前 prompt 重建新的 recalled block**

```java
assertEquals(1, secondCall.stream()
        .filter(message -> message.role() == MessageRole.SYSTEM)
        .filter(message -> message.content().contains("# Recalled Memories"))
        .count());
assertFalse(secondCall.get(0).content().contains("旧 recalled block 原文"));
```

- [ ] **Step 2: 再写 `SystemPromptBuilderTest`，锁定基础指令中新增“recalled memory 不是事实源，使用前必须验证”的稳定规则**

```java
assertTrue(boundary.baseInstructions().contains("recalled memory 是历史线索"));
assertTrue(boundary.baseInstructions().contains("必须重新用工具验证"));
```

- [ ] **Step 3: 运行定向测试确认失败**

Run: `mvn -pl repopilot-cli,repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=InteractiveRuntimeRunnerTest,SystemPromptBuilderTest test`
Expected: FAIL，提示当前 runtime 不会注入 recalled memory，也不会剥离旧 recalled block。

- [ ] **Step 4: 实现 `RecalledMemoryPromptRenderer`，把若干条记忆聚合成一条 `SYSTEM` 消息**

```java
ConversationMessage render(List<MemoryRecord> records) {
    return new ConversationMessage(
            MessageRole.SYSTEM,
            "# Recalled Memories\n" + renderEntries(records)
    );
}
```

- [ ] **Step 5: 修改 `DefaultInteractiveRuntimeRunner`，把 `rebuildPromptBoundary(...)` 升级为能接收当前 prompt，并在“新边界 + preserved history”之间插入 recalled block**

```java
List<ConversationMessage> refreshedHistory = rebuildPromptBoundary(
        sessionSummary,
        history,
        safePrompt,
        toolRegistry,
        runMode
);
```

- [ ] **Step 6: 在 `stripPromptBoundaryMessages(...)` 中增加 `isRecalledMemoryMessage(...)`，确保旧 recalled block 不会无限累积**

```java
private boolean isRecalledMemoryMessage(ConversationMessage message) {
    return message.role() == MessageRole.SYSTEM
            && message.content().contains("# Recalled Memories");
}
```

- [ ] **Step 7: 重新运行定向测试确认通过**

Run: `mvn -pl repopilot-cli,repopilot-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=InteractiveRuntimeRunnerTest,SystemPromptBuilderTest test`
Expected: PASS，交互式 runtime 每轮只保留一份 recalled block，且基础提示清楚强调“记忆不是事实源”。

### Task 5: 单次运行入口接入自动召回，并补齐 request 侧验证

**Files:**
- Modify: `repopilot-cli/src/main/java/com/repopilot/cli/runtime/CliRuntimeBootstrap.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/runtime/CliRuntimeBootstrapTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/runtime/OpenAiCompatibleChatModelAdapterTest.java`
- Modify: `repopilot-cli/src/test/java/com/repopilot/cli/runtime/AnthropicChatModelAdapterTest.java`

- [ ] **Step 1: 写 `CliRuntimeBootstrapTest`，锁定单次运行入口也会读取 memory index 并注入 recalled block**

```java
assertTrue(recordedMessages.stream()
        .filter(message -> message.role() == MessageRole.SYSTEM)
        .anyMatch(message -> message.content().contains("# Recalled Memories")));
```

- [ ] **Step 2: 写或补模型适配器请求体测试，锁定 recalled block 会跟基础 system prompt、runtime context 一起被正确发送**

```java
assertTrue(lastRequestBody.contains("# Recalled Memories"));
assertTrue(lastRequestBody.contains("project-plan-execute-boundary"));
```

- [ ] **Step 3: 运行定向测试确认失败**

Run: `mvn -pl repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CliRuntimeBootstrapTest,OpenAiCompatibleChatModelAdapterTest,AnthropicChatModelAdapterTest test`
Expected: FAIL，提示单次运行入口当前还不会注入 recalled memory。

- [ ] **Step 4: 修改 `CliRuntimeBootstrap`，在 `buildMessages(...)` 前调用与交互式 runtime 同一套 recall 组件，并把 recalled block 放进本轮 `SYSTEM` 消息序列**

```java
List<ConversationMessage> messages = new ArrayList<>(buildInitialMessages(promptBoundary));
if (recalledMessage != null) {
    messages.add(recalledMessage);
}
messages.add(new ConversationMessage(MessageRole.USER, prompt));
```

- [ ] **Step 5: 重新运行定向测试确认通过**

Run: `mvn -pl repopilot-cli -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CliRuntimeBootstrapTest,OpenAiCompatibleChatModelAdapterTest,AnthropicChatModelAdapterTest test`
Expected: PASS，交互式与单次运行两条入口都能稳定注入 recalled memory。

### Task 6: 文档、CLI 说明与真实验收收口

**Files:**
- Modify: `README.md`
- Modify: `docs/demo/repopilot-demo.md`
- Optional Create: `.repopilot/memory/MEMORY.md`（仅在仓库决定保留演示样例时）

- [ ] **Step 1: 先写 README 文档变更，新增持久记忆能力说明、CLI 命令示例和“记忆不是事实源”的行为约束**

```text
/remember
/memories
/memory <id>
/forget <id>
```

- [ ] **Step 2: 更新 `docs/demo/repopilot-demo.md`，加入最小演示脚本：写入记忆 -> 发起相关任务 -> 观察 recalled block -> 删除记忆**

```text
/remember
type=project
title=Plan 与 Execute 必须分阶段
...
```

- [ ] **Step 3: 运行本轮所有关键测试，确认 core 与 cli 侧不回归**

Run: `mvn test`
Expected: PASS，`repopilot-core`、`repopilot-cli`、`repopilot-server`、`repopilot-protocol` 全部测试通过。

- [ ] **Step 4: 使用真实 provider 做交互式 CLI 验收，确认 recall selector 的额外模型调用与最终主任务都能成功完成**

Run:

```bash
docker compose up -d postgres
mvn -q -pl repopilot-server -am -DskipTests install
cd repopilot-server
set -a; source ../.env.local; set +a
mvn spring-boot:run
```

另开终端：

```bash
mvn -q -DskipTests install
mvn -q -pl repopilot-cli -am -DskipTests dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime
CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)"
java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication
```

Expected:
- `/remember` 可成功写入 `.repopilot/memory/`
- 发起相关任务时，本轮请求包含 `# Recalled Memories`
- 最终回答仍通过工具重新验证，而不是直接复述记忆

- [ ] **Step 5: 最后自查面试口径，确保能用四句话解释清楚这套能力**

```text
1. short-term memory 与 persistent memory 分层
2. 持久记忆是工作区可审计 Markdown 文件
3. 默认只看索引，按任务召回少量正文
4. 记忆不是事实源，使用前必须重新验证
```
