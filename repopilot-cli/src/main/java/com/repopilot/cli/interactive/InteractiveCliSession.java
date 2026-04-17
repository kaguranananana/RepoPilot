package com.repopilot.cli.interactive;

import com.repopilot.cli.runtime.CliRuntimeBootstrap;
import com.repopilot.cli.runtime.LocalEnvironmentMapLoader;
import com.repopilot.cli.session.DefaultHttpSessionApiClient;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionSummary;
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

    private final BufferedReader inputReader;
    private final PrintWriter outputWriter;
    private final SessionClient sessionClient;
    private final InteractiveCliConfig config;
    private final InteractiveRuntimeRunner runtimeRunner;
    private final ConsoleTraceObserver traceObserver;

    private SessionSummary currentSession;
    private List<ConversationMessage> history = List.of();

    public static InteractiveCliSession createDefault() {
        Path workspaceRoot = Path.of("").toAbsolutePath().normalize();
        Map<String, String> environment = LocalEnvironmentMapLoader.load(workspaceRoot, System.getenv());
        InteractiveCliConfig config = InteractiveCliConfig.fromEnvironment(environment);
        PrintWriter outputWriter = new PrintWriter(System.out, true, StandardCharsets.UTF_8);

        // 默认会话把标准输入输出直接接到终端，
        // 这样用户双击启动 main 后就能马上进入 REPL。
        return new InteractiveCliSession(
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                outputWriter,
                new DefaultHttpSessionApiClient(config.serverBaseUrl())::createSession,
                config,
                new DefaultInteractiveRuntimeRunner(
                        Clock.systemUTC(),
                        new CliRuntimeBootstrap.EnvironmentBackedModelAdapterFactory(environment),
                        8
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
        this.inputReader = Objects.requireNonNull(inputReader, "inputReader must not be null.");
        this.outputWriter = Objects.requireNonNull(outputWriter, "outputWriter must not be null.");
        this.sessionClient = Objects.requireNonNull(sessionClient, "sessionClient must not be null.");
        this.config = Objects.requireNonNull(config, "config must not be null.");
        this.runtimeRunner = Objects.requireNonNull(runtimeRunner, "runtimeRunner must not be null.");
        this.traceObserver = Objects.requireNonNull(traceObserver, "traceObserver must not be null.");
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

            runSingleTurn(normalizedInput);
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
            traceObserver.onSessionCreated(nextSession);
        } catch (RuntimeException exception) {
            traceObserver.onError(exception.getMessage());
        }
    }

    private void runSingleTurn(String prompt) {
        traceObserver.onUserPrompt(prompt);

        try {
            InteractiveTurnResult result = runtimeRunner.runTurn(
                    currentSession,
                    history,
                    prompt,
                    traceObserver
            );
            this.history = result.messages();
            traceObserver.onAssistantAnswer(result.finalAnswer());
        } catch (RuntimeException exception) {
            traceObserver.onError(exception.getMessage());
        }
    }

    private String readNextInput() {
        try {
            outputWriter.print("repopilot >> ");
            outputWriter.flush();
            return inputReader.readLine();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read interactive input.", exception);
        }
    }

    @FunctionalInterface
    interface SessionClient {

        SessionSummary createSession(CreateSessionRequest request);
    }
}
