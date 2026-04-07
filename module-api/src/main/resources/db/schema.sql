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
