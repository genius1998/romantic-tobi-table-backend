package com.tomy.tomy.openai;

import com.fasterxml.jackson.databind.JsonNode;

public record OpenAiJsonResponse(
        JsonNode parsedJson,
        String rawContent,
        String errorMessage
) {
    public boolean successful() {
        return parsedJson != null && errorMessage == null;
    }
}
