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

class ReceiptParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesHeaderStoreNameWithoutAppendingBusinessInfo() {
        OpenAiChatClient openAiChatClient = mock(OpenAiChatClient.class);
        when(openAiChatClient.completeJson(anyString(), anyString(), anyInt()))
                .thenReturn(new OpenAiJsonResponse(null, null, "OpenAI unavailable"));

        ReceiptParser parser = new ReceiptParser(openAiChatClient);
        EnhancedParsedReceipt parsedReceipt = parser.parse("""
                [영수증]
                돈수촌/ 216-06-12840/ 김다이
                경북 구미시 1공단로 192 (공단동), 57, 58호
                2025-04-1
                19:06
                합 계 금 액
                16.000
                """);

        assertThat(parsedReceipt.storeName()).isEqualTo("돈수촌");
        assertThat(parsedReceipt.address()).isEqualTo("경북 구미시 1공단로 192 (공단동), 57, 58호");
        assertThat(parsedReceipt.amount()).isEqualTo("16,000");
        assertThat(parsedReceipt.paidAt()).isEqualTo("2025-04-01 19:06:00");
        assertThat(parsedReceipt.raw().candidates().storeNameCandidates())
                .extracting(EnhancedParsedReceipt.CandidateValue::value)
                .contains("돈수촌");
    }

    @Test
    void prefersLargestAmountNearTotalLabelOverAddressRoomNumbers() throws Exception {
        OpenAiChatClient openAiChatClient = mock(OpenAiChatClient.class);
        when(openAiChatClient.completeJson(anyString(), anyString(), anyInt()))
                .thenReturn(new OpenAiJsonResponse(
                        objectMapper.readTree("""
                                {
                                  "store_name": "돈우리산동점",
                                  "payment_amount": 52500,
                                  "store_address": "경북 구미시 산동읍 신당1로 310 (신당리, 팁 스퀘어) 2층 203,204호",
                                  "payment_date": "2025-02-01"
                                }
                                """),
                        null,
                        null
                ));

        ReceiptParser parser = new ReceiptParser(openAiChatClient);
        EnhancedParsedReceipt parsedReceipt = parser.parse("""
                [영수증]
                돈우리산동점 / 684-22-01968/ 신상현
                경북 구미시 산동읍 신당1로 310 (신당리, 팁 스퀘어
                ) 2층 203,204호
                010 4140-9055/20250201-01-0004
                2025-02-01 21:25:40/12
                상품명
                생삼겹살 150g
                단가 수량 금액
                6,500 3
                19.500
                합계 금액
                52.500
                승인금액:
                4.768
                승인번호: 41900622
                52.500
                """);

        assertThat(parsedReceipt.storeName()).isEqualTo("돈우리산동점");
        assertThat(parsedReceipt.amount()).isEqualTo("52,500");
        assertThat(parsedReceipt.debug().largestNumberCandidates()).doesNotContain("203204");
        assertThat(parsedReceipt.raw().candidates().paidAmountCandidates())
                .extracting(EnhancedParsedReceipt.CandidateValue::value)
                .contains("52500");
    }

    @Test
    void keepsMerchantNameAsHanaSikdangWithoutAppendingRepresentativeName() throws Exception {
        OpenAiChatClient openAiChatClient = mock(OpenAiChatClient.class);
        when(openAiChatClient.completeJson(anyString(), anyString(), anyInt()))
                .thenReturn(new OpenAiJsonResponse(
                        objectMapper.readTree("""
                                {
                                  "store_name": "하나식당",
                                  "payment_amount": 8000,
                                  "store_address": "경북 구미시 신비로1길 4 1층 (신평동)",
                                  "payment_date": "2025-03-13"
                                }
                                """),
                        null,
                        null
                ));

        ReceiptParser parser = new ReceiptParser(openAiChatClient);
        EnhancedParsedReceipt parsedReceipt = parser.parse("""
                신용승인
                TID: 1003837746
                2025/03/
                13 M뱅크BC체크
                9440-31**-****-342*
                전표 No:250313546631
                비씨
                전자서명전표 일시불
                합 계
                승인번호
                7,272원
                728원
                8,000원
                6797
                가맹점명: 하나식당
                박순옥
                저화번호: 054-461-5048
                경북 구미시 신비로1길 4 1층 (신평동)
                *감사합니다*
                (고객용)
                가맹점 No: 738826821
                157-36-01357
                """);

        assertThat(parsedReceipt.storeName()).isEqualTo("하나식당");
        assertThat(parsedReceipt.address()).isEqualTo("경북 구미시 신비로1길 4 1층 (신평동)");
        assertThat(parsedReceipt.amount()).isEqualTo("8,000");
        assertThat(parsedReceipt.paidAt()).isEqualTo("2025-03-13");
        assertThat(parsedReceipt.raw().candidates().storeNameCandidates())
                .extracting(EnhancedParsedReceipt.CandidateValue::value)
                .contains("하나식당")
                .doesNotContain("박순옥");
    }

    @Test
    void ignoresNoticeBannerAndUsesActualMerchantLabel() throws Exception {
        OpenAiChatClient openAiChatClient = mock(OpenAiChatClient.class);
        when(openAiChatClient.completeJson(anyString(), anyString(), anyInt()))
                .thenReturn(new OpenAiJsonResponse(
                        objectMapper.readTree("""
                                {
                                  "store_name": "독도회세꼬시",
                                  "payment_amount": 96000,
                                  "store_address": "경상북도 구미시 신평동 70-387",
                                  "payment_date": null
                                }
                                """),
                        null,
                        null
                ));

        ReceiptParser parser = new ReceiptParser(openAiChatClient);
        EnhancedParsedReceipt parsedReceipt = parser.parse("""
                K
                가맹점명: 가맹점주소가 모두 실제와
                다른경우 신고안내(포상금 10만원 지급)
                KSNET 매출전표시문을 첨부하여 우편으로 접수
                케이에스넷
                IC신용승인
                가맹점: 독도회세꼬시
                주소: 경상북도 구미시 신평동 70-387
                (고객용)
                전표번호: 004686
                TEL:054-642-2264
                대표자: 유재길
                사업자: 513-11-55201
                합 계
                96,000
                """);

        assertThat(parsedReceipt.storeName()).isEqualTo("독도회세꼬시");
        assertThat(parsedReceipt.address()).isEqualTo("경상북도 구미시 신평동 70-387");
        assertThat(parsedReceipt.amount()).isEqualTo("96,000");
        assertThat(parsedReceipt.raw().candidates().storeNameCandidates())
                .extracting(EnhancedParsedReceipt.CandidateValue::value)
                .doesNotContain("가맹점주소가 모두 실제와");
    }

    @Test
    void extractsStoreNameAfterProcessorWarningBannerAndSplitsOwnerName() throws Exception {
        OpenAiChatClient openAiChatClient = mock(OpenAiChatClient.class);
        when(openAiChatClient.completeJson(anyString(), anyString(), anyInt()))
                .thenReturn(new OpenAiJsonResponse(
                        objectMapper.readTree("""
                                {
                                  "store_name": ", 가맹점",
                                  "payment_amount": 8000,
                                  "store_address": null,
                                  "payment_date": "2025-06-29"
                                }
                                """),
                        null,
                        null
                ));

        ReceiptParser parser = new ReceiptParser(openAiChatClient);
        EnhancedParsedReceipt parsedReceipt = parser.parse("""
                H-H-KCP
                엔에이치엔케이씨피 주식회사
                가맹점명, 가맹점 주소가 실제와 다를 경우
                신고안내 (포상금 10만원 지급)
                -매출전표 사본을 첨부하여 우편접수
                자세한 안내: www.crafia.or.kr/ 02-2011-0777
                수궁순대국밥/구연옥
                Tel.054-456-3050 / TID:***3867551 / 368-70-00364
                경북 구미시 원남로 100 원평동
                IC신용승인 0520
                967****965* 신한카드
                2025/06/29 16:23:52
                승인번호: 44912583
                0125409094
                일시불
                IC
                금액
                7,272원 세금
                728원
                합계
                8,000원
                [고객용]
                """);

        assertThat(parsedReceipt.storeName()).isEqualTo("수궁순대국밥");
        assertThat(parsedReceipt.address()).isEqualTo("경북 구미시 원남로 100 원평동");
        assertThat(parsedReceipt.amount()).isEqualTo("8,000");
        assertThat(parsedReceipt.paidAt()).isEqualTo("2025-06-29 16:23:52");
        assertThat(parsedReceipt.raw().candidates().storeNameCandidates())
                .extracting(EnhancedParsedReceipt.CandidateValue::value)
                .contains("수궁순대국밥")
                .doesNotContain(", 가맹점", "구연옥", "엔에이치엔케이씨피 주식회사");
    }

    @Test
    void acceptsValidatedLlmStoreNameEvenWhenNoExactCandidateMatches() throws Exception {
        OpenAiChatClient openAiChatClient = mock(OpenAiChatClient.class);
        when(openAiChatClient.completeJson(anyString(), anyString(), anyInt()))
                .thenReturn(new OpenAiJsonResponse(
                        objectMapper.readTree("""
                                {
                                  "store_name": "수궁순대국밥",
                                  "payment_amount": 8000,
                                  "store_address": "경북 구미시 원남로 100 원평동",
                                  "payment_date": "2025-06-29"
                                }
                                """),
                        null,
                        null
                ));

        ReceiptParser parser = new ReceiptParser(openAiChatClient);
        EnhancedParsedReceipt parsedReceipt = parser.parse("""
                가맹점명:
                수궁순대국밥(본점)
                경북 구미시 원남로 100 원평동
                2025/06/29
                합계
                8,000원
                """);

        assertThat(parsedReceipt.storeName()).isEqualTo("수궁순대국밥");
        assertThat(parsedReceipt.raw().llmResult().storeName().value()).isEqualTo("수궁순대국밥");
        assertThat(parsedReceipt.raw().candidates().storeNameCandidates())
                .extracting(EnhancedParsedReceipt.CandidateValue::value)
                .contains("수궁순대국밥(본점");
    }
}
