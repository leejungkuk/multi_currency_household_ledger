-- orphan(auth.users 에 없는 member_id)이 있으면 이 문장이 그대로 실패한다.
-- 실패는 의도된 동작이다 — 마이그레이션이 데이터를 지우지 않는다.
alter table ledger_entry
  add constraint fk_ledger_entry_member
  foreign key (member_id) references auth.users(id)
  on delete cascade;
