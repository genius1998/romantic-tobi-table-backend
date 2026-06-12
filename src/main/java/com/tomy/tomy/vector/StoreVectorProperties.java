package com.tomy.tomy.vector;

import com.tomy.tomy.config.DotEnvReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StoreVectorProperties {

    private final DotEnvReader dotEnvReader;
    private final String enabled;
    private final String qdrantUrl;
    private final String qdrantApiKey;
    private final String collectionName;
    private final String adminToken;
    private final int vectorSize;
    private final int candidateLimit;

    public StoreVectorProperties(
            DotEnvReader dotEnvReader,
            @Value("${store.vector.enabled:}") String enabled,
            @Value("${store.vector.qdrant.url:}") String qdrantUrl,
            @Value("${store.vector.qdrant.api-key:}") String qdrantApiKey,
            @Value("${store.vector.qdrant.collection:}") String collectionName,
            @Value("${store.vector.admin-token:}") String adminToken,
            @Value("${openai.embedding.dimensions:1536}") int vectorSize,
            @Value("${store.vector.candidate-limit:10}") int candidateLimit
    ) {
        this.dotEnvReader = dotEnvReader;
        this.enabled = enabled;
        this.qdrantUrl = qdrantUrl;
        this.qdrantApiKey = qdrantApiKey;
        this.collectionName = collectionName;
        this.adminToken = adminToken;
        this.vectorSize = vectorSize;
        this.candidateLimit = candidateLimit;
    }

    public boolean enabled() {
        return Boolean.parseBoolean(resolve(enabled, "STORE_VECTOR_ENABLED", "true"));
    }

    public String qdrantUrl() {
        return removeTrailingSlash(resolve(qdrantUrl, "QDRANT_URL", "http://localhost:6333"));
    }

    public String qdrantApiKey() {
        return resolve(qdrantApiKey, "QDRANT_API_KEY", "");
    }

    public String collectionName() {
        return resolve(collectionName, "QDRANT_COLLECTION", "tomy_stores");
    }

    public String adminToken() {
        return resolve(adminToken, "STORE_VECTOR_ADMIN_TOKEN", "");
    }

    public int vectorSize() {
        return vectorSize;
    }

    public int candidateLimit() {
        return candidateLimit;
    }

    private String resolve(String configuredValue, String envName, String defaultValue) {
        String fromProperty = dotEnvReader.sanitize(configuredValue);
        if (hasText(fromProperty) && !isPlaceholder(fromProperty)) {
            return fromProperty;
        }

        String fromEnvironment = dotEnvReader.sanitize(System.getenv(envName));
        if (hasText(fromEnvironment)) {
            return fromEnvironment;
        }

        String fromDotEnv = dotEnvReader.sanitize(dotEnvReader.read(envName));
        if (hasText(fromDotEnv)) {
            return fromDotEnv;
        }

        return defaultValue;
    }

    private String removeTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isPlaceholder(String value) {
        return value.startsWith("<") && value.endsWith(">");
    }
}
