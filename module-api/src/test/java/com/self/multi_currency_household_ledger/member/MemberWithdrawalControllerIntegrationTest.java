package com.self.multi_currency_household_ledger.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.self.multi_currency_household_ledger.AuthUserFixture;
import com.self.multi_currency_household_ledger.member.client.AppleTokenRevoker;
import com.self.multi_currency_household_ledger.member.client.RevokeOutcome;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key"
        })
class MemberWithdrawalControllerIntegrationTest {

    private static final UUID MEMBER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String APPLE_AUTHORIZATION_CODE = "apple-authorization-code";
    private static final String MEMBER_B_CLAIMED_APPLE_CODE = "claimed-member-b-authorization-code";
    private static final String CREATE_LEDGER_REQUEST =
            """
            {
              "amount": 1000.00,
              "currencyCode": "KRW",
              "categoryId": 1,
              "assetId": 3,
              "transactionDate": "2026-04-06",
              "memo": "탈퇴 테스트"
            }
            """;
    private static final String IMPORT_LEDGER_REQUEST =
            """
            {
              "entries": [
                {
                  "clientEntryId": "20000000-0000-0000-0000-000000000001",
                  "amount": 1000.00,
                  "currencyCode": "KRW",
                  "categoryId": 1,
                  "assetId": 3,
                  "transactionDate": "2026-04-06",
                  "memo": "탈퇴 후 import"
                }
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AppleTokenRevoker appleTokenRevoker;

    @BeforeEach
    void setUp() {
        new AuthUserFixture(jdbcTemplate).reset(MEMBER_A, MEMBER_B);
        given(appleTokenRevoker.revoke(nullable(String.class))).willReturn(RevokeOutcome.SKIPPED);
    }

    @Test
    @DisplayName("DELETE /api/v1/members/me는 현재 auth.users와 그 회원 거래를 함께 삭제한다")
    void withdraw_deletes_auth_user_and_cascades_ledger_entries() throws Exception {
        createLedger(MEMBER_A);

        withdraw(MEMBER_A)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(authUserCount(MEMBER_A)).isZero();
        assertThat(ledgerCount(MEMBER_A)).isZero();
        then(appleTokenRevoker).should().revoke(null);
    }

    @Test
    @DisplayName("회원 A 토큰으로 탈퇴해도 회원 B 거래와 계정은 삭제되지 않는다")
    void withdraw_isolated_by_jwt_subject_and_preserves_other_member_data() throws Exception {
        createLedger(MEMBER_A);
        createLedger(MEMBER_B);

        withdraw(MEMBER_A).andExpect(status().isOk());

        assertThat(authUserCount(MEMBER_A)).isZero();
        assertThat(ledgerCount(MEMBER_A)).isZero();
        assertThat(authUserCount(MEMBER_B)).isEqualTo(1L);
        assertThat(ledgerCount(MEMBER_B)).isEqualTo(1L);
    }

    @Test
    @DisplayName("회원 A 토큰에 회원 B의 것이라고 주장하는 코드를 붙여도 A 데이터만 삭제한다")
    void withdraw_isolated_by_jwt_subject_even_with_code_claimed_for_another_member() throws Exception {
        createLedger(MEMBER_A);
        createLedger(MEMBER_B);

        withdraw(MEMBER_A, MEMBER_B_CLAIMED_APPLE_CODE).andExpect(status().isOk());

        assertThat(authUserCount(MEMBER_A)).isZero();
        assertThat(ledgerCount(MEMBER_A)).isZero();
        assertThat(authUserCount(MEMBER_B)).isEqualTo(1L);
        assertThat(ledgerCount(MEMBER_B)).isEqualTo(1L);
        then(appleTokenRevoker).should().revoke(MEMBER_B_CLAIMED_APPLE_CODE);
        // 이 IDOR 머지 게이트는 JWT subject 기준 DB 삭제 격리를 검증한다.
        // Apple 쪽 revoke 대상의 코드 소유권 검증은 의도적으로 이 단언의 범위 밖이다.
    }

    @Test
    @DisplayName("빈 문자열 코드는 application/json 본문에서 가공 없이 revoker로 전달한다")
    void withdraw_forwards_empty_apple_authorization_code() throws Exception {
        withdraw(MEMBER_A, "").andExpect(status().isOk());

        assertThat(authUserCount(MEMBER_A)).isZero();
        then(appleTokenRevoker).should().revoke("");
    }

    @Test
    @DisplayName("Apple authorization code를 application/json 본문에서 revoker로 전달한다")
    void withdraw_forwards_apple_authorization_code() throws Exception {
        withdraw(MEMBER_A, APPLE_AUTHORIZATION_CODE).andExpect(status().isOk());

        assertThat(authUserCount(MEMBER_A)).isZero();
        then(appleTokenRevoker).should().revoke(APPLE_AUTHORIZATION_CODE);
    }

    @Test
    @DisplayName("Apple revoke가 FAILED여도 200을 반환하고 계정 삭제를 유지한다")
    void withdraw_keeps_deletion_when_apple_revoke_fails() throws Exception {
        given(appleTokenRevoker.revoke(APPLE_AUTHORIZATION_CODE)).willReturn(RevokeOutcome.FAILED);

        withdraw(MEMBER_A, APPLE_AUTHORIZATION_CODE).andExpect(status().isOk());

        assertThat(authUserCount(MEMBER_A)).isZero();
        then(appleTokenRevoker).should().revoke(APPLE_AUTHORIZATION_CODE);
    }

    @Test
    @DisplayName("Apple revoke는 삭제 트랜잭션 커밋 뒤 이미 삭제된 행을 관측하며 실행된다")
    void withdraw_revokes_after_delete_transaction_commits() throws Exception {
        given(appleTokenRevoker.revoke(APPLE_AUTHORIZATION_CODE)).willAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            assertThat(authUserCount(MEMBER_A)).isZero();
            return RevokeOutcome.REVOKED;
        });

        withdraw(MEMBER_A, APPLE_AUTHORIZATION_CODE).andExpect(status().isOk());

        then(appleTokenRevoker).should().revoke(APPLE_AUTHORIZATION_CODE);
    }

    @Test
    @DisplayName("513자 Apple authorization code는 400 ErrorResponse이며 계정을 삭제하지 않는다")
    void withdraw_rejects_apple_authorization_code_over_512_characters() throws Exception {
        String request = "{\"appleAuthorizationCode\":\"" + "a".repeat(513) + "\"}";

        mockMvc.perform(delete("/api/v1/members/me")
                        .with(memberJwt(MEMBER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
        then(appleTokenRevoker).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("빈 application/json 본문은 본문 없음으로 취급해 null을 전달한다")
    void withdraw_with_empty_json_body_passes_null_code() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me")
                        .with(memberJwt(MEMBER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isOk());

        assertThat(authUserCount(MEMBER_A)).isZero();
        then(appleTokenRevoker).should().revoke(null);
    }

    @Test
    @DisplayName("Content-Type 없는 JSON 본문은 관측된 Spring 동작대로 본문 없음으로 취급한다")
    void withdraw_with_body_but_without_content_type_passes_null_code() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me")
                        .with(memberJwt(MEMBER_A))
                        .content(withdrawalRequest(APPLE_AUTHORIZATION_CODE)))
                .andExpect(status().isOk());

        assertThat(authUserCount(MEMBER_A)).isZero();
        then(appleTokenRevoker).should().revoke(null);
    }

    @Test
    @DisplayName("코드 포함 탈퇴 요청 전 구간은 member_id와 authorizationCode를 로그에 남기지 않는다")
    void withdraw_does_not_log_member_id_or_apple_authorization_code() throws Exception {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
        try {
            withdraw(MEMBER_A, APPLE_AUTHORIZATION_CODE).andExpect(status().isOk());
        } finally {
            rootLogger.detachAppender(appender);
        }

        assertThat(appender.list)
                .extracting(MemberWithdrawalControllerIntegrationTest::logText)
                .noneMatch(
                        message -> message.contains(MEMBER_A.toString()) || message.contains(APPLE_AUTHORIZATION_CODE));
    }

    @Test
    @DisplayName("같은 잔여 토큰으로 탈퇴를 재호출해도 두 요청 모두 200이다")
    void withdraw_is_idempotent_for_repeated_requests() throws Exception {
        withdraw(MEMBER_A).andExpect(status().isOk());
        withdraw(MEMBER_A).andExpect(status().isOk());

        assertThat(authUserCount(MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("토큰 없는 탈퇴 요청은 deny-by-default에 의해 401이다")
    void withdraw_requires_authentication() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me")).andExpect(status().isUnauthorized());

        assertThat(authUserCount(MEMBER_A)).isEqualTo(1L);
    }

    @Test
    @DisplayName("탈퇴 후 잔여 토큰으로 거래를 쓰면 401이고 행이 되살아나지 않는다")
    void deleted_member_cannot_recreate_ledger_entry_with_remaining_token() throws Exception {
        withdraw(MEMBER_A).andExpect(status().isOk());

        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
        try {
            mockMvc.perform(post("/api/v1/ledgers")
                            .with(memberJwt(MEMBER_A))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_LEDGER_REQUEST))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

            // 플랜이 "핵심 보정"으로 지목한 경로. import 는 DataIntegrityViolationException 을 무조건
            // 409 로 바꾸던 catch 가 있어, 보정이 없으면 여기만 401 이 아니라 409 로 새어 나간다.
            mockMvc.perform(post("/api/v1/ledgers/import")
                            .with(memberJwt(MEMBER_A))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(IMPORT_LEDGER_REQUEST))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        } finally {
            rootLogger.detachAppender(appender);
        }

        // 파기하겠다고 약속한 UUID 를 파기 실패 로그가 붙잡으면 안 된다(Loki 14일 잔존 = 파기 의무 저촉).
        // 핸들러만 침묵시키면 늦는다 — Hibernate 가 예외를 던지기 전에 제약 위반 Detail(위반 키 값 포함)을
        // 먼저 기록하므로, 이 단언은 애플리케이션 로거 전체를 본다.
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(MEMBER_A.toString()));

        assertThat(authUserCount(MEMBER_A)).isZero();
        assertThat(ledgerCount(MEMBER_A)).isZero();
    }

    @Test
    @DisplayName("탈퇴와 거래 쓰기가 동시에 실행되어도 최종적으로 삭제 회원의 거래는 0건이다")
    void concurrent_withdrawal_and_write_converge_to_no_child_rows() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MvcResult> write = executor.submit(() -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(post("/api/v1/ledgers")
                                .with(memberJwt(MEMBER_A))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(CREATE_LEDGER_REQUEST))
                        .andReturn();
            });
            Future<MvcResult> withdrawal = executor.submit(() -> {
                ready.countDown();
                start.await();
                return withdraw(MEMBER_A).andReturn();
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int writeStatus = write.get(10, TimeUnit.SECONDS).getResponse().getStatus();
            int withdrawalStatus =
                    withdrawal.get(10, TimeUnit.SECONDS).getResponse().getStatus();

            assertThat(writeStatus).isIn(200, 401);
            assertThat(withdrawalStatus).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }

        assertThat(authUserCount(MEMBER_A)).isZero();
        assertThat(ledgerCount(MEMBER_A)).isZero();
    }

    private void createLedger(UUID memberId) throws Exception {
        mockMvc.perform(post("/api/v1/ledgers")
                        .with(memberJwt(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_LEDGER_REQUEST))
                .andExpect(status().isOk());
    }

    private ResultActions withdraw(UUID memberId) throws Exception {
        return mockMvc.perform(delete("/api/v1/members/me").with(memberJwt(memberId)));
    }

    private ResultActions withdraw(UUID memberId, String appleAuthorizationCode) throws Exception {
        return mockMvc.perform(delete("/api/v1/members/me")
                .with(memberJwt(memberId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(withdrawalRequest(appleAuthorizationCode)));
    }

    private static String withdrawalRequest(String appleAuthorizationCode) {
        return "{\"appleAuthorizationCode\":\"" + appleAuthorizationCode + "\"}";
    }

    private static String logText(ILoggingEvent event) {
        String throwable =
                event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy());
        return event.getFormattedMessage() + throwable;
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .JwtRequestPostProcessor
            memberJwt(UUID memberId) {
        return jwt().jwt(token -> token.subject(memberId.toString()).audience(List.of("authenticated")));
    }

    private long authUserCount(UUID memberId) {
        Long count = jdbcTemplate.queryForObject("select count(*) from auth.users where id = ?", Long.class, memberId);
        return count == null ? 0L : count;
    }

    private long ledgerCount(UUID memberId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from ledger_entry where member_id = ?", Long.class, memberId);
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
