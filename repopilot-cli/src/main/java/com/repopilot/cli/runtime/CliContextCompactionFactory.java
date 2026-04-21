package com.repopilot.cli.runtime;

import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.context.ContextCompactor;
import com.repopilot.core.context.ContextInputTokenEstimator;
import com.repopilot.core.tool.ToolDefinition;
import java.util.List;
import java.util.Objects;

/**
 * CLI runtime 的上下文压缩装配入口。
 * 这里集中放置真实运行时的 token budget，避免单次运行和交互式运行各自硬编码。
 */
public final class CliContextCompactionFactory {

    private static final int MAX_HIGH_FIDELITY_MESSAGES = 10_000;
    private static final int RETAINED_HIGH_FIDELITY_MESSAGES = 8;
    private static final int MAX_RECENT_TOOL_RESULTS = 1;
    private static final int CONTEXT_WINDOW_TOKENS = 5_000;
    private static final int RESERVED_OUTPUT_TOKENS = 800;
    private static final int SAFETY_BUFFER_TOKENS = 700;
    private static final String TOKEN_ENCODING = "cl100k_base";

    private CliContextCompactionFactory() {
    }

    public static ContextCompactor createContextCompactor() {
        return new ContextCompactor(new ContextCompactionPolicy(
                MAX_HIGH_FIDELITY_MESSAGES,
                RETAINED_HIGH_FIDELITY_MESSAGES,
                MAX_RECENT_TOOL_RESULTS,
                CONTEXT_WINDOW_TOKENS,
                RESERVED_OUTPUT_TOKENS,
                SAFETY_BUFFER_TOKENS
        ));
    }

    public static ContextInputTokenEstimator createInputTokenEstimator(List<ToolDefinition> availableTools) {
        List<ToolDefinition> safeAvailableTools = List.copyOf(
                Objects.requireNonNull(availableTools, "availableTools must not be null.")
        );
        ModelInputTokenEstimator tokenEstimator = new JTokkitModelInputTokenEstimator(TOKEN_ENCODING);
        return messages -> tokenEstimator.estimateInputTokens(messages, safeAvailableTools);
    }
}
