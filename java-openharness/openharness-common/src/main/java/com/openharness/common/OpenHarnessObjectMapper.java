package com.openharness.common;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Global Jackson ObjectMapper configured to match Python Pydantic v2 serialization behavior.
 * ALL modules MUST use this instance — do not create your own ObjectMapper.
 */
public final class OpenHarnessObjectMapper {

    private static final ObjectMapper INSTANCE = new ObjectMapper()
            // Pydantic exclude_none=true: don't serialize null fields
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            // Pydantic enums serialize as strings
            .configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true)
            .configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, true)
            // ISO-8601 date format
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Allow unknown fields for frontend protocol evolution
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Support field-based serialization for models without standard getters (e.g. model() vs getModel())
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            // Sealed interface polymorphic serialization (Python Union type discrimination)
            .activateDefaultTyping(
                    BasicPolymorphicTypeValidator.builder()
                            .allowIfBaseType(Object.class)
                            .build(),
                    ObjectMapper.DefaultTyping.NON_FINAL,
                    JsonTypeInfo.As.PROPERTY)
            // Java 8 time support (Instant, Duration, etc.)
            .registerModule(new JavaTimeModule());

    private OpenHarnessObjectMapper() {}

    public static ObjectMapper get() {
        return INSTANCE;
    }
}
