package com.self.multi_currency_household_ledger.config;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import com.self.multi_currency_household_ledger.common.dto.ErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RequestSizeContractConfig {

    private static final String CONTENT_TOO_LARGE = "413";

    @Bean
    OpenApiCustomizer requestSizeOpenApiCustomizer() {
        return openApi -> {
            ModelConverters.getInstance()
                    .readAllAsResolvedSchema(ErrorResponse.class)
                    .referencedSchemas
                    .forEach(openApi::schema);

            ApiResponse contentTooLarge = contentTooLargeResponse();
            openApi.getPaths().values().stream()
                    .flatMap(pathItem -> pathItem.readOperations().stream())
                    .filter(operation -> operation.getRequestBody() != null)
                    .forEach(operation -> operation.getResponses().addApiResponse(CONTENT_TOO_LARGE, contentTooLarge));
        };
    }

    private static ApiResponse contentTooLargeResponse() {
        return new ApiResponse()
                .description("Content Too Large")
                .content(new Content()
                        .addMediaType(
                                APPLICATION_JSON_VALUE,
                                new MediaType()
                                        .schema(new Schema<>()
                                                .$ref(Components.COMPONENTS_SCHEMAS_REF
                                                        + ErrorResponse.class.getSimpleName()))
                                        .example(Map.of(
                                                "success", false,
                                                "code", "REQUEST_BODY_TOO_LARGE",
                                                "message", "요청 본문이 너무 큽니다. 나눠서 보내 주세요.",
                                                "timestamp", "2026-01-01T00:00:00"))));
    }
}
