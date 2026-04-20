package com.repopilot.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.approval.ToolApprovalHandler;
import com.repopilot.core.context.ContextCompactionPolicy;
import com.repopilot.core.context.ContextCompactor;
import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.permission.WorkspacePermissionPolicy;
import com.repopilot.core.review.DiffReviewService;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.tool.ToolRegistry;
import com.repopilot.core.tool.builtin.BuiltinToolRegistrar;
import com.repopilot.core.tool.governance.GovernedToolExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLoopCodingTaskSmokeTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldCompleteSearchReadPatchCommandValidationAndFinalAnswerPath() throws Exception {
        Path targetFile = workspaceRoot.resolve("src/Demo.txt");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "name=RepoPilot\nstatus=draft\n");
        ToolRegistry toolRegistry = new ToolRegistry();
        BuiltinToolRegistrar.registerAll(
                toolRegistry,
                workspaceRoot,
                SkillLoader.createDefault(workspaceRoot, workspaceRoot.resolve("home"))
        );
        GovernedToolExecutor governedToolExecutor = new GovernedToolExecutor(
                toolRegistry,
                new WorkspacePermissionPolicy(workspaceRoot),
                new DiffReviewService(workspaceRoot),
                request -> ToolApprovalHandler.ApprovalDecision.approve("测试批准")
        );
        ModelAdapter modelAdapter = new ScriptedCodingModelAdapter();

        AgentLoopResult result = new AgentLoop(
                governedToolExecutor,
                AgentLoopObserver.noop(),
                com.repopilot.core.trace.TracePublisher.noop(),
                new ContextCompactor(new ContextCompactionPolicy(32, 16, 4))
        ).run(new AgentLoopRequest(
                modelAdapter,
                List.of(new ConversationMessage(MessageRole.USER, "把 Demo 状态改成 ready 并验证")),
                8
        ));

        assertEquals("编码任务闭环完成", result.finalAnswer());
        assertEquals("name=RepoPilot\nstatus=ready\n", Files.readString(targetFile));
        assertTrue(result.messages().stream()
                .filter(message -> message.role() == MessageRole.TOOL)
                .anyMatch(message -> message.content().contains("[grep_files]")));
        assertTrue(result.messages().stream()
                .filter(message -> message.role() == MessageRole.TOOL)
                .anyMatch(message -> message.content().contains("[read_file]")));
        assertTrue(result.messages().stream()
                .filter(message -> message.role() == MessageRole.TOOL)
                .anyMatch(message -> message.content().contains("[apply_patch]")));
        assertTrue(result.messages().stream()
                .filter(message -> message.role() == MessageRole.TOOL)
                .anyMatch(message -> message.content().contains("exitCode: 0")));
    }

    private static final class ScriptedCodingModelAdapter implements ModelAdapter {

        private int cursor;

        @Override
        public ModelResponse next(List<ConversationMessage> messages) {
            cursor += 1;
            return switch (cursor) {
                case 1 -> new ToolCallModelResponse(List.of(new ToolCall(
                        "call-1",
                        "grep_files",
                        Map.of("pattern", "status=draft", "path", "src")
                )));
                case 2 -> new ToolCallModelResponse(List.of(new ToolCall(
                        "call-2",
                        "read_file",
                        Map.of("path", "src/Demo.txt")
                )));
                case 3 -> new ToolCallModelResponse(List.of(new ToolCall(
                        "call-3",
                        "apply_patch",
                        Map.of(
                                "path", "src/Demo.txt",
                                "patch", """
                                        @@
                                         name=RepoPilot
                                        -status=draft
                                        +status=ready
                                        """
                        )
                )));
                case 4 -> new ToolCallModelResponse(List.of(new ToolCall(
                        "call-4",
                        "run_command",
                        Map.of("command", "grep -n 'status=ready' src/Demo.txt")
                )));
                default -> new FinalModelResponse("编码任务闭环完成");
            };
        }
    }
}
