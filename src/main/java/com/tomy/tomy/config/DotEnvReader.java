package com.tomy.tomy.config;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class DotEnvReader {

    public String read(String key) {
        Path dotEnv = Path.of(".env");
        if (!Files.exists(dotEnv)) {
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(dotEnv, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }

                String name = trimmed.substring(0, separator).trim();
                if (!key.equals(name)) {
                    continue;
                }

                return sanitize(trimmed.substring(separator + 1));
            }
        } catch (IOException ignored) {
            return null;
        }

        return null;
    }

    public String sanitize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
