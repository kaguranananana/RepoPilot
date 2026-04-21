package com.repopilot.core.context;

/**
 * 结构化上下文摘要生成器。
 * 真实运行时可以接模型实现，单元测试可以注入确定性实现。
 */
@FunctionalInterface
public interface StructuredContextSummaryGenerator {

    StructuredContextSummary generate(StructuredContextSummaryRequest request);

    static StructuredContextSummaryGenerator unavailable() {
        return request -> {
            throw new IllegalStateException("规则压缩后仍超过 token budget，必须配置结构化模型摘要生成器。");
        };
    }
}
