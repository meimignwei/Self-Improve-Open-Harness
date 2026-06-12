package com.openharness.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openharness.common.OpenHarnessObjectMapper;

import java.io.PrintStream;

/**
 * JSON-Lines codec: one JSON object per line, matching TypeScript types.ts exactly.
 * Java equivalent of Python ui/protocol.py.
 */
public class JsonLinesProtocol {

    private static final ObjectMapper MAPPER = OpenHarnessObjectMapper.get();

    public static String serialize(BackendEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"serialization failed\"}";
        }
    }

    public static void emit(BackendEvent event, PrintStream out) {
        out.println(serialize(event));
        out.flush();
    }

    public static FrontendRequest deserialize(String line) {
        try {
            return MAPPER.readValue(line, FrontendRequest.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static String serializeFrontendRequest(FrontendRequest request) {
        try {
            return MAPPER.writeValueAsString(request);
        } catch (Exception e) {
            return "{}";
        }
    }
}
