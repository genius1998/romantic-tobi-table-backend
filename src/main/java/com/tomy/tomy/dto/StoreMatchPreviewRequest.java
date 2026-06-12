package com.tomy.tomy.dto;

public record StoreMatchPreviewRequest(
        String storeName,
        String address,
        String city
) {
}
