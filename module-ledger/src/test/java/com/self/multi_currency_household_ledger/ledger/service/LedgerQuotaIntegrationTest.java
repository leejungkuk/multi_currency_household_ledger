package com.self.multi_currency_household_ledger.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.exchange.service.ExchangeRateService;
import com.self.multi_currency_household_ledger.ledger.TestJpaConfig;
import com.self.multi_currency_household_ledger.ledger.TestLedgerApplication;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.dto.CreateLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.dto.ImportLedgerEntriesRequest;
import com.self.multi_currency_household_ledger.ledger.dto.SyncLedgerEntryRequest;
import com.self.multi_currency_household_ledger.ledger.exception.LedgerErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원별 저장 행수 상한이 실제 DB 위에서 동작하는지 확인한다. 상한을 3으로 낮춰 경계를 싸게 재현한다.
 *
 * <p>이 방어선은 레이트 리밋과 축이 다르다 — 익명 가입이 무료라 공격자는 한도 안의 속도로 계속 쌓기만 하면 되고,
 * 디스크가 소진되면 전 회원이 함께 멈춘다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({
    TestLedgerApplication.class,
    TestJpaConfig.class,
    LedgerService.class,
    LedgerSyncInsertService.class,
    LedgerQuotaPolicy.class,
    LedgerQuotaIntegrationTest.ClockConfig.class
})
@TestPropertySource(properties = "ledger.quota.max-entries-per-member=3")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LedgerQuotaIntegrationTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 6);
    private static final long CATEGORY_ID = 1L;
    private static final long ASSET_ID = 3L;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private ExchangeRateService exchangeRateService;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
    }

    @Test
    @DisplayName("한도까지는 생성되고 그 다음 건은 403 LEDGER_QUOTA_EXCEEDED 로 거부된다")
    void create_beyond_quota_is_rejected() {
        for (int i = 0; i < 3; i++) {
            ledgerService.create(krwEntry(), MEMBER_ID);
        }

        assertThatThrownBy(() -> ledgerService.create(krwEntry(), MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", LedgerErrorCode.LEDGER_QUOTA_EXCEEDED.getCode());
        assertThat(ledgerEntryRepository.countByMemberId(MEMBER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("쿼터는 회원별로 격리된다 — 한 회원이 한도를 채워도 다른 회원은 영향받지 않는다")
    void quota_is_isolated_per_member() {
        for (int i = 0; i < 3; i++) {
            ledgerService.create(krwEntry(), MEMBER_ID);
        }

        assertThatCode(() -> ledgerService.create(krwEntry(), OTHER_MEMBER_ID)).doesNotThrowAnyException();
        assertThat(ledgerEntryRepository.countByMemberId(OTHER_MEMBER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("한도를 넘기는 import 는 한 행도 쓰지 않고 거부된다 — 부분 저장이 남으면 다음 요청이 더 쉽게 통과한다")
    void import_beyond_quota_writes_nothing() {
        ImportLedgerEntriesRequest request = new ImportLedgerEntriesRequest(List.of(
                importItem(UUID.randomUUID()),
                importItem(UUID.randomUUID()),
                importItem(UUID.randomUUID()),
                importItem(UUID.randomUUID())));

        assertThatThrownBy(() -> ledgerService.importEntries(request, MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", LedgerErrorCode.LEDGER_QUOTA_EXCEEDED.getCode());
        assertThat(ledgerEntryRepository.countByMemberId(MEMBER_ID)).isZero();
    }

    @Test
    @DisplayName("한도를 꽉 채운 뒤의 멱등 재import 는 거부되지 않는다 — 갱신분을 신규로 세면 재동기화가 막힌다")
    void idempotent_reimport_at_quota_limit_is_allowed() {
        ImportLedgerEntriesRequest request = new ImportLedgerEntriesRequest(
                List.of(importItem(UUID.randomUUID()), importItem(UUID.randomUUID()), importItem(UUID.randomUUID())));
        ledgerService.importEntries(request, MEMBER_ID);
        assertThat(ledgerEntryRepository.countByMemberId(MEMBER_ID)).isEqualTo(3);

        assertThatCode(() -> ledgerService.importEntries(request, MEMBER_ID)).doesNotThrowAnyException();
        assertThat(ledgerEntryRepository.countByMemberId(MEMBER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("한도를 채운 뒤 신규 clientEntryId sync 는 거부된다 — 오프라인 sync 는 iOS 의 상시 쓰기 경로다")
    void sync_of_new_entry_beyond_quota_is_rejected() {
        for (int i = 0; i < 3; i++) {
            ledgerService.create(krwEntry(), MEMBER_ID);
        }

        assertThatThrownBy(() -> ledgerService.sync(syncRequest(UUID.randomUUID()), MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", LedgerErrorCode.LEDGER_QUOTA_EXCEEDED.getCode());
        assertThat(ledgerEntryRepository.countByMemberId(MEMBER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("한도를 채웠어도 기존 clientEntryId sync(교체)는 통과한다 — 갱신은 행을 늘리지 않는다")
    void sync_replacing_existing_entry_at_quota_limit_is_allowed() {
        UUID clientEntryId = UUID.randomUUID();
        ledgerService.sync(syncRequest(clientEntryId), MEMBER_ID);
        ledgerService.create(krwEntry(), MEMBER_ID);
        ledgerService.create(krwEntry(), MEMBER_ID);

        assertThatCode(() -> ledgerService.sync(syncRequest(clientEntryId), MEMBER_ID))
                .doesNotThrowAnyException();
        assertThat(ledgerEntryRepository.countByMemberId(MEMBER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("신규분 계산은 member_id 로 격리된다 — 타 회원의 같은 clientEntryId 를 기존으로 세면 쿼터가 우회된다")
    void new_entry_count_does_not_leak_across_members() {
        UUID sharedClientEntryId = UUID.randomUUID();
        ledgerService.importEntries(
                new ImportLedgerEntriesRequest(List.of(importItem(sharedClientEntryId))), OTHER_MEMBER_ID);
        for (int i = 0; i < 3; i++) {
            ledgerService.create(krwEntry(), MEMBER_ID);
        }

        assertThatThrownBy(() -> ledgerService.importEntries(
                        new ImportLedgerEntriesRequest(List.of(importItem(sharedClientEntryId))), MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", LedgerErrorCode.LEDGER_QUOTA_EXCEEDED.getCode());
        assertThat(ledgerEntryRepository.countByMemberId(MEMBER_ID)).isEqualTo(3);
    }

    private static SyncLedgerEntryRequest syncRequest(UUID clientEntryId) {
        return new SyncLedgerEntryRequest(
                clientEntryId, new BigDecimal("5000.00"), CurrencyCode.KRW, CATEGORY_ID, ASSET_ID, TODAY, null);
    }

    private static CreateLedgerEntryRequest krwEntry() {
        return new CreateLedgerEntryRequest(
                new BigDecimal("5000.00"), CurrencyCode.KRW, CATEGORY_ID, ASSET_ID, TODAY, null);
    }

    private static ImportLedgerEntriesRequest.ImportLedgerEntryItem importItem(UUID clientEntryId) {
        return new ImportLedgerEntriesRequest.ImportLedgerEntryItem(
                clientEntryId, new BigDecimal("5000.00"), CurrencyCode.KRW, CATEGORY_ID, ASSET_ID, TODAY, null);
    }

    @TestConfiguration
    static class ClockConfig {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-04-05T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}
