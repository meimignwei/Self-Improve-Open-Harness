package io.agentscope.core.tool;

import java.util.ArrayList;
import java.util.List;

public class Toolkit {

    private final List<Object> registeredObjects = new ArrayList<>();
    private final List<ToolBase> registeredAgentTools = new ArrayList<>();

    public void registerObject(Object tool) {
        registeredObjects.add(tool);
    }

    public void registerAgentTool(ToolBase tool) {
        registeredAgentTools.add(tool);
    }

    public List<Object> getRegisteredObjects() {
        return List.copyOf(registeredObjects);
    }

    public List<ToolBase> getRegisteredAgentTools() {
        return List.copyOf(registeredAgentTools);
    }

    public int size() {
        return registeredObjects.size() + registeredAgentTools.size();
    }
}
