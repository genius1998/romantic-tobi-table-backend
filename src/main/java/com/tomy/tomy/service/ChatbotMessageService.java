package com.tomy.tomy.service;

import com.tomy.tomy.chatbot.ChatbotProxyClient;
import com.tomy.tomy.dto.ChatbotMessageRequest;
import com.tomy.tomy.dto.ChatbotMessageResponse;
import org.springframework.stereotype.Service;

@Service
public class ChatbotMessageService {

    private final ChatbotProxyClient proxyClient;

    public ChatbotMessageService(ChatbotProxyClient proxyClient) {
        this.proxyClient = proxyClient;
    }

    /**
     * 챗봇 질문을 FastAPI로 전달하고 응답을 반환한다.
     *
     * @param request       사용자 질문 요청
     * @param authorization Controller에서 받은 "Bearer eyJ..." 헤더 값
     */
    public ChatbotMessageResponse ask(ChatbotMessageRequest request, String authorization) {
        return proxyClient.call(request, authorization);
    }
}
