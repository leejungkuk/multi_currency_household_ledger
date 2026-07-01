package com.self.multi_currency_household_ledger.common.web;

/**
 * 공개 참조 read(카탈로그·환율) 응답에 일관 적용하는 Cache-Control 헤더 값의 단일 진실 원천(SSOT).
 *
 * <p>module-ledger·module-exchange 컨트롤러와 그 테스트가 이 상수만 참조해 모듈 간 값 드리프트를 차단한다.
 */
public final class CacheControlHeaders {

    /** 공개 참조 데이터(고정 시드·일 1회 갱신)에 적용: public 캐시 1시간. */
    public static final String PUBLIC_READ = "public, max-age=3600";

    private CacheControlHeaders() {}
}
