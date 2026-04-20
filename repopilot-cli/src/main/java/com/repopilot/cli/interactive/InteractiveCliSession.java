package com.repopilot.cli.interactive;

import com.repopilot.cli.runtime.CliRuntimeBootstrap;
import com.repopilot.cli.runtime.LocalEnvironmentMapLoader;
import com.repopilot.cli.session.DefaultHttpTraceApiClient;
import com.repopilot.cli.session.DefaultHttpSessionApiClient;
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
                new ConsoleTraceObserver(outputWriter, config.traceLevel())
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
                new UserSkillCommandParser()
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
                new UserSkillCommandParser()
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
                new UserSkillCommandParser()
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
        this.lineInput = Objects.requireNonNull(lineInput, "lineInput must not be null.");
        this.outputWriter = Objects.requireNonNull(outputWriter, "outputWriter must not be null.");
        this.sessionClient = Objects.requireNonNull(sessionClient, "sessionClient must not be null.");
        this.traceClient = Objects.requireNonNull(traceClient, "traceClient must not be null.");
        this.config = Objects.requireNonNull(config, "config must not be null.");
        this.runtimeRunner = Objects.requireNonNull(runtimeRunner, "runtimeRunner must not be null.");
        this.traceObserver = Objects.requireNonNull(traceObserver, "traceObserver must not be null.");
        this.skillCommandParser = Objects.requireNonNull(skillCommandParser, "skillCommandParser must not be null.");
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
