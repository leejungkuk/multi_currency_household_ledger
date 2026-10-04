-- 예산 카테고리 몫의 줄 순서(2026-10-04). PUT categoryAmounts 의 배열 인덱스(0부터)를 저장하고, 조회 categories 가 이 순서를 따른다.
-- default 0 인 이유: 이 컬럼을 모르는 이미지(머지 전 브랜치·롤백 대상)가 몫을 insert 해도 깨지지 않게 한다. 읽기는 (sort_order, category_id) 순이라 동률도 결정적이다.
alter table budget_category_allocation add column sort_order integer not null default 0;

-- 기존 행은 지금까지의 응답 순서(카테고리 sort_order asc, id asc)로 채운다 — 배포 전후로 열어 보는 순서가 바뀌지 않는다.
update budget_category_allocation a
   set sort_order = r.line_order
  from (select x.budget_id, x.category_id,
               row_number() over (partition by x.budget_id order by c.sort_order, c.id) - 1 as line_order
          from budget_category_allocation x
          join category c on c.id = x.category_id) r
 where r.budget_id = a.budget_id and r.category_id = a.category_id;
