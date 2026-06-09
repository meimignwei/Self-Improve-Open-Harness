package io.agentscope.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 需要交互 I/O 的工具基类。
 * 与 @Tool 注解的工具不同，ToolBase 支持异步用户交互。
 */
public abstract class ToolBase {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final String name;
    private final String description;

    protected ToolBase(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public abstract ToolResultBlock callSync(ToolCallParam param);
}
