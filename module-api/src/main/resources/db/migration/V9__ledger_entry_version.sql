-- 낙관적 락 버전. 기존 행은 default 0 으로 채워진다(운영 ddl-auto=validate — 컬럼이 없으면 기동 실패).
alter table ledger_entry
    add column version bigint not null default 0;
