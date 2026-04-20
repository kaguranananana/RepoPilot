package com.repopilot.cli.command;

import com.repopilot.cli.runtime.CliRuntimeBootstrap;
import com.repopilot.cli.session.DefaultHttpTraceApiClient;
import com.repopilot.cli.session.DefaultHttpSessionApiClient;
import com.repopilot.cli.session.SessionApiClient;
import com.repopilot.cli.session.TraceApiClient;
import com.repopilot.core.trace.TracePublisher;
import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionSummary;
import com.repopilot.protocol.trace.AppendTraceEventRequest;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * `run` 子命令负责打通 CLI 的最小执行主链路。
 * 当前版本只做三件事：
 * 1. 解析本次运行所需的最小参数。
 * 2. 先向 server 创建一个可审计的 session。
 * 3. 再把用户 prompt 送进本地 core runtime。
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "创建 session 并启动一次最小本地 runtime。"
)
public class RunCommand implements Callable<Integer> {

    @Option(
            names = "--workspace-id",
            required = true,
            description = "本次运行绑定的工作区标识。"
    )
    private String workspaceId;

    @Option(
            names = "--server-base-url",
            required = true,
            description = "控制面 server 的基础地址。"
    )
    private String serverBaseUrl;

    @Option(
            names = "--prompt",
            required = true,
            description = "本轮要送入 runtime 的用户任务。"
    )
    private String prompt;

    @Option(
            names = "--max-steps",
            defaultValue = "12",
            description = "本轮 AgentLoop 的最大模型步数。"
    )
    private int maxSteps;

    @Spec
    private CommandSpec spec;

    private final SessionApiClientFactory sessionApiClientFactory;
    private final TraceApiClientFactory traceApiClientFactory;
    private final CliRuntimeBootstrap cliRuntimeBootstrap;

    public RunCommand() {
        this(DefaultHttpSessionApiClient::new, DefaultHttpTraceApiClient::new, CliRuntimeBootstrap.createDefault());
    }

    RunCommand(
            SessionApiClientFactory sessionApiClientFactory,
            TraceApiClientFactory traceApiClientFactory,
            CliRuntimeBootstrap cliRuntimeBootstrap
    ) {
        this.sessionApiClientFactory =
                Objects.requireNonNull(sessionApiClientFactory, "sessionApiClientFactory must not be null.");
        this.traceApiClientFactory =
                Objects.requireNonNull(traceApiClientFactory, "traceApiClientFactory must not be null.");
        this.cliRuntimeBootstrap = Objects.requireNonNull(cliRuntimeBootstrap, "cliRuntimeBootstrap must not be null.");
    }

    @Override
    public Integer call() {
        // 先显式校验 CLI 输入，
        // 避免把空字符串一路带到 HTTP 层或 runtime 层后再出现语义不清的错误。
        String safeWorkspaceId = requireNonBlank(workspaceId, "workspaceId must not be blank.");
        String safeServerBaseUrl = requireNonBlank(serverBaseUrl, "serverBaseUrl must not be blank.");
        String safePrompt = requireNonBlank(prompt, "prompt must not be blank.");
        int safeMaxSteps = requirePositive(maxSteps, "maxSteps must be greater than zero.");

        // 第一步：创建 session，
        // 让后续本地 runtime 的一次运行从一开始就具备控制面可追踪的会话身份。
        SessionApiClient sessionApiClient = sessionApiClientFactory.create(safeServerBaseUrl);
        SessionSummary sessionSummary = sessionApiClient.createSession(new CreateSessionRequest(safeWorkspaceId, "cli"));
        // 第二步：基于当前 session 组装 trace 发布器，
        // 让 core 产出的结构化事件可以直接同步到控制面。
        TraceApiClient traceApiClient = traceApiClientFactory.create(safeServerBaseUrl);
        TracePublisher tracePublisher = createTracePublisher(sessionSummary, traceApiClient);

        // 第三步：把 prompt、session 和 trace 发布器一起交给本地 runtime，
        // 这里故意保持主链路直通，不额外插入降级或兜底分支。
        String finalAnswer = cliRuntimeBootstrap.run(sessionSummary, safePrompt, tracePublisher, safeMaxSteps);

        // 第四步：把 runtime 最终回答直接打印到 CLI 输出，
        // 这样命令行入口就形成了最小可验证闭环。
        spec.commandLine().getOut().println(finalAnswer);
        return 0;
    }

    private TracePublisher createTracePublisher(SessionSummary sessionSummary, TraceApiClient traceApiClient) {
        Objects.requireNonNull(sessionSummary, "sessionSummary must not be null.");
        Objects.requireNonNull(traceApiClient, "traceApiClient must not be null.");

        return event -> {
            // 这里把 core 侧最小 trace 事件直接映射成控制面协议请求，
            // 保持“事件产生”和“事件传输”两层职责清晰分离。
            AppendTraceEventRequest request = new AppendTraceEventRequest(
                    event.type(),
                    "cli",
                    event.summary(),
                    event.occurredAt(),
                    event.metadata()
            );
            // 当前所有 trace 都严格绑定到同一个 sessionId，
            // 这样 server 端就能按单次运行完整回放整条链路。
            traceApiClient.appendTraceEvent(sessionSummary.sessionId(), request);
        };
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private int requirePositive(int value, String message) {
        if (value <= 0) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    @FunctionalInterface
    interface SessionApiClientFactory {

        SessionApiClient create(String serverBaseUrl);
    }

    @FunctionalInterface
    interface TraceApiClientFactory {

        TraceApiClient create(String serverBaseUrl);
    }
}
