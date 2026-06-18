package com.tomy.tomy.controller;

import com.tomy.tomy.dto.ChatbotMessageRequest;
import com.tomy.tomy.dto.ChatbotMessageResponse;
import com.tomy.tomy.service.ChatbotMessageService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chatbot")
public class ChatbotMessageController {

    private final ChatbotMessageService chatbotMessageService;

    public ChatbotMessageController(ChatbotMessageService chatbotMessageService) {
        this.chatbotMessageService = chatbotMessageService;
    }

    /**
     * React Native → Spring Boot → FastAPI 프록시 엔드포인트.
     * JWT 인증 필수. Authorization 헤더를 FastAPI로 그대로 전달한다.
     */
    @PostMapping("/message")
    public ResponseEntity<ChatbotMessageResponse> message(
            @Valid @RequestBody ChatbotMessageRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        ChatbotMessageResponse response = chatbotMessageService.ask(request, authorization);
        return ResponseEntity.ok(response);
    }
}
