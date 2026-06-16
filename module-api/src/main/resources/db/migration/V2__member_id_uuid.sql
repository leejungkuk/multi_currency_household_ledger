-- member_id 를 bigint → uuid 로 전환한다. bigint→uuid 암묵 캐스트가 없어 USING 식이 필요한데,
-- 이 마이그레이션은 *빈 테이블*(MVP 재설계 전 dev 데이터 폐기 전제, pre-launch)을 가정한다.
-- 기존 행이 있으면 모두 nil-uuid 로 덮어써 소유자가 유실되므로, 실데이터가 있는 환경에
-- 적용하기 전에는 USING 변환 전략(행별 매핑)을 반드시 재검토할 것.
drop index if exists idx_ledger_member_date;

alter table ledger_entry
    alter column member_id type uuid using '00000000-0000-0000-0000-000000000000'::uuid;

create index idx_ledger_member_date on ledger_entry (member_id, transaction_date);
