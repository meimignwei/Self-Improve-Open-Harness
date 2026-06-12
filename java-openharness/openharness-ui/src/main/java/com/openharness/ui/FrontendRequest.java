package com.openharness.ui;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 5 frontend request subtypes received from stdin.
 * Java equivalent of TypeScript FrontendRequest.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FrontendRequest.UserInputRequest.class, name = "user_input"),
        @JsonSubTypes.Type(value = FrontendRequest.PermissionResponse.class, name = "permission_response"),
        @JsonSubTypes.Type(value = FrontendRequest.SelectResponse.class, name = "select_response"),
        @JsonSubTypes.Type(value = FrontendRequest.ModalResponse.class, name = "modal_response"),
        @JsonSubTypes.Type(value = FrontendRequest.ResizeNotification.class, name = "resize")
})
public sealed interface FrontendRequest {

    record UserInputRequest(String text, boolean multiline) implements FrontendRequest {}

    record PermissionResponse(String requestId, String decision, String reason) implements FrontendRequest {}

    record SelectResponse(String requestId, String selectedOption) implements FrontendRequest {}

    record ModalResponse(String requestId, String buttonClicked) implements FrontendRequest {}

    record ResizeNotification(int rows, int columns) implements FrontendRequest {}
}
