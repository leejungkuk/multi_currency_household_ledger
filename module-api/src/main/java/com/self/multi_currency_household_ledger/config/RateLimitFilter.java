package com.self.multi_currency_household_ledger.config;

import com.self.multi_currency_household_ledger.common.exception.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

final class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String INVALID_ADDRESS = "invalid";
    private static final int RETRY_JITTER_BOUND_SECONDS = 6;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final long windowMillis;
    private final int readLimit;
    private final int writeLimit;
    private final int maxKeys;
    private final Counter readRejectedCounter;
    private final Counter writeRejectedCounter;
    private final Counter failOpenCounter;
    private final AtomicReference<Generation> generation;

    RateLimitFilter(
            Clock clock,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper,
            Duration window,
            int readLimit,
            int writeLimit,
            int maxKeys) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        Objects.requireNonNull(window, "window");
        if (window.compareTo(Duration.ofSeconds(1)) < 0 || readLimit <= 0 || writeLimit <= 0 || maxKeys <= 0) {
            throw new IllegalArgumentException(
                    "Rate limit window and limits must be positive, and window must be at least one second.");
        }

        this.windowMillis = window.toMillis();
        this.readLimit = readLimit;
        this.writeLimit = writeLimit;
        this.maxKeys = maxKeys;
        this.readRejectedCounter =
                Counter.builder("woni.rate_limit.rejected").tag("class", "read").register(meterRegistry);
        this.writeRejectedCounter = Counter.builder("woni.rate_limit.rejected")
                .tag("class", "write")
                .register(meterRegistry);
        this.failOpenCounter = Counter.builder("woni.rate_limit.fail_open").register(meterRegistry);
        this.generation = new AtomicReference<>(
                new Generation(windowStart(clock.millis()), new ConcurrentHashMap<>(), new AtomicBoolean()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long now = clock.millis();
        Generation current = generation(now);
        boolean read = isRead(request.getMethod());
        int limit = read ? readLimit : writeLimit;
        String key = (read ? "r:" : "w:") + normalizeAddress(request.getRemoteAddr());

        AtomicInteger counter = current.counts().get(key);
        if (counter == null) {
            if (current.counts().size() >= maxKeys) {
                failOpenCounter.increment();
                if (current.capWarned().compareAndSet(false, true)) {
                    LOGGER.warn("Rate limit key cap reached; new keys are temporarily allowed.");
                }
                filterChain.doFilter(request, response);
                return;
            }
            counter = current.counts().computeIfAbsent(key, ignored -> new AtomicInteger());
        }

        if (counter.get() > limit || counter.incrementAndGet() > limit) {
            reject(response, read, now);
            return;
        }
        filterChain.doFilter(request, response);
    }

    Set<String> currentKeys() {
        return Set.copyOf(generation.get().counts().keySet());
    }

    private Generation generation(long now) {
        long currentWindowStart = windowStart(now);
        Generation current = generation.get();
        if (current.windowStart() == currentWindowStart) {
            return current;
        }

        Generation replacement = new Generation(currentWindowStart, new ConcurrentHashMap<>(), new AtomicBoolean());
        if (generation.compareAndSet(current, replacement)) {
            return replacement;
        }
        return generation.get();
    }

    private long windowStart(long now) {
        return now - Math.floorMod(now, windowMillis);
    }

    private void reject(HttpServletResponse response, boolean read, long now) throws IOException {
        (read ? readRejectedCounter : writeRejectedCounter).increment();
        long remainingMillis = windowMillis - Math.floorMod(now, windowMillis);
        long remainingSeconds = Math.max(1, Math.ceilDiv(remainingMillis, 1_000));
        long retryAfter = remainingSeconds + ThreadLocalRandom.current().nextInt(RETRY_JITTER_BOUND_SECONDS);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        SecurityConfig.writeErrorResponse(response, objectMapper, ErrorCode.Common.TOO_MANY_REQUESTS);
    }

    private static boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private static String normalizeAddress(String address) {
        if (address == null || address.isEmpty()) {
            return INVALID_ADDRESS;
        }
        if (address.indexOf(':') < 0) {
            return isIpv4Literal(address) ? address : INVALID_ADDRESS;
        }
        // XFF 에 IPv6 를 대괄호로 싣는 프록시가 있다. 벗겨내지 않으면 그 표기를 쓰는 사용자 전원이 invalid 한 버킷에 몰린다.
        String literal = address.charAt(0) == '[' && address.charAt(address.length() - 1) == ']'
                ? address.substring(1, address.length() - 1)
                : address;
        if (literal.isEmpty() || !isIpv6LiteralCandidate(literal)) {
            return INVALID_ADDRESS;
        }

        try {
            InetAddress parsed = InetAddress.getByName(literal);
            byte[] bytes = parsed.getAddress();
            if (bytes.length == 4) {
                return parsed.getHostAddress();
            }
            if (bytes.length == 16) {
                Arrays.fill(bytes, 8, 16, (byte) 0);
                return InetAddress.getByAddress(bytes).getHostAddress();
            }
        } catch (UnknownHostException ignored) {
            // 공격자 제어 원문은 저장하거나 로그하지 않고 단일 버킷으로 접는다.
        }
        return INVALID_ADDRESS;
    }

    /**
     * {@code getByName} 에 넘겨도 리졸버를 타지 않는 입력인지 본다. JDK 21 은 <b>첫 글자가 hex digit 또는 {@code ':'} 일 때만</b>
     * 리터럴 파싱을 시도하고 그렇지 않으면 이름 해석으로 넘긴다 — 실측 {@code "zz:1"} 863µs/회, {@code "gg:hh"} 13ms(getaddrinfo).
     * 허용 문자를 {@code [0-9a-fA-F:.]} 로 좁혀 요청 스레드의 블로킹 I/O 를 원천 차단한다. scope id 의 {@code '%'} 도 여기서 걸린다.
     */
    private static boolean isIpv6LiteralCandidate(String address) {
        if (!isHexDigit(address.charAt(0)) && address.charAt(0) != ':') {
            return false;
        }
        for (int index = 0; index < address.length(); index++) {
            char character = address.charAt(index);
            if (!isHexDigit(character) && character != ':' && character != '.') {
                return false;
            }
        }
        return true;
    }

    // Character.digit(c, 16) 을 쓰지 않는다 — 그쪽은 아라비아-인도 숫자 등 비 ASCII 자릿수도 통과시킨다.
    private static boolean isHexDigit(char character) {
        return (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f')
                || (character >= 'A' && character <= 'F');
    }

    private static boolean isIpv4Literal(String address) {
        if (address.length() > 15) {
            return false;
        }

        int dots = 0;
        int digits = 0;
        int octet = 0;
        for (int index = 0; index < address.length(); index++) {
            char character = address.charAt(index);
            if (character >= '0' && character <= '9') {
                // 선행 0 거부: "01.2.3.4" 가 통과하면 "1.2.3.4" 와 다른 키가 되어 표기 회전만으로 한도가 배로 늘어난다.
                if (digits == 1 && octet == 0) {
                    return false;
                }
                digits++;
                octet = octet * 10 + character - '0';
                if (digits > 3 || octet > 255) {
                    return false;
                }
            } else if (character == '.' && digits > 0 && dots < 3) {
                dots++;
                digits = 0;
                octet = 0;
            } else {
                return false;
            }
        }
        return dots == 3 && digits > 0;
    }

    private record Generation(
            long windowStart, ConcurrentHashMap<String, AtomicInteger> counts, AtomicBoolean capWarned) {}
}
