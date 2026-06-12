package com.openharness.extensions.hooks;

import java.util.Map;
import java.util.Objects;

/**
 * 4 hook types: Command, Prompt, HTTP, Agent.
 * Java equivalent of Python's HookDefinition sealed union.
 */
public sealed interface HookDefinition
        permits HookDefinition.CommandHook, HookDefinition.PromptHook,
                HookDefinition.HttpHook, HookDefinition.AgentHook {

    String matcher();
    int timeoutSeconds();
    boolean blockOnFailure();
    int priority();

    record CommandHook(String command, String matcher, int timeoutSeconds,
                       boolean blockOnFailure, int priority) implements HookDefinition {}

    record PromptHook(String prompt, String model, String matcher, int timeoutSeconds,
                       boolean blockOnFailure, int priority) implements HookDefinition {}

    record HttpHook(String url, Map<String, String> headers, String matcher,
                    int timeoutSeconds, boolean blockOnFailure, int priority) implements HookDefinition {}

    record AgentHook(String prompt, String model, String matcher, int timeoutSeconds,
                      boolean blockOnFailure, int priority) implements HookDefinition {}
}
