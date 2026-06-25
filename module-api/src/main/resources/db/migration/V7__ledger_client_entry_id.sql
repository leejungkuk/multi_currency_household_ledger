alter table ledger_entry
    add column client_entry_id uuid;

alter table ledger_entry
    add column client_payload_hash varchar(64);

create unique index uq_ledger_entry_member_client_entry
    on ledger_entry (member_id, client_entry_id)
    where client_entry_id is not null;
