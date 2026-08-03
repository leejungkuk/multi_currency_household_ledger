package com.self.multi_currency_household_ledger.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.self.multi_currency_household_ledger.exchange.domain.CurrencyCode;
import com.self.multi_currency_household_ledger.ledger.dto.ImportLedgerEntriesRequest;
import com.self.multi_currency_household_ledger.ledger.dto.ImportLedgerEntriesRequest.ImportLedgerEntryItem;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

/**
 * 서버가 유효하다고 선언한 최대 import 페이로드가 기본 본문 상한 안에 드는지 고정한다. 잡는 회귀는 서버 쪽
 * 변경(MAX_ENTRIES, memo {@code @Size.max}, 상한 하향)뿐이고 iOS 저장소 단독 변경(입력 캡·배치 크기)은
 * 감지하지 못한다.
 *
 * <p>직렬화는 bare {@code new ObjectMapper()} 가 아니라 {@code application.yml} 의 {@code spring.jackson.*} 를
 * 그대로 먹인 오토컨피그 ObjectMapper 로 한다 — naming strategy·property inclusion 이 추가되면 실제 wire
 * 크기가 달라지므로 예산 가드가 그 변화를 따라가야 한다. 앱 컨텍스트를 통째로 띄우지 않는 이유는
 * {@code @EnableJpaAuditing} 이 JPA 메타모델을 요구해 JSON 슬라이스가 기동하지 않기 때문이다.
 */
class ImportPayloadSizeBudgetTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withPropertyValues(jacksonProperties());

    @Test
    void 서버가_허용하는_최대_import는_기본_본문_상한_안에_든다() throws Exception {
        int memoMax = ImportLedgerEntryItem.class
                .getDeclaredField("memo")
                .getAnnotation(Size.class)
                .max();
        String memo = "한".repeat(memoMax);
        List<ImportLedgerEntryItem> entries = IntStream.range(0, ImportLedgerEntriesRequest.MAX_ENTRIES)
                .mapToObj(index -> new ImportLedgerEntryItem(
                        new UUID(0, index),
                        new BigDecimal("99999999.00"),
                        CurrencyCode.USD,
                        13L,
                        6L,
                        LocalDate.of(9999, 12, 31),
                        memo))
                .toList();

        contextRunner.run(context -> {
            byte[] payload = context.getBean(ObjectMapper.class)
                    .writeValueAsString(new ImportLedgerEntriesRequest(entries))
                    .getBytes(StandardCharsets.UTF_8);

            assertThat((long) payload.length)
                    .as("서버가 유효하다고 선언한 최대 import가 기본 요청 본문 상한을 넘으면 정상 동기화가 깨진다")
                    .isLessThan(defaultMaxBodyBytes());
        });
    }

    /** 키를 나열하지 않고 yml 에서 통째로 가져온다 — 설정이 추가돼도 테스트가 자동으로 따라간다. */
    private static String[] jacksonProperties() {
        EnumerablePropertySource<?> source = (EnumerablePropertySource<?>) applicationYml();
        return Arrays.stream(source.getPropertyNames())
                .filter(name -> name.startsWith("spring.jackson."))
                .map(name -> name + "=" + source.getProperty(name))
                .toArray(String[]::new);
    }

    private static long defaultMaxBodyBytes() throws IOException {
        String placeholder = (String) applicationYml().getProperty("woni.security.max-request-body-size");
        String defaultValue = placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1);
        return DataSize.parse(defaultValue).toBytes();
    }

    private static PropertySource<?> applicationYml() {
        try {
            List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
            return sources.getFirst();
        } catch (IOException e) {
            throw new IllegalStateException("application.yml 을 읽지 못했다.", e);
        }
    }
}
