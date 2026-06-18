package com.tomy.tomy.repository;

import com.tomy.tomy.domain.Store;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {
    Page<Store> findByNameContainingIgnoreCase(String name, Pageable pageable);
    Optional<Store> findByName(String name);

    // 챗봇용 가맹점 검색 (keyword=이름/aliases, category, district, rewardEligible 복합 필터)
    @Query("""
            SELECT s FROM Store s
            WHERE (:keyword IS NULL OR LOWER(s.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                    OR LOWER(COALESCE(s.aliases, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                    OR LOWER(COALESCE(s.address, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                    OR LOWER(COALESCE(s.jibunAddress, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:category IS NULL OR LOWER(COALESCE(s.primaryCategory, '')) LIKE LOWER(CONCAT('%', :category, '%'))
                                   OR LOWER(COALESCE(s.secondaryCategory, '')) LIKE LOWER(CONCAT('%', :category, '%'))
                                   OR LOWER(s.name) LIKE LOWER(CONCAT('%', :category, '%')))
              AND (:district IS NULL OR LOWER(COALESCE(s.district, '')) LIKE LOWER(CONCAT('%', :district, '%'))
                                   OR LOWER(COALESCE(s.address, '')) LIKE LOWER(CONCAT('%', :district, '%'))
                                   OR LOWER(COALESCE(s.jibunAddress, '')) LIKE LOWER(CONCAT('%', :district, '%'))
                                   OR LOWER(s.name) LIKE LOWER(CONCAT('%', :district, '%')))
              AND (:rewardEligible IS NULL OR s.rewardEligible = :rewardEligible)
            ORDER BY s.name ASC
            """)
    List<Store> searchForChatbot(
            @Param("keyword") String keyword,
            @Param("category") String category,
            @Param("district") String district,
            @Param("rewardEligible") Boolean rewardEligible,
            Pageable pageable);
}
