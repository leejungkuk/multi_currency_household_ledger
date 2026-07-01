update ledger_entry
set updated_at = coalesce(updated_at, created_at, now())
where updated_at is null;

alter table ledger_entry
    alter column updated_at set not null;

create index idx_ledger_member_updated_at_id
    on ledger_entry (member_id, updated_at, id);
