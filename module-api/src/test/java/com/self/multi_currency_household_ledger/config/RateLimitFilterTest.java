package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class RateLimitFilterTest {

    private static final Duration WINDOW = Duration.ofSeconds(1);
    private static final Instant WINDOW_MIDDLE = Instant.ofEpochMilli(500);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void 한도까지_통과하고_다음_요청은_429와_재시도_정보를_반환한다() throws Exception {
        AdjustableClock clock = new AdjustableClock(WINDOW_MIDDLE);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = newFilter(clock, meterRegistry, 2, 2, 10);

        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.1"));
        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.1"));

        Outcome rejected = perform(filter, HttpMethod.GET, "192.0.2.1");

        assertRejected(rejected);
        assertThat(Integer.parseInt(rejected.response().getHeader(HttpHeaders.RETRY_AFTER)))
                .isBetween(1, 6);
        assertThat(rejected.response().getContentAsString()).contains("\"code\":\"TOO_MANY_REQUESTS\"");
        assertThat(meterRegistry
                        .get("woni.rate_limit.rejected")
                        .tag("class", "read")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .get("woni.rate_limit.rejected")
                        .tag("class", "write")
                        .counter()
                        .count())
                .isZero();
        assertThat(meterRegistry.get("woni.rate_limit.fail_open").counter().count())
                .isZero();
    }

    @Test
    void 윈도우가_지나면_다시_통과한다() throws Exception {
        AdjustableClock clock = new AdjustableClock(WINDOW_MIDDLE);
        RateLimitFilter filter = newFilter(clock, new SimpleMeterRegistry(), 1, 1, 10);

        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.2"));
        assertRejected(perform(filter, HttpMethod.GET, "192.0.2.2"));

        clock.advance(WINDOW);

        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.2"));
    }

    @Test
    void 고정_윈도우_경계에서는_연속_이중_한도를_수용한다() throws Exception {
        AdjustableClock clock = new AdjustableClock(Instant.ofEpochMilli(999));
        RateLimitFilter filter = newFilter(clock, new SimpleMeterRegistry(), 2, 2, 10);

        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.3"));
        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.3"));

        clock.advance(Duration.ofMillis(2));

        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.3"));
        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.3"));
    }

    @Test
    void IP와_읽기_쓰기_버킷은_서로_분리된다() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = newFilter(new AdjustableClock(WINDOW_MIDDLE), meterRegistry, 1, 1, 10);

        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.4"));
        assertRejected(perform(filter, HttpMethod.GET, "192.0.2.4"));
        // HEAD 가 읽기에서 빠지면 쓰기 예산을 먹는다 — 소진된 읽기 버킷을 공유하는지로 고정한다.
        assertRejected(perform(filter, HttpMethod.HEAD, "192.0.2.4"));
        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.5"));
        assertPassed(perform(filter, HttpMethod.POST, "192.0.2.4"));
        assertRejected(perform(filter, HttpMethod.POST, "192.0.2.4"));
        assertThat(meterRegistry
                        .get("woni.rate_limit.rejected")
                        .tag("class", "write")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void 동시_요청은_정확히_한도만큼만_통과한다() throws Exception {
        int workers = 8;
        int limit = 3;
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            for (int repetition = 0; repetition < 20; repetition++) {
                RateLimitFilter filter =
                        newFilter(new AdjustableClock(WINDOW_MIDDLE), new SimpleMeterRegistry(), limit, limit, 10);
                CyclicBarrier barrier = new CyclicBarrier(workers);
                List<Future<Outcome>> results = new ArrayList<>();
                for (int worker = 0; worker < workers; worker++) {
                    results.add(executor.submit(() -> {
                        barrier.await();
                        return perform(filter, HttpMethod.GET, "192.0.2.6");
                    }));
                }

                int passed = 0;
                for (Future<Outcome> result : results) {
                    if (result.get().chained()) {
                        passed++;
                    }
                }
                assertThat(passed).isEqualTo(limit);
            }
        }
    }

    @Test
    void 새_윈도우의_첫_요청은_이전_키를_폐기한다() throws Exception {
        AdjustableClock clock = new AdjustableClock(WINDOW_MIDDLE);
        RateLimitFilter filter = newFilter(clock, new SimpleMeterRegistry(), 2, 2, 10);
        perform(filter, HttpMethod.GET, "192.0.2.7");
        perform(filter, HttpMethod.GET, "192.0.2.8");
        assertThat(filter.currentKeys()).containsExactlyInAnyOrder("r:192.0.2.7", "r:192.0.2.8");

        clock.advance(WINDOW);
        perform(filter, HttpMethod.GET, "192.0.2.9");

        // 크기 1 만으로는 "이전 키 하나가 남고 새 키가 누락된" 회귀를 구분하지 못한다 — 키 집합 자체를 고정한다.
        assertThat(filter.currentKeys()).containsExactly("r:192.0.2.9");
    }

    @Test
    void IPv6는_64비트_접두로_집계한다() throws Exception {
        RateLimitFilter filter = newFilter(new AdjustableClock(WINDOW_MIDDLE), new SimpleMeterRegistry(), 1, 1, 10);

        assertPassed(perform(filter, HttpMethod.GET, "2001:db8:1:2::1"));
        assertRejected(perform(filter, HttpMethod.GET, "2001:db8:1:2::ffff"));
        // 대괄호 표기·대문자 hex·완전 표기도 같은 /64 다. 하나라도 놓치면 그 표기 사용자 전원이 invalid 버킷에 몰린다.
        assertRejected(perform(filter, HttpMethod.GET, "[2001:db8:1:2::2]"));
        assertRejected(perform(filter, HttpMethod.GET, "2001:DB8:1:2:0:0:0:3"));
        assertPassed(perform(filter, HttpMethod.GET, "2001:db8:1:3::1"));

        assertThat(filter.currentKeys()).containsExactlyInAnyOrder("r:2001:db8:1:2:0:0:0:0", "r:2001:db8:1:3:0:0:0:0");
    }

    @Test
    void 키_캡을_넘은_신규_IP는_통과하고_fail_open을_기록한다() throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RateLimitFilter filter = newFilter(new AdjustableClock(WINDOW_MIDDLE), meterRegistry, 1, 1, 1);
        perform(filter, HttpMethod.GET, "192.0.2.10");

        Outcome failOpen = perform(filter, HttpMethod.GET, "192.0.2.11");

        assertPassed(failOpen);
        assertThat(meterRegistry.get("woni.rate_limit.fail_open").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void IPv4_mapped_표기들은_하나의_IPv4_버킷을_공유한다() throws Exception {
        RateLimitFilter filter = newFilter(new AdjustableClock(WINDOW_MIDDLE), new SimpleMeterRegistry(), 2, 2, 10);
        assertPassed(perform(filter, HttpMethod.GET, "::ffff:192.0.2.1"));
        assertPassed(perform(filter, HttpMethod.GET, "::ffff:c000:201"));

        assertRejected(perform(filter, HttpMethod.GET, "::ffff:192.0.2.1"));
        assertRejected(perform(filter, HttpMethod.GET, "::ffff:c000:201"));
        assertRejected(perform(filter, HttpMethod.GET, "192.0.2.1"));
    }

    @Test
    void 정규화할_수_없는_주소는_모두_하나의_버킷으로_접힌다() throws Exception {
        RateLimitFilter filter = newFilter(new AdjustableClock(WINDOW_MIDDLE), new SimpleMeterRegistry(), 1, 1, 10);

        assertPassed(perform(filter, HttpMethod.GET, null));

        // 원문을 키로 쓰면 아래가 전부 별도 버킷이 되어 문자열 회전만으로 맵을 채우고 한도를 무한 우회한다(플랜 §10).
        assertRejected(perform(filter, HttpMethod.GET, ""));
        assertRejected(perform(filter, HttpMethod.GET, "not-an-ip"));
        assertRejected(perform(filter, HttpMethod.GET, "fe80::1%en0"));
        assertRejected(perform(filter, HttpMethod.GET, "zz:1"));
        assertRejected(perform(filter, HttpMethod.GET, "001.002.003.004"));
        assertRejected(perform(filter, HttpMethod.GET, "1.2.3.4.5"));

        assertThat(filter.currentKeys()).containsExactly("r:invalid");
        // 정상 주소는 그 버킷에 오염되지 않는다.
        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.20"));
    }

    @Test
    void 생성자는_사용할_수_없는_윈도우와_한도를_거부한다() {
        assertThatThrownBy(() -> newFilter(Clock.systemUTC(), new SimpleMeterRegistry(), 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newFilter(Clock.systemUTC(), new SimpleMeterRegistry(), 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newFilter(Clock.systemUTC(), new SimpleMeterRegistry(), 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        // window 가 0 이면 슬롯 계산의 floorMod 가 매 요청 ArithmeticException 을 던진다.
        assertThatThrownBy(() -> newFilter(Duration.ZERO, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        // ZERO 만으로는 "1초 하한" 과 "양수" 를 구분하지 못한다 — 하한 자체를 고정한다.
        assertThatThrownBy(() -> newFilter(Duration.ofMillis(999), 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 재시도_대기_시간은_윈도우_잔여_시간을_반영한다() throws Exception {
        // 1초 창에서는 잔여 항이 항상 1 이라 ceil(잔여) 를 상수 1 로 바꿔도 잡히지 않는다. 운영 창(60s)으로 그 항을 고정한다.
        AdjustableClock clock = new AdjustableClock(Instant.ofEpochMilli(10_000));
        RateLimitFilter filter =
                new RateLimitFilter(clock, new SimpleMeterRegistry(), OBJECT_MAPPER, Duration.ofSeconds(60), 1, 1, 10);

        assertPassed(perform(filter, HttpMethod.GET, "192.0.2.30"));
        Outcome rejected = perform(filter, HttpMethod.GET, "192.0.2.30");

        assertRejected(rejected);
        // 창 [0, 60s) 의 10초 지점 → 잔여 50초 + 지터 0~5초.
        assertThat(Integer.parseInt(rejected.response().getHeader(HttpHeaders.RETRY_AFTER)))
                .isBetween(50, 55);
    }

    private static RateLimitFilter newFilter(
            Clock clock, SimpleMeterRegistry meterRegistry, int readLimit, int writeLimit, int maxKeys) {
        return new RateLimitFilter(clock, meterRegistry, OBJECT_MAPPER, WINDOW, readLimit, writeLimit, maxKeys);
    }

    private static RateLimitFilter newFilter(Duration window, int readLimit, int writeLimit, int maxKeys) {
        return new RateLimitFilter(
                Clock.systemUTC(), new SimpleMeterRegistry(), OBJECT_MAPPER, window, readLimit, writeLimit, maxKeys);
    }

    private static Outcome perform(RateLimitFilter filter, HttpMethod method, String remoteAddress) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method.name(), "/api/v1/test");
        request.setRemoteAddr(remoteAddress);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain.getRequest() != null);
    }

    /**
     * {@code MockHttpServletResponse} 는 생성 직후 status 가 200 이라, 상태코드만 단언하면 필터가 체인을 아예 호출하지 않아도
     * "통과" 로 보인다. 체인 호출 여부를 함께 들고 다녀야 그 회귀가 잡힌다.
     */
    private record Outcome(MockHttpServletResponse response, boolean chained) {}

    private static void assertPassed(Outcome outcome) {
        assertThat(outcome.chained()).as("체인으로 전달되어야 한다").isTrue();
        assertThat(outcome.response().getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    private static void assertRejected(Outcome outcome) {
        assertThat(outcome.chained()).as("체인 호출 없이 필터에서 끊겨야 한다").isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    private static final class AdjustableClock extends Clock {

        private Clock delegate;

        private AdjustableClock(Instant initialInstant) {
            delegate = Clock.fixed(initialInstant, ZoneOffset.UTC);
        }

        void advance(Duration duration) {
            delegate = Clock.offset(delegate, duration);
        }

        @Override
        public ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return delegate.withZone(zone);
        }

        @Override
        public Instant instant() {
            return delegate.instant();
        }
    }
}
