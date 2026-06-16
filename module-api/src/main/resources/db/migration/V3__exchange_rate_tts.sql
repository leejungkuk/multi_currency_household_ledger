alter table exchange_rate
    rename column deal_bas_rate to tts;

alter table exchange_rate
    alter column tts type numeric(19, 6) using tts::numeric(19, 6);

alter table ledger_entry
    alter column applied_rate type numeric(19, 6) using applied_rate::numeric(19, 6);
