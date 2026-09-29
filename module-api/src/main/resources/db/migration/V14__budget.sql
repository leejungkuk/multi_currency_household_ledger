-- 월 예산. 끔은 행이다(currency_code·total_amount 둘 다 null), 행이 없으면 미설정이다.
create table budget (
  id            bigserial primary key,
  member_id     uuid not null,
  axis          varchar(10) not null check (axis in ('EXPENSE','INCOME')),
  kind          varchar(10) not null check (kind in ('DEFAULT','MONTH')),
  month         date not null check (extract(day from month) = 1),  -- DEFAULT=적용 시작 달, MONTH=그 달
  currency_code varchar(3),
  total_amount  numeric(19,2) check (total_amount between 0 and 99999999),
  created_at    timestamp, updated_at timestamp,
  constraint fk_budget_member foreign key (member_id) references auth.users(id) on delete cascade,
  constraint uk_budget unique (member_id, axis, kind, month),
  constraint ck_budget_off check ((currency_code is null) = (total_amount is null))   -- 둘 다 null = 끔
);

-- budget_id 는 DB cascade 다 — purge 의 JPQL bulk delete 는 JPA cascade 를 거치지 않는다.
-- category_id 도 cascade 다 — 없으면 auth.users 삭제가 category cascade(V11) 를 budget cascade 보다 먼저 돌려
-- budget_allocation 참조로 실패한다(postgres:16 실측 2026-09-29).
create table budget_allocation (
  id            bigserial primary key,
  budget_id     bigint not null references budget(id) on delete cascade,
  payment_group varchar(20),
  category_id   bigint references category(id) on delete cascade,
  amount        numeric(19,2) not null check (amount between 0 and 99999999),
  constraint ck_budget_allocation_target check (num_nonnulls(payment_group, category_id) = 1),
  constraint uk_budget_allocation_group unique (budget_id, payment_group),
  constraint uk_budget_allocation_category unique (budget_id, category_id)
);

alter table budget            enable row level security;   -- V12 관례. PublicSchemaLockdownIntegrationTest 가 요구한다
alter table budget_allocation enable row level security;
