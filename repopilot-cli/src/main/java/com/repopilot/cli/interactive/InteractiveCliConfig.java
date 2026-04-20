package com.repopilot.cli.interactive;

import com.repopilot.cli.runtime.CliRuntimeBootstrap;
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
        TraceLevel traceLevel,
        int maxSteps
) {

    public InteractiveCliConfig(String serverBaseUrl, String workspaceId) {
        this(serverBaseUrl, workspaceId, TraceLevel.SUMMARY, CliRuntimeBootstrap.DEFAULT_MAX_STEPS);
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
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("REPOPILOT_MAX_STEPS must be greater than zero.");
        }
    }

    public static InteractiveCliConfig load(Path workspaceRoot, Map<String, String> processEnvironment) {
        return fromEnvironment(LocalEnvironmentMapLoader.load(workspaceRoot, processEnvironment));
    }

    public static InteractiveCliConfig fromEnvironment(Map<String, String> environment) {
        return new InteractiveCliConfig(
                environment.get("REPOPILOT_SERVER_BASE_URL"),
                environment.get("REPOPILOT_WORKSPACE_ID"),
                TraceLevel.fromEnvironmentValue(environment.get("REPOPILOT_TRACE_LEVEL")),
                parseMaxSteps(environment.get("REPOPILOT_MAX_STEPS"))
        );
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    private static int parseMaxSteps(String value) {
        if (value == null || value.isBlank()) {
            return CliRuntimeBootstrap.DEFAULT_MAX_STEPS;
        }

        try {
            int parsedValue = Integer.parseInt(value.strip());
            if (parsedValue <= 0) {
                throw new IllegalArgumentException("REPOPILOT_MAX_STEPS must be greater than zero.");
            }
            return parsedValue;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("REPOPILOT_MAX_STEPS must be an integer.", exception);
        }
    }
}
