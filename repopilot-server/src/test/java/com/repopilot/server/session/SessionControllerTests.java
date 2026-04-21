package com.repopilot.server.session;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:repopilot-server-controller;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class SessionControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCreateSessionAndReturnCreatedSummary() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId": "workspace-001",
                                  "requestedBy": "cli"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").isString())
                .andExpect(jsonPath("$.workspaceId").value("workspace-001"))
                .andExpect(jsonPath("$.requestedBy").value("cli"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString());
    }

    @Test
    void shouldAppendTraceEventAndListSessionTraceHistory() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId": "workspace-002",
                                  "requestedBy": "cli"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String sessionId = extractJsonValue(responseBody, "sessionId");

        mockMvc.perform(post("/api/sessions/{sessionId}/trace-events", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "TOOL_CALL_REQUESTED",
                                  "source": "cli",
                                  "summary": "准备读取 pom.xml",
                                  "occurredAt": "2026-04-15T08:01:00Z",
                                  "metadata": {
                                    "tool": "read_file"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.type").value("TOOL_CALL_REQUESTED"))
                .andExpect(jsonPath("$.source").value("cli"))
                .andExpect(jsonPath("$.metadata.tool").value("read_file"));

        mockMvc.perform(get("/api/sessions/{sessionId}/trace-events", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sessionId").value(sessionId))
                .andExpect(jsonPath("$[0].type").value("TOOL_CALL_REQUESTED"))
                .andExpect(jsonPath("$[0].summary").value("准备读取 pom.xml"));
    }

    @Test
    void shouldPersistLatestPlanAndUpdatePlanStatus() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId": "workspace-plan",
                                  "requestedBy": "cli"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String sessionId = extractJsonValue(createResult.getResponse().getContentAsString(), "sessionId");

        MvcResult planResult = mockMvc.perform(post("/api/sessions/{sessionId}/plans", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "turnId": "turn-001",
                                  "content": "1. read file\\n2. patch file"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.turnId").value("turn-001"))
                .andExpect(jsonPath("$.content").value("1. read file\n2. patch file"))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRM"))
                .andReturn();

        String planId = extractJsonValue(planResult.getResponse().getContentAsString(), "planId");

        mockMvc.perform(get("/api/sessions/{sessionId}/plans/latest", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(planId))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRM"));

        mockMvc.perform(patch("/api/sessions/{sessionId}/plans/{planId}/status", sessionId, planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "APPROVED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(patch("/api/sessions/{sessionId}/plans/{planId}/status", sessionId, planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "EXECUTED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    @Test
    void shouldRejectIllegalPlanStatusTransition() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId": "workspace-plan-invalid",
                                  "requestedBy": "cli"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String sessionId = extractJsonValue(createResult.getResponse().getContentAsString(), "sessionId");
        MvcResult planResult = mockMvc.perform(post("/api/sessions/{sessionId}/plans", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "plan content"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String planId = extractJsonValue(planResult.getResponse().getContentAsString(), "planId");

        mockMvc.perform(patch("/api/sessions/{sessionId}/plans/{planId}/status", sessionId, planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "EXECUTED"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnNotFoundWhenSessionDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/sessions/{sessionId}", "missing-session"))
                .andExpect(status().isNotFound());
    }

    private String extractJsonValue(String json, String fieldName) {
        String marker = "\"" + fieldName + "\":\"";
        int start = json.indexOf(marker);
        int valueStart = start + marker.length();
        int valueEnd = json.indexOf('"', valueStart);
        return json.substring(valueStart, valueEnd);
    }
}
