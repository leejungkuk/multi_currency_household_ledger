-- 카테고리 행 DELETE 마다 도는 FK 검사(fk_ledger_category: category_id = ?)와
-- 커스텀 카테고리 고아 정리의 not exists 서브쿼리가 같은 인덱스를 탄다. 선두 컬럼은 category_id 여야 한다.
create index idx_ledger_category on ledger_entry (category_id);
