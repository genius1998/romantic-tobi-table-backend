package com.tomy.tomy.repository;

import com.tomy.tomy.domain.Receipt;
import com.tomy.tomy.domain.Store;
import com.tomy.tomy.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReceiptRepository extends JpaRepository<Receipt, Long> {
    Optional<Receipt> findByUserAndStoreAndRecognizedDate(User user, Store store, LocalDate recognizedDate);
    List<Receipt> findByUser(User user);
    Optional<Receipt> findByUserAndStoreNameAndRecognizedDate(User user, String storeName, LocalDate recognizedDate);

    boolean existsByStoreNameAndRecognizedDateAndTotalPrice(String storeName, LocalDate recognizedDate, Integer totalPrice);

    List<Receipt> findAllByUser(User user);

    // 챗봇용: 최근 N건 영수증 조회 (최신순)
    List<Receipt> findTopNByUserOrderByCreatedAtDesc(User user, org.springframework.data.domain.Pageable pageable);

    // 챗봇용: 특정 사용자의 특정 영수증 조회 (소유권 검증용)
    Optional<Receipt> findByIdAndUser(Long id, User user);
}
