package com.tomy.tomy.service;

import com.tomy.tomy.domain.Pet;
import com.tomy.tomy.domain.Receipt;
import com.tomy.tomy.domain.Store;
import com.tomy.tomy.domain.User;
import com.tomy.tomy.dto.ChatbotMeContextResponse;
import com.tomy.tomy.dto.ChatbotPetContextDto;
import com.tomy.tomy.dto.ChatbotReceiptContextDto;
import com.tomy.tomy.dto.ChatbotRecentReceiptsResponse;
import com.tomy.tomy.dto.ChatbotStoreDto;
import com.tomy.tomy.dto.ChatbotStoreSearchResponse;
import com.tomy.tomy.repository.PetRepository;
import com.tomy.tomy.repository.ReceiptRepository;
import com.tomy.tomy.repository.StoreRepository;
import com.tomy.tomy.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatbotContextService {

    // 레벨별 레벨업 필요 EXP (PetService와 동일 기준)
    private static final Map<Integer, Integer> LEVEL_EXP_MAP = Map.of(
            1, 300, 2, 600, 3, 900, 4, 1200, 5, 1500);
    private static final int MAX_LEVEL = 5;

    private final UserRepository userRepository;
    private final PetRepository petRepository;
    private final ReceiptRepository receiptRepository;
    private final StoreRepository storeRepository;

    @Transactional(readOnly = true)
    public ChatbotMeContextResponse getMyContext(String userId) {
        User user = findUser(userId);
        Pet pet = petRepository.findByUserId(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Pet not found."));

        int level = pet.getLevel();
        int exp = pet.getExp();
        int nextLevelRequired = level < MAX_LEVEL ? LEVEL_EXP_MAP.getOrDefault(level, 0) : LEVEL_EXP_MAP.get(MAX_LEVEL);
        int remaining = Math.max(0, nextLevelRequired - exp);

        ChatbotPetContextDto petDto = new ChatbotPetContextDto(level, exp, nextLevelRequired, remaining);
        return new ChatbotMeContextResponse(user.getId(), pet.getCurrentPoint(), petDto);
    }

    @Transactional(readOnly = true)
    public ChatbotRecentReceiptsResponse getRecentReceipts(String userId, int limit) {
        User user = findUser(userId);
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<Receipt> receipts = receiptRepository.findTopNByUserOrderByCreatedAtDesc(
                user, PageRequest.of(0, safeLimit));
        List<ChatbotReceiptContextDto> dtos = receipts.stream()
                .map(ChatbotReceiptContextDto::from)
                .collect(Collectors.toList());
        return new ChatbotRecentReceiptsResponse(dtos);
    }

    @Transactional(readOnly = true)
    public ChatbotReceiptContextDto getReceiptDetail(String userId, Long receiptId) {
        User user = findUser(userId);
        Receipt receipt = receiptRepository.findByIdAndUser(receiptId, user)
                .orElseThrow(() -> new IllegalArgumentException("영수증을 찾을 수 없거나 접근 권한이 없습니다."));
        return ChatbotReceiptContextDto.from(receipt);
    }

    @Transactional(readOnly = true)
    public ChatbotStoreSearchResponse searchStores(String keyword, String category, String district, int limit) {
        String kw = (keyword != null && !keyword.isBlank()) ? keyword.trim() : null;
        String cat = (category != null && !category.isBlank()) ? category.trim() : null;
        String dist = (district != null && !district.isBlank()) ? district.trim() : null;

        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<Store> stores = storeRepository.searchForChatbot(kw, cat, dist, null,
                PageRequest.of(0, safeLimit));
        List<ChatbotStoreDto> dtos = stores.stream()
                .map(ChatbotStoreDto::from)
                .collect(Collectors.toList());
        return new ChatbotStoreSearchResponse(dtos.size(), dtos);
    }

    private User findUser(String userId) {
        return userRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
    }
}
