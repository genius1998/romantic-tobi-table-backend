package com.tomy.tomy.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomy.tomy.config.ChatbotServerProperties;
import com.tomy.tomy.dto.ChatbotActionDto;
import com.tomy.tomy.dto.ChatbotMessageRequest;
import com.tomy.tomy.dto.ChatbotMessageResponse;
import com.tomy.tomy.dto.ChatbotSourceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ChatbotProxyClient {

    private static final Logger log = LoggerFactory.getLogger(ChatbotProxyClient.class);
    private static final int QUESTION_PREVIEW_LIMIT = 50;
    private static final int TOKEN_PREVIEW_LENGTH = 12;

    private final RestTemplate restTemplate;
    private final String chatUrl;
    private final ObjectMapper objectMapper;
    private final boolean debugLogEnabled;

    public ChatbotProxyClient(ChatbotServerProperties props, ObjectMapper objectMapper) {
        this.chatUrl = props.getBaseUrl() + "/chat";
        this.objectMapper = objectMapper;
        this.debugLogEnabled = props.isDebugLogEnabled();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    public ChatbotMessageResponse call(ChatbotMessageRequest request, String bearerToken) {
        long startedAtMs = System.currentTimeMillis();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(bearerToken)) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }

        logRequestStart(request, bearerToken);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", request.getQuestion());
        if (request.getReceiptId() != null) {
            body.put("receiptId", request.getReceiptId());
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> raw = restTemplate.exchange(chatUrl, HttpMethod.POST, entity, Map.class);

            boolean fallbackUsed = raw.getBody() == null;
            if (fallbackUsed) {
                log.warn("FastAPI returned an empty body from {}", chatUrl);
            }

            ChatbotMessageResponse response = fallbackUsed ? fallback() : mapResponse(raw.getBody());
            logRequestCompletion(response, startedAtMs, fallbackUsed);
            return response;
        } catch (ResourceAccessException e) {
            log.error("FastAPI connection failed ({}): {}", chatUrl, e.getMessage());
            ChatbotMessageResponse response = fallback();
            logRequestCompletion(response, startedAtMs, true);
            return response;
        } catch (Exception e) {
            log.error("FastAPI call failed: {}", e.getMessage());
            ChatbotMessageResponse response = fallback();
            logRequestCompletion(response, startedAtMs, true);
            return response;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ChatbotMessageResponse mapResponse(Map raw) {
        if (raw == null) {
            return fallback();
        }

        String answer = raw.get("answer") != null ? raw.get("answer").toString() : "";
        String intent = raw.get("intent") != null ? raw.get("intent").toString() : "UNKNOWN";

        List<ChatbotSourceDto> sources = Collections.emptyList();
        Object rawSources = raw.get("sources");
        if (rawSources instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) rawSources;
            sources = list.stream()
                    .map(m -> {
                        Object ci = m.get("chunkIndex");
                        Integer chunkIndex = ci instanceof Number ? ((Number) ci).intValue() : null;
                        return new ChatbotSourceDto(
                                (String) m.get("sourceFile"),
                                chunkIndex,
                                (String) m.get("preview")
                        );
                    })
                    .toList();
        }

        List<ChatbotActionDto> actions = Collections.emptyList();
        Object rawActions = raw.get("actions");
        if (rawActions instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) rawActions;
            actions = list.stream()
                    .map(m -> new ChatbotActionDto(
                            (String) m.get("type"),
                            (String) m.get("label"),
                            (String) m.get("target")
                    ))
                    .toList();
        }

        return new ChatbotMessageResponse(answer, intent, sources, actions);
    }

    private ChatbotMessageResponse fallback() {
        return new ChatbotMessageResponse(
                "현재 토미도우미 연결이 원활하지 않습니다. 잠시 후 다시 시도해 주세요.",
                "UNKNOWN",
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private void logRequestStart(ChatbotMessageRequest request, String bearerToken) {
        if (!debugLogEnabled) {
            return;
        }

        log.info(
                "Chatbot proxy request: questionPreview='{}', receiptIdPresent={}, receiptId={}, clientContextPresent={}, authorizationPresent={}, authorizationMasked={}",
                previewQuestion(request.getQuestion()),
                request.getReceiptId() != null,
                request.getReceiptId(),
                request.getClientContext() != null && !request.getClientContext().isEmpty(),
                StringUtils.hasText(bearerToken),
                maskAuthorization(bearerToken)
        );
        log.info("Chatbot proxy FastAPI call start");
    }

    private void logRequestCompletion(ChatbotMessageResponse response, long startedAtMs, boolean fallbackUsed) {
        if (!debugLogEnabled) {
            return;
        }

        long elapsedMs = System.currentTimeMillis() - startedAtMs;
        int actionsCount = response.getActions() != null ? response.getActions().size() : 0;
        String intent = StringUtils.hasText(response.getIntent()) ? response.getIntent() : "UNKNOWN";

        log.info(
                "Chatbot proxy FastAPI call end: intent={}, actionsCount={}, elapsedMs={}, fallback={}",
                intent,
                actionsCount,
                elapsedMs,
                fallbackUsed
        );
    }

    private String previewQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            return "";
        }

        String normalized = question.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= QUESTION_PREVIEW_LIMIT) {
            return normalized;
        }

        return normalized.substring(0, QUESTION_PREVIEW_LIMIT);
    }

    private String maskAuthorization(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            return "absent";
        }

        String prefix = "";
        String token = authorizationHeader.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            prefix = "Bearer ";
            token = token.substring(7).trim();
        }

        if (!StringUtils.hasText(token)) {
            return prefix + "***masked";
        }

        if (token.length() <= TOKEN_PREVIEW_LENGTH) {
            return prefix + token.charAt(0) + "...masked";
        }

        return prefix + token.substring(0, TOKEN_PREVIEW_LENGTH) + "...masked";
    }
}
