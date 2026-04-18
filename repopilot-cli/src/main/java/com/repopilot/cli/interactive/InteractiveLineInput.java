package com.repopilot.cli.interactive;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Objects;

/**
 * 交互终端输入协调器。
 * 它把底层的逐行输入包装成一个共享入口，
 * 让 REPL 主循环和审批提示可以安全共用同一个终端输入源。
 */
public final class InteractiveLineInput {

    private final BufferedReader inputReader;

    public InteractiveLineInput(BufferedReader inputReader) {
        this.inputReader = Objects.requireNonNull(inputReader, "inputReader must not be null.");
    }

    public synchronized String readLine() throws IOException {
        // 所有终端输入都必须从这个共享入口读取，
        // 这样 REPL 与审批提示才能严格串行消费同一条输入流，
        // 不会出现两个调用方各自直接读标准输入导致的边界混乱。
        return inputReader.readLine();
    }
}
