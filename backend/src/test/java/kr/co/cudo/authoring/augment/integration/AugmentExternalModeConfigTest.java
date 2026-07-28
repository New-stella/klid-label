package kr.co.cudo.authoring.augment.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 외부 증강 연동 모드 설정 회귀 가드 — Phase 7-A1 (ENV-ISSUE-01).
 *
 * <p><b>왜 프로퍼티 주입/컨텍스트 로딩이 아니라 실제 yml 파일을 파싱하는가</b>:
 * <ol>
 *   <li>기존 {@code DevAugmentCallbackSimulatorBeanConditionTest} 는 {@code withPropertyValues} 로
 *       키를 직접 주입해 검증했기 때문에, application-local.yml 에서 {@code augment:} 블록이
 *       {@code kpst:} 하위로 잘못 중첩돼 <b>키 자체가 존재하지 않던 결함</b>을 못 잡았다.</li>
 *   <li>테스트 클래스패스에는 {@code src/test/resources/application-local.yml} 이 있어 컨텍스트를
 *       띄우면 <b>배포용 local yml 이 통째로 가려진다</b>. 즉 컨텍스트 기반 검증으로는 실제 배포
 *       파일의 중첩 드리프트를 영원히 잡을 수 없다.</li>
 * </ol>
 * 따라서 {@code src/main/resources} 의 <b>실제 파일</b>을 Spring 과 동일한 로더
 * ({@link YamlPropertySourceLoader})로 읽어 최종 평탄화 키를 단언한다.
 */
class AugmentExternalModeConfigTest {

    private static final String KEY = "authoring.augment.external.mode";
    private static final Path RESOURCES = Path.of("src", "main", "resources");

    private static final YamlPropertySourceLoader LOADER = new YamlPropertySourceLoader();

    /** 실제 yml 파일에서 평탄화된 프로퍼티 값을 읽는다. 없으면 null. */
    private static Object property(String fileName, String key) throws IOException {
        Path path = RESOURCES.resolve(fileName);
        assertThat(Files.isReadable(path)).as(fileName + " 존재").isTrue();
        Resource resource = new FileSystemResource(path);
        List<PropertySource<?>> sources = LOADER.load(fileName, resource);
        for (PropertySource<?> source : sources) {
            if (source.containsProperty(key)) {
                return source.getProperty(key);
            }
        }
        return null;
    }

    /** {@code ${VAR:default}} 형태 값에서 기본값만 뽑는다. */
    private static String defaultOf(Object rawValue) {
        assertThat(rawValue).isNotNull();
        String raw = rawValue.toString();
        int colon = raw.indexOf(':');
        if (raw.startsWith("${") && colon > 0) {
            return raw.substring(colon + 1, raw.length() - 1);
        }
        return raw;
    }

    @Test
    @DisplayName("application_local_yml_의_augment_설정이_authoring_최상위에서_로드됨")
    void localYamlExposesAugmentModeUnderAuthoring() throws IOException {
        Object mode = property("application-local.yml", KEY);
        assertThat(mode)
                .as("kpst 하위 오중첩이면 이 키가 null 이 된다(ENV-ISSUE-01 회귀 가드)")
                .isNotNull();
        // Phase 7-A2 — 자족 시뮬레이터(dev)를 제거했으므로 local 도 mock-server 로 실제 위탁한다.
        assertThat(defaultOf(mode)).isEqualTo("http");
        // 오중첩되어 있던 구 키가 되살아나지 않는지도 함께 고정한다.
        assertThat(property("application-local.yml", "kpst.augment.external.mode")).isNull();
        assertThat(property("application-local.yml", "authoring.augment.external.base-url"))
                .isNotNull();
    }

    @Test
    @DisplayName("공통_기본_모드는_http_다")
    void defaultModeIsHttp() throws IOException {
        assertThat(defaultOf(property("application.yml", KEY))).isEqualTo("http");
        assertThat(property("application.yml", "authoring.augment.external.base-url")).isNotNull();
        assertThat(property("application.yml", "authoring.augment.external.max-input-files"))
                .isNotNull();
    }

    @Test
    @DisplayName("prd_프로파일에서는_noop_이_명시되어_기본값_http_가_새지_않는다")
    void prdPinsNoop() throws IOException {
        assertThat(defaultOf(property("application-prd.yml", KEY))).isEqualTo("noop");
    }

    @Test
    @DisplayName("dev_stg_프로파일도_noop_이_명시된다")
    void devAndStgPinNoop() throws IOException {
        assertThat(defaultOf(property("application-dev.yml", KEY))).isEqualTo("noop");
        assertThat(defaultOf(property("application-stg.yml", KEY))).isEqualTo("noop");
    }
}
