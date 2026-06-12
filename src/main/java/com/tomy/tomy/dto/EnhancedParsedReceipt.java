package com.tomy.tomy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnhancedParsedReceipt(
        @JsonProperty("store_name") String storeName,
        String address,
        String amount,
        @JsonProperty("paid_at") String paidAt,
        DebugInfo debug,
        RawDebug raw
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DebugInfo(
            @JsonProperty("largest_number_candidates") List<String> largestNumberCandidates,
            @JsonProperty("datetime_candidates") List<String> datetimeCandidates,
            @JsonProperty("chatgpt_reason") String chatgptReason,
            @JsonProperty("normalized_lines") List<String> normalizedLines
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RawDebug(
            @JsonProperty("llm_result") LlmResult llmResult,
            Candidates candidates,
            @JsonProperty("ocr_text") String ocrText
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Candidates(
            @JsonProperty("store_name_candidates") List<CandidateValue> storeNameCandidates,
            @JsonProperty("paid_amount_candidates") List<CandidateValue> paidAmountCandidates,
            @JsonProperty("address_candidates") List<CandidateValue> addressCandidates,
            @JsonProperty("paid_date_candidates") List<CandidateValue> paidDateCandidates
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CandidateValue(
            String value,
            String evidence,
            String reason,
            double score
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LlmResult(
            @JsonProperty("store_name") LlmFieldResult storeName,
            @JsonProperty("paid_amount") LlmFieldResult paidAmount,
            @JsonProperty("address") LlmFieldResult address,
            @JsonProperty("paid_date") LlmFieldResult paidDate,
            String error
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LlmFieldResult(
            String value,
            double confidence,
            String evidence,
            String reason
    ) {
    }
}
