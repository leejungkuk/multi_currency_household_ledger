package com.self.multi_currency_household_ledger.member.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuthUserRepository {

    /**
     * 버려진 익명 계정 판정 술어. 후보 조회와 계정별 삭제가 <b>같은 술어</b>를 쓰도록 한 곳에 둔다 — 조회와
     * 삭제 사이에 사용자가 활동한 계정은 DELETE 가 0행을 반환해 자동으로 보호된다(TOCTOU 닫힘).
     *
     * <p>상관 술어({@code s.user_id = u.id} · {@code e.member_id = u.id} · {@code c.owner_member_id = u.id})를
     * 빠뜨리면 에러 없이 매일 0건만 삭제된다 — "후보 없음"과 구분되지 않는 무음 실패다.
     *
     * <p>cutoff 가 둘인 것은 컬럼 타입이 다르기 때문이다: {@code auth.*} 는 timestamptz({@code :cutoffInstant}),
     * {@code ledger_entry}·{@code category} 의 감사 컬럼은 JVM 기본 존으로 기록된 naive timestamp({@code :cutoffLocal}).
     * 하나로 통일하면 세션 TimeZone 에 따라 조용히 어긋난다.
     *
     * <p>{@code auth.sessions.refreshed_at} 은 그 컬럼만 naive 라 cutoff 가 3종이 되므로 쓰지 않는다 —
     * {@code updated_at} 이 같은 시각을 담고 refresh 마다 갱신된다(실측). {@code auth.users.deleted_at} 도
     * 우리 경로가 hard delete 뿐이라 그런 행을 만들지 않으므로 술어에 넣지 않는다.
     */
    private static final String ABANDONED_ANONYMOUS_PREDICATE =
            """
            u.is_anonymous
              and u.created_at < :cutoffInstant
              and u.updated_at < :cutoffInstant
              and (u.last_sign_in_at is null or u.last_sign_in_at < :cutoffInstant)
              and not exists (select 1 from auth.sessions s
                              where s.user_id = u.id
                                and coalesce(s.updated_at, s.created_at) >= :cutoffInstant)
              and not exists (select 1 from ledger_entry e
                              where e.member_id = u.id and e.updated_at >= :cutoffLocal)
              and not exists (select 1 from category c
                              where c.owner_member_id = u.id
                                and coalesce(c.updated_at, c.created_at, 'infinity') >= :cutoffLocal)""";

    /** Postgres 는 DELETE 에 LIMIT 절이 없다 — 상한은 후보 조회에서만 걸고 삭제는 id 단건으로 한다. */
    private static final String FIND_ABANDONED_ANONYMOUS_IDS =
            """
            select u.id
            from auth.users u
            where %s
            order by u.created_at
            limit :limit
            """
                    .formatted(ABANDONED_ANONYMOUS_PREDICATE);

    private static final String DELETE_ABANDONED_ANONYMOUS_USER =
            """
            delete from auth.users u
            where u.id = :id
              and %s
            """
                    .formatted(ABANDONED_ANONYMOUS_PREDICATE);

    private final EntityManager entityManager;

    public int deleteById(UUID memberId) {
        return entityManager
                .createNativeQuery("delete from auth.users where id = :id")
                .setParameter("id", memberId)
                .executeUpdate();
    }

    public List<UUID> findAbandonedAnonymousIds(Instant cutoffInstant, LocalDateTime cutoffLocal, int limit) {
        List<?> ids = entityManager
                .createNativeQuery(FIND_ABANDONED_ANONYMOUS_IDS)
                .setParameter("cutoffInstant", cutoffInstant)
                .setParameter("cutoffLocal", cutoffLocal)
                .setParameter("limit", limit)
                .getResultList();
        return ids.stream().map(UUID.class::cast).toList();
    }

    public int deleteAbandonedAnonymousUser(UUID memberId, Instant cutoffInstant, LocalDateTime cutoffLocal) {
        return entityManager
                .createNativeQuery(DELETE_ABANDONED_ANONYMOUS_USER)
                .setParameter("id", memberId)
                .setParameter("cutoffInstant", cutoffInstant)
                .setParameter("cutoffLocal", cutoffLocal)
                .executeUpdate();
    }
}
