package com.tomy.tomy.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ChatbotPetContextDto {
    private int level;
    private int exp;
    private int nextLevelRequiredExp;
    private int remainingExpToNextLevel;
}
