package com.tomy.tomy.dto;

import com.tomy.tomy.domain.Store;
import com.tomy.tomy.ocr.OcrService;
import com.tomy.tomy.service.StoreMatchingService;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

public record ReceiptAnalysisResponse(
        String fileName,
        String contentType,
        long fileSize,
        OcrDebug ocr,
        EnhancedParsedReceipt parsedReceipt,
        NormalizedReceipt normalizedReceipt,
        boolean matched,
        StoreResponse matchedStore,
        String decisionType,
        StoreMatchingService.StoreMatchDebug matchingDebug
) {
    public static ReceiptAnalysisResponse from(
            MultipartFile file,
            OcrService.OcrResult ocrResult,
            EnhancedParsedReceipt parsedReceipt,
            int amount,
            LocalDate paidDate,
            StoreMatchingService.StoreMatchResult matchResult
    ) {
        Store store = matchResult.store();
        StoreResponse matchedStore = store == null
                ? null
                : new StoreResponse(store.getId(), store.getName(), store.getAddress());

        return new ReceiptAnalysisResponse(
                file.getOriginalFilename(),
                file.getContentType(),
                file.getSize(),
                new OcrDebug(
                        ocrResult.fullText(),
                        splitLines(ocrResult.fullText()),
                        ocrResult.rawMeta()
                ),
                parsedReceipt,
                new NormalizedReceipt(amount, paidDate, amount / 100L),
                matchResult.matched(),
                matchedStore,
                resolveDecisionType(matchResult),
                matchResult.debug()
        );
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        return Arrays.stream(text.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static String resolveDecisionType(StoreMatchingService.StoreMatchResult result) {
        if (!result.matched()) {
            return result.reason() == null ? "NO_MATCH" : result.reason();
        }

        if ("LLM_REVIEW".equals(result.method())) {
            return "LLM_REVIEW";
        }

        if ("RULE_SCORE".equals(result.method())) {
            return "RULE_SCORE_DIRECT_MATCH";
        }

        if ("RULE_NAME_ONLY".equals(result.method())) {
            return "RULE_NAME_ONLY_DIRECT_MATCH";
        }

        return result.method();
    }

    public record OcrDebug(
            String fullText,
            List<String> lines,
            String rawMeta
    ) {
    }

    public record NormalizedReceipt(
            int amount,
            LocalDate paidDate,
            long expectedPoints
    ) {
    }
}
