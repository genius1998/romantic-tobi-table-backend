package com.tomy.tomy.dto;

public record StoreVectorReindexResponse(
        boolean enabled,
        int totalStores,
        int indexedStores,
        String message,
        String error
) {
}
