-- 결제수단 몫은 budget 행의 컬럼으로, 카테고리 몫만 자식 테이블에 둔다(2026-10-04).
-- 결제수단 그룹은 3개로 닫힌 집합(PaymentGroup)이라 컬럼, 카테고리는 회원이 늘리는 열린 집합이라 행이다.
-- V14·V15 는 개발 DB 에 적용돼 있어 고치지 않는다(체크섬). 운영은 V13 이라 빈 테이블 위에서 V14→V17 을 연달아 돈다.
alter table budget
  add column credit_card_amount       numeric(19,2) check (credit_card_amount between 0 and 99999999),
  add column cash_and_debit_amount    numeric(19,2) check (cash_and_debit_amount between 0 and 99999999),
  add column account_and_other_amount numeric(19,2) check (account_and_other_amount between 0 and 99999999);

-- V14 는 payment_group 값을 제한하지 않았다. 아래 복사는 세 값만 옮기므로, 다른 값이 있으면 여기서 실패해 조용히 유실되지 않게 한다.
alter table budget_allocation add constraint ck_budget_allocation_known_group
  check (payment_group in ('CREDIT_CARD', 'CASH_AND_DEBIT', 'ACCOUNT_AND_OTHER'));

update budget b set credit_card_amount = a.amount
  from budget_allocation a where a.budget_id = b.id and a.payment_group = 'CREDIT_CARD';
update budget b set cash_and_debit_amount = a.amount
  from budget_allocation a where a.budget_id = b.id and a.payment_group = 'CASH_AND_DEBIT';
update budget b set account_and_other_amount = a.amount
  from budget_allocation a where a.budget_id = b.id and a.payment_group = 'ACCOUNT_AND_OTHER';

-- 몫 합 > 전체인 행(v1 잔재)이 있으면 이 문장이 실패한다. 의도된 동작이다 — 마이그레이션이 데이터를 지우지 않는다(V10 선례).
alter table budget add constraint ck_budget_payment_groups_within_total check (
  coalesce(credit_card_amount, 0) + coalesce(cash_and_debit_amount, 0) + coalesce(account_and_other_amount, 0)
    <= total_amount);

-- 두 FK 모두 cascade 다(V14 와 같은 이유): auth.users 삭제가 category cascade 를 budget cascade 보다 먼저 돌려도 막히지 않아야 한다.
create table budget_category_allocation (
  budget_id   bigint not null references budget(id) on delete cascade,
  category_id bigint not null references category(id) on delete cascade,
  amount      numeric(19,2) not null check (amount between 0 and 99999999),
  constraint pk_budget_category_allocation primary key (budget_id, category_id)
);
-- 카테고리 hard delete(고아 정리·purge·탈퇴 cascade)가 참조 행을 찾을 때 쓴다. 없으면 이 테이블 전체를 훑는다.
create index idx_budget_category_allocation_category on budget_category_allocation (category_id);
alter table budget_category_allocation enable row level security;   -- V12 관례. PublicSchemaLockdownIntegrationTest 가 요구한다

insert into budget_category_allocation (budget_id, category_id, amount)
  select budget_id, category_id, amount from budget_allocation where category_id is not null;

drop table budget_allocation;
