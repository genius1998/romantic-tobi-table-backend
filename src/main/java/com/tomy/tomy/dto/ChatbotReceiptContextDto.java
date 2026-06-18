package com.tomy.tomy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tomy.tomy.domain.Receipt;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatbotReceiptContextDto {
    private Long receiptId;
    private String storeName;
    private Long matchedStoreId;
    private String matchedStoreName;
    private Integer paymentAmount;
    private LocalDate paymentDate;
    private String verificationStatus;
    private String failureReason;
    private BigDecimal matchScore;
    private String matchMethod;
    private Long earnedPoint;
    private LocalDateTime createdAt;

    public static ChatbotReceiptContextDto from(Receipt r) {
        Long earnedPoint = null;
        if (r.getVerificationStatus() != null &&
                r.getVerificationStatus().name().equals("SUCCESS") &&
                r.getTotalPrice() != null) {
            earnedPoint = (long) (r.getTotalPrice() / 100);
        }

        return new ChatbotReceiptContextDto(
                r.getId(),
                r.getStoreName(),
                r.getStore() != null ? r.getStore().getId() : null,
                r.getStore() != null ? r.getStore().getName() : null,
                r.getTotalPrice(),
                r.getRecognizedDate(),
                r.getVerificationStatus() != null ? r.getVerificationStatus().name() : null,
                r.getFailureReason() != null ? r.getFailureReason().name() : null,
                r.getMatchScore(),
                r.getMatchMethod(),
                earnedPoint,
                r.getCreatedAt()
        );
    }
}
