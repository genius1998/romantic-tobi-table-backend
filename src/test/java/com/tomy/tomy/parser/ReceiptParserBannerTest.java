package com.tomy.tomy.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tomy.tomy.dto.EnhancedParsedReceipt;
import com.tomy.tomy.openai.OpenAiChatClient;
import com.tomy.tomy.openai.OpenAiJsonResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReceiptParserBannerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsProcessorBannerStoreCandidateAndFallsBackToActualMerchantName() throws Exception {
        OpenAiChatClient openAiChatClient = mock(OpenAiChatClient.class);
        when(openAiChatClient.completeJson(anyString(), anyString(), anyInt()))
                .thenReturn(new OpenAiJsonResponse(
                        objectMapper.readTree("""
                                {
                                  "store_name": "Easy Check 가맹점명, 가맹점",
                                  "payment_amount": 7000,
                                  "store_address": "경상북도 구미시 원남로 84",
                                  "payment_date": "2017-04-11"
                                }
                                """),
                        null,
                        null
                ));

        ReceiptParser parser = new ReceiptParser(openAiChatClient);
        EnhancedParsedReceipt parsedReceipt = parser.parse("""
                Easy Check 가맹점명, 가맹점주소가 실제와 다른
                경우 신고안내 (포상금 10만원 지급)
                KICC 여신금융협회: 02-2011-0777
                도니요
                221-25-62760 TEL: 054)453-7922 문경일
                경상북도 구미시 원남로 84
                2017-04-11 14:27(화) POS:01 BILL:000004
                합
                계:
                7,000
                """);

        assertThat(parsedReceipt.storeName()).isEqualTo("도니요");
        assertThat(parsedReceipt.address()).isEqualTo("경상북도 구미시 원남로 84");
        assertThat(parsedReceipt.amount()).isEqualTo("7,000");
        assertThat(parsedReceipt.paidAt()).isEqualTo("2017-04-11 14:27:00");
        assertThat(parsedReceipt.raw().candidates().storeNameCandidates())
                .extracting(EnhancedParsedReceipt.CandidateValue::value)
                .contains("도니요")
                .doesNotContain("Easy Check 가맹점명, 가맹점");
    }
}
