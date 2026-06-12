package com.tomy.tomy.openai;

import com.tomy.tomy.config.DotEnvReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpenAiApiKeyResolver {

    private static final String ENV_KEY = "OPENAI_API_KEY";

    private final String configuredApiKey;
    private final DotEnvReader dotEnvReader;

    public OpenAiApiKeyResolver(
            @Value("${openai.api.key:}") String configuredApiKey,
            DotEnvReader dotEnvReader
    ) {
        this.configuredApiKey = configuredApiKey;
        this.dotEnvReader = dotEnvReader;
    }

    public String resolve() {
        String fromDotEnv = dotEnvReader.sanitize(dotEnvReader.read(ENV_KEY));
        if (isValidOpenAiKey(fromDotEnv)) {
            return fromDotEnv;
        }

        String fromProperty = dotEnvReader.sanitize(configuredApiKey);
        if (isValidOpenAiKey(fromProperty)) {
            return fromProperty;
        }

        String fromEnvironment = dotEnvReader.sanitize(System.getenv(ENV_KEY));
        if (isValidOpenAiKey(fromEnvironment)) {
            return fromEnvironment;
        }

        throw new IllegalStateException("OpenAI API key is not configured or is not an OpenAI key. Set OPENAI_API_KEY in .env to a key that starts with sk-.");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isPlaceholder(String value) {
        return value.startsWith("<") && value.endsWith(">");
    }

    private boolean isValidOpenAiKey(String value) {
        return hasText(value) && !isPlaceholder(value) && value.startsWith("sk-");
    }
}
