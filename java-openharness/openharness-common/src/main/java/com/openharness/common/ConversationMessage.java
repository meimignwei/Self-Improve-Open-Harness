package com.openharness.common;

import java.util.List;

/**
 * A single conversation message containing a role and a list of content blocks.
 * Java record equivalent of Python's ConversationMessage Pydantic model.
 */
public record ConversationMessage(Role role, List<ContentBlock> content) {}
