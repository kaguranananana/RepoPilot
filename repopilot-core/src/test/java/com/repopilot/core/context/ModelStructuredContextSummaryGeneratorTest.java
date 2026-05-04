package com.repopilot.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelAdapter;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.model.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelStructuredContextSummaryGeneratorTest {

    @Test
    void shouldParseStructuredJsonSummaryFromFinalModelResponse() {
        ModelStructuredContextSummaryGenerator generator = new ModelStructuredContextSummaryGenerator(
                messages -> new FinalModelResponse("""
                        {
                          "user_goal": "修复测试失败",
                          "current_phase": "EXECUTE",
                          "plan_state": "已完成定位",
                          "touched_files": ["src/App.java"],
                          "important_findings": ["断言失败来自状态字段"],
                          "failed_commands": ["mvn test -> exit 1"],
                          "decisions": ["只做最小补丁"],
                          "next_actions": ["修改状态字段后复测"]
                        }
                        """)
        );

        StructuredContextSummary summary = generator.generate(request());

        assertEquals("修复测试失败", summary.userGoal());
        assertEquals(List.of("src/App.java"), summary.touchedFiles());
        assertEquals(List.of("修改状态字段后复测"), summary.nextActions());
    }

    @Test
    void shouldRejectToolCallsFromSummaryModel() {
        ModelStructuredContextSummaryGenerator generator = new ModelStructuredContextSummaryGenerator(
                new SingleResponseModelAdapter(new ToolCallModelResponse(List.of(new ToolCall(
                        "call-1",
                        "read_file",
                        Map.of("path", "README.md")
                ))))
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> generator.generate(request())
        );

        assertEquals(
                "结构化摘要模型只允许调用 submit_structured_context_summary 工具。",
                exception.getMessage()
        );
    }

    @Test
    void shouldParseStructuredSummaryFromDedicatedToolCall() {
        ModelStructuredContextSummaryGenerator generator = new ModelStructuredContextSummaryGenerator(
                new SingleResponseModelAdapter(new ToolCallModelResponse(List.of(new ToolCall(
                        "call-structured-summary",
                        "submit_structured_context_summary",
                        Map.of(
                                "user_goal", "修复测试失败",
                                "current_phase", "EXECUTE",
                                "plan_state", "已完成定位",
                                "touched_files", "[\"src/App.java\"]",
                                "important_findings", "[\"断言失败来自状态字段\"]",
                                "failed_commands", "[\"mvn test -> exit 1\"]",
                                "decisions", "[\"只做最小补丁\"]",
                                "next_actions", "[\"修改状态字段后复测\"]"
                        )
                ))))
        );

        StructuredContextSummary summary = generator.generate(request());

        assertEquals("修复测试失败", summary.userGoal());
        assertEquals(List.of("src/App.java"), summary.touchedFiles());
        assertEquals(List.of("修改状态字段后复测"), summary.nextActions());
    }

    private StructuredContextSummaryRequest request() {
        WorkingMemorySnapshot snapshot = new WorkingMemorySnapshot(
                "修复测试失败",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "继续推进当前任务",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                null,
                null
        );
        return new StructuredContextSummaryRequest(
                List.of(new ConversationMessage(MessageRole.USER, "修复测试失败")),
                snapshot
        );
    }

    private static final class SingleResponseModelAdapter implements ModelAdapter {

        private final ModelResponse response;

        private SingleResponseModelAdapter(ModelResponse response) {
            this.response = response;
        }

        @Override
        public ModelResponse next(List<ConversationMessage> messages) {
            return response;
        }
    }
}
