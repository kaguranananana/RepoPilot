package com.repopilot.protocol.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 协议层统一使用的 ObjectMapper 工厂。
 * 先把时间模块和基础序列化规则固定下来，
 * 这样 CLI、core、server 三边看到的 JSON 结构才能保持一致。
 */
public final class ProtocolObjectMapperFactory {

    private ProtocolObjectMapperFactory() {
    }

    public static ObjectMapper create() {
        ObjectMapper objectMapper = new ObjectMapper();

        // 注册 JavaTimeModule，让 Instant 这类时间对象能被 Jackson 正常处理。
        objectMapper.registerModule(new JavaTimeModule());
        // 关闭时间戳输出，确保协议层统一使用可读的 ISO-8601 字符串。
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return objectMapper;
    }
}

