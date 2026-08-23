package com.self.multi_currency_household_ledger.member.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthUserRepositoryTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CUTOFF_INSTANT = Instant.parse("2025-08-23T19:00:00Z");
    private static final LocalDateTime CUTOFF_LOCAL = LocalDateTime.parse("2025-08-24T04:00:00");
    private static final int BATCH_LIMIT = 500;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    @InjectMocks
    private AuthUserRepository authUserRepository;

    @Test
    @DisplayName("auth.users 삭제는 id 술어와 JWT subject 파라미터를 사용하고 영향 행수를 반환한다")
    void deleteById_uses_id_predicate_and_binds_member_id() {
        given(entityManager.createNativeQuery(anyString())).willReturn(query);
        given(query.setParameter("id", MEMBER_ID)).willReturn(query);
        given(query.executeUpdate()).willReturn(1);

        int deletedRows = authUserRepository.deleteById(MEMBER_ID);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        then(entityManager).should().createNativeQuery(sql.capture());
        assertThat(sql.getValue()).containsIgnoringCase("delete from auth.users");
        assertThat(sql.getValue()).containsIgnoringCase("where id = :id");
        then(query).should().setParameter("id", MEMBER_ID);
        then(query).should().executeUpdate();
        assertThat(deletedRows).isEqualTo(1);
    }

    @Test
    @DisplayName("버려진 익명 계정 후보 조회는 익명·상관 술어와 두 cutoff·상한을 바인딩한다")
    void findAbandonedAnonymousIds_binds_predicate_two_cutoffs_and_limit() {
        given(entityManager.createNativeQuery(anyString())).willReturn(query);
        given(query.setParameter(anyString(), any())).willReturn(query);
        given(query.getResultList()).willReturn(List.of(MEMBER_ID));

        List<UUID> candidates = authUserRepository.findAbandonedAnonymousIds(CUTOFF_INSTANT, CUTOFF_LOCAL, BATCH_LIMIT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        then(entityManager).should().createNativeQuery(sql.capture());
        assertThat(sql.getValue()).containsIgnoringCase("from auth.users");
        assertThat(sql.getValue()).containsIgnoringCase("order by u.created_at");
        assertAbandonedAnonymousPredicate(sql.getValue());
        then(query).should().setParameter("cutoffInstant", CUTOFF_INSTANT);
        then(query).should().setParameter("cutoffLocal", CUTOFF_LOCAL);
        then(query).should().setParameter("limit", BATCH_LIMIT);
        assertThat(candidates).containsExactly(MEMBER_ID);
    }

    @Test
    @DisplayName("계정별 삭제는 id 외에 후보 조회와 같은 술어를 그대로 재평가한다")
    void deleteAbandonedAnonymousUser_keeps_the_same_predicate_as_the_candidate_query() {
        given(entityManager.createNativeQuery(anyString())).willReturn(query);
        given(query.setParameter(anyString(), any())).willReturn(query);
        given(query.executeUpdate()).willReturn(1);

        int deletedRows = authUserRepository.deleteAbandonedAnonymousUser(MEMBER_ID, CUTOFF_INSTANT, CUTOFF_LOCAL);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        then(entityManager).should().createNativeQuery(sql.capture());
        assertThat(sql.getValue()).containsIgnoringCase("delete from auth.users");
        assertThat(sql.getValue()).contains("u.id = :id");
        assertAbandonedAnonymousPredicate(sql.getValue());
        then(query).should().setParameter("id", MEMBER_ID);
        then(query).should().setParameter("cutoffInstant", CUTOFF_INSTANT);
        then(query).should().setParameter("cutoffLocal", CUTOFF_LOCAL);
        assertThat(deletedRows).isEqualTo(1);
    }

    /**
     * 두 쿼리가 같은 술어를 쓰는지 본다. 상관 술어가 빠지면 에러 없이 매일 0건만 삭제되는 무음 실패가 되므로
     * 문자열로라도 못 박는다 — 의미 검증은 module-api 의 Testcontainers 실동작 테스트가 담당한다.
     */
    private static void assertAbandonedAnonymousPredicate(String sql) {
        assertThat(sql).containsIgnoringCase("u.is_anonymous");
        assertThat(sql).contains("u.created_at < :cutoffInstant");
        assertThat(sql).contains("u.updated_at < :cutoffInstant");
        assertThat(sql).contains("u.last_sign_in_at is null or u.last_sign_in_at < :cutoffInstant");
        assertThat(sql).contains("s.user_id = u.id");
        assertThat(sql).contains("e.member_id = u.id");
        assertThat(sql).contains("c.owner_member_id = u.id");
        assertThat(sql).contains("e.updated_at >= :cutoffLocal");
        assertThat(sql).contains("coalesce(c.updated_at, c.created_at, 'infinity') >= :cutoffLocal");
        assertThat(sql).contains("coalesce(s.updated_at, s.created_at) >= :cutoffInstant");
        // 금지 컬럼: refreshed_at 은 naive 라 cutoff 가 3종이 되고, deleted_at 은 우리가 만들지 않는 상태다.
        assertThat(sql).doesNotContain("refreshed_at");
        assertThat(sql).doesNotContain("deleted_at");
    }
}
