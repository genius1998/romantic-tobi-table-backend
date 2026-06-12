package com.tomy.tomy.dto;

import com.tomy.tomy.domain.Store;
import com.tomy.tomy.service.StoreMatchingService;

public record StoreMatchPreviewResponse(
        String inputStoreName,
        String inputAddress,
        String inputCity,
        String searchAddress,
        boolean matched,
        StoreResponse matchedStore,
        String decisionType,
        StoreMatchingService.StoreMatchDebug debug
) {
    public static StoreMatchPreviewResponse from(
            StoreMatchPreviewRequest request,
            String searchAddress,
            StoreMatchingService.StoreMatchResult result
    ) {
        Store matchedStore = result.store();
        StoreResponse matchedStoreResponse = matchedStore == null
                ? null
                : new StoreResponse(matchedStore.getId(), matchedStore.getName(), matchedStore.getAddress());

        return new StoreMatchPreviewResponse(
                request.storeName(),
                request.address(),
                request.city(),
                searchAddress,
                result.matched(),
                matchedStoreResponse,
                resolveDecisionType(result),
                result.debug()
        );
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
}
