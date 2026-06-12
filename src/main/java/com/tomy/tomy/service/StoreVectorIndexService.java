package com.tomy.tomy.service;

import com.tomy.tomy.domain.Store;
import com.tomy.tomy.dto.EnhancedParsedReceipt;
import com.tomy.tomy.openai.OpenAiEmbeddingClient;
import com.tomy.tomy.repository.StoreRepository;
import com.tomy.tomy.vector.QdrantVectorClient;
import com.tomy.tomy.vector.StoreVectorProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoreVectorIndexService {

    private static final int EMBEDDING_BATCH_SIZE = 64;
    private static final Pattern CITY_PATTERN = Pattern.compile("([가-힣]+시)");

    private final StoreRepository storeRepository;
    private final OpenAiEmbeddingClient embeddingClient;
    private final QdrantVectorClient qdrantVectorClient;
    private final StoreVectorProperties properties;

    @Transactional(readOnly = true)
    public ReindexResult rebuildIndex() {
        if (!properties.enabled()) {
            return new ReindexResult(false, 0, 0, "store_vector_disabled");
        }

        List<Store> stores = storeRepository.findAll();
        qdrantVectorClient.ensureCollection();

        int indexedCount = 0;
        for (int start = 0; start < stores.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, stores.size());
            List<Store> batch = stores.subList(start, end);
            List<String> documents = batch.stream()
                    .map(this::buildStoreDocument)
                    .toList();
            List<List<Double>> embeddings = embeddingClient.embed(documents);

            List<QdrantVectorClient.StoreVectorPoint> points = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                points.add(new QdrantVectorClient.StoreVectorPoint(batch.get(i), documents.get(i), embeddings.get(i)));
            }

            qdrantVectorClient.upsert(points);
            indexedCount += points.size();
        }

        return new ReindexResult(true, stores.size(), indexedCount, null);
    }

    @Transactional(readOnly = true)
    public VectorSearchResult search(EnhancedParsedReceipt parsedReceipt, int requestedLimit) {
        if (!properties.enabled()) {
            return VectorSearchResult.unavailable("store_vector_disabled");
        }

        int limit = Math.max(1, Math.min(requestedLimit, properties.candidateLimit()));
        try {
            qdrantVectorClient.ensureCollection();
            List<Double> queryEmbedding = embeddingClient.embed(buildReceiptDocument(parsedReceipt));
            List<QdrantVectorClient.SearchHit> hits = qdrantVectorClient.search(queryEmbedding, limit);
            if (hits.isEmpty()) {
                return VectorSearchResult.available(List.of());
            }

            List<Long> storeIds = hits.stream()
                    .map(QdrantVectorClient.SearchHit::storeId)
                    .toList();
            Map<Long, Store> storesById = storeRepository.findAllById(storeIds).stream()
                    .collect(Collectors.toMap(Store::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));

            List<VectorCandidate> candidates = hits.stream()
                    .map(hit -> new VectorCandidate(storesById.get(hit.storeId()), hit.score()))
                    .filter(candidate -> candidate.store() != null)
                    .sorted(Comparator.comparingDouble(VectorCandidate::vectorScore).reversed())
                    .toList();

            return VectorSearchResult.available(candidates);
        } catch (Exception exception) {
            return VectorSearchResult.unavailable(exception.getMessage());
        }
    }

    public String buildStoreDocument(Store store) {
        return """
                store_name: %s
                address: %s
                city: %s
                district: %s
                primary_category: %s
                secondary_category: %s
                phone: %s
                """.formatted(
                store.getName(),
                store.getAddress(),
                extractCity(store.getAddress()),
                nullToEmpty(store.getDistrict()),
                nullToEmpty(store.getPrimaryCategory()),
                nullToEmpty(store.getSecondaryCategory()),
                nullToEmpty(store.getPhone())
        );
    }

    public String buildReceiptDocument(EnhancedParsedReceipt receipt) {
        return """
                store_name: %s
                address: %s
                city: %s
                """.formatted(
                nullToEmpty(receipt.storeName()),
                nullToEmpty(receipt.address()),
                extractCity(receipt.address())
        );
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String extractCity(String address) {
        if (address == null || address.isBlank()) {
            return "구미시";
        }

        Matcher matcher = CITY_PATTERN.matcher(address);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return "구미시";
    }

    public record ReindexResult(
            boolean enabled,
            int totalStores,
            int indexedStores,
            String error
    ) {
    }

    public record VectorSearchResult(
            boolean available,
            List<VectorCandidate> candidates,
            String error
    ) {
        private static VectorSearchResult available(List<VectorCandidate> candidates) {
            return new VectorSearchResult(true, candidates, null);
        }

        private static VectorSearchResult unavailable(String error) {
            return new VectorSearchResult(false, List.of(), error);
        }
    }

    public record VectorCandidate(
            Store store,
            double vectorScore
    ) {
    }
}
