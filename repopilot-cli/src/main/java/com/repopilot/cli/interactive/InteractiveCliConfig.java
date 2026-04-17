package com.repopilot.cli.interactive;

import com.repopilot.cli.runtime.LocalEnvironmentMapLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 交互式 CLI 启动配置。
 * 这部分配置只解决“如何连控制面、绑定哪个工作区”，
 * 不负责模型提供方与 API Key 的解析。
 */
public record InteractiveCliConfig(
        String serverBaseUrl,
        String workspaceId,
        TraceLevel traceLevel
) {

    public InteractiveCliConfig(String serverBaseUrl, String workspaceId) {
        this(serverBaseUrl, workspaceId, TraceLevel.SUMMARY);
    }

    public InteractiveCliConfig {
        serverBaseUrl = requireNonBlank(
                serverBaseUrl,
                "REPOPILOT_SERVER_BASE_URL must not be blank for interactive mode."
        );
        workspaceId = requireNonBlank(
                workspaceId,
                "REPOPILOT_WORKSPACE_ID must not be blank for interactive mode."
        );
        traceLevel = Objects.requireNonNull(traceLevel, "traceLevel must not be null.");
    }

    public static InteractiveCliConfig load(Path workspaceRoot, Map<String, String> processEnvironment) {
        return fromEnvironment(LocalEnvironmentMapLoader.load(workspaceRoot, processEnvironment));
    }

    public static InteractiveCliConfig fromEnvironment(Map<String, String> environment) {
        return new InteractiveCliConfig(
                environment.get("REPOPILOT_SERVER_BASE_URL"),
                environment.get("REPOPILOT_WORKSPACE_ID"),
                TraceLevel.fromEnvironmentValue(environment.get("REPOPILOT_TRACE_LEVEL"))
        );
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }
}
