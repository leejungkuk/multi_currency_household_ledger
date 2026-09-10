create schema if not exists auth;
create table if not exists auth.users (id uuid primary key);

-- 버려진 익명 계정 정리 배치가 읽는 컬럼만 실제 GoTrue 스키마에 맞춰 덧댄다.
-- nullable 은 실측(2026-08-23)과 같게 유지한다 — is_anonymous 만 not null 이고 시각 컬럼은 전부 nullable 이다.
-- stub 을 not null 로 조이면 실제가 nullable 인 계정에서 `updated_at < :cutoff` 가 NOT TRUE 가 되어
-- 그 계정이 영구히 안 지워지는데 테스트는 그대로 그린으로 통과한다.
-- default 는 기존 픽스처(insert into auth.users (id) values (?))가 후보에서 자동 제외되도록 두는 것이다.
alter table auth.users add column if not exists is_anonymous    boolean not null default false;
alter table auth.users add column if not exists created_at      timestamptz      default now();
alter table auth.users add column if not exists updated_at      timestamptz      default now();
alter table auth.users add column if not exists last_sign_in_at timestamptz;

create table if not exists auth.sessions (
    id uuid primary key,
    user_id uuid not null references auth.users(id) on delete cascade,
    created_at timestamptz default now(),
    updated_at timestamptz default now()
);

-- Supabase 가 프로젝트마다 붙여 주는 API 롤과, postgres 롤이 만드는 public 객체에 그 롤들의 ALL 을 자동 부여하는
-- 기본 권한을 흉내 낸다(운영 실측 2026-09-10: anon·authenticated·service_role 이 ledger_entry 에 SELECT~TRUNCATE 전권).
-- 이게 없으면 V12 의 회수 경로가 테스트에서 통째로 건너뛰어져(롤 존재 검사) 회수 SQL 이 틀려도 그린이다.
create role anon nologin;
create role authenticated nologin;
create role service_role nologin;
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;
