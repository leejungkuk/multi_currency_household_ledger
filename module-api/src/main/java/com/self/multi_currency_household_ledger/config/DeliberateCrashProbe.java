package com.self.multi_currency_household_ledger.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * ⚠️ 제품 기능이 아니라 CD 롤백·낙인 경로를 실검증하기 위한 <b>고의 결함</b>이며, 검증 직후 revert 된다.
 *
 * <p>롤백은 사고 때만 도는 경로라 한 번도 실행해 보지 않으면 없는 것과 같다(step3 §D 검증 ②). 검증하려면 배포 에이전트가
 * "정상 이미지 → 크래시 루프 이미지" 를 실제로 겪어야 하는데, 그 결함은 <b>이미지에 구워져</b> 있어야 한다 — 호스트
 * {@code .env} 오타로 깨뜨리면 롤백 대상인 구 이미지도 같이 못 뜨고 digest 도 그대로라 트리거 자체가 걸리지 않는다.
 *
 * <p>{@code @Profile("prod")} 인 이유: 테스트 컨텍스트에는 뜨지 않아 build·Trivy required check 가 그린으로 통과하고
 * 운영 프로파일에서만 기동을 실패시킨다. 즉 "빌드는 되는데 배포하면 죽는" 실제 사고와 같은 형태다.
 */
@Profile("prod")
@Component
public class DeliberateCrashProbe {

    @PostConstruct
    public void boom() {
        throw new IllegalStateException("CD 롤백 검증용 고의 실패 — revert 대상");
    }
}
