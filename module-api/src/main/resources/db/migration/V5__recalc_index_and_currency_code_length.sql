-- 보정창 재계산 쿼리(LedgerEntryRepository.findForeignEntriesOnOrAfter:
-- currency_code <> 'KRW' and transaction_date >= ?)용 인덱스.
-- member_id 술어가 없는 시스템 배치 스캔이라 (member_id, transaction_date) 인덱스로는 커버되지 않는다.
create index idx_ledger_txn_date_currency on ledger_entry (transaction_date, currency_code);

-- exchange_rate.currency_code 컬럼 크기 정합: CurrencyCode enum은 최대 3자이므로 varchar(255)는 과다.
-- ledger_entry.currency_code(varchar(10))와 일관되게 축소한다.
alter table exchange_rate
    alter column currency_code type varchar(10);
