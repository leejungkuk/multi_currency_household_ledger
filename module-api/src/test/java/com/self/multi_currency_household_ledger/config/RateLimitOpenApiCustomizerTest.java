package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpHeaders;

/**
 * 계약(openapi.json)의 429 회귀를 일반 빌드에서 잡는 유일한 테스트. {@code OpenApiSnapshotTest} 는 {@code @Tag("snapshot")} 이라
 * {@code test} 에서 제외되고, 내용도 문자열 존재만 본다.
 */
class RateLimitOpenApiCustomizerTest {

    private static final String ERROR_RESPONSE_REF = Components.COMPONENTS_SCHEMAS_REF + "ErrorResponse";

    private final OpenApiCustomizer customizer = new RateLimitContractConfig().rateLimitOpenApiCustomizer();

    @Test
    void 모든_HTTP_메서드의_operation_에_429_를_추가하고_기존_응답은_유지한다() {
        OpenAPI openApi = openApiWithEveryMethod();

        customizer.customise(openApi);

        assertThat(operations(openApi)).hasSize(9).allSatisfy(operation -> assertThat(operation.getResponses())
                .containsKeys("200", "429"));
    }

    @Test
    void 모든_429_본문이_components_에_등록된_ErrorResponse_를_참조한다() {
        OpenAPI openApi = openApiWithEveryMethod();

        customizer.customise(openApi);

        Schema<?> registered = openApi.getComponents().getSchemas().get("ErrorResponse");
        assertThat(registered).isNotNull();
        assertThat(registered.getProperties()).containsOnlyKeys("success", "code", "message", "timestamp");
        // 첫 operation 만 보면 operation 별로 다른 429 를 만드는 구현을 놓친다.
        assertThat(operations(openApi)).allSatisfy(operation -> assertThat(operation
                        .getResponses()
                        .get("429")
                        .getContent()
                        .get(APPLICATION_JSON_VALUE)
                        .getSchema()
                        .get$ref())
                .isEqualTo(ERROR_RESPONSE_REF));
    }

    @Test
    void 모든_429_에_Retry_After_헤더가_선언된다() {
        OpenAPI openApi = openApiWithEveryMethod();

        customizer.customise(openApi);

        assertThat(operations(openApi)).allSatisfy(operation -> assertThat(
                        operation.getResponses().get("429").getHeaders())
                .hasEntrySatisfying(
                        HttpHeaders.RETRY_AFTER,
                        header -> assertThat(header.getSchema().getType()).isEqualTo("integer")));
    }

    /**
     * 계약을 지키는 것은 커스터마이저 동작만이 아니다. 이 설정이 조건부가 되면 리밋을 끈 채 스냅샷을 재생성할 때 429 가 계약에서 조용히 사라진다(실측:
     * {@code @ConditionalOnProperty} 를 붙이고 {@code enabled=false} 로 재생성하면 429 보유 operation 이 18 → 0). 위 세 테스트는
     * 커스터마이저를 직접 생성해 배선을 타지 않으므로 그 회귀를 잡지 못한다 — 여기서 따로 막는다.
     */
    @Test
    void 계약_설정은_조건부가_아니다() {
        assertThat(AnnotationUtils.findAnnotation(RateLimitContractConfig.class, Conditional.class))
                .isNull();
    }

    private static List<Operation> operations(OpenAPI openApi) {
        return openApi.getPaths().values().stream()
                .flatMap(pathItem -> pathItem.readOperations().stream())
                .toList();
    }

    /** components 가 비어 있는 상태에서 시작한다 — 스키마 등록까지 커스터마이저의 책임이다. */
    private static OpenAPI openApiWithEveryMethod() {
        return new OpenAPI()
                .paths(new Paths()
                        // readOperations() 가 반환하는 8종을 모두 채워, 일부 메서드를 빠뜨리는 구현이 통과하지 못하게 한다.
                        .addPathItem(
                                "/api/v1/ledgers",
                                new PathItem()
                                        .get(operation("getLedgerEntries"))
                                        .put(operation("replaceLedgerEntry"))
                                        .post(operation("createLedgerEntry"))
                                        .delete(operation("deleteLedgerEntry"))
                                        .patch(operation("patchLedgerEntry"))
                                        .head(operation("headLedgerEntries"))
                                        .options(operation("optionsLedgerEntries"))
                                        .trace(operation("traceLedgerEntries")))
                        .addPathItem("/api/v1/exchange-rates", new PathItem().get(operation("getExchangeRates"))));
    }

    private static Operation operation(String operationId) {
        return new Operation()
                .operationId(operationId)
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
    }
}
