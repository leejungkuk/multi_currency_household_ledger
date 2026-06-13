-- =========================================
-- exchange_rate
-- 환율 정보 (수출입은행 Open API)
-- =========================================
CREATE TABLE IF NOT EXISTS exchange_rate (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    currency_code   VARCHAR(10)     NOT NULL                COMMENT '통화 코드 (USD, EUR, JPY, CNY, GBP)',
    deal_bas_rate   DECIMAL(12, 2)  NOT NULL                COMMENT '매매 기준율',
    base_date       DATE            NOT NULL                COMMENT '환율 기준일',
    created_at      DATETIME(6)     NOT NULL,
    updated_at      DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_exchange_rate_currency_date UNIQUE (currency_code, base_date),
    INDEX idx_exchange_rate_base_date (base_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '환율 정보';

-- =========================================
-- category
-- 가계부 카테고리 (수입/지출)
-- =========================================
CREATE TABLE IF NOT EXISTS category (
    id               BIGINT          NOT NULL AUTO_INCREMENT,
    transaction_type VARCHAR(10)     NOT NULL                COMMENT '거래 유형 (EXPENSE, INCOME)',
    code             VARCHAR(50)     NOT NULL                COMMENT '카테고리 코드 (enum 식별자)',
    display_name     VARCHAR(100)    NOT NULL                COMMENT '표시 이름',
    icon             VARCHAR(20)                             COMMENT '아이콘 (이모지 등)',
    sort_order       INT             NOT NULL DEFAULT 0      COMMENT '정렬 순서',
    owner_member_id  BIGINT          NOT NULL DEFAULT 0      COMMENT '소유자 ID (0: 시스템 공용 기본값)',
    is_active        BOOLEAN         NOT NULL DEFAULT TRUE   COMMENT '활성화 여부',
    created_at       DATETIME(6)     NOT NULL,
    updated_at       DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_category_owner_type_code UNIQUE (owner_member_id, transaction_type, code),
    INDEX idx_category_owner_type (owner_member_id, transaction_type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '가계부 카테고리';

-- =========================================
-- asset
-- 가계부 자산 (결제 수단 / 보관 장소)
-- =========================================
CREATE TABLE IF NOT EXISTS asset (
    id               BIGINT          NOT NULL AUTO_INCREMENT,
    code             VARCHAR(50)     NOT NULL                COMMENT '자산 코드',
    display_name     VARCHAR(100)    NOT NULL                COMMENT '표시 이름',
    icon             VARCHAR(20)                             COMMENT '아이콘',
    sort_order       INT             NOT NULL DEFAULT 0      COMMENT '정렬 순서',
    owner_member_id  BIGINT          NOT NULL DEFAULT 0      COMMENT '소유자 ID (0: 시스템 공용 기본값)',
    is_active        BOOLEAN         NOT NULL DEFAULT TRUE   COMMENT '활성화 여부',
    created_at       DATETIME(6)     NOT NULL,
    updated_at       DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_asset_owner_code UNIQUE (owner_member_id, code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '가계부 자산 (결제 수단)';

-- =========================================
-- ledger_entry
-- 가계부 거래 내역
-- =========================================
CREATE TABLE IF NOT EXISTS ledger_entry (
    id               BIGINT          NOT NULL AUTO_INCREMENT,
    member_id        BIGINT          NOT NULL                COMMENT '회원 ID',
    transaction_type VARCHAR(10)     NOT NULL                COMMENT '거래 유형 (EXPENSE, INCOME)',
    category_id      BIGINT          NOT NULL                COMMENT '카테고리 ID (FK)',
    asset_id         BIGINT          NOT NULL                COMMENT '자산 ID (FK)',
    original_amount  DECIMAL(15, 2)  NOT NULL                COMMENT '원본 거래 금액 (외화/원화)',
    currency_code    VARCHAR(10)     NOT NULL                COMMENT '통화 코드',
    applied_rate     DECIMAL(12, 2)  NOT NULL                COMMENT '적용 환율 (KRW면 1, 외화면 deal_bas_rate)',
    rate_base_date   DATE                                    COMMENT '환율 기준일 (실제 적용된 환율의 날짜)',
    krw_amount       DECIMAL(15, 2)  NOT NULL                COMMENT '환산된 원화 금액',
    transaction_date DATE            NOT NULL                COMMENT '거래 일자',
    memo             VARCHAR(255)                            COMMENT '메모',
    created_at       DATETIME(6)     NOT NULL,
    updated_at       DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ledger_category FOREIGN KEY (category_id) REFERENCES category (id),
    CONSTRAINT fk_ledger_asset FOREIGN KEY (asset_id) REFERENCES asset (id),
    INDEX idx_ledger_member_date (member_id, transaction_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '가계부 거래 내역';