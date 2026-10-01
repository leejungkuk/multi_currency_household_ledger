-- 월 예산을 달마다 따로·지출만으로 줄인다. 한 회원·한 달에 행은 0개(미설정) 아니면 1개(있음)다.
-- V14 는 개발 DB 에 적용돼 있어 고치지 않는다(체크섬이 어긋나면 개발 서버가 기동하지 않는다).
-- 운영은 V13 이라 옮길 값이 없다. 기본값 이력(DEFAULT)·수입(INCOME)·끔(금액 null) 행은 새 모델에 뜻이 없어 지운다.
delete from budget where axis <> 'EXPENSE' or kind <> 'MONTH' or total_amount is null;   -- 몫은 budget_id cascade
alter table budget drop constraint uk_budget;
alter table budget drop constraint ck_budget_off;
alter table budget drop column axis;
alter table budget drop column kind;
alter table budget alter column currency_code set not null;
alter table budget alter column total_amount set not null;
-- 남은 행은 (EXPENSE, MONTH, 금액 있음)뿐이라 V14 의 (member_id, axis, kind, month) 유일성에서 바로 따른다.
alter table budget add constraint uk_budget unique (member_id, month);
