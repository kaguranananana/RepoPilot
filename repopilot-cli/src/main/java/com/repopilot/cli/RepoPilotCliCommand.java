package com.repopilot.cli;

import com.repopilot.cli.command.RunCommand;
import picocli.CommandLine.Command;

/**
 * CLI 模块的最小根命令。
 * 阶段 0 先把命令入口立起来，后续再逐步挂接 run、status、sessions 等子命令。
 */
@Command(
        name = "repopilot",
        mixinStandardHelpOptions = true,
        description = "RepoPilot 智能编码代理平台的命令行入口。",
        subcommands = {
                RunCommand.class
        }
)
public class RepoPilotCliCommand implements Runnable {

    @Override
    public void run() {
        // 阶段 0 故意保持最小实现，避免在骨架阶段提前混入业务逻辑。
        System.out.println("RepoPilot CLI skeleton is ready.");
    }
}
