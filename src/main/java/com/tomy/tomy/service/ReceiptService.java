package com.tomy.tomy.service;

import com.tomy.tomy.domain.Pet;
import com.tomy.tomy.domain.Receipt;
import com.tomy.tomy.domain.Store;
import com.tomy.tomy.domain.User;
import com.tomy.tomy.dto.AchievementUpdateRequest;
import com.tomy.tomy.dto.EnhancedParsedReceipt;
import com.tomy.tomy.dto.ReceiptAnalysisResponse;
import com.tomy.tomy.dto.ReceiptCheckResponse;
import com.tomy.tomy.dto.ReceiptUploadResponse;
import com.tomy.tomy.exception.DuplicateReceiptException;
import com.tomy.tomy.ocr.OcrService;
import com.tomy.tomy.parser.ReceiptParser;
import com.tomy.tomy.repository.PetRepository;
import com.tomy.tomy.repository.ReceiptRepository;
import com.tomy.tomy.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final UserRepository userRepository;
    private final PetRepository petRepository;
    private final OcrService ocrService;
    private final ReceiptParser receiptParser;
    private final StoreMatchingService storeMatchingService;
    private final AchievementService achievementService;

    @Value("${receipt.duplicate-check.enabled:true}")
    private boolean duplicateCheckEnabled;

    @Transactional
    public ReceiptUploadResponse uploadReceipt(Long userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        ReceiptAnalysisContext analysis = analyzeReceiptContext(file);
        EnhancedParsedReceipt parsedData = analysis.parsedReceipt();
        int amount = analysis.amount();
        LocalDate paidAt = analysis.paidDate();

        if (duplicateCheckEnabled && paidAt != null) {
            if (receiptRepository.existsByStoreNameAndRecognizedDateAndTotalPrice(parsedData.storeName(), paidAt, amount)) {
                throw new DuplicateReceiptException("이미 인증된 영수증입니다.");
            }
        }

        StoreMatchingService.StoreMatchResult storeMatchResult = analysis.matchResult();
        Store matchedStore = storeMatchResult.store();

        if (matchedStore == null) {
            return new ReceiptUploadResponse(
                    parsedData.storeName(),
                    parsedData.paidAt(),
                    parsedData.address(),
                    parsedData.amount(),
                    null,
                    null,
                    "소상공인 가게가 아닙니다",
                    storeMatchResult.debug()
            );
        }

        long pointsEarned = amount / 100;

        Pet pet = petRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Pet not found for user."));
        pet.setCurrentPoint(pet.getCurrentPoint() + pointsEarned);
        petRepository.save(pet);

        Receipt receipt = new Receipt();
        receipt.setUser(user);
        receipt.setStore(matchedStore);
        receipt.setStoreName(parsedData.storeName());
        receipt.setAddress(parsedData.address());

        receipt.setTotalPrice(amount);
        receipt.setRecognizedText(analysis.rawText());
        receipt.setRecognizedDate(paidAt);
        receipt.setCreatedAt(LocalDateTime.now());
        receiptRepository.save(receipt);
        achievementService.updateUserAchievementProgress(userId, AchievementUpdateRequest.AchievementType.RECEIPT);

        return new ReceiptUploadResponse(
                parsedData.storeName(),
                parsedData.paidAt(),
                parsedData.address(),
                parsedData.amount(),
                pointsEarned,
                pet.getCurrentPoint(),
                "영수증 인증 완료",
                storeMatchResult.debug()
        );
    }

    @Transactional(readOnly = true)
    public ReceiptAnalysisResponse analyzeReceipt(MultipartFile file) {
        ReceiptAnalysisContext analysis = analyzeReceiptContext(file);
        return ReceiptAnalysisResponse.from(
                file,
                analysis.ocrResult(),
                analysis.parsedReceipt(),
                analysis.amount(),
                analysis.paidDate(),
                analysis.matchResult()
        );
    }

    @Transactional(readOnly = true)
    public List<ReceiptCheckResponse> getReceipts(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        return receiptRepository.findAllByUser(user).stream()
                .map(ReceiptCheckResponse::from)
                .collect(Collectors.toList());
    }

    private ReceiptAnalysisContext analyzeReceiptContext(MultipartFile file) {
        var ocrResult = ocrService.extractText(file);
        String rawText = ocrResult.fullText();
        EnhancedParsedReceipt parsedData = receiptParser.parse(rawText);
        int amount = parseAmount(parsedData.amount());
        LocalDate paidAt = parsePaidDate(parsedData.paidAt());
        StoreMatchingService.StoreMatchResult storeMatchResult = storeMatchingService.match(parsedData);

        return new ReceiptAnalysisContext(ocrResult, rawText, parsedData, amount, paidAt, storeMatchResult);
    }

    private int parseAmount(String amount) {
        if (amount == null || amount.isBlank()) {
            return 0;
        }

        try {
            return Integer.parseInt(amount.replaceAll(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private LocalDate parsePaidDate(String paidAt) {
        if (paidAt == null || paidAt.length() < 10) {
            return null;
        }

        try {
            String dateString = paidAt.substring(0, 10).replace('/', '-').replace('.', '-');
            return LocalDate.parse(dateString);
        } catch (Exception e) {
            return null;
        }
    }

    private record ReceiptAnalysisContext(
            OcrService.OcrResult ocrResult,
            String rawText,
            EnhancedParsedReceipt parsedReceipt,
            int amount,
            LocalDate paidDate,
            StoreMatchingService.StoreMatchResult matchResult
    ) {
    }
}
