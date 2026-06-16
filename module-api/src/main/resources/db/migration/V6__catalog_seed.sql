alter table category
    drop constraint if exists uk_category_owner_type_code;

drop index if exists idx_category_owner_type;

alter table category
    add column display_name_ko varchar(100),
    add column display_name_en varchar(100);

update category
set display_name_ko = display_name,
    display_name_en = display_name
where display_name_ko is null
   or display_name_en is null;

alter table category
    alter column display_name_ko set not null,
    alter column display_name_en set not null;

update category
set code = 'LEGACY_CATEGORY_' || id,
    is_active = false;

alter table category
    drop column display_name,
    drop column owner_member_id;

insert into category (id, transaction_type, code, display_name_ko, display_name_en, icon, sort_order, is_active)
values
    (1, 'EXPENSE', 'FOOD_DINING', '식비', 'Food & Dining', '🍽️', 1, true),
    (2, 'EXPENSE', 'CAFE_DRINKS', '카페/음료', 'Café & Drinks', '☕', 2, true),
    (3, 'EXPENSE', 'TRANSPORT', '교통', 'Transport', '🚌', 3, true),
    (4, 'EXPENSE', 'ACCOMMODATION', '숙박', 'Accommodation', '🏨', 4, true),
    (5, 'EXPENSE', 'GROCERIES', '식료품', 'Groceries', '🛒', 5, true),
    (6, 'EXPENSE', 'SHOPPING', '쇼핑', 'Shopping', '🛍️', 6, true),
    (7, 'EXPENSE', 'BEAUTY', '미용', 'Beauty', '💇', 7, true),
    (8, 'EXPENSE', 'ENTERTAINMENT', '여가/오락', 'Entertainment', '🎭', 8, true),
    (9, 'EXPENSE', 'HEALTH_MEDICAL', '건강/의료', 'Health & Medical', '💊', 9, true),
    (10, 'EXPENSE', 'SUBSCRIPTIONS', '구독', 'Subscriptions', '📱', 10, true),
    (11, 'EXPENSE', 'EDUCATION', '교육', 'Education', '📚', 11, true),
    (12, 'EXPENSE', 'TRAVEL', '여행', 'Travel', '✈️', 12, true),
    (13, 'EXPENSE', 'OTHER_EXPENSE', '기타', 'Other', '📦', 13, true),
    (14, 'INCOME', 'SALARY', '급여', 'Salary', '💼', 1, true),
    (15, 'INCOME', 'SIDE_INCOME', '부수입', 'Side Income', '💻', 2, true),
    (16, 'INCOME', 'ALLOWANCE', '용돈', 'Allowance', '🪙', 3, true),
    (17, 'INCOME', 'REFUND', '환불', 'Refund', '🔄', 4, true),
    (18, 'INCOME', 'TAX_REFUND', '세금 환급', 'Tax Refund', '🧾', 5, true),
    (19, 'INCOME', 'TRANSFER', '이체', 'Transfer', '💸', 6, true),
    (20, 'INCOME', 'INVESTMENT', '투자 수익', 'Investment', '📈', 7, true),
    (21, 'INCOME', 'OTHER_INCOME', '기타', 'Other', '📥', 8, true)
on conflict (id) do update
set transaction_type = excluded.transaction_type,
    code = excluded.code,
    display_name_ko = excluded.display_name_ko,
    display_name_en = excluded.display_name_en,
    icon = excluded.icon,
    sort_order = excluded.sort_order,
    is_active = excluded.is_active;

alter table category
    add constraint uk_category_type_code unique (transaction_type, code);

select setval(pg_get_serial_sequence('category', 'id'), (select max(id) from category));

alter table asset
    drop constraint if exists uk_asset_owner_code;

alter table asset
    add column display_name_ko varchar(100),
    add column display_name_en varchar(100);

update asset
set display_name_ko = display_name,
    display_name_en = display_name
where display_name_ko is null
   or display_name_en is null;

alter table asset
    alter column display_name_ko set not null,
    alter column display_name_en set not null;

update asset
set code = 'LEGACY_ASSET_' || id,
    is_active = false;

alter table asset
    drop column display_name,
    drop column icon,
    drop column owner_member_id;

insert into asset (id, code, display_name_ko, display_name_en, sort_order, is_active)
values
    (1, 'CREDIT_CARD', '신용카드', 'Credit Card', 1, true),
    (2, 'DEBIT_CARD', '체크카드', 'Debit Card', 2, true),
    (3, 'CASH', '현금', 'Cash', 3, true),
    (4, 'ACCOUNT', '계좌', 'Account', 4, true),
    (5, 'CHECK', '수표', 'Check', 5, true),
    (6, 'OTHER', '기타', 'Other', 6, true)
on conflict (id) do update
set code = excluded.code,
    display_name_ko = excluded.display_name_ko,
    display_name_en = excluded.display_name_en,
    sort_order = excluded.sort_order,
    is_active = excluded.is_active;

alter table asset
    add constraint uk_asset_code unique (code);

select setval(pg_get_serial_sequence('asset', 'id'), (select max(id) from asset));
