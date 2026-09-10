package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Supabase Data API(PostgREST) 가 다시 켜져도 public 스키마가 열리지 않는다는 것을 <b>DB 카탈로그</b>로 고정한다.
 *
 * <p>Data API 는 대시보드 토글 하나로 꺼 뒀다(2026-09-10). 토글은 코드 밖이라 되돌리는 순간 아무 게이트도 없이 원상복구되고,
 * 그때 iOS 에 박힌 anon 키 하나로 전 회원 거래가 읽히고 지워진다 — 운영 실측으로 {@code anon} 이 {@code ledger_entry} 에
 * SELECT~TRUNCATE 전권을 갖고 있었다. 그래서 V12 가 DB 레벨에서 잠그고, 이 테스트가 그 잠금이 <b>모든</b> public 테이블에
 * 걸려 있는지 본다. 새 테이블을 RLS 없이 추가하면 여기서 빨개진다 — 그 테이블도 마이그레이션에서 켜는 것이 답이다.
 *
 * <p>앱은 테이블 소유자({@code postgres}) 로 붙으므로 정책 없는 RLS 에 영향을 받지 않는다. 단 {@code FORCE ROW LEVEL SECURITY}
 * 는 소유자에게도 적용돼 정책이 없으면 모든 조회가 <b>조용히 0행</b>이 되므로 별도로 막는다. 앱 롤을 분리하는 날(BACKLOG) 은 그
 * 롤용 정책이 같이 들어가야 하고, 그 전제도 이 테스트가 깨지는 것으로 드러난다.
 *
 * <p>권한 회수는 Testcontainers 의 일반 Postgres 에 API 롤이 없어 그냥 두면 검증되지 않는다 — {@code auth-users-stub.sql} 이
 * Supabase 의 롤·기본 권한을 흉내 내서 회수 경로가 실제로 돌게 한다.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.supabase.co/auth/v1",
            "exchange.eximbank.api-key=test-api-key"
        })
class PublicSchemaLockdownIntegrationTest {

    private static final String API_ROLES = "('anon', 'authenticated', 'service_role')";

    /**
     * Flyway 이력 테이블은 마이그레이션 안에서 ALTER 할 수 없다 — Flyway 가 이력용·마이그레이션용 연결을 따로 쓰고 이력 연결이
     * 트랜잭션 안에서 AccessShareLock 을 쥔 채 기다리므로 ALTER 가 영원히 대기한다(실측). 메타데이터뿐이라 잠글 가치도 없다.
     */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    @SuppressWarnings("UnusedVariable")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("public 스키마의 모든 테이블에 RLS 가 켜져 있다 — 정책이 없으므로 소유자가 아닌 롤은 전면 거부")
    void every_public_table_has_rls() {
        List<String> withoutRls = jdbcTemplate.queryForList(
                "select relname from pg_class where relnamespace = 'public'::regnamespace and relkind = 'r'"
                        + " and not relrowsecurity and relname <> ? order by relname",
                String.class,
                FLYWAY_HISTORY);

        assertThat(withoutRls)
                .as("RLS 가 꺼진 public 테이블 — Data API 가 켜지면 anon 키로 그대로 열린다. 마이그레이션에서 enable row level security 를 건다")
                .isEmpty();
    }

    @Test
    @DisplayName("FORCE ROW LEVEL SECURITY 는 어디에도 없다 — 소유자로 붙는 앱이 정책 없이 0행이 되는 스위치")
    void no_public_table_forces_rls() {
        List<String> forced = jdbcTemplate.queryForList(
                "select relname from pg_class where relnamespace = 'public'::regnamespace and relkind = 'r'"
                        + " and relforcerowsecurity order by relname",
                String.class);

        assertThat(forced).as("FORCE 가 걸린 테이블 — 앱(소유자 롤)의 모든 조회가 조용히 0행이 된다").isEmpty();
    }

    @Test
    @DisplayName("Supabase API 롤은 public 테이블·시퀀스에 아무 권한도 없다")
    void api_roles_hold_no_privileges_on_public_objects() {
        List<String> granted = jdbcTemplate.queryForList(
                "select distinct c.relname || ' -> ' || r.rolname from pg_class c"
                        + " cross join lateral aclexplode(c.relacl) a join pg_roles r on r.oid = a.grantee"
                        + " where c.relnamespace = 'public'::regnamespace and c.relkind in ('r', 'S')"
                        + " and r.rolname in " + API_ROLES + " order by 1",
                String.class);

        assertThat(granted)
                .as("API 롤에 남은 권한 — Supabase 가 자동 부여한 것을 V12 가 회수하지 못했다")
                .isEmpty();
    }

    @Test
    @DisplayName("앞으로 만들 public 객체에도 API 롤 권한이 자동 부여되지 않는다 — 기본 권한 회수")
    void api_roles_have_no_default_privileges_in_public() {
        List<String> defaults = jdbcTemplate.queryForList(
                "select distinct r.rolname from pg_default_acl d"
                        + " cross join lateral aclexplode(d.defaclacl) a join pg_roles r on r.oid = a.grantee"
                        + " where d.defaclnamespace = 'public'::regnamespace and r.rolname in " + API_ROLES
                        + " order by 1",
                String.class);

        assertThat(defaults).as("기본 권한이 남은 API 롤 — 다음 마이그레이션이 만드는 테이블부터 다시 열린다").isEmpty();
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
