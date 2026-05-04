package com.repopilot.cli.interactive;

import com.repopilot.cli.runtime.CliRuntimeBootstrap;
import com.repopilot.cli.runtime.LocalEnvironmentMapLoader;
import com.repopilot.cli.session.DefaultHttpTraceApiClient;
import com.repopilot.cli.session.DefaultHttpSessionApiClient;
import com.repopilot.core.memory.FilePersistentMemoryStore;
import com.repopilot.core.memory.MemoryRecord;
import com.repopilot.core.memory.MemoryType;
import com.repopilot.core.memory.PersistentMemoryStore;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionSummary;
import com.repopilot.protocol.trace.AppendTraceEventRequest;
import com.repopilot.protocol.trace.TraceEventRecord;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 交互式 CLI 会话。
 * 它负责管理整场 REPL 生命周期和消息历史，
 * 但真正的模型调用与工具执行仍然交给单轮运行器。
 */
public final class InteractiveCliSession {

    private static final String EXIT_COMMAND = "/exit";
    private static final String HELP_COMMAND = "/help";
    private static final String RESET_COMMAND = "/reset";
    private static final String PLAN_COMMAND = "/plan";
    private static final String EXECUTE_COMMAND = "/execute";

    private final InteractiveLineInput lineInput;
    private final PrintWriter outputWriter;
    private final SessionClient sessionClient;
    private final TraceClient traceClient;
    private final InteractiveCliConfig config;
    private final InteractiveRuntimeRunner runtimeRunner;
    private final ConsoleTraceObserver traceObserver;
    private final UserSkillCommandParser skillCommandParser;
    private final UserMemoryCommandParser memoryCommandParser;
    private final PersistentMemoryStore memoryStore;

    private SessionSummary currentSession;
    private List<ConversationMessage> history = List.of();
    private InteractionMode interactionMode = InteractionMode.EXECUTE;

    public static InteractiveCliSession createDefault() {
        Path workspaceRoot = Path.of("").toAbsolutePath().normalize();
        Map<String, String> environment = LocalEnvironmentMapLoader.load(workspaceRoot, System.getenv());
        InteractiveCliConfig config = InteractiveCliConfig.fromEnvironment(environment);
        PrintWriter outputWriter = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        BufferedReader inputReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        InteractiveLineInput lineInput = new InteractiveLineInput(inputReader);

        // 默认会话把标准输入输出直接接到终端，
        // 这样用户双击启动 main 后就能马上进入 REPL。
        return new InteractiveCliSession(
                lineInput,
                outputWriter,
                new DefaultHttpSessionApiClient(config.serverBaseUrl())::createSession,
                new DefaultHttpTraceApiClient(config.serverBaseUrl())::appendTraceEvent,
                config,
                new DefaultInteractiveRuntimeRunner(
                        Clock.systemUTC(),
                        new CliRuntimeBootstrap.EnvironmentBackedModelAdapterFactory(environment),
                        workspaceRoot,
                        config.maxSteps(),
                        new TerminalApprovalHandler(lineInput, outputWriter)
                ),
                new ConsoleTraceObserver(outputWriter, config.traceLevel()),
                new UserSkillCommandParser(),
                new UserMemoryCommandParser(),
                new FilePersistentMemoryStore(workspaceRoot)
        );
    }

    InteractiveCliSession(
            BufferedReader inputReader,
            PrintWriter outputWriter,
            SessionClient sessionClient,
            InteractiveCliConfig config,
            InteractiveRuntimeRunner runtimeRunner,
            ConsoleTraceObserver traceObserver
    ) {
        this(
                new InteractiveLineInput(inputReader),
                outputWriter,
                sessionClient,
                TraceClient.noop(),
                config,
                runtimeRunner,
                traceObserver,
                new UserSkillCommandParser(),
                new UserMemoryCommandParser(),
                new FilePersistentMemoryStore(Path.of("").toAbsolutePath().normalize())
        );
    }

    InteractiveCliSession(
            InteractiveLineInput lineInput,
            PrintWriter outputWriter,
            SessionClient sessionClient,
            InteractiveCliConfig config,
            InteractiveRuntimeRunner runtimeRunner,
            ConsoleTraceObserver traceObserver
    ) {
        this(
                lineInput,
                outputWriter,
                sessionClient,
                TraceClient.noop(),
                config,
                runtimeRunner,
                traceObserver,
                new UserSkillCommandParser(),
                new UserMemoryCommandParser(),
                new FilePersistentMemoryStore(Path.of("").toAbsolutePath().normalize())
        );
    }

    InteractiveCliSession(
            InteractiveLineInput lineInput,
            PrintWriter outputWriter,
            SessionClient sessionClient,
            TraceClient traceClient,
            InteractiveCliConfig config,
            InteractiveRuntimeRunner runtimeRunner,
            ConsoleTraceObserver traceObserver
    ) {
        this(
                lineInput,
                outputWriter,
                sessionClient,
                traceClient,
                config,
                runtimeRunner,
                traceObserver,
                new UserSkillCommandParser(),
                new UserMemoryCommandParser(),
                new FilePersistentMemoryStore(Path.of("").toAbsolutePath().normalize())
        );
    }

    InteractiveCliSession(
            InteractiveLineInput lineInput,
            PrintWriter outputWriter,
            SessionClient sessionClient,
            TraceClient traceClient,
            InteractiveCliConfig config,
            InteractiveRuntimeRunner runtimeRunner,
            ConsoleTraceObserver traceObserver,
            UserSkillCommandParser skillCommandParser
    ) {
        this(
                lineInput,
                outputWriter,
                sessionClient,
                traceClient,
                config,
                runtimeRunner,
                traceObserver,
                skillCommandParser,
                new UserMemoryCommandParser(),
                new FilePersistentMemoryStore(Path.of("").toAbsolutePath().normalize())
        );
    }

    InteractiveCliSession(
            BufferedReader inputReader,
            PrintWriter outputWriter,
            SessionClient sessionClient,
            InteractiveCliConfig config,
            InteractiveRuntimeRunner runtimeRunner,
            ConsoleTraceObserver traceObserver,
            UserSkillCommandParser skillCommandParser,
            PersistentMemoryStore memoryStore
    ) {
        this(
                new InteractiveLineInput(inputReader),
                outputWriter,
                sessionClient,
                TraceClient.noop(),
                config,
                runtimeRunner,
                traceObserver,
                skillCommandParser,
                new UserMemoryCommandParser(),
                memoryStore
        );
    }

    InteractiveCliSession(
            InteractiveLineInput lineInput,
            PrintWriter outputWriter,
            SessionClient sessionClient,
            TraceClient traceClient,
            InteractiveCliConfig config,
            InteractiveRuntimeRunner runtimeRunner,
            ConsoleTraceObserver traceObserver,
            UserSkillCommandParser skillCommandParser,
            PersistentMemoryStore memoryStore
    ) {
        this(
                lineInput,
                outputWriter,
                sessionClient,
                traceClient,
                config,
                runtimeRunner,
                traceObserver,
                skillCommandParser,
                new UserMemoryCommandParser(),
                memoryStore
        );
    }

    InteractiveCliSession(
            InteractiveLineInput lineInput,
            PrintWriter outputWriter,
            SessionClient sessionClient,
            TraceClient traceClient,
            InteractiveCliConfig config,
            InteractiveRuntimeRunner runtimeRunner,
            ConsoleTraceObserver traceObserver,
            UserSkillCommandParser skillCommandParser,
            UserMemoryCommandParser memoryCommandParser,
            PersistentMemoryStore memoryStore
    ) {
        this.lineInput = Objects.requireNonNull(lineInput, "lineInput must not be null.");
        this.outputWriter = Objects.requireNonNull(outputWriter, "outputWriter must not be null.");
        this.sessionClient = Objects.requireNonNull(sessionClient, "sessionClient must not be null.");
        this.traceClient = Objects.requireNonNull(traceClient, "traceClient must not be null.");
        this.config = Objects.requireNonNull(config, "config must not be null.");
        this.runtimeRunner = Objects.requireNonNull(runtimeRunner, "runtimeRunner must not be null.");
        this.traceObserver = Objects.requireNonNull(traceObserver, "traceObserver must not be null.");
        this.skillCommandParser = Objects.requireNonNull(skillCommandParser, "skillCommandParser must not be null.");
        this.memoryCommandParser = Objects.requireNonNull(memoryCommandParser, "memoryCommandParser must not be null.");
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore must not be null.");
    }

    public void start() {
        initializeFreshSession();
        traceObserver.printHelp();

        while (true) {
            String input = readNextInput();
            if (input == null) {
                return;
            }

            String normalizedInput = input.strip();
            if (normalizedInput.isEmpty()) {
                continue;
            }
            if (EXIT_COMMAND.equals(normalizedInput)) {
                return;
            }
            if (HELP_COMMAND.equals(normalizedInput)) {
                traceObserver.printHelp();
                continue;
            }
            if (RESET_COMMAND.equals(normalizedInput)) {
                resetSession();
                continue;
            }
            if (isModeCommand(normalizedInput, PLAN_COMMAND)) {
                handleModeCommand(InteractionMode.PLAN, normalizedInput, PLAN_COMMAND);
                continue;
            }
            if (isModeCommand(normalizedInput, EXECUTE_COMMAND)) {
                handleModeCommand(InteractionMode.EXECUTE, normalizedInput, EXECUTE_COMMAND);
                continue;
            }

            runSingleInput(normalizedInput);
        }
    }

    private void initializeFreshSession() {
        SessionSummary nextSession = sessionClient.createSession(new CreateSessionRequest(config.workspaceId(), "cli"));
        List<ConversationMessage> nextHistory = runtimeRunner.createInitialHistory(nextSession);

        this.currentSession = nextSession;
        this.history = nextHistory;
        traceObserver.onSessionCreated(nextSession);
    }

    private void resetSession() {
        try {
            // 先完整创建新 session 与新历史，
            // 只有两者都成功后才替换当前状态，避免把旧会话覆盖成半初始化状态。
            SessionSummary nextSession = sessionClient.createSession(new CreateSessionRequest(config.workspaceId(), "cli"));
            List<ConversationMessage> nextHistory = runtimeRunner.createInitialHistory(nextSession);
            this.currentSession = nextSession;
            this.history = nextHistory;
            this.interactionMode = InteractionMode.EXECUTE;
            traceObserver.onSessionCreated(nextSession);
        } catch (RuntimeException exception) {
            traceObserver.onError(exception.getMessage());
        }
    }

    private boolean isModeCommand(String input, String command) {
        // 只接受精确命令或“命令 + 空格 + 任务”，不做自然语言自动切换。
        return input.equals(command) || input.startsWith(command + " ");
    }

    private void handleModeCommand(InteractionMode nextMode, String input, String command) {
        this.interactionMode = nextMode;
        traceObserver.onInteractionModeChanged(nextMode);

        String remainingPrompt = input.substring(command.length()).strip();
        if (!remainingPrompt.isEmpty()) {
            runSingleInput(remainingPrompt);
        }
    }

    private void runSingleInput(String input) {
        traceObserver.onUserPrompt(input);

        try {
            UserMemoryCommand memoryCommand = memoryCommandParser.parse(input).orElse(null);
            if (memoryCommand != null) {
                runMemoryCommand(memoryCommand);
                return;
            }
            UserSkillCommand skillCommand = skillCommandParser.parse(input).orElse(null);
            if (skillCommand != null) {
                runSkillCommand(skillCommand);
                return;
            }

            InteractiveTurnResult result = runtimeRunner.runTurn(
                    currentSession,
                    history,
                    input,
                    traceObserver,
                    createTracePublisher(currentSession),
                    interactionMode
            );
            this.history = result.messages();
            traceObserver.onAssistantAnswer(result.finalAnswer());
        } catch (RuntimeException exception) {
            traceObserver.onError(exception.getMessage());
        }
    }

    private void runMemoryCommand(UserMemoryCommand memoryCommand) {
        switch (memoryCommand.type()) {
            case REMEMBER -> runRememberCommand();
            case LIST -> traceObserver.onAssistantAnswer(renderMemoryIndex());
            case SHOW -> traceObserver.onAssistantAnswer(renderSingleMemory(memoryCommand.id().orElseThrow()));
            case FORGET -> forgetMemory(memoryCommand.id().orElseThrow());
        }
    }

    private void runRememberCommand() {
        try {
            MemoryType type = MemoryType.fromStorageValue(readMemoryField("memory type [user/project/feedback/reference]: "));
            String title = readMemoryField("memory title: ");
            String summary = readMemoryField("memory summary: ");
            String body = readMemoryField("memory body: ");
            String tagsLine = readMemoryField("memory tags (comma separated, optional): ");
            MemoryRecord record = new MemoryRecord(
                    generateMemoryId(title),
                    type,
                    title,
                    summary,
                    body,
                    Instant.now(),
                    Instant.now(),
                    parseTags(tagsLine)
            );
            memoryStore.save(record);
            traceObserver.onAssistantAnswer("已保存记忆 " + record.id());
        } catch (IOException exception) {
            throw new IllegalStateException("读取记忆录入输入失败。", exception);
        }
    }

    private void forgetMemory(String id) {
        MemoryRecord existingRecord = memoryStore.get(id).orElseThrow(
                () -> new IllegalArgumentException("记忆不存在: " + id)
        );
        memoryStore.delete(existingRecord.id());
        traceObserver.onAssistantAnswer("已删除记忆 " + existingRecord.id());
    }

    private String renderMemoryIndex() {
        List<com.repopilot.core.memory.MemoryIndexEntry> entries = memoryStore.list();
        if (entries.isEmpty()) {
            return "当前没有持久记忆。";
        }
        StringBuilder builder = new StringBuilder("当前持久记忆：");
        for (com.repopilot.core.memory.MemoryIndexEntry entry : entries) {
            builder.append(System.lineSeparator())
                    .append("- ")
                    .append(entry.id())
                    .append(" [")
                    .append(entry.type().storageValue())
                    .append("] ")
                    .append(entry.title())
                    .append(" :: ")
                    .append(entry.summary());
        }
        return builder.toString();
    }

    private String renderSingleMemory(String id) {
        MemoryRecord record = memoryStore.get(id).orElseThrow(
                () -> new IllegalArgumentException("记忆不存在: " + id)
        );
        return """
                id: %s
                type: %s
                title: %s
                summary: %s

                %s
                """.formatted(
                record.id(),
                record.type().storageValue(),
                record.title(),
                record.summary(),
                record.body()
        ).strip();
    }

    private String readMemoryField(String prompt) throws IOException {
        outputWriter.print(prompt);
        outputWriter.flush();
        String value = lineInput.readLine();
        if (value == null) {
            throw new IllegalStateException("记忆录入被中断。");
        }
        return value.strip();
    }

    private List<String> parseTags(String tagsLine) {
        if (tagsLine == null || tagsLine.isBlank()) {
            return List.of();
        }
        return List.of(tagsLine.split(",")).stream()
                .map(String::strip)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }

    private String generateMemoryId(String title) {
        // 首版显式只接受从标题里提取出的 ASCII token。
        // 这里按非字母数字切分，
        // 再把有效 token 用 `-` 连接成稳定 id，
        // 如果标题里完全没有可用 token，就直接暴露错误而不是偷偷生成不可读 id。
        String generatedId = List.of(title.split("[^A-Za-z0-9]+")).stream()
                .map(String::strip)
                .filter(token -> !token.isEmpty())
                .map(String::toLowerCase)
                .distinct()
                .reduce((left, right) -> left + "-" + right)
                .orElse("");
        if (generatedId.isEmpty()) {
            throw new IllegalArgumentException("无法从标题生成稳定 id，请在标题中包含英文字母或数字。");
        }
        return generatedId;
    }

    private void runSkillCommand(UserSkillCommand skillCommand) {
        InteractiveTurnResult activationResult = runtimeRunner.activateSkill(
                currentSession,
                history,
                skillCommand.skillName()
        );
        this.history = activationResult.messages();

        if (!skillCommand.hasRemainingPrompt()) {
            traceObserver.onAssistantAnswer(activationResult.finalAnswer());
            return;
        }

        InteractiveTurnResult turnResult = runtimeRunner.runTurn(
                currentSession,
                history,
                skillCommand.remainingPrompt(),
                traceObserver,
                createTracePublisher(currentSession),
                interactionMode
        );
        this.history = turnResult.messages();
        traceObserver.onAssistantAnswer(turnResult.finalAnswer());
    }

    private TracePublisher createTracePublisher(SessionSummary sessionSummary) {
        Objects.requireNonNull(sessionSummary, "sessionSummary must not be null.");

        return event -> {
            // 交互式 CLI 的 console trace 和 server trace 共用同一批 AgentLoop 事件，
            // 这里只负责把 core 事件映射成控制面追加请求，不改变运行时决策。
            AppendTraceEventRequest request = new AppendTraceEventRequest(
                    event.type(),
                    "cli",
                    event.summary(),
                    event.occurredAt(),
                    event.metadata()
            );
            traceClient.appendTraceEvent(sessionSummary.sessionId(), request);
        };
    }

    private String readNextInput() {
        try {
            outputWriter.print("repopilot >> ");
            outputWriter.flush();
            // 统一通过共享输入协调器读取，
            // 这样审批阶段回推的那一行普通输入才能在这里被继续消费。
            return lineInput.readLine();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read interactive input.", exception);
        }
    }

    @FunctionalInterface
    interface SessionClient {

        SessionSummary createSession(CreateSessionRequest request);
    }

    @FunctionalInterface
    interface TraceClient {

        TraceEventRecord appendTraceEvent(String sessionId, AppendTraceEventRequest request);

        static TraceClient noop() {
            return (sessionId, request) -> new TraceEventRecord(
                    "trace-noop",
                    sessionId,
                    request.type(),
                    request.source(),
                    request.summary(),
                    request.occurredAt(),
                    request.metadata()
            );
        }
    }
}
