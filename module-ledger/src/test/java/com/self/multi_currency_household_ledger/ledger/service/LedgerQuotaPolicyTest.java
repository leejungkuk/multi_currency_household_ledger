package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class LedgerQuotaPolicyTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int MAX_ENTRIES = 100;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    @DisplayName("한도 안이면 통과한다")
    void within_quota_passes() {
        given(ledgerEntryRepository.countByMemberId(MEMBER_ID)).willReturn(98L);

        assertThatCode(() -> policy().assertCanCreate(MEMBER_ID, 2)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한도를 정확히 채우는 것은 허용하고 한 건 더는 거부한다 — 경계 off-by-one 고정")
    void quota_boundary_is_inclusive() {
        given(ledgerEntryRepository.countByMemberId(MEMBER_ID)).willReturn((long) MAX_ENTRIES - 1);

        assertThatCode(() -> policy().assertCanCreate(MEMBER_ID, 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy().assertCanCreate(MEMBER_ID, 2))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", LedgerErrorCode.LEDGER_QUOTA_EXCEEDED.getCode());
    }

    @Test
    @DisplayName("한도를 넘기는 대량 생성은 거부한다 — 익명 가입이 무료라 레이트 리밋으로는 막지 못하는 축이다")
    void bulk_creation_beyond_quota_is_rejected() {
        given(ledgerEntryRepository.countByMemberId(MEMBER_ID)).willReturn(50L);

        assertThatThrownBy(() -> policy().assertCanCreate(MEMBER_ID, 51))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", LedgerErrorCode.LEDGER_QUOTA_EXCEEDED.getCode());
    }

    @Test
    @DisplayName("이미 한도를 넘긴 회원은 한 건도 더 만들 수 없다")
    void member_over_quota_cannot_create_anything() {
        given(ledgerEntryRepository.countByMemberId(MEMBER_ID)).willReturn((long) MAX_ENTRIES + 10);

        assertThatThrownBy(() -> policy().assertCanCreate(MEMBER_ID, 1)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("쿼터 초과는 403 — 재시도해도 달라지지 않고 회원이 행을 지워야 해소되므로 429 가 아니다")
    void quota_exceeded_is_forbidden() {
        assertThat(LedgerErrorCode.LEDGER_QUOTA_EXCEEDED.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("한도 설정이 0 이하면 기동에 실패한다 — 오타 하나로 전 회원의 쓰기가 막히는 것을 조기에 드러낸다")
    void non_positive_quota_fails_fast() {
        assertThatThrownBy(() -> new LedgerQuotaPolicy(ledgerEntryRepository, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LedgerQuotaPolicy policy() {
        return new LedgerQuotaPolicy(ledgerEntryRepository, MAX_ENTRIES);
    }
}
