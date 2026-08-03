package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.AnnotationUtils;

class RequestSizeOpenApiCustomizerTest {

    private static final String ERROR_RESPONSE_REF = Components.COMPONENTS_SCHEMAS_REF + "ErrorResponse";

    private final OpenApiCustomizer customizer = new RequestSizeContractConfig().requestSizeOpenApiCustomizer();

    @Test
    void request_body가_있는_operation에만_413을_추가하고_기존_응답은_유지한다() {
        OpenAPI openApi = openApiWithBodyAndBodylessOperations();

        customizer.customise(openApi);

        assertThat(openApi.getPaths().get("/api/v1/ledgers").getPost().getResponses())
                .containsKeys("200", "413");
        assertThat(openApi.getPaths().get("/api/v1/ledgers").getGet().getResponses())
                .containsOnlyKeys("200");
    }

    @Test
    void 응답_스키마를_직접_등록하고_413에서_ErrorResponse를_참조한다() {
        OpenAPI openApi = openApiWithBodyAndBodylessOperations();

        customizer.customise(openApi);

        Schema<?> registered = openApi.getComponents().getSchemas().get("ErrorResponse");
        assertThat(registered).isNotNull();
        assertThat(registered.getProperties()).containsOnlyKeys("success", "code", "message", "timestamp");
        assertThat(response(openApi)
                        .getContent()
                        .get(APPLICATION_JSON_VALUE)
                        .getSchema()
                        .get$ref())
                .isEqualTo(ERROR_RESPONSE_REF);
    }

    /**
     * 지금은 항상 통과하지만, kill switch 도입 트리거가 발동해 런타임 설정이 조건부가 되는 날에도 계약 설정까지
     * 조건부로 따라가 413이 스냅샷에서 조용히 사라지는 회귀를 막는다.
     */
    @Test
    void 계약_설정은_조건부가_아니다() {
        assertThat(AnnotationUtils.findAnnotation(RequestSizeContractConfig.class, Conditional.class))
                .isNull();
    }

    @Test
    void 응답_example은_클라이언트가_분기할_에러_코드를_명시한다() {
        OpenAPI openApi = openApiWithBodyAndBodylessOperations();

        customizer.customise(openApi);

        assertThat(response(openApi).getContent().get(APPLICATION_JSON_VALUE).getExample())
                .isInstanceOfSatisfying(
                        Map.class, example -> assertThat(example.get("code")).isEqualTo("REQUEST_BODY_TOO_LARGE"));
    }

    private static ApiResponse response(OpenAPI openApi) {
        return openApi.getPaths()
                .get("/api/v1/ledgers")
                .getPost()
                .getResponses()
                .get("413");
    }

    /** components가 비어 있는 상태에서 시작해 ErrorResponse 등록까지 커스터마이저 책임으로 고정한다. */
    private static OpenAPI openApiWithBodyAndBodylessOperations() {
        return new OpenAPI()
                .paths(new Paths()
                        .addPathItem(
                                "/api/v1/ledgers",
                                new PathItem()
                                        .post(operation("createLedgerEntry").requestBody(new RequestBody()))
                                        .get(operation("getLedgerEntries"))));
    }

    private static Operation operation(String operationId) {
        return new Operation()
                .operationId(operationId)
                .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
    }
}
