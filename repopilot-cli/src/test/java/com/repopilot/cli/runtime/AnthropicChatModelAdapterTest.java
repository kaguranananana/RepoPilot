package com.repopilot.cli.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelResponse;
import com.repopilot.core.model.ToolCall;
import com.repopilot.core.model.ToolCallModelResponse;
import com.repopilot.core.tool.ToolDefinition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnthropicChatModelAdapterTest {

    private HttpServer httpServer;
    private String baseUrl;
    private String lastApiKeyHeader;
    private String lastAnthropicVersionHeader;
    private String lastRequestBody;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        httpServer.stop(0);
    }

    @Test
    void shouldCallAnthropicMessagesApiAndParseFinalAnswer() throws Exception {
        httpServer.createContext("/v1/messages", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            lastApiKeyHeader = exchange.getRequestHeaders().getFirst("x-api-key");
            lastAnthropicVersionHeader = exchange.getRequestHeaders().getFirst("anthropic-version");
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            respondJson(exchange, """
                    {
                      "id": "msg_001",
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "text",
                          "text": "真实模型回答"
                        }
                      ]
                    }
                    """);
        });
        httpServer.start();

        AnthropicChatModelAdapter adapter = new AnthropicChatModelAdapter(
                "test-key",
                baseUrl,
                "kimi-k2.6",
                List.of()
        );

        ModelResponse response = adapter.next(List.of(
                new ConversationMessage(MessageRole.SYSTEM, "你是 RepoPilot。"),
                new ConversationMessage(MessageRole.USER, "请回复测试通过")
        ));

        FinalModelResponse finalResponse = assertInstanceOf(FinalModelResponse.class, response);
        assertEquals("真实模型回答", finalResponse.message());
        assertEquals("test-key", lastApiKeyHeader);
        assertEquals("2023-06-01", lastAnthropicVersionHeader);
        assertTrue(lastRequestBody.contains("\"model\":\"kimi-k2.6\""));
        assertTrue(lastRequestBody.contains("\"max_tokens\":4096"));
        assertTrue(lastRequestBody.contains("\"system\":\"你是 RepoPilot。\""));
        assertTrue(lastRequestBody.contains("\"role\":\"user\""));
        assertTrue(lastRequestBody.contains("\"type\":\"text\""));
        assertTrue(lastRequestBody.contains("\"text\":\"请回复测试通过\""));
    }

    @Test
    void shouldParseTokenUsageFromAnthropicResponse() throws Exception {
        httpServer.createContext("/v1/messages", exchange -> respondJson(exchange, """
                {
                  "id": "msg_002",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "text",
                      "text": "带 usage 的回答"
                    }
                  ],
                  "usage": {
                    "input_tokens": 123,
                    "output_tokens": 17
                  }
                }
                """));
        httpServer.start();

        AnthropicChatModelAdapter adapter = new AnthropicChatModelAdapter(
                "test-key",
                baseUrl,
                "kimi-k2.6",
                List.of()
        );

        ModelResponse response = adapter.next(List.of(
                new ConversationMessage(MessageRole.USER, "请回复")
        ));

        FinalModelResponse finalResponse = assertInstanceOf(FinalModelResponse.class, response);
        assertTrue(finalResponse.tokenUsage().isPresent());
        assertEquals(123, finalResponse.tokenUsage().orElseThrow().promptTokens());
        assertEquals(17, finalResponse.tokenUsage().orElseThrow().completionTokens());
        assertEquals(140, finalResponse.tokenUsage().orElseThrow().totalTokens());
    }

    @Test
    void shouldCountCachedPromptTokensFromAnthropicUsage() throws Exception {
        httpServer.createContext("/v1/messages", exchange -> respondJson(exchange, """
                {
                  "id": "msg_002_cached",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    {
                      "type": "text",
                      "text": "缓存命中的回答"
                    }
                  ],
                  "usage": {
                    "input_tokens": 0,
                    "cache_creation_input_tokens": 0,
                    "cache_read_input_tokens": 10,
                    "output_tokens": 4,
                    "prompt_tokens": 10,
                    "completion_tokens": 4,
                    "total_tokens": 14
                  }
                }
                """));
        httpServer.start();

        AnthropicChatModelAdapter adapter = new AnthropicChatModelAdapter(
                "test-key",
                baseUrl,
                "kimi-k2.6",
                List.of()
        );

        ModelResponse response = adapter.next(List.of(
                new ConversationMessage(MessageRole.USER, "请回复")
        ));

        FinalModelResponse finalResponse = assertInstanceOf(FinalModelResponse.class, response);
        assertTrue(finalResponse.tokenUsage().isPresent());
        assertEquals(10, finalResponse.tokenUsage().orElseThrow().promptTokens());
        assertEquals(4, finalResponse.tokenUsage().orElseThrow().completionTokens());
        assertEquals(14, finalResponse.tokenUsage().orElseThrow().totalTokens());
    }

    @Test
    void shouldSendToolsAndParseToolUseBlocks() throws Exception {
        httpServer.createContext("/v1/messages", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            respondJson(exchange, """
                    {
                      "id": "msg_003",
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "text",
                          "text": "我先调用工具。"
                        },
                        {
                          "type": "tool_use",
                          "id": "toolu_001",
                          "name": "activate_skill",
                          "input": {
                            "name": "debug"
                          }
                        }
                      ],
                      "stop_reason": "tool_use"
                    }
                    """);
        });
        httpServer.start();

        AnthropicChatModelAdapter adapter = new AnthropicChatModelAdapter(
                "test-key",
                baseUrl,
                "kimi-k2.6",
                List.of(new ToolDefinition(
                        "activate_skill",
                        "按名称激活单个 Skill",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "name", Map.of("type", "string", "description", "要激活的 Skill 名称")
                                ),
                                "required", List.of("name")
                        )
                ))
        );

        ModelResponse response = adapter.next(List.of(
                new ConversationMessage(MessageRole.SYSTEM, "你是 RepoPilot。"),
                new ConversationMessage(MessageRole.USER, "激活 debug Skill")
        ));

        ToolCallModelResponse toolCallResponse = assertInstanceOf(ToolCallModelResponse.class, response);
        assertEquals(1, toolCallResponse.toolCalls().size());
        assertEquals("toolu_001", toolCallResponse.toolCalls().get(0).id());
        assertEquals("activate_skill", toolCallResponse.toolCalls().get(0).toolName());
        assertEquals(Map.of("name", "debug"), toolCallResponse.toolCalls().get(0).arguments());
        assertTrue(lastRequestBody.contains("\"tools\""));
        assertTrue(lastRequestBody.contains("\"name\":\"activate_skill\""));
        assertTrue(lastRequestBody.contains("\"input_schema\""));
    }

    @Test
    void shouldSerializeAssistantToolUseAndToolResultMessages() throws Exception {
        httpServer.createContext("/v1/messages", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            respondJson(exchange, """
                    {
                      "id": "msg_004",
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "text",
                          "text": "README.md 的内容是 RepoPilot"
                        }
                      ]
                    }
                    """);
        });
        httpServer.start();

        AnthropicChatModelAdapter adapter = new AnthropicChatModelAdapter(
                "test-key",
                baseUrl,
                "kimi-k2.6",
                List.of(new ToolDefinition(
                        "read_file",
                        "读取文件",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "要读取的文件路径")
                                ),
                                "required", List.of("path")
                        )
                ))
        );

        ModelResponse response = adapter.next(List.of(
                new ConversationMessage(MessageRole.USER, "读取 README.md"),
                ConversationMessage.assistantToolCalls(List.of(new ToolCall(
                        "toolu_001",
                        "read_file",
                        Map.of("path", "README.md")
                ))),
                ConversationMessage.toolResult("toolu_001", "[read_file] RepoPilot")
        ));

        FinalModelResponse finalResponse = assertInstanceOf(FinalModelResponse.class, response);
        assertEquals("README.md 的内容是 RepoPilot", finalResponse.message());
        assertTrue(lastRequestBody.contains("\"role\":\"assistant\""));
        assertTrue(lastRequestBody.contains("\"type\":\"tool_use\""));
        assertTrue(lastRequestBody.contains("\"role\":\"user\""));
        assertTrue(lastRequestBody.contains("\"type\":\"tool_result\""));
        assertTrue(lastRequestBody.contains("\"tool_use_id\":\"toolu_001\""));
    }

    @Test
    void shouldFilterToolSchemaAfterActivatedSkillMessagesAppearInHistory() throws Exception {
        httpServer.createContext("/v1/messages", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            respondJson(exchange, """
                    {
                      "id": "msg_005",
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "text",
                          "text": "继续分析"
                        }
                      ]
                    }
                    """);
        });
        httpServer.start();

        AnthropicChatModelAdapter adapter = new AnthropicChatModelAdapter(
                "test-key",
                baseUrl,
                "kimi-k2.6",
                List.of(
                        new ToolDefinition("read_file", "读取文件", Map.of("type", "object")),
                        new ToolDefinition("grep_files", "搜索文件", Map.of("type", "object")),
                        new ToolDefinition("run_command", "执行命令", Map.of("type", "object"))
                )
        );

        ModelResponse response = adapter.next(List.of(
                new ConversationMessage(
                        MessageRole.SYSTEM,
                        """
                                # Activated Skill
                                name: readonly
                                source: project
                                allowed-tools: read_file, grep_files

                                ## Readonly Skill
                                先阅读，再总结。
                                """.strip()
                ),
                new ConversationMessage(MessageRole.USER, "继续分析 pom.xml")
        ));

        FinalModelResponse finalResponse = assertInstanceOf(FinalModelResponse.class, response);
        assertEquals("继续分析", finalResponse.message());
        assertTrue(lastRequestBody.contains("\"name\":\"read_file\""));
        assertTrue(lastRequestBody.contains("\"name\":\"grep_files\""));
        assertFalse(lastRequestBody.contains("\"name\":\"run_command\""));
    }

    @Test
    void shouldAggregateSystemMessagesIntoAnthropicSystemPrompt() throws Exception {
        httpServer.createContext("/v1/messages", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            respondJson(exchange, """
                    {
                      "id": "msg_006",
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {
                          "type": "text",
                          "text": "继续分析"
                        }
                      ]
                    }
                    """);
        });
        httpServer.start();

        AnthropicChatModelAdapter adapter = new AnthropicChatModelAdapter(
                "test-key",
                baseUrl,
                "kimi-k2.6",
                List.of()
        );

        ModelResponse response = adapter.next(List.of(
                new ConversationMessage(MessageRole.SYSTEM, "你是 RepoPilot。"),
                ConversationMessage.workingMemory("""
                        working_memory
                        task_goal: 读取 pom.xml
                        """),
                ConversationMessage.contextSummary("""
                        context_summary
                        user_constraints:
                        - 不要修改无关文件
                        """),
                new ConversationMessage(MessageRole.USER, "继续分析 pom.xml")
        ));

        FinalModelResponse finalResponse = assertInstanceOf(FinalModelResponse.class, response);
        assertEquals("继续分析", finalResponse.message());
        assertTrue(lastRequestBody.contains("\"system\":\"你是 RepoPilot。\\n\\nworking_memory"));
        assertTrue(lastRequestBody.contains("task_goal: 读取 pom.xml"));
        assertTrue(lastRequestBody.contains("context_summary"));
        assertTrue(lastRequestBody.contains("\"role\":\"user\""));
        assertTrue(lastRequestBody.contains("\"text\":\"继续分析 pom.xml\""));
    }

    @Test
    void shouldFailFastWhenAnthropicApiReturnsNonSuccessStatus() throws Exception {
        httpServer.createContext("/v1/messages", exchange -> respondJson(exchange, 401, """
                {
                  "type": "error",
                  "error": {
                    "type": "authentication_error",
                    "message": "invalid x-api-key"
                  }
                }
                """));
        httpServer.start();

        AnthropicChatModelAdapter adapter = new AnthropicChatModelAdapter(
                "bad-key",
                baseUrl,
                "kimi-k2.6",
                List.of()
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> adapter.next(List.of(new ConversationMessage(MessageRole.USER, "hello")))
        );

        assertTrue(exception.getMessage().contains("Anthropic API request failed with status 401"));
        assertTrue(exception.getMessage().contains("invalid x-api-key"));
    }

    private void respondJson(HttpExchange exchange, String responseBody) throws IOException {
        respondJson(exchange, 200, responseBody);
    }

    private void respondJson(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
