package com.tomy.tomy.controller;

import com.tomy.tomy.dto.*;
import com.tomy.tomy.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal; // New import
import org.springframework.security.core.userdetails.UserDetails; // New import
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
        try {
            authService.signup(request.getUserId(), request.getPassword(), request.getBirthday(), request.getNickname(), request.getGender(), request.getAllowNotification());
            return ResponseEntity.ok(new AuthResponse("회원가입 성공"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
        }
    }

    @Operation(summary = "아이디 중복 확인", description = "입력된 아이디의 사용 가능 여부를 확인합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "확인 성공",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(example = "{\"isTaken\": true}")))
    })
    @GetMapping("/check")
    public ResponseEntity<?> checkUserIdDuplication(
            @Parameter(description = "중복 확인할 아이디", required = true, example = "test1234")
            @RequestParam("userId") String userId) {
        boolean isTaken = authService.checkUserIdDuplication(userId);
        return ResponseEntity.ok(Map.of("isTaken", isTaken));
    }

    @PostMapping("/login")

    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            String accessToken = authService.login(request.getUserId(), request.getPassword());
            return ResponseEntity.ok(new LoginResponse(accessToken));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("아이디 또는 비밀번호가 올바르지 않습니다."));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("로그인 인증에 실패했습니다."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        // For JWT, logout typically involves client-side token invalidation or server-side blacklist
        // This is a placeholder for API spec compliance
        return ResponseEntity.ok(new AuthResponse("로그아웃 완료"));
    }

    @DeleteMapping("/withdraw")
    public ResponseEntity<?> withdraw(@AuthenticationPrincipal UserDetails userDetails, @RequestBody WithdrawRequest request) {
        try {
            String userId = userDetails.getUsername();
            authService.withdraw(userId, request);
            return ResponseEntity.ok(new AuthResponse("회원 탈퇴 완료"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/info/update")
    public ResponseEntity<?> updatePassword(@RequestBody UpdatePasswordRequest request) {
        try {
            if (!request.getPassword().equals(request.getPass_check())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("비밀번호와 비밀번호 확인이 일치하지 않습니다."));
            }
            authService.updatePassword(request.getUserId(), request.getPassword());
            return ResponseEntity.ok(new AuthResponse("비밀번호 변경 완료되었습니다."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/mypage")
    public ResponseEntity<?> getMyPage(@AuthenticationPrincipal UserDetails userDetails) {
        try {
            MyPageResponse myPageResponse = authService.getMyPageInfo(userDetails.getUsername());
            return ResponseEntity.ok(myPageResponse);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }
}
