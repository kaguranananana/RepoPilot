package com.repopilot.cli.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ConversationMessage;
import com.repopilot.core.model.FinalModelResponse;
import com.repopilot.core.model.MessageRole;
import com.repopilot.core.model.ModelResponse;
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

class DeepSeekChatModelAdapterTest {

    private HttpServer httpServer;
    private String baseUrl;
    private String lastAuthorizationHeader;
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
    void shouldCallDeepSeekChatCompletionsApiAndParseFinalAnswer() throws Exception {
        httpServer.createContext("/chat/completions", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            respondJson(exchange, """
                    {
                      "id": "chatcmpl-001",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "真实模型回答"
                          }
                        }
                      ]
                    }
                    """);
        });
        httpServer.start();

        DeepSeekChatModelAdapter adapter = new DeepSeekChatModelAdapter(
                "test-key",
                baseUrl,
                "deepseek-chat",
                List.of()
        );

        ModelResponse response = adapter.next(List.of(
                new ConversationMessage(MessageRole.SYSTEM, "你是 RepoPilot。"),
                new ConversationMessage(MessageRole.USER, "请回复测试通过")
        ));

        FinalModelResponse finalResponse = assertInstanceOf(FinalModelResponse.class, response);
        assertEquals("真实模型回答", finalResponse.message());
        assertEquals("Bearer test-key", lastAuthorizationHeader);
        assertTrue(lastRequestBody.contains("\"model\":\"deepseek-chat\""));
        assertTrue(lastRequestBody.contains("\"role\":\"system\""));
        assertTrue(lastRequestBody.contains("\"content\":\"你是 RepoPilot。\""));
        assertTrue(lastRequestBody.contains("\"role\":\"user\""));
        assertTrue(lastRequestBody.contains("\"content\":\"请回复测试通过\""));
        assertTrue(lastRequestBody.contains("\"stream\":false"));
    }

    @Test
    void shouldSendToolsAndParseToolCalls() throws Exception {
        httpServer.createContext("/chat/completions", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            respondJson(exchange, """
                    {
                      "id": "chatcmpl-002",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "",
                            "tool_calls": [
                              {
                                "id": "call-001",
                                "type": "function",
                                "function": {
                                  "name": "read_file",
                                  "arguments": "{\\"path\\":\\"README.md\\"}"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                    """);
        });
        httpServer.start();

        DeepSeekChatModelAdapter adapter = new DeepSeekChatModelAdapter(
                "test-key",
                baseUrl,
                "deepseek-chat",
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
                new ConversationMessage(MessageRole.SYSTEM, "你是 RepoPilot。"),
                new ConversationMessage(MessageRole.USER, "读取 README.md")
        ));

        ToolCallModelResponse toolCallResponse = assertInstanceOf(ToolCallModelResponse.class, response);
        assertEquals(1, toolCallResponse.toolCalls().size());
        assertEquals("call-001", toolCallResponse.toolCalls().get(0).id());
        assertEquals("read_file", toolCallResponse.toolCalls().get(0).toolName());
        assertEquals(Map.of("path", "README.md"), toolCallResponse.toolCalls().get(0).arguments());
        assertTrue(lastRequestBody.contains("\"tools\""));
        assertTrue(lastRequestBody.contains("\"name\":\"read_file\""));
        assertTrue(lastRequestBody.contains("\"required\":[\"path\"]"));
    }

    @Test
    void shouldSerializeAssistantToolCallAndToolResultMessages() throws Exception {
        httpServer.createContext("/chat/completions", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            respondJson(exchange, """
                    {
                      "id": "chatcmpl-003",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "README.md 的内容是 RepoPilot"
                          }
                        }
                      ]
                    }
                    """);
        });
        httpServer.start();

        DeepSeekChatModelAdapter adapter = new DeepSeekChatModelAdapter(
                "test-key",
                baseUrl,
                "deepseek-chat",
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
                ConversationMessage.assistantToolCalls(List.of(new com.repopilot.core.model.ToolCall(
                        "call-001",
                        "read_file",
                        Map.of("path", "README.md")
                ))),
                ConversationMessage.toolResult("call-001", "[read_file] RepoPilot")
        ));

        FinalModelResponse finalResponse = assertInstanceOf(FinalModelResponse.class, response);
        assertEquals("README.md 的内容是 RepoPilot", finalResponse.message());
        assertTrue(lastRequestBody.contains("\"tool_calls\""));
        assertTrue(lastRequestBody.contains("\"tool_call_id\":\"call-001\""));
        assertTrue(lastRequestBody.contains("\"role\":\"tool\""));
    }

    @Test
    void shouldFailFastWhenDeepSeekApiReturnsNonSuccessStatus() throws Exception {
        httpServer.createContext("/chat/completions", exchange -> {
            respondJson(exchange, 401, """
                    {
                      "error": {
                        "message": "Authentication Fails"
                      }
                    }
                    """);
        });
        httpServer.start();

        DeepSeekChatModelAdapter adapter = new DeepSeekChatModelAdapter(
                "bad-key",
                baseUrl,
                "deepseek-chat",
                List.of()
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> adapter.next(List.of(new ConversationMessage(MessageRole.USER, "hello")))
        );

        assertTrue(exception.getMessage().contains("DeepSeek API request failed with status 401"));
        assertTrue(exception.getMessage().contains("Authentication Fails"));
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
