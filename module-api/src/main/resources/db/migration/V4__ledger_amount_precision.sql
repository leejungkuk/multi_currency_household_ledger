alter table ledger_entry
    alter column original_amount type numeric(19, 2) using original_amount::numeric(19, 2),
    alter column krw_amount type numeric(19, 2) using krw_amount::numeric(19, 2);
