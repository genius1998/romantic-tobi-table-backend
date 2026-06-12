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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Component
public class OpenAiEmbeddingClient {

    private static final Logger logger = Logger.getLogger(OpenAiEmbeddingClient.class.getName());
    private static final String EMBEDDINGS_URL = "https://api.openai.com/v1/embeddings";

    private final OpenAiApiKeyResolver apiKeyResolver;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int dimensions;

    public OpenAiEmbeddingClient(
            OpenAiApiKeyResolver apiKeyResolver,
            @Value("${openai.embedding.model:text-embedding-3-small}") String model,
            @Value("${openai.embedding.dimensions:1536}") int dimensions
    ) {
        this.apiKeyResolver = apiKeyResolver;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.model = model;
        this.dimensions = dimensions;
    }

    public List<Double> embed(String input) {
        List<List<Double>> embeddings = embed(List.of(input));
        return embeddings.get(0);
    }

    public List<List<Double>> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKeyResolver.resolve());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", inputs);
        body.put("encoding_format", "float");
        if (dimensions > 0) {
            body.put("dimensions", dimensions);
        }

        try {
            logger.info("Sending OpenAI embeddings request. model=" + model + ", input_count=" + inputs.size());
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(EMBEDDINGS_URL, entity, String.class);

            JsonNode data = objectMapper.readTree(response.getBody()).path("data");
            List<List<Double>> embeddings = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                embeddings.add(null);
            }

            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= inputs.size()) {
                    continue;
                }
                embeddings.set(index, toDoubleList(item.path("embedding")));
            }

            if (embeddings.stream().anyMatch(embedding -> embedding == null || embedding.isEmpty())) {
                throw new IllegalStateException("OpenAI embeddings response did not include every requested input.");
            }

            return embeddings;
        } catch (Exception exception) {
            throw new IllegalStateException("OpenAI embeddings API call failed: " + exception.getMessage(), exception);
        }
    }

    public String getModel() {
        return model;
    }

    public int getDimensions() {
        return dimensions;
    }

    private List<Double> toDoubleList(JsonNode embeddingNode) {
        List<Double> embedding = new ArrayList<>();
        for (JsonNode value : embeddingNode) {
            embedding.add(value.asDouble());
        }
        return embedding;
    }
}
