package com.tomy.tomy.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public class ChatbotMessageRequest {

    @NotBlank(message = "질문을 입력해 주세요.")
    private String question;

    private Long receiptId;

    private Map<String, Object> clientContext;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }

    public Map<String, Object> getClientContext() { return clientContext; }
    public void setClientContext(Map<String, Object> clientContext) { this.clientContext = clientContext; }
}
