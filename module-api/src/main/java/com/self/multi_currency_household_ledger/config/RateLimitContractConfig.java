package com.self.multi_currency_household_ledger.config;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import com.self.multi_currency_household_ledger.common.dto.ErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * 레이트 리밋의 429 를 API 계약에 싣는다. 필터가 Security 체인 안에 있어 모든 경로에 적용되므로 전역 부여가 사실에 맞다.
 *
 * <p>{@link RateLimitConfig} 와 달리 <b>조건이 없다</b> — {@code enabled=false} 상태에서 스냅샷을 재생성해도
 * 429 가 계약에서 사라지면 안 된다(리밋을 꺼도 계약은 불변).
 */
@Configuration
class RateLimitContractConfig {

    private static final String TOO_MANY_REQUESTS = "429";

    @Bean
    OpenApiCustomizer rateLimitOpenApiCustomizer() {
        return openApi -> {
            // ErrorResponse 는 어떤 operation 의 응답 타입도 아니라 springdoc 이 스키마를 만들어두지 않는다.
            // 다른 DTO 와 같은 메커니즘으로 해석해야 계약 문서가 일관되므로 ModelConverters 로 등록한다.
            ModelConverters.getInstance()
                    .readAllAsResolvedSchema(ErrorResponse.class)
                    .referencedSchemas
                    .forEach(openApi::schema);

            ApiResponse tooManyRequests = tooManyRequestsResponse();
            openApi.getPaths().values().stream()
                    .flatMap(pathItem -> pathItem.readOperations().stream())
                    .forEach(operation -> operation.getResponses().addApiResponse(TOO_MANY_REQUESTS, tooManyRequests));
        };
    }

    private static ApiResponse tooManyRequestsResponse() {
        return new ApiResponse()
                .description("Too Many Requests")
                .addHeaderObject(
                        HttpHeaders.RETRY_AFTER,
                        new Header()
                                .description("Seconds to wait before retrying.")
                                .schema(new IntegerSchema()))
                .content(new Content()
                        .addMediaType(
                                APPLICATION_JSON_VALUE,
                                new MediaType()
                                        .schema(new Schema<>()
                                                .$ref(Components.COMPONENTS_SCHEMAS_REF
                                                        + ErrorResponse.class.getSimpleName()))));
    }
}
