alter table category add column owner_member_id uuid;

alter table category
  add constraint fk_category_owner_member
  foreign key (owner_member_id) references auth.users(id)
  on delete cascade;

create index idx_category_owner on category (owner_member_id);

-- code="CUSTOM" 고정과 전역 유니크가 충돌하므로 시스템 행 한정 부분 유니크로 교체
alter table category drop constraint uk_category_type_code;
create unique index uk_category_type_code
    on category (transaction_type, code) where owner_member_id is null;

-- 시스템 시드는 id 고정(iOS 번들 대응)이 규칙 — 22~9999를 시스템 전용으로 예약, 커스텀은 10000부터
select setval(pg_get_serial_sequence('category', 'id'), 10000, false);
