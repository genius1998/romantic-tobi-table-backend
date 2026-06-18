package com.tomy.tomy.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

@Component
public class OpenAiChatClient {

    private static final Logger logger = Logger.getLogger(OpenAiChatClient.class.getName());
    private static final String CHAT_COMPLETIONS_URL = "https://api.openai.com/v1/chat/completions";

    private final OpenAiApiKeyResolver apiKeyResolver;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String model;
    private final String reasoningEffort;

    public OpenAiChatClient(
            OpenAiApiKeyResolver apiKeyResolver,
            @Value("${openai.chat.model:gpt-4.1-mini}") String model
    ) {
        this.apiKeyResolver = apiKeyResolver;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.model = model;
        this.reasoningEffort = null;
    }

    public OpenAiJsonResponse completeJson(String systemPrompt, String userPrompt, int maxCompletionTokens) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKeyResolver.resolve());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("response_format", Map.of("type", "json_object"));
        body.put("temperature", 0);
        body.put("max_completion_tokens", Math.max(maxCompletionTokens, 800));

        try {
            logger.info("Sending OpenAI chat completion request. model=" + model + ", reasoning_effort=" + reasoningEffort);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(CHAT_COMPLETIONS_URL, entity, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode firstChoice = root.path("choices").path(0);
            String content = firstChoice.path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                return new OpenAiJsonResponse(null, null, "OpenAI response did not contain message content.");
            }

            try {
                return new OpenAiJsonResponse(objectMapper.readTree(content), content, null);
            } catch (Exception jsonParseException) {
                return new OpenAiJsonResponse(null, content, "OpenAI response was not valid JSON: " + jsonParseException.getMessage());
            }
        } catch (Exception exception) {
            logger.warning("OpenAI API call failed: " + exception.getMessage());
            return new OpenAiJsonResponse(null, null, "OpenAI API call failed: " + exception.getMessage());
        }
    }

    public String getModel() {
        return model;
    }

    private boolean usesDefaultOnlyTemperature(String modelName) {
        return modelName != null && modelName.startsWith("gpt-5");
    }
}
