package com.tomy.tomy.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomy.tomy.domain.Store;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class QdrantVectorClient {

    private final StoreVectorProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public void ensureCollection() {
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    collectionUrl(),
                    HttpMethod.GET,
                    new HttpEntity<>(headers()),
                    String.class
            );
            validateCollection(response.getBody());
        } catch (HttpClientErrorException.NotFound notFound) {
            createCollection();
        }
    }

    public void upsert(List<StoreVectorPoint> points) {
        if (points.isEmpty()) {
            return;
        }

        List<Map<String, Object>> qdrantPoints = points.stream()
                .map(point -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("store_id", point.store().getId());
                    payload.put("name", point.store().getName());
                    payload.put("address", point.store().getAddress());
                    payload.put("district", point.store().getDistrict());
                    payload.put("primary_category", point.store().getPrimaryCategory());
                    payload.put("secondary_category", point.store().getSecondaryCategory());
                    payload.put("phone", point.store().getPhone());
                    payload.put("document", point.document());

                    Map<String, Object> qdrantPoint = new LinkedHashMap<>();
                    qdrantPoint.put("id", point.store().getId());
                    qdrantPoint.put("vector", point.embedding());
                    qdrantPoint.put("payload", payload);
                    return qdrantPoint;
                })
                .toList();

        String url = UriComponentsBuilder.fromHttpUrl(collectionUrl())
                .path("/points")
                .queryParam("wait", "true")
                .toUriString();

        restTemplate.exchange(
                url,
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("points", qdrantPoints), headers()),
                String.class
        );
    }

    public List<SearchHit> search(List<Double> queryEmbedding, int limit) {
        String url = UriComponentsBuilder.fromHttpUrl(collectionUrl())
                .path("/points/query")
                .toUriString();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", queryEmbedding);
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("with_vector", false);

        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(body, headers()),
                String.class
        );

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode result = root.path("result");
            JsonNode points = result.path("points");
            if (points.isMissingNode() && result.isArray()) {
                points = result;
            }

            List<SearchHit> hits = new ArrayList<>();
            for (JsonNode point : points) {
                JsonNode payload = point.path("payload");
                Long storeId = payload.path("store_id").isNumber()
                        ? payload.path("store_id").asLong()
                        : point.path("id").asLong();
                hits.add(new SearchHit(storeId, point.path("score").asDouble(0.0)));
            }
            return hits;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse Qdrant search response: " + exception.getMessage(), exception);
        }
    }

    private void createCollection() {
        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("size", properties.vectorSize());
        vectors.put("distance", "Cosine");

        restTemplate.exchange(
                collectionUrl(),
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("vectors", vectors), headers()),
                String.class
        );
    }

    private void validateCollection(String responseBody) {
        try {
            JsonNode vectors = objectMapper.readTree(responseBody)
                    .path("result")
                    .path("config")
                    .path("params")
                    .path("vectors");

            int currentSize = vectors.path("size").asInt(-1);
            if (currentSize > 0 && currentSize != properties.vectorSize()) {
                throw new IllegalStateException(
                        "Qdrant collection vector size mismatch. expected="
                                + properties.vectorSize()
                                + ", actual="
                                + currentSize
                );
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception ignored) {
            // Older Qdrant responses can differ slightly; search/upsert will still validate at runtime.
        }
    }

    private String collectionUrl() {
        return properties.qdrantUrl() + "/collections/" + properties.collectionName();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (hasText(properties.qdrantApiKey())) {
            headers.set("api-key", properties.qdrantApiKey());
        }
        return headers;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record StoreVectorPoint(
            Store store,
            String document,
            List<Double> embedding
    ) {
    }

    public record SearchHit(
            Long storeId,
            double score
    ) {
    }
}
