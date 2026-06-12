package com.tomy.tomy.controller;

import com.tomy.tomy.domain.User;
import com.tomy.tomy.dto.ErrorResponse;
import com.tomy.tomy.dto.ReceiptAnalysisResponse;
import com.tomy.tomy.dto.ReceiptCheckResponse;
import com.tomy.tomy.dto.ReceiptUploadResponse;
import com.tomy.tomy.exception.DuplicateReceiptException;
import com.tomy.tomy.repository.UserRepository;
import com.tomy.tomy.security.JwtTokenProvider;
import com.tomy.tomy.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/receipt")
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptService receiptService;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadReceipt(@RequestHeader("Authorization") String authorizationHeader,
                                           @RequestParam("file") MultipartFile file) {
        try {
            if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Authorization header missing or invalid."));
            }
            String token = authorizationHeader.substring(7);
            if (!jwtTokenProvider.validateToken(token)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Invalid or expired token."));
            }

            String username = jwtTokenProvider.getUserIdFromJWT(token);
            Optional<User> userOptional = userRepository.findByUserId(username);
            if (userOptional.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("User not found for token."));
            }
            Long userId = userOptional.get().getId();

            ReceiptUploadResponse response = receiptService.uploadReceipt(userId, file);

            if ("소상공인 가게가 아닙니다".equals(response.getMessage())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            return ResponseEntity.ok(response);
        } catch (DuplicateReceiptException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(new ErrorResponse(resolveResponseStatusReason(e)));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("영수증 업로드 실패."));
        }
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> analyzeReceipt(@RequestHeader("Authorization") String authorizationHeader,
                                            @RequestParam("file") MultipartFile file) {
        try {
            if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Authorization header missing or invalid."));
            }
            String token = authorizationHeader.substring(7);
            if (!jwtTokenProvider.validateToken(token)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Invalid or expired token."));
            }

            String username = jwtTokenProvider.getUserIdFromJWT(token);
            Optional<User> userOptional = userRepository.findByUserId(username);
            if (userOptional.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("User not found for token."));
            }

            ReceiptAnalysisResponse response = receiptService.analyzeReceipt(file);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(new ErrorResponse(resolveResponseStatusReason(e)));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("영수증 분석 실패."));
        }
    }

    @GetMapping("/check")
    public ResponseEntity<?> getReceipts(@RequestHeader("Authorization") String authorizationHeader) {
        try {
            if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Authorization header missing or invalid."));
            }
            String token = authorizationHeader.substring(7);
            if (!jwtTokenProvider.validateToken(token)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Invalid or expired token."));
            }

            String username = jwtTokenProvider.getUserIdFromJWT(token);
            Optional<User> userOptional = userRepository.findByUserId(username);
            if (userOptional.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("User not found for token."));
            }
            Long userId = userOptional.get().getId();

            List<ReceiptCheckResponse> receipts = receiptService.getReceipts(userId);
            return ResponseEntity.ok(receipts);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("영수증 조회 실패."));
        }
    }

    private String resolveResponseStatusReason(ResponseStatusException e) {
        return e.getReason() == null || e.getReason().isBlank() ? e.getMessage() : e.getReason();
    }
}
