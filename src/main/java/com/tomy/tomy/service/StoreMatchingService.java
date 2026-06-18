package com.tomy.tomy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tomy.tomy.domain.Store;
import com.tomy.tomy.dto.EnhancedParsedReceipt;
import com.tomy.tomy.openai.OpenAiChatClient;
import com.tomy.tomy.openai.OpenAiJsonResponse;
import com.tomy.tomy.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoreMatchingService {

    private static final int CANDIDATE_LIMIT = 10;
    private static final int LLM_CANDIDATE_LIMIT = 5;
    private static final double MIN_CANDIDATE_SCORE = 0.35;
    private static final double AUTO_MATCH_SCORE = 0.88;
    private static final double AUTO_MATCH_NAME_SCORE = 0.80;
    private static final double AUTO_MATCH_ADDRESS_SCORE = 0.72;
    private static final double NAME_ONLY_AUTO_MATCH_SCORE = 0.70;
    private static final double NAME_ONLY_AUTO_MATCH_NAME_SCORE = 0.98;
    private static final double NAME_ONLY_AUTO_MATCH_SEMANTIC_SCORE = 0.75;
    private static final double LLM_REVIEW_SCORE = 0.55;
    private static final double LLM_ACCEPT_SCORE = 0.62;
    private static final double LLM_ACCEPT_NAME_SCORE = 0.62;
    private static final double LLM_ACCEPT_ADDRESS_SCORE = 0.50;
    private static final double LLM_ACCEPT_CONFIDENCE = 0.75;
    private static final double LLM_ACCEPT_HIGH_RULE_SCORE = 0.85;
    private static final double LLM_ACCEPT_HIGH_RULE_NAME_SCORE = 0.74;
    private static final double LLM_ACCEPT_HIGH_RULE_ADDRESS_SCORE = 0.65;
    private static final double LLM_ACCEPT_HIGH_RULE_CONFIDENCE = 0.50;
    // 이름은 정확히 맞지만 주소 확인 불가인 경우 완화된 LLM 수락 기준
    private static final double LLM_STRONG_NAME_SCORE = 0.95;
    private static final double LLM_STRONG_NAME_MAX_ADDRESS = 0.55;
    private static final double LLM_STRONG_NAME_CONFIDENCE = 0.65;
    private static final double NAME_CONTAINMENT_BONUS = 0.04;
    private static final double ADDRESS_CONTAINMENT_BONUS = 0.12;
    private static final Pattern ADDRESS_DETAIL_PATTERN = Pattern.compile("([가-힣]{2,}(번길|로|길)|\\d)");
    private static final Pattern ROAD_NUMBER_PATTERN = Pattern.compile("(?:번길|로|길)\\s*(\\d+)(?:\\s*-\\s*(\\d+))?");
    private static final Pattern ROAD_NAME_PATTERN = Pattern.compile("([가-힣0-9]+(?:번길|대로|로|길))\\s*\\d+");
    private static final Pattern ADDRESS_UNIT_PATTERN = Pattern.compile("(\\d{1,4})\\s*(층|호)");
    private static final Pattern CLUSTERED_ROOM_PATTERN = Pattern.compile("(\\d{2,4})\\s*[,，]\\s*(\\d{2,4})\\s*호");

    private final StoreRepository storeRepository;
    private final StoreVectorIndexService storeVectorIndexService;
    private final OpenAiChatClient openAiChatClient;

    @Transactional(readOnly = true)
    public StoreMatchResult match(EnhancedParsedReceipt parsedReceipt) {
        if (!hasText(parsedReceipt.storeName())) {
            return StoreMatchResult.noMatch("receipt_store_name_missing", List.of(), null, "SKIPPED", null);
        }

        CandidateSearch candidateSearch = findCandidates(parsedReceipt);
        List<ScoredStore> candidates = candidateSearch.candidates();
        if (candidates.isEmpty()) {
            return StoreMatchResult.noMatch(
                    "no_candidate",
                    List.of(),
                    null,
                    candidateSearch.retrievalMethod(),
                    candidateSearch.vectorSearchError()
            );
        }

        ScoredStore best = candidates.get(0);
        if (canAutoMatch(parsedReceipt, best)) {
            return StoreMatchResult.matched(
                    best.store(),
                    "RULE_SCORE",
                    best.score(),
                    toDebugCandidates(candidates),
                    null,
                    candidateSearch.retrievalMethod(),
                    candidateSearch.vectorSearchError()
            );
        }
        if (canNameOnlyAutoMatch(parsedReceipt, best, candidates)) {
            return StoreMatchResult.matched(
                    best.store(),
                    "RULE_NAME_ONLY",
                    best.score(),
                    toDebugCandidates(candidates),
                    null,
                    candidateSearch.retrievalMethod(),
                    candidateSearch.vectorSearchError()
            );
        }

        LlmDecision llmDecision = null;
        if (shouldAskLlm(parsedReceipt, best)) {
            llmDecision = askLlm(parsedReceipt, candidates.stream().limit(LLM_CANDIDATE_LIMIT).toList());
            Optional<ScoredStore> llmSelected = findCandidate(candidates, llmDecision.matchedStoreId());
            if (llmSelected.isPresent() && canAcceptLlmDecision(llmSelected.get(), llmDecision)) {
                ScoredStore selected = llmSelected.get();
                return StoreMatchResult.matched(
                        selected.store(),
                        "LLM_REVIEW",
                        selected.score(),
                        toDebugCandidates(candidates),
                        llmDecision,
                        candidateSearch.retrievalMethod(),
                        candidateSearch.vectorSearchError()
                );
            }
        }

        return StoreMatchResult.noMatch(
                "low_confidence",
                toDebugCandidates(candidates),
                llmDecision,
                candidateSearch.retrievalMethod(),
                candidateSearch.vectorSearchError()
        );
    }

    private CandidateSearch findCandidates(EnhancedParsedReceipt parsedReceipt) {
        StoreVectorIndexService.VectorSearchResult vectorSearchResult =
                storeVectorIndexService.search(parsedReceipt, CANDIDATE_LIMIT);
        List<ScoredStore> lexicalCandidates = findLexicalCandidates(parsedReceipt);

        if (vectorSearchResult.available() && !vectorSearchResult.candidates().isEmpty()) {
            List<ScoredStore> vectorCandidates = vectorSearchResult.candidates().stream()
                    .map(candidate -> score(parsedReceipt, candidate.store(), candidate.vectorScore(), "VECTOR_DB"))
                    .filter(candidate -> candidate.score() >= MIN_CANDIDATE_SCORE
                            || candidate.nameScore() >= 0.45
                            || candidate.addressScore() >= 0.85
                            || candidate.semanticScore() >= 0.70)
                    .collect(Collectors.toList());
            return new CandidateSearch(mergeCandidates(vectorCandidates, lexicalCandidates), "HYBRID_VECTOR_LEXICAL", null);
        }

        String fallbackMethod = vectorSearchResult.available()
                ? "LEXICAL_FALLBACK_EMPTY_VECTOR_INDEX"
                : "LEXICAL_FALLBACK_VECTOR_UNAVAILABLE";

        return new CandidateSearch(lexicalCandidates, fallbackMethod, vectorSearchResult.error());
    }

    private List<ScoredStore> findLexicalCandidates(EnhancedParsedReceipt parsedReceipt) {
        return storeRepository.findAll().stream()
                .map(store -> score(parsedReceipt, store, 0.0, "LEXICAL"))
                .filter(candidate -> candidate.score() >= MIN_CANDIDATE_SCORE
                        || candidate.nameScore() >= 0.45
                        || candidate.addressScore() >= 0.85)
                .sorted(Comparator.comparingDouble(ScoredStore::score).reversed())
                .limit(CANDIDATE_LIMIT)
                .collect(Collectors.toList());
    }

    private List<ScoredStore> mergeCandidates(List<ScoredStore> vectorCandidates, List<ScoredStore> lexicalCandidates) {
        Map<Long, ScoredStore> candidatesByStoreId = new LinkedHashMap<>();
        for (ScoredStore candidate : vectorCandidates) {
            candidatesByStoreId.put(candidate.store().getId(), candidate);
        }
        for (ScoredStore candidate : lexicalCandidates) {
            candidatesByStoreId.merge(candidate.store().getId(), candidate, this::higherScoreCandidate);
        }

        return candidatesByStoreId.values().stream()
                .sorted(Comparator.comparingDouble(ScoredStore::score).reversed())
                .limit(CANDIDATE_LIMIT)
                .collect(Collectors.toList());
    }

    private ScoredStore higherScoreCandidate(ScoredStore left, ScoredStore right) {
        if (right.score() > left.score()) {
            return right;
        }
        if (right.score() == left.score() && "VECTOR_DB".equals(right.source())) {
            return right;
        }
        return left;
    }

    private ScoredStore score(EnhancedParsedReceipt receipt, Store store, double vectorScore, String source) {
        double semanticScore = clamp(vectorScore);

        NameScore name = scoreName(receipt.storeName(), store.getName());
        double nameScore = name.score();
        double nameContainment = name.containmentScore();

        AddressScore address = scoreAddress(receipt.address(), store.getAddress());
        double addressScore = address.score();
        double addressContainment = address.containmentScore();

        double totalScore = source.equals("VECTOR_DB")
                ? (semanticScore * 0.45) + (nameScore * 0.38) + (addressScore * 0.17)
                : (nameScore * 0.68) + (addressScore * 0.32);
        if (nameContainment >= 1.0) {
            totalScore += NAME_CONTAINMENT_BONUS;
        }
        if (addressContainment >= 1.0) {
            totalScore += ADDRESS_CONTAINMENT_BONUS;
        }

        return new ScoredStore(
                store,
                round(Math.min(totalScore, 1.0)),
                round(nameScore),
                round(addressScore),
                round(semanticScore),
                source
        );
    }

    private boolean canAutoMatch(EnhancedParsedReceipt receipt, ScoredStore candidate) {
        return hasUsableAddress(receipt.address())
                && candidate.score() >= AUTO_MATCH_SCORE
                && candidate.nameScore() >= AUTO_MATCH_NAME_SCORE
                && candidate.addressScore() >= AUTO_MATCH_ADDRESS_SCORE;
    }

    private boolean canNameOnlyAutoMatch(EnhancedParsedReceipt receipt, ScoredStore best, List<ScoredStore> candidates) {
        if (hasUsableAddress(receipt.address())) {
            return false;
        }
        if (best.nameScore() < NAME_ONLY_AUTO_MATCH_NAME_SCORE || best.score() < NAME_ONLY_AUTO_MATCH_SCORE) {
            return false;
        }
        if ("VECTOR_DB".equals(best.source()) && best.semanticScore() < NAME_ONLY_AUTO_MATCH_SEMANTIC_SCORE) {
            return false;
        }

        long sameNameCandidateCount = candidates.stream()
                .filter(candidate -> candidate.nameScore() >= NAME_ONLY_AUTO_MATCH_NAME_SCORE)
                .count();
        return sameNameCandidateCount == 1;
    }

    private boolean shouldAskLlm(EnhancedParsedReceipt receipt, ScoredStore best) {
        // 기존: 주소가 있을 때 LLM 호출
        if (hasUsableAddress(receipt.address())
                && best.score() >= LLM_REVIEW_SCORE
                && (best.nameScore() >= 0.45 || best.semanticScore() >= 0.70)) {
            return true;
        }
        // 추가: 주소가 없어도 이름점수가 매우 높으면 LLM에게 판단 위임
        return !hasUsableAddress(receipt.address())
                && best.nameScore() >= LLM_STRONG_NAME_SCORE
                && best.score() >= LLM_REVIEW_SCORE;
    }

    private boolean canAcceptLlmDecision(ScoredStore candidate, LlmDecision decision) {
        if (decision == null || decision.matchedStoreId() == null) {
            return false;
        }

        // 기존 경로 1: LLM 신뢰도 0.75 이상
        boolean highConfidence = decision.confidence() >= LLM_ACCEPT_CONFIDENCE;

        // 기존 경로 2: 규칙 점수가 높고 LLM 신뢰도 0.50 이상
        boolean highRuleScore = decision.confidence() >= LLM_ACCEPT_HIGH_RULE_CONFIDENCE
                && candidate.score() >= LLM_ACCEPT_HIGH_RULE_SCORE
                && candidate.nameScore() >= LLM_ACCEPT_HIGH_RULE_NAME_SCORE
                && candidate.addressScore() >= LLM_ACCEPT_HIGH_RULE_ADDRESS_SCORE;

        // 신규 경로 3: 이름이 거의 정확하고(≥0.95) 주소 확인 불가(≤0.55)일 때 LLM 신뢰도 0.65로 완화
        boolean strongNameNoAddress = candidate.nameScore() >= LLM_STRONG_NAME_SCORE
                && candidate.addressScore() < LLM_STRONG_NAME_MAX_ADDRESS
                && decision.confidence() >= LLM_STRONG_NAME_CONFIDENCE;

        boolean confidenceAccepted = highConfidence || highRuleScore || strongNameNoAddress;

        // 주소를 신뢰할 수 없는(이름 강 일치) 경우 주소 점수 하한을 면제
        boolean addressOk = candidate.addressScore() >= LLM_ACCEPT_ADDRESS_SCORE
                || candidate.nameScore() >= LLM_STRONG_NAME_SCORE;

        return confidenceAccepted
                && candidate.score() >= LLM_ACCEPT_SCORE
                && candidate.nameScore() >= LLM_ACCEPT_NAME_SCORE
                && addressOk;
    }

    private LlmDecision askLlm(EnhancedParsedReceipt receipt, List<ScoredStore> candidates) {
        String candidatesText = candidates.stream()
                .map(candidate -> """
                        - store_id: %d
                          name: %s
                          normalized_name: %s
                          address: %s
                          rule_score: %.3f
                          name_score: %.3f
                          address_score: %.3f
                          semantic_score: %.3f
                          source: %s
                        """.formatted(
                        candidate.store().getId(),
                        candidate.store().getName(),
                        normalizeName(candidate.store().getName()),
                        candidate.store().getAddress(),
                        candidate.score(),
                        candidate.nameScore(),
                        candidate.addressScore(),
                        candidate.semanticScore(),
                        candidate.source()
                ))
                .collect(Collectors.joining("\n"));

        String systemPrompt = """
                You judge whether one Korean receipt OCR result matches one registered Gumi-si store candidate.
                Return JSON only with this shape:
                {"matched_store_id":number|null,"confidence":0.0-1.0,"reason":"short reason"}
                Rules:
                - Choose only one candidate id from the list, or null.
                - Return null unless the store name and address are sufficiently consistent.
                - Same franchise name in a different neighborhood or road is not a match.
                - If the address conflicts, return null.
                - Prefer a candidate sharing a distinctive brand/name token and compatible road over a candidate sharing only campus or address words.
                - Korean campus/branch words can be abbreviated or reordered. For example, 금오공과대학교, 금오공대, and 공대 can be the same campus signal.
                - A reordered campus branch name with the same distinctive store token and compatible road is a strong match.
                - Do not choose a different franchise only because its address score is higher.
                - Do not use amount or date as evidence for store identity.
                """;

        String userPrompt = """
                Receipt OCR:
                store_name: %s
                normalized_store_name: %s
                address: %s

                Candidates:
                %s
                """.formatted(receipt.storeName(), normalizeName(receipt.storeName()), receipt.address(), candidatesText);

        OpenAiJsonResponse response = openAiChatClient.completeJson(systemPrompt, userPrompt, 300);
        JsonNode json = response.parsedJson();
        if (json == null) {
            return new LlmDecision(null, 0.0, null, response.errorMessage());
        }

        Long matchedStoreId = null;
        JsonNode idNode = json.path("matched_store_id");
        if (idNode.isNumber()) {
            matchedStoreId = idNode.asLong();
        }

        return new LlmDecision(
                matchedStoreId,
                clamp(json.path("confidence").asDouble(0.0)),
                json.path("reason").asText(null),
                null
        );
    }

    private Optional<ScoredStore> findCandidate(List<ScoredStore> candidates, Long storeId) {
        if (storeId == null) {
            return Optional.empty();
        }

        return candidates.stream()
                .filter(candidate -> storeId.equals(candidate.store().getId()))
                .findFirst();
    }

    private List<CandidateDebug> toDebugCandidates(List<ScoredStore> candidates) {
        return candidates.stream()
                .map(candidate -> new CandidateDebug(
                        candidate.store().getId(),
                        candidate.store().getName(),
                        candidate.store().getAddress(),
                        candidate.score(),
                        candidate.nameScore(),
                        candidate.addressScore(),
                        candidate.semanticScore(),
                        candidate.source()
                ))
                .collect(Collectors.toList());
    }

    private double stringSimilarity(String left, String right) {
        if (!hasText(left) || !hasText(right)) {
            return 0.0;
        }

        double editSimilarity = levenshteinSimilarity(left, right);
        double diceSimilarity = diceCoefficient(left, right);
        return Math.max(editSimilarity, diceSimilarity);
    }

    private NameScore scoreName(String receiptNameRaw, String storeNameRaw) {
        String receiptName = normalizeName(receiptNameRaw);
        String storeName = normalizeName(storeNameRaw);
        double similarity = stringSimilarity(receiptName, storeName);
        double containment = nameContainmentScore(receiptName, storeName);
        double reorderedToken = reorderedNameScore(receiptName, storeName);

        return new NameScore(round(Math.max(Math.max(similarity, containment), reorderedToken)), round(containment));
    }

    private double nameContainmentScore(String left, String right) {
        if (!hasText(left) || !hasText(right)) {
            return 0.0;
        }

        if (left.equals(right)) {
            return 1.0;
        }

        if (left.contains(right) || right.contains(left)) {
            String longerName = left.length() >= right.length() ? left : right;
            String shorterName = left.length() < right.length() ? left : right;
            int shorterLength = Math.min(left.length(), right.length());
            if (shorterLength <= 2) {
                if (hasOnlyBranchSuffix(longerName, shorterName)) {
                    return 0.84;
                }
                return 0.58;
            }
            if (shorterLength == 3) {
                if (hasOnlyBranchSuffix(longerName, shorterName)) {
                    return 0.84;
                }
                return 0.72;
            }
            return 1.0;
        }

        return 0.0;
    }

    private boolean hasOnlyBranchSuffix(String longerName, String shorterName) {
        int index = longerName.indexOf(shorterName);
        if (index < 0) {
            return false;
        }

        String remaining = longerName.substring(0, index) + longerName.substring(index + shorterName.length());
        if (!hasText(remaining)) {
            return false;
        }

        String branch = remaining
                .replace("본점", "")
                .replace("지점", "")
                .replace("점", "");
        return Set.of(
                "인동", "인의", "황상", "진평", "구평", "거의동", "거의",
                "공대", "정문", "금오", "대학", "구미"
        ).contains(branch);
    }

    private double reorderedNameScore(String left, String right) {
        Set<String> leftTokens = nameBigrams(left);
        Set<String> rightTokens = nameBigrams(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }

        Set<String> sharedTokens = new HashSet<>(leftTokens);
        sharedTokens.retainAll(rightTokens);
        if (sharedTokens.size() < 2 || sharedTokens.stream().noneMatch(this::isDistinctiveNameToken)) {
            return 0.0;
        }

        double coverage = (2.0 * sharedTokens.size()) / (leftTokens.size() + rightTokens.size());
        if (coverage < 0.45) {
            return 0.0;
        }

        return Math.min(0.78, Math.max(0.68, coverage + 0.12));
    }

    private Set<String> nameBigrams(String normalizedName) {
        Set<String> tokens = new HashSet<>();
        if (!hasText(normalizedName) || normalizedName.length() < 2) {
            return tokens;
        }

        for (int i = 0; i < normalizedName.length() - 1; i++) {
            tokens.add(normalizedName.substring(i, i + 2));
        }
        return tokens;
    }

    private boolean isDistinctiveNameToken(String token) {
        return !Set.of("공대", "금오", "대학", "학교", "정문", "구미", "대점", "문점").contains(token);
    }

    private AddressScore scoreAddress(String receiptAddress, String storeAddress) {
        String receipt = normalizeAddress(receiptAddress);
        String store = normalizeAddress(storeAddress);
        if (!hasText(receipt) || !hasText(store)) {
            return new AddressScore(0.0, 0.0);
        }

        double similarity = stringSimilarity(receipt, store);
        double containment = addressContainmentScore(receipt, store);
        if (hasConflictingRoadNumber(receiptAddress, storeAddress)
                && !hasStrongSecondaryAddressMatch(receiptAddress, storeAddress)) {
            similarity = Math.min(similarity, 0.45);
            containment = 0.0;
        }
        return new AddressScore(round(Math.max(similarity, containment)), round(containment));
    }

    private double addressContainmentScore(String receiptAddress, String storeAddress) {
        if (!hasAddressDetail(receiptAddress)) {
            return 0.0;
        }

        if (storeAddress.contains(receiptAddress) || receiptAddress.contains(storeAddress)) {
            return 1.0;
        }

        String receiptWithoutCity = removeGumiCity(receiptAddress);
        String storeWithoutCity = removeGumiCity(storeAddress);
        if (receiptWithoutCity.length() >= 3
                && (storeWithoutCity.contains(receiptWithoutCity) || receiptWithoutCity.contains(storeWithoutCity))) {
            return 1.0;
        }

        return 0.0;
    }

    private boolean hasUsableAddress(String address) {
        return hasText(address) && hasAddressDetail(normalizeAddress(address));
    }

    private boolean hasAddressDetail(String normalizedAddress) {
        return hasText(normalizedAddress) && ADDRESS_DETAIL_PATTERN.matcher(normalizedAddress).find();
    }

    private String removeGumiCity(String normalizedAddress) {
        return normalizedAddress
                .replace("구미시", "")
                .replace("경상북도", "")
                .replace("경북", "");
    }

    private boolean hasConflictingRoadNumber(String receiptAddress, String storeAddress) {
        Optional<AddressNumber> receiptNumber = extractRoadNumber(receiptAddress);
        Optional<AddressNumber> storeNumber = extractRoadNumber(storeAddress);
        if (receiptNumber.isEmpty() || storeNumber.isEmpty()) {
            return false;
        }

        AddressNumber receipt = receiptNumber.get();
        AddressNumber store = storeNumber.get();
        if (!receipt.main().equals(store.main())) {
            return true;
        }

        return receipt.sub() != null && store.sub() != null && !receipt.sub().equals(store.sub());
    }

    private boolean hasStrongSecondaryAddressMatch(String receiptAddress, String storeAddress) {
        Optional<String> receiptRoadName = extractRoadName(receiptAddress);
        Optional<String> storeRoadName = extractRoadName(storeAddress);
        if (receiptRoadName.isEmpty() || storeRoadName.isEmpty() || !receiptRoadName.get().equals(storeRoadName.get())) {
            return false;
        }

        Set<String> receiptUnits = extractAddressUnitTokens(receiptAddress);
        Set<String> storeUnits = extractAddressUnitTokens(storeAddress);
        if (receiptUnits.isEmpty() || storeUnits.isEmpty()) {
            return false;
        }

        Set<String> sharedUnits = new HashSet<>(receiptUnits);
        sharedUnits.retainAll(storeUnits);
        return sharedUnits.size() >= 2;
    }

    private Optional<String> extractRoadName(String address) {
        if (!hasText(address)) {
            return Optional.empty();
        }

        String normalized = Normalizer.normalize(address, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^0-9a-z가-힣\\s]", "");
        Matcher matcher = ROAD_NAME_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return Optional.empty();
        }

        return Optional.of(matcher.group(1));
    }

    private Set<String> extractAddressUnitTokens(String address) {
        if (!hasText(address)) {
            return Set.of();
        }

        String normalized = Normalizer.normalize(address, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Set<String> tokens = new HashSet<>();

        Matcher unitMatcher = ADDRESS_UNIT_PATTERN.matcher(normalized);
        while (unitMatcher.find()) {
            tokens.add(unitMatcher.group(2) + unitMatcher.group(1));
        }

        Matcher clusteredRoomMatcher = CLUSTERED_ROOM_PATTERN.matcher(normalized);
        while (clusteredRoomMatcher.find()) {
            tokens.add("호" + clusteredRoomMatcher.group(1));
            tokens.add("호" + clusteredRoomMatcher.group(2));
        }

        return tokens;
    }

    private Optional<AddressNumber> extractRoadNumber(String address) {
        if (!hasText(address)) {
            return Optional.empty();
        }

        String normalized = Normalizer.normalize(address, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Matcher matcher = ROAD_NUMBER_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return Optional.empty();
        }

        return Optional.of(new AddressNumber(matcher.group(1), matcher.group(2)));
    }

    private double levenshteinSimilarity(String left, String right) {
        int maxLength = Math.max(left.length(), right.length());
        if (maxLength == 0) {
            return 1.0;
        }

        int distance = levenshteinDistance(left, right);
        return 1.0 - ((double) distance / maxLength);
    }

    private int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];

        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost
                );
            }

            int[] temp = previous;
            previous = current;
            current = temp;
        }

        return previous[right.length()];
    }

    private double diceCoefficient(String left, String right) {
        if (left.length() < 2 || right.length() < 2) {
            return left.equals(right) ? 1.0 : 0.0;
        }

        int matches = 0;
        boolean[] used = new boolean[right.length() - 1];
        for (int i = 0; i < left.length() - 1; i++) {
            String leftBigram = left.substring(i, i + 2);
            for (int j = 0; j < right.length() - 1; j++) {
                if (!used[j] && leftBigram.equals(right.substring(j, j + 2))) {
                    used[j] = true;
                    matches++;
                    break;
                }
            }
        }

        return (2.0 * matches) / ((left.length() - 1) + (right.length() - 1));
    }

    private String normalizeName(String value) {
        return normalizeBase(value)
                .replace("주식회사", "")
                .replace("유한회사", "")
                .replace("농업회사법인", "")
                .replace("협동조합", "")
                .replace("cu", "씨유")
                .replace("c유", "씨유")
                .replace("메가엠지씨커피", "메가커피")
                .replace("금오공과대학교", "공대")
                .replace("금오공과대", "공대")
                .replace("금오공대", "공대")
                .replace("금오대", "공대")
                .replace("구미점", "점")
                .replaceAll("점$", "");
    }

    private String normalizeAddress(String value) {
        return normalizeBase(value)
                .replace("1f", "1층")
                .replace("if", "1층")
                .replace("lf", "1층")
                .replace("i층", "1층")
                .replace("대한민국", "")
                .replace("경상북도", "")
                .replace("경북", "")
                .replace("경상남도", "")
                .replace("경남", "")
                .replace("전라북도", "")
                .replace("전북", "")
                .replace("전라남도", "")
                .replace("전남", "")
                .replace("충청북도", "")
                .replace("충북", "")
                .replace("충청남도", "")
                .replace("충남", "");
    }

    private String normalizeBase(String value) {
        if (!hasText(value)) {
            return "";
        }

        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace("(주)", "")
                .replace("㈜", "")
                .replaceAll("[^0-9a-z가-힣]", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record ScoredStore(
            Store store,
            double score,
            double nameScore,
            double addressScore,
            double semanticScore,
            String source
    ) {
    }

    private record AddressScore(
            double score,
            double containmentScore
    ) {
    }

    private record NameScore(
            double score,
            double containmentScore
    ) {
    }

    private record AddressNumber(
            String main,
            String sub
    ) {
    }

    private record CandidateSearch(
            List<ScoredStore> candidates,
            String retrievalMethod,
            String vectorSearchError
    ) {
    }

    public record StoreMatchResult(
            Store store,
            boolean matched,
            String method,
            double score,
            List<CandidateDebug> candidates,
            String reason,
            LlmDecision llmDecision,
            String retrievalMethod,
            String vectorSearchError
    ) {
        private static StoreMatchResult matched(
                Store store,
                String method,
                double score,
                List<CandidateDebug> candidates,
                LlmDecision llmDecision,
                String retrievalMethod,
                String vectorSearchError
        ) {
            return new StoreMatchResult(store, true, method, score, candidates, null, llmDecision, retrievalMethod, vectorSearchError);
        }

        private static StoreMatchResult noMatch(
                String reason,
                List<CandidateDebug> candidates,
                LlmDecision llmDecision,
                String retrievalMethod,
                String vectorSearchError
        ) {
            return new StoreMatchResult(null, false, null, 0.0, candidates, reason, llmDecision, retrievalMethod, vectorSearchError);
        }

        public StoreMatchDebug debug() {
            return new StoreMatchDebug(matched, method, score, reason, candidates, llmDecision, retrievalMethod, vectorSearchError);
        }
    }

    public record StoreMatchDebug(
            boolean matched,
            String method,
            double score,
            String reason,
            List<CandidateDebug> candidates,
            LlmDecision llmDecision,
            String retrievalMethod,
            String vectorSearchError
    ) {
    }

    public record CandidateDebug(
            Long storeId,
            String name,
            String address,
            double score,
            double nameScore,
            double addressScore,
            double semanticScore,
            String source
    ) {
    }

    public record LlmDecision(
            Long matchedStoreId,
            double confidence,
            String reason,
            String error
    ) {
    }
}
