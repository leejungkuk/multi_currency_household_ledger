package com.self.multi_currency_household_ledger.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.AuthUserFixture;
import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.domain.Asset;
import com.self.multi_currency_household_ledger.ledger.domain.AssetRepository;
import com.self.multi_currency_household_ledger.ledger.domain.Category;
import com.self.multi_currency_household_ledger.ledger.domain.CategoryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntry;
import com.self.multi_currency_household_ledger.ledger.domain.LedgerEntryRepository;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.member.repository.AuthUserRepository;
import com.self.multi_currency_household_ledger.member.service.AnonymousAccountCleanupService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 버려진 익명 계정 정리 배치의 <b>실동작</b> 검증. Mockito 의 SQL 문자열 단언은 {@code = true}/{@code = false}
 * 나 상관 술어의 의미를 검증하지 못하므로, 삭제 대상 판정은 여기서 실 Postgres 로 못 박는다.
 *
 * <p>시각 리터럴은 전부 {@code Clock.fixed} 에서 파생한 Java 값을 바인딩한다 — DB {@code now()} 를 쓰면
 * Testcontainers(UTC)와 JVM 기본 존 사이 스큐가 경계 판정을 뒤집는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key"
        })
class AnonymousAccountCleanupIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-08-23T19:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, KST);
    private static final int RETENTION_DAYS = 365;
    private static final int BATCH_LIMIT = 500;
    private static final int ZONE_SKEW_SECONDS = 3 * 3600;
    private static final Instant CUTOFF = NOW.minus(RETENTION_DAYS, ChronoUnit.DAYS);
    private static final Instant STALE = CUTOFF.minus(Duration.ofDays(10));
    private static final Instant FRESH = CUTOFF.plus(Duration.ofDays(10));

    private static final UUID MEMBER_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID MEMBER_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID MEMBER_C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID MEMBER_D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthUserRepository authUserRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private AssetRepository assetRepository;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    private AuthUserFixture authUsers;

    @BeforeEach
    void setUp() {
        authUsers = new AuthUserFixture(jdbcTemplate);
        authUsers.reset();
    }

    @Test
    @DisplayName("A1 모든 흔적이 보유기간 이전이면 익명 계정을 지우고 거래·커스텀 카테고리를 cascade로 회수한다")
    void deletes_abandoned_anonymous_account_and_cascades_its_rows() {
        abandonedAnonymous(MEMBER_A);
        insertLedgerEntry(MEMBER_A, STALE);
        insertCustomCategory(MEMBER_A, STALE);

        SimpleMeterRegistry registry = runCleanup(true, BATCH_LIMIT);

        assertThat(authUserCount(MEMBER_A)).isZero();
        assertThat(ledgerCount(MEMBER_A)).isZero();
        assertThat(customCategoryCount(MEMBER_A)).isZero();
        assertThat(counter(registry, "candidate")).isEqualTo(1.0);
        assertThat(counter(registry, "deleted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A2 보정 기간 안에 갱신된 거래가 있으면 보존한다")
    void preserves_account_with_a_recent_ledger_entry() {
        abandonedAnonymous(MEMBER_A);
        insertLedgerEntry(MEMBER_A, FRESH);

        SimpleMeterRegistry registry = runCleanup(true, BATCH_LIMIT);

        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
        assertThat(counter(registry, "candidate")).isZero();
        assertThat(counter(registry, "deleted")).isZero();
    }

    @Test
    @DisplayName("A3 최근 카테고리는 물론 created_at·updated_at이 둘 다 NULL인 카테고리를 가진 계정도 보존한다")
    void preserves_account_with_recent_or_timestampless_category() {
        abandonedAnonymous(MEMBER_A);
        insertCustomCategory(MEMBER_A, FRESH);
        abandonedAnonymous(MEMBER_B);
        insertCustomCategoryWithoutTimestamps(MEMBER_B);

        runCleanup(true, BATCH_LIMIT);

        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
        assertThat(authUserCount(MEMBER_B)).isEqualTo(1L);
    }

    @Test
    @DisplayName("A4 영구(가입) 계정은 모든 흔적이 아무리 오래돼도 삭제 대상이 아니다")
    void never_deletes_a_permanent_account_however_old_it_is() {
        authUsers.insertUser(MEMBER_A, false, STALE, STALE, STALE);
        insertLedgerEntry(MEMBER_A, STALE);
        // 같은 조건의 익명 계정을 함께 둬서 "쿼리가 아무것도 못 찾아 통과"하는 무의미한 그린을 막는다.
        abandonedAnonymous(MEMBER_B);

        SimpleMeterRegistry registry = runCleanup(true, BATCH_LIMIT);

        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
        assertThat(ledgerCount(MEMBER_A)).isEqualTo(1L);
        assertThat(authUserCount(MEMBER_B)).isZero();
        assertThat(counter(registry, "deleted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A5 상한을 넘는 후보는 상한까지만 지우고 나머지는 다음 주기로 남긴다")
    void deletes_only_up_to_the_batch_limit() {
        abandonedAnonymous(MEMBER_A);
        abandonedAnonymous(MEMBER_B);
        abandonedAnonymous(MEMBER_C);

        SimpleMeterRegistry registry = runCleanup(true, 2);

        assertThat(anonymousUserCount()).isEqualTo(1L);
        assertThat(counter(registry, "candidate")).isEqualTo(2.0);
        assertThat(counter(registry, "deleted")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("A6 관측 전용 모드는 후보 수를 관측하되 한 건도 삭제하지 않는다")
    void observation_mode_counts_candidates_but_deletes_nothing() {
        abandonedAnonymous(MEMBER_A);

        SimpleMeterRegistry registry = runCleanup(false, BATCH_LIMIT);

        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
        assertThat(counter(registry, "candidate")).isEqualTo(1.0);
        assertThat(counter(registry, "disabled")).isEqualTo(1.0);
        assertThat(counter(registry, "deleted")).isZero();
    }

    @Test
    @DisplayName("A11 최근 세션 활동(갱신된 updated_at·refresh 없는 created_at)이 있으면 보존한다")
    void preserves_account_with_recent_session_activity() {
        abandonedAnonymous(MEMBER_A);
        authUsers.insertSession(MEMBER_A, STALE, FRESH); // 토큰 refresh 로 updated_at 이 갱신된 세션
        abandonedAnonymous(MEMBER_B);
        authUsers.insertSession(MEMBER_B, FRESH, null); // refresh 이력이 없어 updated_at 이 NULL 인 세션
        abandonedAnonymous(MEMBER_C);
        authUsers.insertSession(MEMBER_C, STALE, STALE);

        runCleanup(true, BATCH_LIMIT);

        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
        assertThat(authUserCount(MEMBER_B)).isEqualTo(1L);
        assertThat(authUserCount(MEMBER_C)).isZero();
    }

    @Test
    @DisplayName("auth.users의 created_at·updated_at·last_sign_in_at 중 하나라도 최근이면 보존한다")
    void preserves_account_when_any_auth_timestamp_is_recent() {
        authUsers.insertUser(MEMBER_A, true, STALE, FRESH, null);
        authUsers.insertUser(MEMBER_B, true, STALE, STALE, FRESH);
        authUsers.insertUser(MEMBER_C, true, FRESH, STALE, null);
        abandonedAnonymous(MEMBER_D);

        runCleanup(true, BATCH_LIMIT);

        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
        assertThat(authUserCount(MEMBER_B)).isEqualTo(1L);
        assertThat(authUserCount(MEMBER_C)).isEqualTo(1L);
        assertThat(authUserCount(MEMBER_D)).isZero();
    }

    @Test
    @DisplayName("타 계정의 최근 거래·세션·카테고리는 이 계정의 후보 판정에 영향을 주지 않는다")
    void another_members_recent_activity_does_not_shield_an_abandoned_account() {
        abandonedAnonymous(MEMBER_A);
        abandonedAnonymous(MEMBER_B);
        insertLedgerEntry(MEMBER_B, FRESH);
        insertCustomCategory(MEMBER_B, FRESH);
        authUsers.insertSession(MEMBER_B, FRESH, FRESH);

        SimpleMeterRegistry registry = runCleanup(true, BATCH_LIMIT);

        // 상관 술어(s.user_id = u.id · e.member_id = u.id · c.owner_member_id = u.id)가 빠지면
        // 이웃 B 의 활동이 A 까지 가려 매일 0건만 지우는 무음 실패가 된다.
        assertThat(authUserCount(MEMBER_A)).isZero();
        assertThat(authUserCount(MEMBER_B)).isEqualTo(1L);
        assertThat(counter(registry, "candidate")).isEqualTo(1.0);
        assertThat(counter(registry, "deleted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("삭제 술어가 후보 조회와 같아 그 사이 활동한 계정은 0행으로 보호된다")
    void delete_predicate_protects_an_account_that_became_active_after_selection() {
        abandonedAnonymous(MEMBER_A);

        List<UUID> candidates = authUserRepository.findAbandonedAnonymousIds(CUTOFF, cutoffLocal(), BATCH_LIMIT);
        assertThat(candidates).containsExactly(MEMBER_A);

        insertLedgerEntry(MEMBER_A, FRESH);
        Integer deletedRows = new TransactionTemplate(transactionManager)
                .execute(status -> authUserRepository.deleteAbandonedAnonymousUser(MEMBER_A, CUTOFF, cutoffLocal()));

        assertThat(deletedRows).isZero();
        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
    }

    @Test
    @DisplayName("naive 감사 컬럼의 cutoff는 JPA Auditing이 기록한 존과 같은 존에서 비교된다")
    void naive_cutoff_is_compared_in_the_zone_jpa_auditing_writes_in() {
        // 이 배치에서 가장 미묘한 결정은 :cutoffLocal 을 clock.getZone() 이 아니라 ZoneId.systemDefault() 로
        // 파생하는 것이다(auditing 이 커스텀 DateTimeProvider 없이 JVM 기본 존으로 LocalDateTime 을 쓰기 때문).
        // 리터럴을 raw JDBC 로 넣는 픽스처는 그 파생식을 그대로 미러할 뿐이라 결정을 보호하지 못한다. 그래서
        // (1) updated_at 을 실제 저장 경로(JPA)로 만들고 (2) cutoff 를 그 시각 직전에 놓고 (3) Clock 존을 JVM
        // 기본 존과 어긋나게 준다 — clock.getZone() 으로 파생하면 cutoffLocal 이 오프셋만큼 밀려 활동이 있는
        // 이 계정까지 후보로 잡힌다.
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
        Instant stale = cutoff.minus(Duration.ofDays(10));
        authUsers.insertUser(MEMBER_A, true, stale, stale, null);
        // 대조군 — 배치가 실제로 삭제까지 돌았음을 보장한다(전부 보존돼 통과하는 무의미한 그린 방지).
        authUsers.insertUser(MEMBER_B, true, stale, stale, null);
        saveLedgerEntryThroughAuditing(MEMBER_A);

        runCleanup(clockWithCutoffAt(cutoff), true, BATCH_LIMIT);

        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
        assertThat(authUserCount(MEMBER_B)).isZero();
    }

    /** 감사 컬럼을 리터럴로 넣지 않고 JPA Auditing 이 쓰게 한다 — cutoff 비교에 실제 기록 경로를 태운다. */
    private void saveLedgerEntryThroughAuditing(UUID memberId) {
        Category category = categoryRepository
                .findByOwnerMemberIdIsNullAndTransactionTypeAndIsActiveTrueOrderBySortOrder(TransactionType.EXPENSE)
                .getFirst();
        Asset asset = assetRepository.findByIsActiveTrueOrderBySortOrder().getFirst();
        ledgerEntryRepository.save(LedgerEntry.of(
                memberId,
                category,
                asset,
                new BigDecimal("1000.00"),
                CurrencyCode.KRW,
                LocalDate.of(2025, 1, 1),
                null,
                null,
                CLOCK));
    }

    /**
     * cutoff 를 {@code cutoff} 에 놓되 존은 JVM 기본 존과 {@link #ZONE_SKEW_SECONDS} 만큼 어긋나게 준다.
     * 두 존이 같으면 {@code ZoneId.systemDefault()} 와 {@code clock.getZone()} 의 차이가 드러나지 않는다.
     */
    private static Clock clockWithCutoffAt(Instant cutoff) {
        ZoneOffset systemOffset = ZoneId.systemDefault().getRules().getOffset(cutoff);
        ZoneOffset skewed = ZoneOffset.ofTotalSeconds(systemOffset.getTotalSeconds() + ZONE_SKEW_SECONDS);
        return Clock.fixed(cutoff.plus(Duration.ofDays(RETENTION_DAYS)), skewed);
    }

    private void abandonedAnonymous(UUID memberId) {
        authUsers.insertUser(memberId, true, STALE, STALE, null);
    }

    private SimpleMeterRegistry runCleanup(boolean enabled, int batchLimit) {
        return runCleanup(CLOCK, enabled, batchLimit);
    }

    private SimpleMeterRegistry runCleanup(Clock clock, boolean enabled, int batchLimit) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AnonymousAccountCleanupService(
                        authUserRepository, registry, transactionManager, clock, enabled, RETENTION_DAYS, batchLimit)
                .cleanupAbandonedAnonymousAccounts();
        return registry;
    }

    private void insertLedgerEntry(UUID memberId, Instant at) {
        jdbcTemplate.update(
                """
                insert into ledger_entry (
                    member_id, transaction_type, category_id, asset_id, original_amount,
                    currency_code, applied_rate, krw_amount, transaction_date, created_at, updated_at
                )
                values (?, 'EXPENSE', 1, 3, 1000.00, 'KRW', 1, 1000.00, ?, ?, ?)
                """,
                memberId,
                LocalDate.of(2025, 1, 1),
                local(at),
                local(at));
    }

    private void insertCustomCategory(UUID ownerMemberId, Instant at) {
        jdbcTemplate.update(
                """
                insert into category (
                    transaction_type, code, display_name_ko, display_name_en, icon,
                    sort_order, owner_member_id, is_active, created_at, updated_at
                )
                values ('EXPENSE', 'CUSTOM', '커스텀', 'Custom', null, 1000, ?, true, ?, ?)
                """,
                ownerMemberId,
                local(at),
                local(at));
    }

    private void insertCustomCategoryWithoutTimestamps(UUID ownerMemberId) {
        jdbcTemplate.update(
                """
                insert into category (
                    transaction_type, code, display_name_ko, display_name_en, icon,
                    sort_order, owner_member_id, is_active
                )
                values ('EXPENSE', 'CUSTOM', '커스텀', 'Custom', null, 1000, ?, true)
                """,
                ownerMemberId);
    }

    /** naive {@code timestamp(6)} 감사 컬럼은 JVM 기본 존으로 기록되므로 서비스의 cutoffLocal 과 같은 변환을 쓴다. */
    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private static LocalDateTime cutoffLocal() {
        return local(CUTOFF);
    }

    private static double counter(SimpleMeterRegistry registry, String result) {
        var counter = registry.find("woni.member.anonymous_cleanup")
                .tag("result", result)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long authUserCount(UUID memberId) {
        Long count = jdbcTemplate.queryForObject("select count(*) from auth.users where id = ?", Long.class, memberId);
        return count == null ? 0L : count;
    }

    private long anonymousUserCount() {
        Long count = jdbcTemplate.queryForObject("select count(*) from auth.users where is_anonymous", Long.class);
        return count == null ? 0L : count;
    }

    private long ledgerCount(UUID memberId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from ledger_entry where member_id = ?", Long.class, memberId);
        return count == null ? 0L : count;
    }

    private long customCategoryCount(UUID memberId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from category where owner_member_id = ?", Long.class, memberId);
        return count == null ? 0L : count;
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:16-alpine").withInitScript("testcontainers/auth-users-stub.sql");
        }
    }
}
