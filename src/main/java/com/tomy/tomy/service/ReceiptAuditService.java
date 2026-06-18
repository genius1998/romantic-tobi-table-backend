package com.tomy.tomy.service;

import com.tomy.tomy.domain.Receipt;
import com.tomy.tomy.domain.User;
import com.tomy.tomy.enums.ReceiptFailureReason;
import com.tomy.tomy.enums.ReceiptVerificationStatus;
import com.tomy.tomy.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * OCR 실패처럼 외부 트랜잭션이 롤백되어도 실패 기록은 남겨야 할 때 사용.
 * REQUIRES_NEW 전파로 별도 트랜잭션에서 커밋.
 */
@Service
@RequiredArgsConstructor
public class ReceiptAuditService {

    private final ReceiptRepository receiptRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveOcrFailure(User user) {
        Receipt receipt = new Receipt();
        receipt.setUser(user);
        receipt.setStore(null);
        receipt.setStoreName(null);
        receipt.setAddress(null);
        receipt.setTotalPrice(0);
        receipt.setRecognizedText("");
        receipt.setRecognizedDate(null);
        receipt.setCreatedAt(LocalDateTime.now());
        receipt.setVerificationStatus(ReceiptVerificationStatus.FAILED);
        receipt.setFailureReason(ReceiptFailureReason.OCR_FAILED);
        receipt.setMatchScore(null);
        receipt.setMatchMethod(null);
        receiptRepository.save(receipt);
    }
}
