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
import com.tomy.tomy.enums.ReceiptFailureReason;
import com.tomy.tomy.enums.ReceiptVerificationStatus;
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

import java.math.BigDecimal;
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
    private final ReceiptAuditService receiptAuditService;

    @Value("${receipt.duplicate-check.enabled:true}")
    private boolean duplicateCheckEnabled;

    @Transactional
    public ReceiptUploadResponse uploadReceipt(Long userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found."));

        // OCR 분석 — 실패 시 REQUIRES_NEW 트랜잭션으로 실패 기록만 저장 후 예외 재전파
        ReceiptAnalysisContext analysis;
        try {
            analysis = analyzeReceiptContext(file);
        } catch (Exception e) {
            receiptAuditService.saveOcrFailure(user);
            throw e;
        }

        EnhancedParsedReceipt parsedData = analysis.parsedReceipt();
        int amount = analysis.amount();
        LocalDate paidAt = analysis.paidDate();

        // 중복 영수증 체크 — 포인트 미적립, DUPLICATE 기록 저장 후 응답 반환
        if (duplicateCheckEnabled && paidAt != null) {
            if (receiptRepository.existsByStoreNameAndRecognizedDateAndTotalPrice(parsedData.storeName(), paidAt, amount)) {
                Receipt duplicate = buildReceipt(user, null, parsedData.storeName(), parsedData.address(),
                        amount, paidAt, analysis.rawText(),
                        ReceiptVerificationStatus.DUPLICATE, ReceiptFailureReason.DUPLICATE_RECEIPT, null, null);
                receiptRepository.save(duplicate);
                return new ReceiptUploadResponse(
                        parsedData.storeName(), parsedData.paidAt(), parsedData.address(),
                        parsedData.amount(), 0L, null,
                        "이미 인증된 영수증입니다.", analysis.matchResult().debug());
            }
        }

        StoreMatchingService.StoreMatchResult storeMatchResult = analysis.matchResult();
        Store matchedStore = storeMatchResult.store();

        // 가게 미매칭 — FAILED 기록 저장
        if (matchedStore == null) {
            ReceiptFailureReason reason = mapMatchReason(storeMatchResult.reason());
            Receipt failed = buildReceipt(user, null, parsedData.storeName(), parsedData.address(),
                    amount, paidAt, analysis.rawText(),
                    ReceiptVerificationStatus.FAILED, reason,
                    storeMatchResult.score() > 0 ? BigDecimal.valueOf(storeMatchResult.score()) : null,
                    storeMatchResult.method());
            receiptRepository.save(failed);
            return new ReceiptUploadResponse(
                    parsedData.storeName(), parsedData.paidAt(), parsedData.address(),
                    parsedData.amount(), null, null,
                    "소상공인 가게가 아닙니다", storeMatchResult.debug());
        }

        // 포인트 적립 불가 가맹점
        if (matchedStore.getRewardEligible() != null && !matchedStore.getRewardEligible()) {
            Receipt ineligible = buildReceipt(user, matchedStore, parsedData.storeName(), parsedData.address(),
                    amount, paidAt, analysis.rawText(),
                    ReceiptVerificationStatus.FAILED, ReceiptFailureReason.NOT_REWARD_ELIGIBLE,
                    BigDecimal.valueOf(storeMatchResult.score()), storeMatchResult.method());
            receiptRepository.save(ineligible);
            return new ReceiptUploadResponse(
                    parsedData.storeName(), parsedData.paidAt(), parsedData.address(),
                    parsedData.amount(), null, null,
                    "포인트 적립 불가 가맹점입니다.", storeMatchResult.debug());
        }

        // 정상 인증 — 포인트 적립
        long pointsEarned = amount / 100;

        Pet pet = petRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Pet not found for user."));
        pet.setCurrentPoint(pet.getCurrentPoint() + pointsEarned);
        petRepository.save(pet);

        Receipt receipt = buildReceipt(user, matchedStore, parsedData.storeName(), parsedData.address(),
                amount, paidAt, analysis.rawText(),
                ReceiptVerificationStatus.SUCCESS, null,
                BigDecimal.valueOf(storeMatchResult.score()), storeMatchResult.method());
        receiptRepository.save(receipt);
        achievementService.updateUserAchievementProgress(userId, AchievementUpdateRequest.AchievementType.RECEIPT);

        return new ReceiptUploadResponse(
                parsedData.storeName(), parsedData.paidAt(), parsedData.address(),
                parsedData.amount(), pointsEarned, pet.getCurrentPoint(),
                "영수증 인증 완료", storeMatchResult.debug());
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

    // StoreMatchResult.reason 문자열을 ReceiptFailureReason enum으로 변환
    private ReceiptFailureReason mapMatchReason(String reason) {
        if (reason == null) return ReceiptFailureReason.UNKNOWN;
        return switch (reason) {
            case "no_candidate" -> ReceiptFailureReason.NO_CANDIDATE;
            case "low_confidence" -> ReceiptFailureReason.LOW_CONFIDENCE;
            case "receipt_store_name_missing" -> ReceiptFailureReason.STORE_NAME_MISSING;
            default -> ReceiptFailureReason.UNKNOWN;
        };
    }

    private Receipt buildReceipt(User user, Store store, String storeName, String address,
                                  int totalPrice, LocalDate recognizedDate, String recognizedText,
                                  ReceiptVerificationStatus status, ReceiptFailureReason failureReason,
                                  BigDecimal matchScore, String matchMethod) {
        Receipt receipt = new Receipt();
        receipt.setUser(user);
        receipt.setStore(store);
        receipt.setStoreName(storeName);
        receipt.setAddress(address);
        receipt.setTotalPrice(totalPrice);
        receipt.setRecognizedDate(recognizedDate);
        receipt.setRecognizedText(recognizedText != null ? recognizedText : "");
        receipt.setCreatedAt(LocalDateTime.now());
        receipt.setVerificationStatus(status);
        receipt.setFailureReason(failureReason);
        receipt.setMatchScore(matchScore);
        receipt.setMatchMethod(matchMethod);
        return receipt;
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
