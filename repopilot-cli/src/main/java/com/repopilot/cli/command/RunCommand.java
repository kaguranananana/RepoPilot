package com.repopilot.cli.command;

import com.repopilot.cli.runtime.CliRuntimeBootstrap;
import com.repopilot.cli.session.DefaultHttpSessionApiClient;
import com.repopilot.cli.session.SessionApiClient;
import com.repopilot.protocol.session.CreateSessionRequest;
import com.repopilot.protocol.session.SessionSummary;
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

    @Spec
    private CommandSpec spec;

    private final SessionApiClientFactory sessionApiClientFactory;
    private final CliRuntimeBootstrap cliRuntimeBootstrap;

    public RunCommand() {
        this(DefaultHttpSessionApiClient::new, CliRuntimeBootstrap.createDefault());
    }

    RunCommand(SessionApiClientFactory sessionApiClientFactory, CliRuntimeBootstrap cliRuntimeBootstrap) {
        this.sessionApiClientFactory =
                Objects.requireNonNull(sessionApiClientFactory, "sessionApiClientFactory must not be null.");
        this.cliRuntimeBootstrap = Objects.requireNonNull(cliRuntimeBootstrap, "cliRuntimeBootstrap must not be null.");
    }

    @Override
    public Integer call() {
        // 先显式校验 CLI 输入，
        // 避免把空字符串一路带到 HTTP 层或 runtime 层后再出现语义不清的错误。
        String safeWorkspaceId = requireNonBlank(workspaceId, "workspaceId must not be blank.");
        String safeServerBaseUrl = requireNonBlank(serverBaseUrl, "serverBaseUrl must not be blank.");
        String safePrompt = requireNonBlank(prompt, "prompt must not be blank.");

        // 第一步：创建 session，
        // 让后续本地 runtime 的一次运行从一开始就具备控制面可追踪的会话身份。
        SessionApiClient sessionApiClient = sessionApiClientFactory.create(safeServerBaseUrl);
        SessionSummary sessionSummary = sessionApiClient.createSession(new CreateSessionRequest(safeWorkspaceId, "cli"));

        // 第二步：把 prompt 和 session 上下文交给本地 runtime，
        // 这里故意保持主链路直通，不额外插入降级或兜底分支。
        String finalAnswer = cliRuntimeBootstrap.run(sessionSummary, safePrompt);

        // 第三步：把 runtime 最终回答直接打印到 CLI 输出，
        // 这样命令行入口就形成了最小可验证闭环。
        spec.commandLine().getOut().println(finalAnswer);
        return 0;
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    @FunctionalInterface
    interface SessionApiClientFactory {

        SessionApiClient create(String serverBaseUrl);
    }
}
