package com.self.multi_currency_household_ledger.common.exception;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 메서드에 그 오퍼레이션이 낼 수 있는 <b>도메인 코드</b>를 적는다. API 계약 생성기가 이것을 읽어 오퍼레이션에
 * {@code x-error-codes} 를 싣는다.
 *
 * <ul>
 *   <li>값은 도메인 코드 문자열({@link ErrorCode#getCode()})이다. 공통 코드({@link ErrorCode.Common})는 계약 생성기가 붙이므로 적지
 *       않는다.
 *   <li>이 애너테이션이 붙은 오퍼레이션만 계약에 {@code x-error-codes} 키를 갖는다. 키가 있으면 "전부 선언됨" 이다. 그래서 기본값이
 *       없다 — 도메인 코드가 없는 오퍼레이션은 {@code @ApiErrorCodes({})} 를 명시해 미선언과 구분한다.
 *   <li>값이 문자열인 이유: 애너테이션 멤버는 인터페이스({@link ErrorCode}) 타입을 가질 수 없고, common 은 도메인 enum 을 볼 수 없다.
 *       오타·삭제된 코드는 module-api 의 계약 테스트가 실제 enum 과 대조해 잡는다.
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ApiErrorCodes {
    String[] value();
}
