package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 증강 Resilience4j 인스턴스 <b>실제 설정 로딩</b> 가드 — DEV_FIX LOW-9.
 *
 * <p>기존 서킷 테스트({@code HttpExternalAugmentClientQueryTest})는 <b>자체 레지스트리</b>를 만들어
 * 주입하므로, 클라이언트가 참조하는 인스턴스명이 {@code application.yml} 에 실제로 정의돼 있는지는
 * 검증하지 못한다. Resilience4j 레지스트리는 미정의 이름을 요청받으면 <b>기본 설정으로 조용히 생성</b>
 * 하므로, 인스턴스명 오타나 yml 누락이 운영에서 "설정이 안 먹는" 형태로만 드러난다.
 *
 * <p>그래서 여기서는 {@code AugmentExternalModeConfigTest} 와 같은 방식으로 <b>배포용 yml 파일 자체</b>를
 * Spring 과 동일한 로더로 읽어 인스턴스 존재와 핵심 정책을 단언한다.
 */
class AugmentResilienceConfigTest {

    private static final Path APPLICATION_YML =
            Path.of("src", "main", "resources", "application.yml");

    private static final String NON_RETRYABLE = NonRetryableExternalException.class.getName();

    private static final YamlPropertySourceLoader LOADER = new YamlPropertySourceLoader();

    private static Object property(String key) throws IOException {
        assertThat(Files.isReadable(APPLICATION_YML)).as("application.yml 존재").isTrue();
        List<PropertySource<?>> sources =
                LOADER.load("application.yml", new FileSystemResource(APPLICATION_YML));
        for (PropertySource<?> source : sources) {
            if (source.containsProperty(key)) {
                return source.getProperty(key);
            }
        }
        return null;
    }

    private static void assertInstanceDefined(String kind, String instance) throws IOException {
        String prefix = "resilience4j." + kind + ".instances." + instance + ".";
        assertThat(property(prefix + (kind.equals("retry")
                ? "max-attempts" : "failure-rate-threshold")))
                .as("%s 인스턴스 '%s' 가 application.yml 에 정의돼 있어야 한다 "
                        + "(미정의면 기본값으로 조용히 폴백한다)", kind, instance)
                .isNotNull();
        // 4xx/디코딩 실패 등 결정적 실패는 재시도·서킷 집계에서 빠져야 한다.
        assertThat(property(prefix + "ignore-exceptions[0]"))
                .as("%s 인스턴스 '%s' 의 ignore-exceptions 에 %s 가 등록돼야 한다",
                        kind, instance, NON_RETRYABLE)
                .isEqualTo(NON_RETRYABLE);
    }

    @Test
    @DisplayName("위탁_폴링_취소_서킷_인스턴스가_실제_yml_에_정의돼_있다")
    void circuitBreakerInstancesAreDefined() throws IOException {
        assertInstanceDefined("circuitbreaker", HttpExternalAugmentClient.SUBMIT_RESILIENCE_NAME);
        assertInstanceDefined("circuitbreaker", HttpExternalAugmentClient.QUERY_RESILIENCE_NAME);
        assertInstanceDefined("circuitbreaker", HttpExternalAugmentClient.CANCEL_RESILIENCE_NAME);
    }

    @Test
    @DisplayName("위탁_폴링_취소_재시도_인스턴스가_실제_yml_에_정의돼_있다")
    void retryInstancesAreDefined() throws IOException {
        assertInstanceDefined("retry", HttpExternalAugmentClient.SUBMIT_RESILIENCE_NAME);
        assertInstanceDefined("retry", HttpExternalAugmentClient.QUERY_RESILIENCE_NAME);
        assertInstanceDefined("retry", HttpExternalAugmentClient.CANCEL_RESILIENCE_NAME);
    }

    /** 폴링 장애가 사용자 능동 행위(취소)를 인질로 잡지 않도록 세 인스턴스는 모두 별개여야 한다. */
    @Test
    @DisplayName("세_인스턴스명이_서로_달라_장애가_전파되지_않는다")
    void instanceNamesAreDistinct() {
        assertThat(List.of(HttpExternalAugmentClient.SUBMIT_RESILIENCE_NAME,
                        HttpExternalAugmentClient.QUERY_RESILIENCE_NAME,
                        HttpExternalAugmentClient.CANCEL_RESILIENCE_NAME))
                .doesNotHaveDuplicates();
    }
}
