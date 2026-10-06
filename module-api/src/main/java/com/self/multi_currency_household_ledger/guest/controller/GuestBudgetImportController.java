package com.self.multi_currency_household_ledger.guest.controller;

import com.self.multi_currency_household_ledger.common.annotation.CurrentMemberId;
import com.self.multi_currency_household_ledger.common.dto.ApiResponse;
import com.self.multi_currency_household_ledger.common.exception.ApiErrorCodes;
import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.guest.dto.GuestBudgetImportRequest;
import com.self.multi_currency_household_ledger.ledger.dto.GuestBudgetImportResponse;
import com.self.multi_currency_household_ledger.ledger.exception.BudgetErrorCode;
import com.self.multi_currency_household_ledger.ledger.service.BudgetService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/budgets")
public class GuestBudgetImportController {

    private final BudgetService budgetService;
    private final JwtDecoder jwtDecoder;

    @PostMapping("/import-from-guest")
    @ApiErrorCodes({"BUDGET_GUEST_TOKEN_INVALID", "CATEGORY_NOT_FOUND", "BUDGET_INVALID_ALLOCATION"})
    @Operation(
            description =
                    "로그인 전 비회원 계정의 예산을 지금 회원 계정으로 복사한다. 회원에게 이미 있는 달은 건너뛰므로 같은 요청을 다시 보내도 결과가 같다. 대응이 없는 사용자 카테고리 몫은 빼고 옮긴다(그 돈은 그 외 카테고리가 된다). guestAccessToken 이 만료·무효이거나 익명 계정이 아니거나 호출 회원 자신이면 403 BUDGET_GUEST_TOKEN_INVALID — 비회원 refresh token 으로 비회원 세션만 갱신해 다시 보낸다. 비회원 예산은 지우지 않는다.")
    public ApiResponse<GuestBudgetImportResponse> importFromGuest(
            @CurrentMemberId UUID memberId, @Valid @RequestBody GuestBudgetImportRequest request) {
        UUID guestId = verifiedGuestId(memberId, request.guestAccessToken());
        return ApiResponse.success(budgetService.importFromGuest(memberId, guestId, request.categoryIdMap()));
    }

    /** 비회원 토큰을 회원 토큰과 같은 디코더로 검증하고 익명 계정의 subject 를 돌려준다. 거절 사유 이름만 로그에 남긴다. */
    private UUID verifiedGuestId(UUID memberId, String guestAccessToken) {
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(guestAccessToken);
        } catch (JwtException e) {
            // 키 조회 실패·제한(RateLimitReachedException)도 앱의 대응은 같다 — 비회원 세션 갱신 후 재시도. 메시지는 싣지 않는다.
            throw rejected(e instanceof BadJwtException ? "DECODE_FAILED" : "DECODE_UNAVAILABLE");
        }
        if (!Boolean.TRUE.equals(jwt.getClaim("is_anonymous"))) {
            throw rejected("NOT_ANONYMOUS");
        }
        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw rejected("INVALID_SUBJECT");
        }
        UUID guestId;
        try {
            guestId = UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw rejected("INVALID_SUBJECT");
        }
        if (guestId.equals(memberId)) {
            throw rejected("SAME_MEMBER");
        }
        return guestId;
    }

    private static BusinessException rejected(String reason) {
        log.info("guest budget import rejected: {}", reason);
        return new BusinessException(BudgetErrorCode.BUDGET_GUEST_TOKEN_INVALID);
    }
}
