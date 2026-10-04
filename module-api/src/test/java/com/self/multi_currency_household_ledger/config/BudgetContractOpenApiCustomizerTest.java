package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

class BudgetContractOpenApiCustomizerTest {

    private final OpenApiCustomizer customizer = new BudgetContractConfig().budgetLineOrderOpenApiCustomizer();

    @Test
    void describes_category_amounts_and_categories_as_display_order() {
        OpenAPI openApi = openApi("categories");

        customizer.customise(openApi);

        Schema<?> request = schema(openApi, "SaveBudgetRequest");
        Schema<?> response = schema(openApi, "MonthlyBudgetResponse");
        assertThat(request.getProperties().get("categoryAmounts").getDescription())
                .isEqualTo("배열 순서 = 표시 순서. 이 순서가 그 달의 카테고리 줄 순서로 저장된다 — 순서만 바꾼 요청도 새 순서로 저장한다.");
        assertThat(response.getProperties().get("categories").getDescription())
                .isEqualTo(
                        "배열 순서 = 표시 순서. 저장한 줄 순서(PUT categoryAmounts 의 배열 순서)이며, 삭제된 카테고리 줄(deleted: true)도 저장된 자리에 있다.");
        assertThat(request.getProperties().get("currency").getDescription()).isNull();
        assertThat(response.getProperties().get("paymentGroups").getDescription())
                .isNull();
    }

    @Test
    void describes_deleted_categories_with_spending() {
        OpenAPI openApi = openApi("categories");

        customizer.customise(openApi);

        assertThat(schema(openApi, "MonthlyBudgetResponse")
                        .getProperties()
                        .get("deletedCategoriesWithSpending")
                        .getDescription())
                .isEqualTo(
                        "이 달에 지출 거래가 1건 이상 있는 삭제된 카테고리(몫 유무와 무관, 카테고리 정렬값·id 순). PUT categoryAmounts 에 넣으면 저장이 받는다. 몫이 없으면 그 지출은 otherCategories 에 들어 있다.");
    }

    @Test
    void fails_when_budget_property_is_missing() {
        OpenAPI openApi = openApi("categoryLines");

        assertThatThrownBy(() -> customizer.customise(openApi))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("categories");
    }

    private static OpenAPI openApi(String responseCategoriesName) {
        return new OpenAPI()
                .components(new Components()
                        .addSchemas(
                                "SaveBudgetRequest",
                                new ObjectSchema()
                                        .addProperty("categoryAmounts", new ArraySchema())
                                        .addProperty("currency", new StringSchema()))
                        .addSchemas(
                                "MonthlyBudgetResponse",
                                new ObjectSchema()
                                        .addProperty(responseCategoriesName, new ArraySchema())
                                        .addProperty("paymentGroups", new ArraySchema())
                                        .addProperty("deletedCategoriesWithSpending", new ArraySchema())));
    }

    private static Schema<?> schema(OpenAPI openApi, String name) {
        return openApi.getComponents().getSchemas().get(name);
    }
}
