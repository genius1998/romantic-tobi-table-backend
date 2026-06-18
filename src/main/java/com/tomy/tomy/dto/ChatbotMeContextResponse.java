package com.tomy.tomy.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ChatbotMeContextResponse {
    private Long userId;
    private Long currentPoint;
    private ChatbotPetContextDto pet;
}
