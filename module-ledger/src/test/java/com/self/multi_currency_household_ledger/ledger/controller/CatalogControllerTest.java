package com.self.multi_currency_household_ledger.ledger.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.self.multi_currency_household_ledger.common.annotation.CurrentMemberId;
import com.self.multi_currency_household_ledger.common.web.CacheControlHeaders;
import com.self.multi_currency_household_ledger.ledger.domain.TransactionType;
import com.self.multi_currency_household_ledger.ledger.dto.AssetResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CategoryResponse;
import com.self.multi_currency_household_ledger.ledger.dto.CreateCustomCategoryRequest;
import com.self.multi_currency_household_ledger.ledger.service.CatalogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = CatalogController.class)
@Import(CatalogControllerTest.CurrentMemberIdResolverConfig.class)
class CatalogControllerTest {

    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CatalogService catalogService;

    @Test
    @DisplayName("거래 유형별 활성화된 카테고리 목록을 조회한다")
    void get_categories_success() throws Exception {
        given(catalogService.getCategories(TransactionType.EXPENSE))
                .willReturn(List.of(new CategoryResponse(1L, "FOOD_DINING", "식비", "Food & Dining", "🍽️", 1)));

        mockMvc.perform(get("/api/v1/categories")
                        .param("transactionType", "EXPENSE")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CacheControlHeaders.PUBLIC_READ))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("FOOD_DINING"))
                .andExpect(jsonPath("$.data[0].displayNameKo").value("식비"))
                .andExpect(jsonPath("$.data[0].displayNameEn").value("Food & Dining"))
                .andExpect(jsonPath("$.data[0].icon").value("🍽️"));
    }

    @Test
    @DisplayName("활성화된 자산 목록을 조회한다")
    void get_assets_success() throws Exception {
        given(catalogService.getAssets()).willReturn(List.of(new AssetResponse(3L, "CASH", "현금", "Cash", 3)));

        mockMvc.perform(get("/api/v1/assets").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, CacheControlHeaders.PUBLIC_READ))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("CASH"))
                .andExpect(jsonPath("$.data[0].displayNameKo").value("현금"))
                .andExpect(jsonPath("$.data[0].displayNameEn").value("Cash"))
                .andExpect(jsonPath("$.data[0].icon").doesNotExist());
    }

    @Test
    @DisplayName("내 커스텀 카테고리 목록을 200 응답 봉투로 반환한다")
    void get_custom_categories_success() throws Exception {
        given(catalogService.getCustomCategories(MEMBER_ID, TransactionType.EXPENSE))
                .willReturn(List.of(new CategoryResponse(10_000L, "CUSTOM", "반려견", "반려견", "🐶", 1000)));

        mockMvc.perform(get("/api/v1/categories/custom").param("transactionType", "EXPENSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(10_000L))
                .andExpect(jsonPath("$.data[0].code").value("CUSTOM"));
    }

    @Test
    @DisplayName("커스텀 카테고리를 생성해 200 응답 봉투로 반환한다")
    void create_custom_category_success() throws Exception {
        CreateCustomCategoryRequest request = new CreateCustomCategoryRequest(TransactionType.EXPENSE, "반려견", "🐶");
        given(catalogService.createCustomCategory(eq(MEMBER_ID), any(CreateCustomCategoryRequest.class)))
                .willReturn(new CategoryResponse(10_000L, "CUSTOM", "반려견", "반려견", "🐶", 1000));

        mockMvc.perform(post("/api/v1/categories/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(10_000L))
                .andExpect(jsonPath("$.data.displayNameKo").value("반려견"));
    }

    @Test
    @DisplayName("커스텀 카테고리 삭제는 null data를 포함한 200 응답이다")
    void delete_custom_category_success() throws Exception {
        mockMvc.perform(delete("/api/v1/categories/custom/{id}", 10_000L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value((Object) null));

        then(catalogService).should().deleteCustomCategory(MEMBER_ID, 10_000L);
    }

    @Test
    @DisplayName("커스텀 카테고리 이름이 공백이면 400이다")
    void create_custom_category_rejects_blank_name() throws Exception {
        assertInvalidRequest(new CreateCustomCategoryRequest(TransactionType.EXPENSE, " ", null));
    }

    @Test
    @DisplayName("커스텀 카테고리 이름이 51자면 400이다")
    void create_custom_category_rejects_name_over_50_characters() throws Exception {
        assertInvalidRequest(new CreateCustomCategoryRequest(TransactionType.EXPENSE, "가".repeat(51), null));
    }

    @Test
    @DisplayName("커스텀 카테고리 아이콘이 21자면 400이다")
    void create_custom_category_rejects_icon_over_20_characters() throws Exception {
        assertInvalidRequest(new CreateCustomCategoryRequest(TransactionType.EXPENSE, "반려견", "가".repeat(21)));
    }

    @Test
    @DisplayName("커스텀 카테고리 거래 유형이 누락되면 400이다")
    void create_custom_category_rejects_missing_transaction_type() throws Exception {
        assertInvalidRequest(new CreateCustomCategoryRequest(null, "반려견", null));
    }

    private void assertInvalidRequest(CreateCustomCategoryRequest request) throws Exception {
        mockMvc.perform(post("/api/v1/categories/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @TestConfiguration
    static class CurrentMemberIdResolverConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(CurrentMemberId.class)
                            && UUID.class.equals(parameter.getParameterType());
                }

                @Override
                public Object resolveArgument(
                        MethodParameter parameter,
                        ModelAndViewContainer mavContainer,
                        NativeWebRequest webRequest,
                        org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                    return MEMBER_ID;
                }
            });
        }
    }
}
