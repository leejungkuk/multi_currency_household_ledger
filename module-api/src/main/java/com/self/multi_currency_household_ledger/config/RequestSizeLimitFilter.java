package com.self.multi_currency_household_ledger.config;

import com.self.multi_currency_household_ledger.common.exception.BusinessException;
import com.self.multi_currency_household_ledger.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

final class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;
    private final ObjectMapper objectMapper;

    RequestSizeLimitFilter(long maxBodyBytes, ObjectMapper objectMapper) {
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("Request body size limit must be positive.");
        }
        this.maxBodyBytes = maxBodyBytes;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            reject(response);
            return;
        }

        try {
            filterChain.doFilter(new CountingRequestWrapper(request, maxBodyBytes), response);
        } catch (RequestBodyTooLargeException ignored) {
            if (!response.isCommitted()) {
                // 다운스트림이 이미 버퍼에 쓴 부분 출력을 지우지 않으면 413 본문이 "부분출력+ErrorResponse"로 깨진다.
                // resetBuffer()가 아니라 reset()인 이유: getOutputStream()을 잡은 다운스트림 뒤에서는 버퍼만
                // 비워도 getWriter()가 IllegalStateException을 던져 413이 500이 된다. 그 선택 상태까지 지우는 것은 reset()뿐이다.
                response.reset();
                reject(response);
            }
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        SecurityConfig.writeErrorResponse(response, objectMapper, ErrorCode.Common.REQUEST_BODY_TOO_LARGE);
    }

    private static final class CountingRequestWrapper extends HttpServletRequestWrapper {

        private final long maxBodyBytes;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        private CountingRequestWrapper(HttpServletRequest request, long maxBodyBytes) {
            super(request);
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new CountingServletInputStream(super.getInputStream(), maxBodyBytes);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    private static final class CountingServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBodyBytes;
        private long bytesRead;

        private CountingServletInputStream(ServletInputStream delegate, long maxBodyBytes) {
            this.delegate = delegate;
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        private void count(int read) {
            bytesRead += read;
            if (bytesRead > maxBodyBytes) {
                throw new RequestBodyTooLargeException();
            }
        }
    }

    static final class RequestBodyTooLargeException extends BusinessException {

        private RequestBodyTooLargeException() {
            super(ErrorCode.Common.REQUEST_BODY_TOO_LARGE);
        }
    }
}
