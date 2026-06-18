package com.tomy.tomy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chatbot.server")
public class ChatbotServerProperties {

    private String baseUrl = "http://localhost:8001";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 120000;
    private boolean debugLogEnabled = true;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    public boolean isDebugLogEnabled() { return debugLogEnabled; }
    public void setDebugLogEnabled(boolean debugLogEnabled) { this.debugLogEnabled = debugLogEnabled; }
}
