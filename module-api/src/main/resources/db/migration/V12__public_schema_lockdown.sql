-- public 스키마를 Supabase Data API(PostgREST) 로부터 DB 레벨에서 잠근다.
--
-- Data API 는 대시보드에서 껐다(2026-09-10, 개발·운영). 하지만 그건 토글 하나라 되돌리면 그 자리에서 iOS 에 박힌
-- anon 키로 전 회원 거래가 열린다 — 운영 실측으로 anon·authenticated·service_role 이 ledger_entry 에
-- SELECT~TRUNCATE 전권을 갖고 있었다. 여기서 두 겹으로 막는다.
--
-- ① RLS 를 켜되 정책은 만들지 않는다 = 소유자가 아닌 롤은 전면 거부. 앱은 테이블 소유자(postgres, Flyway 실행 롤과
--    같다) 로 붙으므로 영향이 없다 — 소유자는 FORCE 가 아닌 한 RLS 를 타지 않는다(FORCE 는 테스트가 막는다).
--    flyway_schema_history 는 여기서 건드리지 않는다 — Flyway 가 이력 테이블용 연결과 마이그레이션용 연결을 따로 쓰고,
--    이력 연결이 트랜잭션 안에서 AccessShareLock 을 쥔 채 기다리므로 마이그레이션 연결의 ALTER 가 영원히 대기한다
--    (Testcontainers 실측 2026-09-10: pg_stat_activity 에 idle in transaction ↔ Lock/relation 교착). 이력 테이블은
--    마이그레이션 메타데이터뿐이라 잠글 가치가 없고, Security Advisor 의 그 한 줄은 대시보드 SQL 로 따로 켜면 사라진다.
-- ② Supabase 가 API 롤에 자동 부여한 권한을 회수하고, 앞으로 만들 객체에도 붙지 않도록 기본 권한을 지운다.
--    default privileges 는 "현재 롤" 기준이고 현재 롤 = postgres 라 Supabase 가 postgres 에 걸어 둔 항목과 같다.
--    롤 존재 검사로 감싼 이유: Testcontainers 의 일반 Postgres 에는 이 롤이 없다(테스트 stub 이 흉내 낸다).
--
-- 앱 롤을 postgres 에서 분리하는 날(BACKLOG) 은 그 롤용 policy(using (true) with check (true)) 가 같이 들어가야 한다.
-- Supabase 의 postgres 는 슈퍼유저가 아니라 새 롤에 BYPASSRLS 를 줄 수 없다.

alter table exchange_rate         enable row level security;
alter table category              enable row level security;
alter table asset                 enable row level security;
alter table ledger_entry          enable row level security;

do $$
declare api_role text;
begin
    foreach api_role in array array['anon', 'authenticated', 'service_role'] loop
        if exists (select 1 from pg_roles where rolname = api_role) then
            execute format('revoke all on all tables in schema public from %I', api_role);
            execute format('revoke all on all sequences in schema public from %I', api_role);
            execute format('alter default privileges in schema public revoke all on tables from %I', api_role);
            execute format('alter default privileges in schema public revoke all on sequences from %I', api_role);
        end if;
    end loop;
end $$;
