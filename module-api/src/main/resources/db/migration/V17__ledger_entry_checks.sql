-- 거래 유형·금액의 도메인 규칙을 DB 에서도 막는다(2026-10-04). LedgerEntry·요청 DTO(@Positive·@DecimalMax)가 먼저 거르므로
-- 정상 경로에서는 걸리지 않는다. 위반 행이 있으면 이 문장이 실패한다 — 마이그레이션이 데이터를 고치지 않는다(V10 선례).
-- krw_amount 는 0 을 허용한다: 아주 작은 외화 금액은 소수 둘째 자리 반올림으로 0.00 원이 될 수 있다.
-- numeric 의 NaN 은 모든 수보다 크게 비교돼 하한만 있는 검사를 통과하므로 따로 막는다(original_amount 는 상한이 막는다).
-- Infinity 는 정밀도가 있는 컬럼에 저장되지 않는다(numeric field overflow).
alter table ledger_entry
  add constraint ck_ledger_entry_transaction_type check (transaction_type in ('INCOME', 'EXPENSE')),
  add constraint ck_ledger_entry_original_amount  check (original_amount > 0 and original_amount <= 99999999),
  add constraint ck_ledger_entry_krw_amount       check (krw_amount >= 0 and krw_amount <> 'NaN'),
  add constraint ck_ledger_entry_applied_rate     check (applied_rate > 0 and applied_rate <> 'NaN');
