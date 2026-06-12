package com.tomy.tomy.service;

import com.tomy.tomy.domain.Store;
import com.tomy.tomy.dto.EnhancedParsedReceipt;
import com.tomy.tomy.dto.StoreMatchPreviewRequest;
import com.tomy.tomy.dto.StoreMatchPreviewResponse;
import com.tomy.tomy.repository.StoreRepository;
import com.tomy.tomy.vector.StoreVectorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final StoreVectorIndexService storeVectorIndexService;
    private final StoreVectorProperties storeVectorProperties;
    private final StoreMatchingService storeMatchingService;

    @Transactional(readOnly = true)
    public Page<Store> getAllStores(Pageable pageable) {
        return storeRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Optional<Store> getStoreById(Long id) {
        return storeRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Page<Store> searchStoresByName(String name, Pageable pageable) {
        return storeRepository.findByNameContainingIgnoreCase(name, pageable);
    }

    @Transactional(readOnly = true)
    public StoreVectorIndexService.ReindexResult rebuildVectorIndex(String adminToken) {
        String configuredAdminToken = storeVectorProperties.adminToken();
        if (configuredAdminToken == null || configuredAdminToken.isBlank()) {
            throw new SecurityException("STORE_VECTOR_ADMIN_TOKEN is not configured.");
        }

        if (adminToken == null || !configuredAdminToken.equals(adminToken)) {
            throw new SecurityException("Invalid store vector admin token.");
        }

        return storeVectorIndexService.rebuildIndex();
    }

    @Transactional(readOnly = true)
    public StoreMatchPreviewResponse previewMatch(StoreMatchPreviewRequest request) {
        if (request == null || request.storeName() == null || request.storeName().isBlank()) {
            throw new IllegalArgumentException("가게명을 입력해 주세요.");
        }

        if (request.address() == null || request.address().isBlank()) {
            throw new IllegalArgumentException("주소를 입력해 주세요.");
        }

        String searchAddress = buildSearchAddress(request.city(), request.address());
        EnhancedParsedReceipt parsedReceipt = new EnhancedParsedReceipt(
                request.storeName(),
                searchAddress,
                null,
                null,
                null,
                null
        );

        StoreMatchingService.StoreMatchResult result = storeMatchingService.match(parsedReceipt);
        return StoreMatchPreviewResponse.from(request, searchAddress, result);
    }

    private String buildSearchAddress(String city, String address) {
        String normalizedAddress = address == null ? "" : address.trim();
        String normalizedCity = city == null ? "" : city.trim();

        if (normalizedCity.isBlank() || normalizedAddress.contains(normalizedCity)) {
            return normalizedAddress;
        }

        return normalizedCity + " " + normalizedAddress;
    }
}
