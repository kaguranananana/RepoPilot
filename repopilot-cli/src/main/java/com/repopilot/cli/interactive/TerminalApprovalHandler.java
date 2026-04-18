package com.repopilot.cli.interactive;

import com.repopilot.core.approval.ToolApprovalHandler;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.Objects;

/**
 * 终端交互式审批处理器。
 * 当治理层要求审批时，它会在当前 REPL 终端里同步打印审查信息，
 * 然后阻塞等待用户输入 `y/n`。
 */
public final class TerminalApprovalHandler implements ToolApprovalHandler {

    private final InteractiveLineInput lineInput;
    private final PrintWriter outputWriter;

    public TerminalApprovalHandler(InteractiveLineInput lineInput, PrintWriter outputWriter) {
        this.lineInput = Objects.requireNonNull(lineInput, "lineInput must not be null.");
        this.outputWriter = Objects.requireNonNull(outputWriter, "outputWriter must not be null.");
    }

    @Override
    public ApprovalDecision requestApproval(ApprovalRequest request) {
        outputWriter.printf("[approval] tool=%s%n", request.toolDefinition().name());
        outputWriter.printf("[approval] reason=%s%n", request.permissionReason());
        if (!request.reviewSummary().isBlank()) {
            outputWriter.println(request.reviewSummary());
        }

        try {
            while (true) {
                // 一旦进入审批阶段，当前回合就必须阻塞在这里，
                // 直到用户给出明确的 y/yes/n/no。
                // 这就是交互层的“审批挂起态”。
                outputWriter.print("Approve? [y/N]: ");
                outputWriter.flush();

                // 审批与普通 REPL 输入共用同一个终端源，
                // 所以这里必须继续从共享输入协调器读取，
                // 保证审批期间不会被新的普通用户轮次抢走输入。
                String input = lineInput.readLine();
                if (input == null) {
                    return ApprovalDecision.deny("审批输入流已结束。");
                }

                String normalizedInput = input.strip().toLowerCase(Locale.ROOT);
                if ("y".equals(normalizedInput) || "yes".equals(normalizedInput)) {
                    return ApprovalDecision.approve("终端用户已批准。");
                }
                if ("n".equals(normalizedInput) || "no".equals(normalizedInput) || normalizedInput.isEmpty()) {
                    return ApprovalDecision.deny("终端用户拒绝审批。");
                }

                // 审批挂起态下，任何非 y/n 的文本都不再被回推成新的用户请求，
                // 而是明确提示“当前仍在等审批回答”，然后继续等待。
                // 这样用户输入的裸文本不会意外触发新的模型轮次。
                outputWriter.println("[approval] 当前正在等待审批，请输入 y/yes 或 n/no。");
                outputWriter.flush();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read approval input.", exception);
        }
    }
}
