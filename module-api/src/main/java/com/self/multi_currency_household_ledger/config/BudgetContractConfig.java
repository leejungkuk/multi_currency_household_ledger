package com.self.multi_currency_household_ledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 월 예산 카테고리 줄 순서의 뜻을 API 계약에 싣는다. 도메인 모듈에 swagger 의존을 넣지 않으려고 DTO 의 {@code @Schema} 대신 여기서
 * 단다.
 *
 * <p>스키마나 속성이 없으면 예외를 던진다 — DTO 속성 이름이 바뀌었을 때 설명이 조용히 사라지지 않고 스냅샷 생성이 실패해야 한다.
 */
@Configuration
class BudgetContractConfig {

    private static final String DISPLAY_ORDER = "배열 순서 = 표시 순서. ";

    @Bean
    OpenApiCustomizer budgetLineOrderOpenApiCustomizer() {
        return openApi -> {
            describe(
                    openApi,
                    "SaveBudgetRequest",
                    "categoryAmounts",
                    DISPLAY_ORDER + "이 순서가 그 달의 카테고리 줄 순서로 저장된다 — 순서만 바꾼 요청도 새 순서로 저장한다.");
            describe(
                    openApi,
                    "MonthlyBudgetResponse",
                    "categories",
                    DISPLAY_ORDER + "저장한 줄 순서(PUT categoryAmounts 의 배열 순서)이며, 삭제된 카테고리 줄(deleted: true)도 저장된 자리에 있다.");
        };
    }

    private static void describe(OpenAPI openApi, String schemaName, String propertyName, String description) {
        Schema<?> schema = openApi.getComponents().getSchemas().get(schemaName);
        Map<String, Schema> properties = schema == null ? null : schema.getProperties();
        Schema<?> property = properties == null ? null : properties.get(propertyName);
        if (property == null) {
            throw new IllegalStateException("계약 스키마에 " + schemaName + "." + propertyName + " 가 없다");
        }
        property.setDescription(description);
    }
}
