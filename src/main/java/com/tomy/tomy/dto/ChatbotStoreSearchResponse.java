package com.tomy.tomy.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ChatbotStoreSearchResponse {
    private int totalResults;
    private List<ChatbotStoreDto> stores;
}
