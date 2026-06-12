package com.tomy.tomy.service;

import com.tomy.tomy.domain.Store;
import com.tomy.tomy.dto.EnhancedParsedReceipt;
import com.tomy.tomy.openai.OpenAiChatClient;
import com.tomy.tomy.repository.StoreRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoreMatchingServiceTest {

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final StoreVectorIndexService storeVectorIndexService = mock(StoreVectorIndexService.class);
    private final OpenAiChatClient openAiChatClient = mock(OpenAiChatClient.class);
    private final StoreMatchingService storeMatchingService =
            new StoreMatchingService(storeRepository, storeVectorIndexService, openAiChatClient);

    @Test
    void hybridSearchMatchesExactLexicalStoreWhenVectorCandidatesMissIt() {
        Store exactStore = store(1L, "도니요", "구미시 원남로 84, 1층 (원평동)");
        Store vectorOnlyStore = store(2L, "오니즈", "구미시 상모로12길 37, 1층(상모동)");

        when(storeVectorIndexService.search(any(EnhancedParsedReceipt.class), eq(10)))
                .thenReturn(new StoreVectorIndexService.VectorSearchResult(
                        true,
                        List.of(new StoreVectorIndexService.VectorCandidate(vectorOnlyStore, 0.75)),
                        null
                ));
        when(storeRepository.findAll()).thenReturn(List.of(exactStore, vectorOnlyStore));

        StoreMatchingService.StoreMatchResult result = storeMatchingService.match(receipt());

        assertThat(result.matched()).isTrue();
        assertThat(result.store().getId()).isEqualTo(1L);
        assertThat(result.method()).isEqualTo("RULE_SCORE");
        assertThat(result.retrievalMethod()).isEqualTo("HYBRID_VECTOR_LEXICAL");
        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.storeId()).isEqualTo(1L);
                    assertThat(candidate.source()).isEqualTo("LEXICAL");
                });
    }

    @Test
    void hybridSearchDoesNotMatchDifferentStoreSharingOnlyAddress() {
        Store addressOnlyStore = store(1L, "별난고기", "구미시 원남로 84, 1층 (원평동)");
        Store vectorOnlyStore = store(2L, "오니즈", "구미시 상모로12길 37, 1층(상모동)");

        when(storeVectorIndexService.search(any(EnhancedParsedReceipt.class), eq(10)))
                .thenReturn(new StoreVectorIndexService.VectorSearchResult(
                        true,
                        List.of(new StoreVectorIndexService.VectorCandidate(vectorOnlyStore, 0.75)),
                        null
                ));
        when(storeRepository.findAll()).thenReturn(List.of(addressOnlyStore, vectorOnlyStore));

        StoreMatchingService.StoreMatchResult result = storeMatchingService.match(receipt());

        assertThat(result.matched()).isFalse();
        assertThat(result.reason()).isEqualTo("low_confidence");
        assertThat(result.candidates())
                .anySatisfy(candidate -> {
                    assertThat(candidate.storeId()).isEqualTo(1L);
                    assertThat(candidate.addressScore()).isEqualTo(1.0);
                    assertThat(candidate.nameScore()).isLessThan(0.62);
                });
    }

    @Test
    void matchesAddressWhenOcrAddsDigitButFloorAndRoomDetailsAgree() {
        Store store = store(3697L, "돈우리 산동점", "구미시 산동읍 신당1로30, 탑스퀘어 2층 203,204호");

        when(storeVectorIndexService.search(any(EnhancedParsedReceipt.class), eq(10)))
                .thenReturn(new StoreVectorIndexService.VectorSearchResult(false, List.of(), "vector unavailable"));
        when(storeRepository.findAll()).thenReturn(List.of(store));

        StoreMatchingService.StoreMatchResult result = storeMatchingService.match(new EnhancedParsedReceipt(
                "돈우리산동점",
                "경북 구미시 산동읍 신당1로 310 (신당리, 팁 스퀘어) 2층 203,204호",
                "52,500",
                "2025-02-01 21:25:40",
                null,
                null
        ));

        assertThat(result.matched()).isTrue();
        assertThat(result.store().getId()).isEqualTo(3697L);
        assertThat(result.method()).isEqualTo("RULE_SCORE");
        assertThat(result.candidates().get(0).addressScore()).isGreaterThanOrEqualTo(0.72);
    }

    private EnhancedParsedReceipt receipt() {
        return new EnhancedParsedReceipt(
                "도니요",
                "경상북도 구미시 원남로 84",
                "7,000",
                "2017-04-11 14:27:00",
                null,
                null
        );
    }

    private Store store(Long id, String name, String address) {
        Store store = new Store();
        store.setId(id);
        store.setName(name);
        store.setAddress(address);
        return store;
    }
}
