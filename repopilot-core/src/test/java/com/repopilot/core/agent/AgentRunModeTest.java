package com.repopilot.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.tool.ToolDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRunModeTest {

    @Test
    void shouldDefaultAgentLoopRequestToExecuteMode() {
        AgentLoopRequest request = new AgentLoopRequest(
                messages -> new FinalModelResponse("完成"),
                List.of(new ConversationMessage(MessageRole.USER, "读取 README.md")),
                3
        );

        assertEquals(AgentRunMode.EXECUTE, request.runMode());
    }

    @Test
    void shouldAllowExplicitPlanModeOnAgentLoopRequest() {
        AgentLoopRequest request = new AgentLoopRequest(
                messages -> new FinalModelResponse("计划"),
                List.of(new ConversationMessage(MessageRole.USER, "先分析修改方案")),
                3,
                AgentRunMode.PLAN
        );

        assertEquals(AgentRunMode.PLAN, request.runMode());
    }

    @Test
    void shouldRestrictPlanModeToReadOnlyToolDefinitions() {
        List<ToolDefinition> allTools = List.of(
                new ToolDefinition("read_file", "读取文件", Map.of()),
                new ToolDefinition("grep_files", "搜索文件", Map.of()),
                new ToolDefinition("apply_patch", "应用补丁", Map.of()),
                new ToolDefinition("write_file", "写文件", Map.of()),
                new ToolDefinition("run_command", "执行命令", Map.of())
        );

        List<String> planToolNames = AgentRunMode.PLAN.filterAvailableTools(allTools)
                .stream()
                .map(ToolDefinition::name)
                .toList();

        assertEquals(List.of("read_file", "grep_files"), planToolNames);
        assertTrue(AgentRunMode.PLAN.allowsTool("read_file"));
        assertTrue(AgentRunMode.PLAN.allowsTool("grep_files"));
        assertFalse(AgentRunMode.PLAN.allowsTool("apply_patch"));
        assertFalse(AgentRunMode.PLAN.allowsTool("write_file"));
        assertFalse(AgentRunMode.PLAN.allowsTool("run_command"));
    }
}
