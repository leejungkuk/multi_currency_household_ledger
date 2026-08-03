package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class RequestSizeLimitFilterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long MAX_BODY_BYTES = 8;

    @Test
    void content_length가_상한을_넘으면_본문을_읽지_않고_413을_반환한다() throws Exception {
        RequestSizeLimitFilter filter = filter();
        MockHttpServletRequest request = request("123456789".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chained.set(true));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.getContentAsString()).contains("\"code\":\"REQUEST_BODY_TOO_LARGE\"");
        assertThat(chained).isFalse();
    }

    @Test
    void content_length가_상한_이하면_원본_바이트를_그대로_전달한다() throws Exception {
        RequestSizeLimitFilter filter = filter();
        byte[] body = "12345678".getBytes(StandardCharsets.UTF_8);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> received = new AtomicReference<>();

        filter.doFilter(
                request(body),
                response,
                (wrapped, ignoredResponse) ->
                        received.set(wrapped.getInputStream().readAllBytes()));

        assertThat(received.get()).isEqualTo(body);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void content_length가_없는_본문은_실제_판독_중_상한을_넘으면_413을_반환한다() throws Exception {
        RequestSizeLimitFilter filter = filter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(chunkedRequest("123456789"), response, (wrapped, ignoredResponse) -> wrapped.getInputStream()
                .readAllBytes());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.getContentAsString()).contains("\"code\":\"REQUEST_BODY_TOO_LARGE\"");
    }

    @Test
    void 실제_본문이_정확히_상한이면_통과한다() throws Exception {
        RequestSizeLimitFilter filter = filter();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(chunkedRequest("12345678"), response, (wrapped, ignoredResponse) -> {
            assertThat(wrapped.getInputStream().readAllBytes()).hasSize((int) MAX_BODY_BYTES);
            chained.set(true);
        });

        assertThat(chained).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void reader로_읽어도_실제_바이트_상한을_강제한다() throws Exception {
        RequestSizeLimitFilter filter = filter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(chunkedRequest("가나다라"), response, (wrapped, ignoredResponse) -> wrapped.getReader()
                .readLine());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    }

    @Test
    void 벌크_read가_delegate로_위임한_반환_길이도_누적한다() throws Exception {
        RequestSizeLimitFilter filter = filter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 벌크 오버라이드가 없으면 InputStream 기본 구현이 read()를 반복해 방어는 유지된다. 이 테스트는 벌크를
        // delegate에 직접 위임하면서 반환 길이를 세지 않아 상한을 완전히 우회하는 회귀를 잡는다.
        filter.doFilter(chunkedRequest("123456789"), response, (wrapped, ignoredResponse) -> {
            byte[] buffer = new byte[16];
            wrapped.getInputStream().read(buffer, 2, 9);
        });

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    }

    @Test
    void 본문_없는_GET은_그대로_통과한다() throws Exception {
        RequestSizeLimitFilter filter = filter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/assets");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chained.set(true));

        assertThat(chained).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void 응답이_커밋된_뒤_상한을_넘으면_기존_응답을_덧쓰지_않는다() throws Exception {
        RequestSizeLimitFilter filter = filter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(chunkedRequest("123456789"), response, (wrapped, ignoredResponse) -> {
            response.setStatus(HttpStatus.ACCEPTED.value());
            response.getWriter().write("accepted");
            response.flushBuffer();
            wrapped.getInputStream().readAllBytes();
        });

        assertThat(response.getStatus()).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(response.getContentAsString()).isEqualTo("accepted");
    }

    /**
     * 이 테스트가 고정하는 축은 둘이다 — 본문 단언은 {@code reset()} 제거를, 헤더 단언은 {@code resetBuffer()}
     * 로의 완화를 잡는다({@code MockHttpServletResponse.resetBuffer()} 는 헤더를 지우지 않는다). 필터 주석이
     * {@code reset()} 을 고른 근거로 든 "getOutputStream() 을 잡은 뒤 getWriter() 가 터진다"는 서블릿 스펙
     * 근거이고 여기서 재현되지 않는다 — MockHttpServletResponse 는 그 상호배제를 강제하지 않는다.
     */
    @Test
    void 커밋되지_않은_부분_출력은_지우고_413_본문만_남긴다() throws Exception {
        RequestSizeLimitFilter filter = filter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(chunkedRequest("123456789"), response, (wrapped, ignoredResponse) -> {
            response.setHeader("X-Partial", "written");
            response.getWriter().write("partial");
            wrapped.getInputStream().readAllBytes();
        });

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.getContentAsString())
                .as("부분 출력을 지우지 않으면 본문이 partial{...} 로 깨져 ErrorResponse 파싱이 실패한다")
                .doesNotContain("partial")
                .startsWith("{")
                .contains("\"code\":\"REQUEST_BODY_TOO_LARGE\"");
        assertThat(response.getHeader("X-Partial")).isNull();
    }

    @Test
    void 상한은_양수여야_한다() {
        assertThatThrownBy(() -> new RequestSizeLimitFilter(0, OBJECT_MAPPER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestSizeLimitFilter(-1, OBJECT_MAPPER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 레이트_리밋_앞의_조기_거부와_필터_catch는_로그를_남기지_않는다() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestSizeLimitFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RequestSizeLimitFilter filter = filter();
            filter.doFilter(
                    request("123456789".getBytes(StandardCharsets.UTF_8)),
                    new MockHttpServletResponse(),
                    (ignoredRequest, ignoredResponse) -> {});
            filter.doFilter(
                    chunkedRequest("123456789"),
                    new MockHttpServletResponse(),
                    (wrapped, ignoredResponse) -> wrapped.getInputStream().readAllBytes());
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .as("두 경로는 레이트 리밋 전에 끝나므로 건당 로그가 있으면 무제한 로그 증폭이 가능하다")
                .isEmpty();
    }

    private static RequestSizeLimitFilter filter() {
        return new RequestSizeLimitFilter(MAX_BODY_BYTES, OBJECT_MAPPER);
    }

    private static MockHttpServletRequest request(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ledgers/sync");
        request.setContent(body);
        return request;
    }

    private static HttpServletRequest chunkedRequest(String body) {
        MockHttpServletRequest request = request(body.getBytes(StandardCharsets.UTF_8));
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return new HttpServletRequestWrapper(request) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
    }
}
