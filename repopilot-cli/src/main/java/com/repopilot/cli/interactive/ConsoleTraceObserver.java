package com.repopilot.cli.interactive;

import com.repopilot.core.agent.AgentLoopObserver;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.protocol.session.SessionSummary;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端链路摘要观察器。
 * 它只打印固定格式的关键摘要，
 * 不直接透传完整 request / response / tool output。
 */
public final class ConsoleTraceObserver implements AgentLoopObserver {

    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile("exitCode:\\s*(\\d+)");
    private static final int MESSAGE_PREVIEW_LIMIT = 120;
    private final PrintWriter outputWriter;
    private final TraceLevel traceLevel;

    public ConsoleTraceObserver(PrintWriter outputWriter) {
        this(outputWriter, TraceLevel.SUMMARY);
    }

    public ConsoleTraceObserver(PrintWriter outputWriter, TraceLevel traceLevel) {
        this.outputWriter = Objects.requireNonNull(outputWriter, "outputWriter must not be null.");
        this.traceLevel = Objects.requireNonNull(traceLevel, "traceLevel must not be null.");
    }

    public void onSessionCreated(SessionSummary sessionSummary) {
        outputWriter.printf(
                "[session] created %s workspace=%s%n",
                sessionSummary.sessionId(),
                sessionSummary.workspaceId()
        );
        outputWriter.flush();
    }

    public void onUserPrompt(String prompt) {
        outputWriter.printf("[user] %s%n", prompt);
        outputWriter.flush();
    }

    public void onAssistantAnswer(String answer) {
        outputWriter.printf("[assistant] %s%n", answer);
        outputWriter.flush();
    }

    public void onError(String message) {
        outputWriter.printf("[error] %s%n", message);
        outputWriter.flush();
    }

    public void printHelp() {
        outputWriter.println("/help  查看交互命令说明");
        outputWriter.println("/reset 重置当前会话并重新创建 session");
        outputWriter.println("/exit  退出交互模式");
        outputWriter.flush();
    }

    @Override
    public void onStepStarted(int stepNumber, List<ConversationMessage> messages) {
        if (!traceLevel.isVerbose()) {
            return;
        }

        outputWriter.printf("[step %d] messages (%d total)%n", stepNumber, messages.size());
        for (int index = 0; index < messages.size(); index++) {
            outputWriter.print(renderMessageBlock(index + 1, messages.get(index)));
        }
        outputWriter.flush();
    }

    @Override
    public void onModelResponse(int stepNumber, ModelResponse response) {
        if (response instanceof ToolCallModelResponse toolCallModelResponse) {
            outputWriter.printf("[step %d] model -> tool_calls(%d)%n", stepNumber, toolCallModelResponse.toolCalls().size());
            if (traceLevel.isVerbose()) {
                for (int index = 0; index < toolCallModelResponse.toolCalls().size(); index++) {
                    outputWriter.print(renderToolCallBlock(index + 1, toolCallModelResponse.toolCalls().get(index)));
                }
            }
        } else if (response instanceof FinalModelResponse) {
            outputWriter.printf("[step %d] model -> final%n", stepNumber);
        }
        outputWriter.flush();
    }

    @Override
    public void onToolExecutionStarted(int stepNumber, ToolCall toolCall) {
        outputWriter.printf("[tool] %s %s%n", toolCall.toolName(), renderArgumentSummary(toolCall));
        outputWriter.flush();
    }

    @Override
    public void onToolExecutionFinished(int stepNumber, ToolCall toolCall, ToolExecutionResult executionResult) {
        outputWriter.printf(
                "[tool:%s] %s %s%n",
                renderStatusLabel(executionResult),
                toolCall.toolName(),
                summarizeToolResult(toolCall.toolName(), executionResult)
        );
        outputWriter.flush();
    }

    @Override
    public void onToolMessageAdded(int stepNumber, ToolCall toolCall, ConversationMessage toolMessage) {
        if (!traceLevel.isVerbose()) {
            return;
        }

        outputWriter.print(renderToolMessageBlock(toolMessage));
        outputWriter.flush();
    }

    private String renderArgumentSummary(ToolCall toolCall) {
        Map<String, String> arguments = toolCall.arguments();
        return switch (toolCall.toolName()) {
            case "read_file" -> "path=" + arguments.getOrDefault("path", "");
            case "grep_files" -> renderGrepArgumentSummary(arguments);
            case "run_command" -> "command=" + arguments.getOrDefault("command", "");
            default -> arguments.toString();
        };
    }

    private String renderGrepArgumentSummary(Map<String, String> arguments) {
        String pattern = arguments.getOrDefault("pattern", "");
        String path = arguments.get("path");
        if (path == null || path.isBlank()) {
            return "pattern=" + pattern;
        }
        return "pattern=" + pattern + " path=" + path.strip();
    }

    private String renderStatusLabel(ToolExecutionResult executionResult) {
        return switch (executionResult.status()) {
            case SUCCESS -> "success";
            case RECOVERABLE_ERROR -> "error";
            case FATAL_ERROR -> "fatal";
        };
    }

    private String summarizeToolResult(String toolName, ToolExecutionResult executionResult) {
        String output = executionResult.output();
        if (!executionResult.isSuccess()) {
            return switch (toolName) {
                case "run_command" -> summarizeRunCommand(output);
                default -> output;
            };
        }

        return switch (toolName) {
            case "read_file" -> summarizeReadFile(output);
            case "grep_files" -> summarizeGrepFiles(output);
            case "run_command" -> summarizeRunCommand(output);
            default -> output.length() + " chars";
        };
    }

    private String summarizeReadFile(String output) {
        int lineCount = output.isEmpty() ? 0 : output.split("\\R", -1).length;

        // 如果最后一行只是由末尾换行带出来的空串，
        // 这里主动扣掉它，让摘要展示更接近用户看到的真实“文本行数”。
        if (output.endsWith("\n") || output.endsWith("\r")) {
            lineCount -= 1;
        }
        return Math.max(lineCount, 0) + " 行";
    }

    private String summarizeGrepFiles(String output) {
        if (output.startsWith("未找到匹配内容")) {
            return "0 命中";
        }
        int matchCount = output.isBlank() ? 0 : output.split("\\R").length;
        return matchCount + " 命中";
    }

    private String summarizeRunCommand(String output) {
        Matcher matcher = EXIT_CODE_PATTERN.matcher(output);
        if (matcher.find()) {
            return "exitCode=" + matcher.group(1);
        }
        return "exitCode=unknown";
    }

    private String renderMessageBlock(int messageIndex, ConversationMessage message) {
        StringBuilder builder = new StringBuilder();

        // assistant 的 tool_calls 消息和普通 assistant 文本消息语义不同，
        // 这里先单独分支，避免把“发起工具调用”误显示成空内容文本。
        if (message.role() == MessageRole.ASSISTANT && !message.toolCalls().isEmpty()) {
            builder.append("  [message ").append(messageIndex).append("] ASSISTANT tool_calls").append(System.lineSeparator());
            builder.append("    toolCalls:").append(System.lineSeparator());
            for (ToolCall toolCall : message.toolCalls()) {
                builder.append("      ")
                        .append(toolCall.id())
                        .append(" -> ")
                        .append(toolCall.toolName())
                        .append(System.lineSeparator());
            }
            return builder.toString();
        }

        if (message.role() == MessageRole.TOOL) {
            builder.append("  [message ").append(messageIndex).append("] TOOL").append(System.lineSeparator());
            builder.append("    toolCallId: ").append(message.toolCallId()).append(System.lineSeparator());
            builder.append(renderContentPreviewBlock("    ", message.content()));
            return builder.toString();
        }

        builder.append("  [message ").append(messageIndex).append("] ").append(message.role()).append(System.lineSeparator());
        builder.append(renderContentPreviewBlock("    ", message.content()));
        return builder.toString();
    }

    private String renderToolCallBlock(int toolCallIndex, ToolCall toolCall) {
        StringBuilder builder = new StringBuilder();
        builder.append("  [tool_call ").append(toolCallIndex).append("]").append(System.lineSeparator());
        builder.append("    id: ").append(toolCall.id()).append(System.lineSeparator());
        builder.append("    tool: ").append(toolCall.toolName()).append(System.lineSeparator());
        builder.append("    arguments:").append(System.lineSeparator());

        if (toolCall.arguments().isEmpty()) {
            builder.append("      (none)").append(System.lineSeparator());
            return builder.toString();
        }

        toolCall.arguments().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> builder.append("      ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(entry.getValue())
                        .append(System.lineSeparator()));
        return builder.toString();
    }

    private String renderToolMessageBlock(ConversationMessage toolMessage) {
        StringBuilder builder = new StringBuilder();
        builder.append("  [tool_message]").append(System.lineSeparator());
        builder.append("    toolCallId: ").append(toolMessage.toolCallId()).append(System.lineSeparator());
        builder.append(renderContentPreviewBlock("    ", toolMessage.content()));
        return builder.toString();
    }

    private String renderContentPreviewBlock(String indent, String content) {
        StringBuilder builder = new StringBuilder();
        builder.append(indent).append("content preview:").append(System.lineSeparator());
        for (String line : renderPreviewLines(content)) {
            builder.append(indent).append("  ").append(line).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private List<String> renderPreviewLines(String content) {
        String normalized = content == null ? "" : content.strip();
        if (normalized.isEmpty()) {
            return List.of("(empty)");
        }

        String truncated = truncateToPreviewLimit(normalized);
        return Pattern.compile("\\R")
                .splitAsStream(truncated)
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private String truncateToPreviewLimit(String content) {
        String collapsed = content.replace("\t", "    ");
        if (collapsed.length() <= MESSAGE_PREVIEW_LIMIT) {
            return collapsed;
        }
        return collapsed.substring(0, MESSAGE_PREVIEW_LIMIT - 3) + "...";
    }

}
