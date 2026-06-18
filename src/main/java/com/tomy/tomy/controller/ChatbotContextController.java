package com.tomy.tomy.controller;

import com.tomy.tomy.dto.ChatbotMeContextResponse;
import com.tomy.tomy.dto.ChatbotReceiptContextDto;
import com.tomy.tomy.dto.ChatbotRecentReceiptsResponse;
import com.tomy.tomy.dto.ChatbotStoreSearchResponse;
import com.tomy.tomy.dto.ErrorResponse;
import com.tomy.tomy.service.ChatbotContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chatbot/context")
@RequiredArgsConstructor
@Tag(name = "챗봇 Context API", description = "토미도우미 챗봇이 답변 생성에 사용하는 사용자 데이터 조회 API")
public class ChatbotContextController {

    private final ChatbotContextService chatbotContextService;

    /**
     * 현재 로그인 사용자의 포인트, 펫 레벨/EXP, 다음 레벨까지 필요 EXP 반환
     */
    @Operation(summary = "내 챗봇 컨텍스트 조회", description = "현재 포인트, 토미 레벨/EXP, 다음 레벨까지 필요 EXP를 반환합니다.")
    @GetMapping("/me")
    public ResponseEntity<?> getMyContext(@AuthenticationPrincipal UserDetails userDetails) {
        try {
            ChatbotMeContextResponse response = chatbotContextService.getMyContext(userDetails.getUsername());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * 최근 N건 영수증 인증 기록 (verificationStatus, failureReason, matchScore 포함)
     */
    @Operation(summary = "최근 영수증 목록 조회", description = "챗봇이 인증 실패 원인 안내에 사용하는 최근 영수증 목록입니다. limit 기본값 5, 최대 50.")
    @GetMapping("/receipts/recent")
    public ResponseEntity<?> getRecentReceipts(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "5") int limit) {
        try {
            ChatbotRecentReceiptsResponse response =
                    chatbotContextService.getRecentReceipts(userDetails.getUsername(), limit);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * 특정 영수증 상세 조회 — 본인 영수증만 접근 가능
     */
    @Operation(summary = "영수증 상세 조회", description = "특정 영수증의 인증 상태와 실패 사유를 조회합니다. 본인 영수증만 접근 가능합니다.")
    @GetMapping("/receipts/{receiptId}")
    public ResponseEntity<?> getReceiptDetail(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long receiptId) {
        try {
            ChatbotReceiptContextDto response =
                    chatbotContextService.getReceiptDetail(userDetails.getUsername(), receiptId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * 가맹점 검색 — keyword(이름/aliases), category(업종), district(동) 복합 필터
     */
    @Operation(summary = "가맹점 검색", description = "챗봇이 가맹점 안내에 사용하는 검색 API. keyword, category, district로 필터링 가능합니다.")
    @GetMapping("/stores/search")
    public ResponseEntity<?> searchStores(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String district,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            ChatbotStoreSearchResponse response =
                    chatbotContextService.searchStores(keyword, category, district, limit);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("가맹점 검색 중 오류가 발생했습니다."));
        }
    }
}
