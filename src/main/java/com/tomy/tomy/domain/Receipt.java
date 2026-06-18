package com.tomy.tomy.domain;

import com.tomy.tomy.enums.ReceiptFailureReason;
import com.tomy.tomy.enums.ReceiptVerificationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "receipt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = true)
    private Store store;

    private String storeName;

    private String address;

    @Column(name = "total_price")
    private Integer totalPrice;

    @Column(name = "recognized_text", nullable = false)
    private String recognizedText;

    @Column(name = "recognized_date")
    private LocalDate recognizedDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private ReceiptVerificationStatus verificationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 50)
    private ReceiptFailureReason failureReason;

    @Column(name = "match_score", precision = 5, scale = 3)
    private BigDecimal matchScore;

    @Column(name = "match_method", length = 30)
    private String matchMethod;

    @Column(name = "ocr_confidence", precision = 5, scale = 3)
    private BigDecimal ocrConfidence;
}
