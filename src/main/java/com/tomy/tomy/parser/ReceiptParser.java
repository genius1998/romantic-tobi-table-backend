package com.tomy.tomy.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomy.tomy.dto.EnhancedParsedReceipt;
import com.tomy.tomy.openai.OpenAiChatClient;
import com.tomy.tomy.openai.OpenAiJsonResponse;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReceiptParser {

    private static final Logger logger = Logger.getLogger(ReceiptParser.class.getName());
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final NumberFormat INTEGER_FORMAT = NumberFormat.getIntegerInstance(Locale.KOREA);

    private static final int TOTAL_PHASES = 5;
    private static final double MIN_LLM_CONFIDENCE = 0.45;

    private static final Pattern STORE_LABEL_PATTERN = Pattern.compile(
            "^\\s*\\[?(가맹점명|가맹점|상호|매장명|사업장명|업체명)\\]?\\s*[:：]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ADDRESS_LABEL_PATTERN = Pattern.compile(
            "^\\s*\\[?(주소|가맹점주소|매장주소)\\]?\\s*[:：]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern POSITIVE_AMOUNT_LABEL_PATTERN = Pattern.compile(
            "(합계금액|총합계|총금액|결제금액|승인금액|받을금액|합계)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NEGATIVE_AMOUNT_LABEL_PATTERN = Pattern.compile(
            "(부가세|vat|공급가액|할인|거스름돈|품목별\\s*금액|단가)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\b\\d{2,4}-\\d{3,4}-\\d{4}\\b|\\b01\\d[-\\s]?\\d{3,4}[-\\s]?\\d{4}\\b|(?:tel|전화번호|저화번호)\\s*[:：]?)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BUSINESS_NUMBER_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{5}\\b");
    private static final Pattern DATE_VALUE_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{2,4})[./-](\\d{1,2})[./-](\\d{1,2})(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?(?!\\d)"
    );
    private static final Pattern DATE_TRAILING_FRAGMENT_PATTERN = Pattern.compile(".*\\d{2,4}[./-]\\d{1,2}[./-]$");
    private static final Pattern DATE_ONLY_PATTERN = Pattern.compile("^\\d{2,4}[./-]\\d{1,2}[./-]\\d{1,2}$");
    private static final Pattern TIME_ONLY_PATTERN = Pattern.compile("^\\d{1,2}:\\d{2}(?::\\d{2})?$");
    private static final Pattern AMOUNT_TOKEN_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,3}(?:[.,]\\d{3})+|\\d{2,9})(?!\\d)");
    private static final Pattern CARD_MASK_PATTERN = Pattern.compile("\\d{4}-\\d{2}\\*\\*-\\*\\*\\*\\*-\\d{4}");
    private static final Pattern APPROVAL_NUMBER_PATTERN = Pattern.compile("(승인번호|전표\\s*No|TID|가맹점\\s*No|가맹점번호)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_INLINE_PATTERN = Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+\\s*원?|\\d{2,9}\\s*원");
    private static final Pattern STORE_TEXT_PATTERN = Pattern.compile("[가-힣A-Za-z]");
    private static final Pattern STORE_NOISE_PREFIX_PATTERN = Pattern.compile(
            "^(신용승인|ic신용승인|전자서명전표|승인번호|전표번호|매입사|카드종류|카드번호|고객용|회원|합계|금액|세금|부가세|거래일시|승인일시|거래금액|고유번호|사업자번호|사업자등록번호|사업자|전화번호|저화번호|전화|tel).*",
            Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> STORE_CUT_TOKENS = Set.of(
            "전화번호", "저화번호", "전화", "주소", "합계", "총액", "결제금액", "승인금액", "승인번호", "전표",
            "TID", "카드", "체크", "일시불", "가맹점 No", "가맹점번호", "사업자번호", "사업자", "고유번호", "대표자", "고객용",
            "감사합니다", "부가세", "공급가액", "KRW", "승인일시", "거래일시"
    );
    private static final Set<String> STORE_NOISE_LINES = Set.of(
            "[영수증]", "영수증", "신용승인", "전자서명전표", "합계", "고객용", "감사합니다", "비씨", "bc"
    );
    private static final Set<String> STORE_BANNER_NOISE_PHRASES = Set.of(
            "가맹점명가맹점주소가실제와다를경우",
            "가맹점명가맹점주소가실제와다른경우",
            "신고안내",
            "포상금10만원지급",
            "여신금융협회",
            "casycheck",
            "kicc",
            "hhkcp",
            "nhnkcp",
            "엔에이치엔케이씨피",
            "매출전표사본",
            "우편접수",
            "자세한안내",
            "crafia",
            "mastercard"
    );
    private static final Set<String> STORE_BANNER_ASCII_TOKENS = Set.of(
            "easycheck",
            "easy check",
            "casycheck",
            "kicc",
            "ksnet",
            "nhnkcp",
            "hhkcp",
            "crafia"
    );
    private static final Set<String> GENERIC_STORE_VALUES = Set.of(
            "가맹점", "가맹점명", "상호", "매장명", "사업장명", "업체명"
    );
    private static final Set<String> ADDRESS_REGION_KEYWORDS = Set.of(
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
            "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주",
            "경상북도", "경상남도", "전라북도", "전라남도", "충청북도", "충청남도", "구미시"
    );
    private static final Set<String> ADDRESS_DETAIL_KEYWORDS = Set.of(
            "시", "군", "구", "동", "읍", "면", "로", "길", "번길", "층"
    );

    private static final String LLM_SYSTEM_PROMPT = """
            너는 한국어 카드 영수증 OCR 텍스트를 구조화하는 영수증 파서다.
            출력은 JSON만 한다.
            """;

    private static final String LLM_USER_PROMPT_TEMPLATE = """
            목표:
            아래 OCR 텍스트에서 가게명, 결제금액, 가맹점주소, 결제날짜를 추출하여 JSON으로만 출력한다.

            반드시 지켜야 할 규칙:
            1. 출력은 JSON 객체 하나만 한다. 설명, 마크다운, 코드블록, 주석을 절대 넣지 않는다.
            2. OCR에 없는 값은 추측하지 말고 null로 출력한다.
            3. 가게명은 "가맹점명:", "상호:", "매장명:", "업체명:" 뒤에 있는 값을 우선 사용한다.
            4. "KICC", "여신금융협회", "CasyCheck", "삼성카드", "MasterCard", "신고안내", "가맹점명/가맹점주소가 실제와 다른 경우" 같은 문구는 가게명으로 사용하지 않는다.
            5. 대표자명은 가게명이 아니다.
            6. 주소는 "주소:", "가맹점주소:", "매장주소:" 뒤에 있는 실제 도로명/지번 주소를 사용한다.
            7. 결제금액은 고객이 실제 결제한 최종 금액이다.
               - "합계", "합 계", "총액", "총 결제금액", "받을금액"이 있으면 이를 최우선으로 사용한다.
               - "거래 금액"과 "부가세"가 따로 있고 "합계"가 있으면 "합계"를 사용한다.
               - 금액은 숫자만 남기고 정수로 출력한다. 예: "16,500원" -> 16500
            8. 결제날짜는 "거래 일시", "승인 일시", "결제 일시", "매출 일시", "전표 일시"에서 찾는다.
               - 날짜가 없거나 값이 비어 있으면 null로 출력한다.
               - 날짜가 있으면 YYYY-MM-DD 형식으로 정규화한다.
               - 시간이 같이 있어도 payment_date에는 날짜만 넣는다.
            9. 애매하면 가장 안전한 값을 선택하고, 확실하지 않으면 null로 둔다.
            10. JSON key는 반드시 아래 4개만 사용한다.

            출력 JSON 형식:
            {
              "store_name": string | null,
              "payment_amount": number | null,
              "store_address": string | null,
              "payment_date": string | null
            }

            OCR 텍스트:
            \"\"\"
            %s
            \"\"\"
            """;

    private final OpenAiChatClient openAiChatClient;

    public ReceiptParser(OpenAiChatClient openAiChatClient) {
        this.openAiChatClient = openAiChatClient;
    }

    public EnhancedParsedReceipt parse(String rawText) {
        long startedAt = System.nanoTime();
        String safeRawText = rawText == null ? "" : rawText;

        logPhase(1, "OCR line parser 정리 중", startedAt);
        List<String> normalizedLines = normalizeLines(safeRawText);

        logPhase(2, "후보 추출 중", startedAt);
        CandidateSet candidateSet = extractCandidates(normalizedLines);

        logPhase(3, "LLM 후보 선택 중", startedAt);
        EnhancedParsedReceipt.LlmResult llmResult = selectWithLlm(safeRawText, candidateSet);

        logPhase(4, "검증 게이트 적용 중", startedAt);
        ResolvedReceipt resolvedReceipt = resolveReceipt(candidateSet, llmResult, normalizedLines);

        logPhase(5, "최종 JSON 조합 중", startedAt);
        EnhancedParsedReceipt.Candidates publicCandidates = candidateSet.toPublicCandidates();
        EnhancedParsedReceipt.RawDebug raw = new EnhancedParsedReceipt.RawDebug(llmResult, publicCandidates, safeRawText);
        EnhancedParsedReceipt.DebugInfo debugInfo = new EnhancedParsedReceipt.DebugInfo(
                candidateSet.paidAmountCandidates().stream().map(EnhancedParsedReceipt.CandidateValue::value).toList(),
                candidateSet.paidDateCandidates().stream().map(DateCandidate::value).distinct().toList(),
                llmResult == null ? "llm_skipped" : llmResult.error(),
                normalizedLines
        );

        return new EnhancedParsedReceipt(
                resolvedReceipt.storeName(),
                resolvedReceipt.address(),
                resolvedReceipt.amount() == null ? null : formatAmount(resolvedReceipt.amount()),
                resolvedReceipt.paidAt(),
                debugInfo,
                raw
        );
    }

    private CandidateSet extractCandidates(List<String> normalizedLines) {
        List<EnhancedParsedReceipt.CandidateValue> storeNameCandidates = dedupeCandidates(extractStoreNameCandidates(normalizedLines));
        List<EnhancedParsedReceipt.CandidateValue> paidAmountCandidates = dedupeCandidates(extractAmountCandidates(normalizedLines));
        List<EnhancedParsedReceipt.CandidateValue> addressCandidates = dedupeCandidates(extractAddressCandidates(normalizedLines));
        List<DateCandidate> paidDateCandidates = dedupeDateCandidates(extractDateCandidates(normalizedLines));
        return new CandidateSet(storeNameCandidates, paidAmountCandidates, addressCandidates, paidDateCandidates);
    }

    private EnhancedParsedReceipt.LlmResult selectWithLlm(String rawText, CandidateSet candidateSet) {
        if (candidateSet.isEmpty()) {
            return new EnhancedParsedReceipt.LlmResult(null, null, null, null, "candidate_empty");
        }

        try {
            OpenAiJsonResponse response = openAiChatClient.completeJson(
                    LLM_SYSTEM_PROMPT,
                    LLM_USER_PROMPT_TEMPLATE.formatted(rawText),
                    700
            );

            JsonNode json = response.parsedJson();
            if (json == null) {
                return new EnhancedParsedReceipt.LlmResult(
                        null,
                        null,
                        null,
                        null,
                        response.errorMessage() != null ? response.errorMessage() : response.rawContent()
                );
            }

            return new EnhancedParsedReceipt.LlmResult(
                    parseLlmField(json, "store_name", null),
                    parseLlmField(json, "payment_amount", "paid_amount"),
                    parseLlmField(json, "store_address", "address"),
                    parseLlmField(json, "payment_date", "paid_date"),
                    response.errorMessage()
            );
        } catch (Exception exception) {
            logger.warning("Failed to build or parse receipt LLM selection: " + exception.getMessage());
            return new EnhancedParsedReceipt.LlmResult(null, null, null, null, exception.getMessage());
        }
    }

    private EnhancedParsedReceipt.LlmFieldResult parseLlmField(JsonNode root, String primaryKey, String legacyKey) {
        JsonNode node = root == null ? null : root.path(primaryKey);
        if ((node == null || node.isMissingNode()) && legacyKey != null) {
            node = root.path(legacyKey);
        }

        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }

        if (node.isValueNode()) {
            String value = node.isNumber() ? Integer.toString(node.asInt()) : node.asText(null);
            return new EnhancedParsedReceipt.LlmFieldResult(
                    hasText(value) ? value.trim() : null,
                    0.80,
                    null,
                    "flat_json_prompt_result"
            );
        }

        JsonNode valueNode = node.get("value");
        String value = null;
        if (valueNode != null && !valueNode.isNull()) {
            value = valueNode.isNumber()
                    ? Integer.toString(valueNode.asInt())
                    : valueNode.asText(null);
        }

        return new EnhancedParsedReceipt.LlmFieldResult(
                hasText(value) ? value.trim() : null,
                clamp(node.path("confidence").asDouble(0.0)),
                textOrNull(node.path("evidence")),
                textOrNull(node.path("reason"))
        );
    }

    private ResolvedReceipt resolveReceipt(
            CandidateSet candidateSet,
            EnhancedParsedReceipt.LlmResult llmResult,
            List<String> normalizedLines
    ) {
        EnhancedParsedReceipt.CandidateValue storeNameCandidate = resolveStoreNameCandidate(
                candidateSet.storeNameCandidates(),
                llmResult == null ? null : llmResult.storeName(),
                normalizedLines
        );
        EnhancedParsedReceipt.CandidateValue amountCandidate = resolveAmountCandidate(
                candidateSet.paidAmountCandidates(),
                llmResult == null ? null : llmResult.paidAmount()
        );
        EnhancedParsedReceipt.CandidateValue addressCandidate = resolveTextCandidate(
                candidateSet.addressCandidates(),
                llmResult == null ? null : llmResult.address(),
                this::isAddressCandidateValid
        );
        DateCandidate dateCandidate = resolveDateCandidate(
                candidateSet.paidDateCandidates(),
                llmResult == null ? null : llmResult.paidDate()
        );

        return new ResolvedReceipt(
                storeNameCandidate == null ? null : storeNameCandidate.value(),
                addressCandidate == null ? null : addressCandidate.value(),
                amountCandidate == null ? null : safeParseInteger(amountCandidate.value()),
                formatPaidAt(dateCandidate)
        );
    }

    private EnhancedParsedReceipt.CandidateValue resolveStoreNameCandidate(
            List<EnhancedParsedReceipt.CandidateValue> candidates,
            EnhancedParsedReceipt.LlmFieldResult llmFieldResult,
            List<String> normalizedLines
    ) {
        EnhancedParsedReceipt.CandidateValue llmCandidate = findMatchingTextCandidate(candidates, llmFieldResult);
        if (llmCandidate != null && isStoreCandidateValid(llmCandidate) && hasAcceptedConfidence(llmFieldResult)) {
            return llmCandidate;
        }

        EnhancedParsedReceipt.CandidateValue llmDirectCandidate = buildDirectLlmStoreCandidate(llmFieldResult, normalizedLines);
        if (llmDirectCandidate != null) {
            return llmDirectCandidate;
        }

        return candidates.stream()
                .filter(this::isStoreCandidateValid)
                .findFirst()
                .orElse(null);
    }

    private EnhancedParsedReceipt.CandidateValue resolveTextCandidate(
            List<EnhancedParsedReceipt.CandidateValue> candidates,
            EnhancedParsedReceipt.LlmFieldResult llmFieldResult,
            Predicate<EnhancedParsedReceipt.CandidateValue> validator
    ) {
        EnhancedParsedReceipt.CandidateValue llmCandidate = findMatchingTextCandidate(candidates, llmFieldResult);
        if (llmCandidate != null && validator.test(llmCandidate) && hasAcceptedConfidence(llmFieldResult)) {
            return llmCandidate;
        }

        return candidates.stream()
                .filter(validator)
                .findFirst()
                .orElse(null);
    }

    private EnhancedParsedReceipt.CandidateValue resolveAmountCandidate(
            List<EnhancedParsedReceipt.CandidateValue> candidates,
            EnhancedParsedReceipt.LlmFieldResult llmFieldResult
    ) {
        EnhancedParsedReceipt.CandidateValue llmCandidate = findMatchingAmountCandidate(candidates, llmFieldResult);
        if (llmCandidate != null && isAmountCandidateValid(llmCandidate) && hasAcceptedConfidence(llmFieldResult)) {
            return llmCandidate;
        }

        return candidates.stream()
                .filter(this::isAmountCandidateValid)
                .findFirst()
                .orElse(null);
    }

    private DateCandidate resolveDateCandidate(
            List<DateCandidate> candidates,
            EnhancedParsedReceipt.LlmFieldResult llmFieldResult
    ) {
        DateCandidate llmCandidate = findMatchingDateCandidate(candidates, llmFieldResult);
        if (llmCandidate != null && isDateCandidateValid(llmCandidate) && hasAcceptedConfidence(llmFieldResult)) {
            return llmCandidate;
        }

        return candidates.stream()
                .filter(this::isDateCandidateValid)
                .findFirst()
                .orElse(null);
    }

    private EnhancedParsedReceipt.CandidateValue findMatchingTextCandidate(
            List<EnhancedParsedReceipt.CandidateValue> candidates,
            EnhancedParsedReceipt.LlmFieldResult llmFieldResult
    ) {
        if (llmFieldResult == null || !hasText(llmFieldResult.value())) {
            return null;
        }

        String target = normalizeLoose(llmFieldResult.value());
        return candidates.stream()
                .filter(candidate -> normalizeLoose(candidate.value()).equals(target))
                .findFirst()
                .orElse(null);
    }

    private EnhancedParsedReceipt.CandidateValue findMatchingAmountCandidate(
            List<EnhancedParsedReceipt.CandidateValue> candidates,
            EnhancedParsedReceipt.LlmFieldResult llmFieldResult
    ) {
        if (llmFieldResult == null || !hasText(llmFieldResult.value())) {
            return null;
        }

        String target = digitsOnly(llmFieldResult.value());
        return candidates.stream()
                .filter(candidate -> digitsOnly(candidate.value()).equals(target))
                .findFirst()
                .orElse(null);
    }

    private DateCandidate findMatchingDateCandidate(
            List<DateCandidate> candidates,
            EnhancedParsedReceipt.LlmFieldResult llmFieldResult
    ) {
        if (llmFieldResult == null || !hasText(llmFieldResult.value())) {
            return null;
        }

        String target = normalizeDateValue(llmFieldResult.value());
        return candidates.stream()
                .filter(candidate -> candidate.value().equals(target))
                .findFirst()
                .orElse(null);
    }

    private List<String> normalizeLines(String rawText) {
        if (!hasText(rawText)) {
            return List.of();
        }

        List<String> baseLines = Arrays.stream(rawText.split("\\R"))
                .map(this::normalizeLine)
                .filter(this::hasText)
                .toList();

        List<String> merged = new ArrayList<>();
        for (int index = 0; index < baseLines.size(); index++) {
            String current = baseLines.get(index);

            if (index + 1 < baseLines.size() && shouldMergeDateFragment(current, baseLines.get(index + 1))) {
                current = mergeDateFragment(current, baseLines.get(index + 1));
                index++;
            }

            while (index + 1 < baseLines.size() && shouldMergeAddressLine(current, baseLines.get(index + 1))) {
                current = current + " " + sanitizeAddressContinuation(baseLines.get(index + 1));
                index++;
            }

            merged.add(current.trim());
        }

        return merged;
    }

    private String normalizeLine(String line) {
        String normalized = line == null ? "" : line
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        if (normalized.isBlank()) {
            return "";
        }

        normalized = normalized
                .replaceAll("총\\s*합\\s*계", "총합계")
                .replaceAll("합\\s*계\\s*금\\s*액", "합계금액")
                .replaceAll("합\\s*계", "합계")
                .replaceAll("총\\s*금\\s*액", "총금액")
                .replaceAll("결제\\s*금\\s*액", "결제금액")
                .replaceAll("승인\\s*금\\s*액", "승인금액")
                .replaceAll("가맹점\\s*명", "가맹점명")
                .replaceAll("매장\\s*명", "매장명")
                .replaceAll("사업장\\s*명", "사업장명")
                .replaceAll("업체\\s*명", "업체명")
                .replaceAll("전표\\s*No", "전표 No")
                .replaceAll("가맹점\\s*No", "가맹점 No");

        return normalized.trim();
    }

    private boolean shouldMergeDateFragment(String current, String next) {
        return DATE_TRAILING_FRAGMENT_PATTERN.matcher(current).matches()
                || (DATE_ONLY_PATTERN.matcher(current).matches() && TIME_ONLY_PATTERN.matcher(next).matches())
                || (current.matches("^\\d{2,4}[./-]\\d{1,2}$") && next.matches("^\\d{1,2}.*"));
    }

    private String mergeDateFragment(String current, String next) {
        if (DATE_ONLY_PATTERN.matcher(current).matches() && TIME_ONLY_PATTERN.matcher(next).matches()) {
            return current + " " + next;
        }

        return current + next;
    }

    private boolean shouldMergeAddressLine(String current, String next) {
        return isLikelyAddress(current)
                && hasText(next)
                && !PHONE_PATTERN.matcher(next).find()
                && !BUSINESS_NUMBER_PATTERN.matcher(next).find()
                && !APPROVAL_NUMBER_PATTERN.matcher(next).find()
                && (isLikelyAddress(next)
                || next.startsWith("(")
                || next.matches("^\\)?\\s*\\d+.*(층|호).*$"));
    }

    private String sanitizeAddressContinuation(String line) {
        return line == null ? "" : line.replaceAll("^\\)+\\s*", "").trim();
    }

    private List<EnhancedParsedReceipt.CandidateValue> extractStoreNameCandidates(List<String> lines) {
        List<EnhancedParsedReceipt.CandidateValue> candidates = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (containsStoreBannerNoise(line)) {
                continue;
            }

            Matcher matcher = STORE_LABEL_PATTERN.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            if (APPROVAL_NUMBER_PATTERN.matcher(line).find() && !line.contains("가맹점명")) {
                continue;
            }

            String label = matcher.group(1);
            String inlineValue = matcher.group(2);
            String candidate = sanitizeStoreCandidate(inlineValue);
            if (!isValidStoreCandidate(candidate) && !hasText(inlineValue) && index + 1 < lines.size()) {
                candidate = sanitizeStoreCandidate(lines.get(index + 1));
            }
            if (!isValidStoreCandidate(candidate)) {
                continue;
            }

            candidates.add(new EnhancedParsedReceipt.CandidateValue(
                    candidate,
                    line,
                    label + " 라벨 뒤 텍스트",
                    round(storeLabelScore(label))
            ));
        }

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (STORE_LABEL_PATTERN.matcher(line).matches()
                    || isStoreNoiseLine(line)
                    || isLikelyAddress(line)
                    || findPositiveAmountLabel(line) != null
                    || containsNegativeAmountLabel(line)) {
                continue;
            }

            double baseScore = fallbackBaseScore(index);
            boolean merchantContext = hasMerchantContext(lines, index);
            for (FallbackStoreCandidate fallbackCandidate : extractFallbackStoreCandidates(line)) {
                String candidate = sanitizeStoreCandidate(fallbackCandidate.value());
                if (!isValidStoreCandidate(candidate)) {
                    continue;
                }

                if (isLikelyOwnerName(candidate) && hasPriorStrongStoreLabel(lines, index)) {
                    continue;
                }

                double score = baseScore + fallbackCandidate.scoreBonus();
                if (merchantContext) {
                    score += 0.10;
                }

                candidates.add(new EnhancedParsedReceipt.CandidateValue(
                        candidate,
                        line,
                        fallbackCandidate.reason(),
                        round(Math.min(score, 0.89))
                ));
            }
        }

        return candidates;
    }

    private List<EnhancedParsedReceipt.CandidateValue> extractAmountCandidates(List<String> lines) {
        List<EnhancedParsedReceipt.CandidateValue> candidates = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String positiveLabel = findPositiveAmountLabel(line);
            if (positiveLabel == null) {
                continue;
            }

            List<AmountToken> windowTokens = new ArrayList<>();
            int endExclusive = Math.min(lines.size(), index + 5);
            for (int cursor = index; cursor < endExclusive; cursor++) {
                if (containsNegativeAmountLabel(lines.get(cursor))) {
                    continue;
                }
                windowTokens.addAll(extractAmountTokens(lines.get(cursor)));
            }

            int maxValue = windowTokens.stream().mapToInt(AmountToken::value).max().orElse(-1);
            for (int offset = 0; offset < 5 && index + offset < lines.size(); offset++) {
                String windowLine = lines.get(index + offset);
                if (containsNegativeAmountLabel(windowLine)) {
                    continue;
                }

                for (AmountToken token : extractAmountTokens(windowLine)) {
                    double score = 0.80 - (offset * 0.06);
                    if (token.value() == maxValue) {
                        score += 0.14;
                    }
                    if (token.hasCurrencySymbol()) {
                        score += 0.03;
                    }
                    if (token.formatted()) {
                        score += 0.02;
                    }

                    candidates.add(new EnhancedParsedReceipt.CandidateValue(
                            Integer.toString(token.value()),
                            windowLine,
                            positiveLabel + " 라벨 근처 금액",
                            round(Math.min(score, 0.99))
                    ));
                }
            }
        }

        int lowerBound = Math.max(0, (int) Math.floor(lines.size() * 0.6));
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (containsNegativeAmountLabel(line) || APPROVAL_NUMBER_PATTERN.matcher(line).find()) {
                continue;
            }

            for (AmountToken token : extractAmountTokens(line)) {
                double score = line.contains("원") ? 0.58 : 0.46;
                if (index >= lowerBound) {
                    score += 0.05;
                }
                if (token.formatted()) {
                    score += 0.03;
                }

                candidates.add(new EnhancedParsedReceipt.CandidateValue(
                        Integer.toString(token.value()),
                        line,
                        line.contains("원") ? "원 표시가 있는 fallback 금액" : "하단부 fallback 금액",
                        round(Math.min(score, 0.79))
                ));
            }
        }

        return candidates;
    }

    private List<EnhancedParsedReceipt.CandidateValue> extractAddressCandidates(List<String> lines) {
        List<EnhancedParsedReceipt.CandidateValue> candidates = new ArrayList<>();

        for (String line : lines) {
            Matcher matcher = ADDRESS_LABEL_PATTERN.matcher(line);
            if (matcher.matches()) {
                String candidate = sanitizeAddressCandidate(matcher.group(2));
                if (isValidAddressCandidate(candidate)) {
                    candidates.add(new EnhancedParsedReceipt.CandidateValue(
                            candidate,
                            line,
                            matcher.group(1) + " 라벨 뒤 텍스트",
                            0.97
                    ));
                }
                continue;
            }

            String candidate = sanitizeAddressCandidate(line);
            if (!isValidAddressCandidate(candidate)) {
                continue;
            }

            double score = candidate.contains("구미시") ? 0.90 : 0.80;
            candidates.add(new EnhancedParsedReceipt.CandidateValue(
                    candidate,
                    line,
                    "한국 주소 패턴 후보",
                    round(score)
            ));
        }

        return candidates;
    }

    private List<DateCandidate> extractDateCandidates(List<String> lines) {
        List<DateCandidate> candidates = new ArrayList<>();

        for (String line : lines) {
            Matcher matcher = DATE_VALUE_PATTERN.matcher(line);
            while (matcher.find()) {
                DateCandidate candidate = buildDateCandidate(line, matcher);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }

        return candidates;
    }

    private DateCandidate buildDateCandidate(String evidence, Matcher matcher) {
        Integer year = normalizeYear(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));

        try {
            LocalDate date = LocalDate.of(year, month, day);
            String time = null;
            if (matcher.group(4) != null && matcher.group(5) != null) {
                int hour = Integer.parseInt(matcher.group(4));
                int minute = Integer.parseInt(matcher.group(5));
                int second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
                LocalTime parsedTime = LocalTime.of(hour, minute, second);
                time = parsedTime.format(second == 0
                        ? DateTimeFormatter.ofPattern("HH:mm")
                        : DateTimeFormatter.ofPattern("HH:mm:ss"));
            }

            double score = evidence.contains("승인일시") || evidence.contains("결제일시") || evidence.contains("거래일시")
                    ? 0.98
                    : time != null ? 0.92 : 0.84;

            return new DateCandidate(
                    date.toString(),
                    time,
                    evidence,
                    time != null ? "일시 패턴 후보" : "날짜 패턴 후보",
                    round(score)
            );
        } catch (Exception exception) {
            return null;
        }
    }

    private List<EnhancedParsedReceipt.CandidateValue> dedupeCandidates(List<EnhancedParsedReceipt.CandidateValue> candidates) {
        Map<String, EnhancedParsedReceipt.CandidateValue> deduped = new LinkedHashMap<>();
        for (EnhancedParsedReceipt.CandidateValue candidate : candidates) {
            if (!hasText(candidate.value())) {
                continue;
            }

            String key = normalizeLoose(candidate.value());
            EnhancedParsedReceipt.CandidateValue existing = deduped.get(key);
            if (existing == null || candidate.score() > existing.score()) {
                deduped.put(key, candidate);
            }
        }

        return deduped.values().stream()
                .sorted(Comparator.comparingDouble(EnhancedParsedReceipt.CandidateValue::score).reversed())
                .toList();
    }

    private List<DateCandidate> dedupeDateCandidates(List<DateCandidate> candidates) {
        Map<String, DateCandidate> deduped = new LinkedHashMap<>();
        for (DateCandidate candidate : candidates) {
            String key = candidate.value() + "|" + (candidate.time() == null ? "" : candidate.time());
            DateCandidate existing = deduped.get(key);
            if (existing == null || candidate.score() > existing.score()) {
                deduped.put(key, candidate);
            }
        }

        return deduped.values().stream()
                .sorted(Comparator.comparingDouble(DateCandidate::score).reversed())
                .toList();
    }

    private String sanitizeStoreCandidate(String value) {
        if (!hasText(value)) {
            return null;
        }

        String candidate = value.trim().replaceAll("\\s+", " ");
        candidate = candidate.replaceAll("^[,\\[\\](){}<>*\\-:：\\s]+", "");
        candidate = candidate.replaceAll("[\\[\\](){}<>*]+$", "").trim();

        int cutIndex = earliestIndex(candidate, STORE_CUT_TOKENS);
        if (cutIndex >= 0) {
            candidate = candidate.substring(0, cutIndex).trim();
        }

        cutIndex = earliestMatchIndex(candidate, PHONE_PATTERN, BUSINESS_NUMBER_PATTERN, DATE_VALUE_PATTERN, AMOUNT_INLINE_PATTERN, CARD_MASK_PATTERN);
        if (cutIndex >= 0) {
            candidate = candidate.substring(0, cutIndex).trim();
        }

        candidate = candidate.replaceAll("[,./|]+$", "").trim();
        candidate = candidate.replaceAll("\\s{2,}", " ").trim();
        if (containsStoreBannerNoise(candidate) || containsMerchantHeaderArtifacts(candidate)) {
            return null;
        }
        return candidate.isBlank() ? null : candidate;
    }

    private String sanitizeAddressCandidate(String value) {
        if (!hasText(value)) {
            return null;
        }

        String candidate = value.trim().replaceAll("\\s+", " ");
        candidate = candidate.replaceFirst("^\\s*(주소|가맹점주소|매장주소)\\s*[:：]?\\s*", "");
        candidate = candidate.replaceAll("\\s*\\(고객용\\).*?$", "").trim();
        candidate = candidate.replaceAll("\\s*고객용.*?$", "").trim();

        int cutIndex = earliestMatchIndex(candidate, PHONE_PATTERN, BUSINESS_NUMBER_PATTERN, APPROVAL_NUMBER_PATTERN);
        if (cutIndex >= 0) {
            candidate = candidate.substring(0, cutIndex).trim();
        }

        candidate = candidate.replaceAll("\\s{2,}", " ").trim();
        return candidate.isBlank() ? null : candidate;
    }

    private boolean isValidStoreCandidate(String candidate) {
        if (!hasText(candidate)) {
            return false;
        }

        if (candidate.length() < 2 || candidate.length() > 30) {
            return false;
        }

        if (!STORE_TEXT_PATTERN.matcher(candidate).find()) {
            return false;
        }

        if (GENERIC_STORE_VALUES.contains(candidate)) {
            return false;
        }

        if (isOnlyGenericStoreTokens(candidate) || containsStoreBannerNoise(candidate) || containsMerchantHeaderArtifacts(candidate)) {
            return false;
        }

        if (PHONE_PATTERN.matcher(candidate).find()
                || BUSINESS_NUMBER_PATTERN.matcher(candidate).find()
                || DATE_VALUE_PATTERN.matcher(candidate).find()
                || containsMoney(candidate)) {
            return false;
        }

        // 숫자가 전체 글자수의 절반 초과이면 번호류(사업자·전화·고유번호)로 간주
        String stripped = candidate.replaceAll("\\s", "");
        long digitCount = stripped.chars().filter(Character::isDigit).count();
        if (digitCount > 3 && digitCount * 2 > stripped.length()) {
            return false;
        }

        if (isLikelyAddress(candidate)) {
            return false;
        }

        String normalized = normalizeLoose(candidate);
        return STORE_CUT_TOKENS.stream().noneMatch(token -> normalized.contains(normalizeLoose(token)));
    }

    private boolean isStoreCandidateValid(EnhancedParsedReceipt.CandidateValue candidate) {
        return candidate != null && isValidStoreCandidate(candidate.value());
    }

    private boolean isValidAddressCandidate(String candidate) {
        if (!hasText(candidate) || candidate.length() < 7) {
            return false;
        }

        if (!isLikelyAddress(candidate)) {
            return false;
        }

        return !PHONE_PATTERN.matcher(candidate).find()
                && !BUSINESS_NUMBER_PATTERN.matcher(candidate).find()
                && !APPROVAL_NUMBER_PATTERN.matcher(candidate).find();
    }

    private boolean isAddressCandidateValid(EnhancedParsedReceipt.CandidateValue candidate) {
        return candidate != null && isValidAddressCandidate(candidate.value());
    }

    private boolean isAmountCandidateValid(EnhancedParsedReceipt.CandidateValue candidate) {
        if (candidate == null || !hasText(candidate.value()) || !candidate.value().matches("\\d+")) {
            return false;
        }

        int value = safeParseInteger(candidate.value());
        if (value <= 0) {
            return false;
        }

        return !containsNegativeAmountLabel(candidate.evidence()) || candidate.score() >= 0.70;
    }

    private boolean isDateCandidateValid(DateCandidate candidate) {
        if (candidate == null || !hasText(candidate.value())) {
            return false;
        }

        try {
            LocalDate.parse(candidate.value());
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean hasAcceptedConfidence(EnhancedParsedReceipt.LlmFieldResult llmFieldResult) {
        return llmFieldResult != null && llmFieldResult.confidence() >= MIN_LLM_CONFIDENCE;
    }

    private boolean isStoreNoiseLine(String line) {
        if (!hasText(line)) {
            return true;
        }

        String normalized = line.trim();
        if (STORE_NOISE_LINES.contains(normalized)) {
            return true;
        }

        return containsStoreBannerNoise(normalized)
                || APPROVAL_NUMBER_PATTERN.matcher(normalized).matches()
                || STORE_NOISE_PREFIX_PATTERN.matcher(normalized).matches();
    }

    private boolean containsStoreBannerNoise(String value) {
        if (!hasText(value)) {
            return false;
        }

        String normalized = normalizeLoose(value);
        return STORE_BANNER_NOISE_PHRASES.stream()
                .map(this::normalizeLoose)
                .anyMatch(normalized::contains);
    }

    private boolean containsMerchantHeaderArtifacts(String value) {
        if (!hasText(value)) {
            return false;
        }

        String normalized = normalizeLoose(value);
        boolean containsMerchantLabel = normalized.contains(normalizeLoose("\uAC00\uB9F9\uC810"))
                || normalized.contains(normalizeLoose("\uAC00\uB9F9\uC810\uBA85"))
                || normalized.contains(normalizeLoose("\uAC00\uB9F9\uC810\uC8FC\uC18C"))
                || normalized.contains(normalizeLoose("\uC2E0\uACE0\uC548\uB0B4"));

        if (containsMerchantLabel) {
            return true;
        }

        return STORE_BANNER_ASCII_TOKENS.stream()
                .map(this::normalizeLoose)
                .anyMatch(normalized::contains);
    }

    private boolean isOnlyGenericStoreTokens(String candidate) {
        String reduced = candidate == null ? "" : candidate.replaceAll("[\\s,./|]+", "");
        for (String generic : GENERIC_STORE_VALUES) {
            reduced = reduced.replace(generic, "");
        }
        return reduced.isBlank();
    }

    private List<FallbackStoreCandidate> extractFallbackStoreCandidates(String line) {
        if (!hasText(line) || containsStoreBannerNoise(line)) {
            return List.of();
        }

        List<FallbackStoreCandidate> candidates = new ArrayList<>();
        String[] rawSegments = line.split("[/|]");
        if (rawSegments.length > 1) {
            String primary = sanitizeStoreCandidate(rawSegments[0]);
            if (hasText(primary)) {
                candidates.add(new FallbackStoreCandidate(primary, "fallback 상호/대표자 분리 후보", 0.16));
            }

            if (rawSegments.length > 1) {
                String secondary = sanitizeStoreCandidate(rawSegments[1]);
                if (hasText(primary) && hasText(secondary) && !isLikelyOwnerName(secondary)) {
                    String combined = sanitizeStoreCandidate(primary + " " + secondary);
                    if (hasText(combined)) {
                        candidates.add(new FallbackStoreCandidate(combined, "fallback 분기명 결합 후보", 0.10));
                    }
                }
            }

            return candidates;
        }

        String candidate = sanitizeStoreCandidate(line);
        if (hasText(candidate)) {
            candidates.add(new FallbackStoreCandidate(candidate, "merchant header fallback 후보", 0.0));
        }
        return candidates;
    }

    private boolean isLikelyOwnerName(String value) {
        if (!hasText(value)) {
            return false;
        }

        String normalized = value.replaceAll("\\s+", "");
        if (!normalized.matches("[가-힣]{2,4}")) {
            return false;
        }

        return !containsAny(normalized, Set.of(
                "점", "식당", "카페", "커피", "치킨", "분식", "순대", "국밥", "김밥", "마트", "슈퍼", "스토어", "헤어", "네일"
        ));
    }

    private double fallbackBaseScore(int index) {
        if (index <= 3) {
            return 0.68;
        }
        if (index <= 8) {
            return 0.60;
        }
        if (index <= 16) {
            return 0.52;
        }
        return 0.44;
    }

    private boolean hasMerchantContext(List<String> lines, int index) {
        int start = Math.max(0, index - 2);
        int end = Math.min(lines.size() - 1, index + 2);
        for (int cursor = start; cursor <= end; cursor++) {
            if (cursor == index) {
                continue;
            }

            String line = lines.get(cursor);
            if (PHONE_PATTERN.matcher(line).find()
                    || BUSINESS_NUMBER_PATTERN.matcher(line).find()
                    || DATE_VALUE_PATTERN.matcher(line).find()
                    || isLikelyAddress(line)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasPriorStrongStoreLabel(List<String> lines, int index) {
        if (index <= 0) {
            return false;
        }

        String previousLine = lines.get(index - 1);
        Matcher matcher = STORE_LABEL_PATTERN.matcher(previousLine);
        if (!matcher.matches() || APPROVAL_NUMBER_PATTERN.matcher(previousLine).find()) {
            return false;
        }

        String previousCandidate = sanitizeStoreCandidate(matcher.group(2));
        return isValidStoreCandidate(previousCandidate);
    }

    private EnhancedParsedReceipt.CandidateValue buildDirectLlmStoreCandidate(
            EnhancedParsedReceipt.LlmFieldResult llmFieldResult,
            List<String> normalizedLines
    ) {
        if (!hasAcceptedConfidence(llmFieldResult) || !hasText(llmFieldResult.value())) {
            return null;
        }

        String candidateValue = sanitizeStoreCandidate(llmFieldResult.value());
        if (!isValidStoreCandidate(candidateValue)) {
            return null;
        }

        String evidenceLine = findEvidenceLineForValue(normalizedLines, candidateValue);
        if (!hasText(evidenceLine)) {
            return null;
        }

        return new EnhancedParsedReceipt.CandidateValue(
                candidateValue,
                evidenceLine,
                "LLM 직접 추출값을 OCR 원문에서 재검증",
                round(Math.max(0.83, llmFieldResult.confidence()))
        );
    }

    private String findEvidenceLineForValue(List<String> lines, String candidateValue) {
        if (!hasText(candidateValue)) {
            return null;
        }

        String normalizedCandidate = normalizeLoose(candidateValue);
        for (String line : lines) {
            if (!hasText(line) || isStoreNoiseLine(line)) {
                continue;
            }

            String normalizedLine = normalizeLoose(line);
            if (normalizedLine.contains(normalizedCandidate)) {
                return line;
            }

            for (FallbackStoreCandidate fallbackCandidate : extractFallbackStoreCandidates(line)) {
                String normalizedFallback = normalizeLoose(fallbackCandidate.value());
                if (normalizedFallback.equals(normalizedCandidate)
                        || normalizedFallback.contains(normalizedCandidate)
                        || normalizedCandidate.contains(normalizedFallback)) {
                    return line;
                }
            }
        }

        return null;
    }

    private boolean isLikelyAddress(String line) {
        if (!hasText(line)) {
            return false;
        }

        return containsAny(line, ADDRESS_REGION_KEYWORDS)
                && containsAny(line, ADDRESS_DETAIL_KEYWORDS)
                && !PHONE_PATTERN.matcher(line).find();
    }

    private boolean containsNegativeAmountLabel(String line) {
        return hasText(line) && NEGATIVE_AMOUNT_LABEL_PATTERN.matcher(line).find();
    }

    private String findPositiveAmountLabel(String line) {
        if (!hasText(line)) {
            return null;
        }

        Matcher matcher = POSITIVE_AMOUNT_LABEL_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<AmountToken> extractAmountTokens(String line) {
        if (!hasText(line) || isLikelyAddress(line)) {
            return List.of();
        }

        List<AmountToken> tokens = new ArrayList<>();
        Matcher matcher = AMOUNT_TOKEN_PATTERN.matcher(line);
        while (matcher.find()) {
            char next = matcher.end() < line.length() ? line.charAt(matcher.end()) : '\0';
            if (next == '호' || next == '층') {
                continue;
            }

            if (APPROVAL_NUMBER_PATTERN.matcher(line).find() && !line.contains("금액")) {
                continue;
            }

            String raw = matcher.group(1);
            int value = safeParseInteger(raw.replace(".", "").replace(",", ""));
            if (value < 100) {
                continue;
            }

            boolean hasCurrency = line.substring(matcher.start()).contains("원") || line.toUpperCase(Locale.ROOT).contains("KRW");
            boolean formatted = raw.contains(",") || raw.contains(".");
            tokens.add(new AmountToken(value, hasCurrency, formatted));
        }

        return tokens;
    }

    private Integer normalizeYear(String rawYear) {
        int year = Integer.parseInt(rawYear);
        if (rawYear.length() == 2) {
            return 2000 + year;
        }
        return year;
    }

    private String formatPaidAt(DateCandidate candidate) {
        if (candidate == null) {
            return null;
        }

        if (!hasText(candidate.time())) {
            return candidate.value();
        }

        String time = candidate.time().length() == 5 ? candidate.time() + ":00" : candidate.time();
        return candidate.value() + " " + time;
    }

    private String normalizeDateValue(String value) {
        if (!hasText(value)) {
            return "";
        }

        try {
            return LocalDate.parse(value.replace('/', '-').replace('.', '-')).toString();
        } catch (Exception exception) {
            return value.trim();
        }
    }

    private int safeParseInteger(String value) {
        if (!hasText(value)) {
            return 0;
        }

        try {
            return Integer.parseInt(digitsOnly(value));
        } catch (Exception exception) {
            return 0;
        }
    }

    private String formatAmount(int amount) {
        return INTEGER_FORMAT.format(amount);
    }

    private boolean containsMoney(String value) {
        return hasText(value) && AMOUNT_INLINE_PATTERN.matcher(value).find();
    }

    private String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private boolean containsAny(String value, Collection<String> keywords) {
        if (!hasText(value)) {
            return false;
        }

        return keywords.stream().anyMatch(value::contains);
    }

    private int earliestIndex(String value, Collection<String> tokens) {
        int earliest = -1;
        for (String token : tokens) {
            int index = value.indexOf(token);
            if (index >= 0 && (earliest == -1 || index < earliest)) {
                earliest = index;
            }
        }
        return earliest;
    }

    @SafeVarargs
    private final int earliestMatchIndex(String value, Pattern... patterns) {
        int earliest = -1;
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(value);
            if (matcher.find() && (earliest == -1 || matcher.start() < earliest)) {
                earliest = matcher.start();
            }
        }
        return earliest;
    }

    private String normalizeLoose(String value) {
        if (!hasText(value)) {
            return "";
        }

        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}]+", "");
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }

        String value = node.asText(null);
        return hasText(value) ? value.trim() : null;
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

    private double storeLabelScore(String label) {
        return switch (Objects.requireNonNullElse(label, "")) {
            case "가맹점명" -> 0.99;
            case "가맹점" -> 0.97;
            case "상호" -> 0.95;
            case "매장명" -> 0.94;
            case "사업장명" -> 0.93;
            case "업체명" -> 0.92;
            default -> 0.90;
        };
    }

    private void logPhase(int phase, String label, long startedAt) {
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        int progress = Math.min(100, (phase * 100) / TOTAL_PHASES);
        long estimatedTotalMillis = phase == 0 ? elapsedMillis : Math.round(elapsedMillis / ((double) phase / TOTAL_PHASES));
        long remainingMillis = Math.max(0L, estimatedTotalMillis - elapsedMillis);
        String eta = LocalTime.now().plusNanos(remainingMillis * 1_000_000L).format(DateTimeFormatter.ofPattern("HH:mm"));

        logger.info("""
                [PHASE %d/%d] %s
                진행률: %d%%
                경과 시간: %s
                예상 남은 시간: %s
                ETA: %s
                """.formatted(
                phase,
                TOTAL_PHASES,
                label,
                progress,
                formatDuration(elapsedMillis),
                formatDuration(remainingMillis),
                eta
        ));
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return "%02d:%02d:%02d".formatted(hours, minutes, seconds);
    }

    private record CandidateSet(
            List<EnhancedParsedReceipt.CandidateValue> storeNameCandidates,
            List<EnhancedParsedReceipt.CandidateValue> paidAmountCandidates,
            List<EnhancedParsedReceipt.CandidateValue> addressCandidates,
            List<DateCandidate> paidDateCandidates
    ) {
        private EnhancedParsedReceipt.Candidates toPublicCandidates() {
            return new EnhancedParsedReceipt.Candidates(
                    storeNameCandidates,
                    paidAmountCandidates,
                    addressCandidates,
                    paidDateCandidates.stream()
                            .map(candidate -> new EnhancedParsedReceipt.CandidateValue(
                                    candidate.value(),
                                    candidate.evidence(),
                                    candidate.reason(),
                                    candidate.score()
                            ))
                            .toList()
            );
        }

        private boolean isEmpty() {
            return storeNameCandidates.isEmpty()
                    && paidAmountCandidates.isEmpty()
                    && addressCandidates.isEmpty()
                    && paidDateCandidates.isEmpty();
        }
    }

    private record AmountToken(
            int value,
            boolean hasCurrencySymbol,
            boolean formatted
    ) {
    }

    private record FallbackStoreCandidate(
            String value,
            String reason,
            double scoreBonus
    ) {
    }

    private record DateCandidate(
            String value,
            String time,
            String evidence,
            String reason,
            double score
    ) {
    }

    private record ResolvedReceipt(
            String storeName,
            String address,
            Integer amount,
            String paidAt
    ) {
    }
}
