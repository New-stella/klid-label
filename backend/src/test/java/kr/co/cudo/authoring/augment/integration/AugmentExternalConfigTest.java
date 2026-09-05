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
 * 외부 증강 연동 설정 회귀 가드 — <b>전 환경이 연동 형상인가</b>.
 *
 * <p><b>왜 프로퍼티 주입/컨텍스트 로딩이 아니라 실제 yml 파일을 파싱하는가</b>:
 * <ol>
 *   <li>{@code withPropertyValues} 로 키를 직접 주입하면, 배포 yml 에서 {@code augment:} 블록이
 *       {@code kpst:} 하위로 잘못 중첩돼 <b>키 자체가 존재하지 않던 결함</b>(ENV-ISSUE-01)을 못 잡는다.</li>
 *   <li>테스트 클래스패스에는 {@code src/test/resources/application-local.yml} 이 있어 컨텍스트를
 *       띄우면 <b>배포용 local yml 이 통째로 가려진다</b>.</li>
 * </ol>
 * 따라서 {@code src/main/resources} 의 <b>실제 파일</b>을 Spring 과 동일한 로더로 읽어 평탄화 키를 단언한다.
 *
 * <h3>★ 무엇이 뒤집혔나 (2026-09-03 사용자 확정, 구속)</h3>
 * <p>구 시험은 <b>dev·stg·prd 에 {@code mode: noop} 이 명시되는지</b>를 고정하고 있었다. 그
 * <b>미연동 모드 토글 축 자체가 폐기</b>됐다 — 「나갈지 말지」를 환경설정으로 고르지 않고
 * <b>연동이 유일한 형상</b>이며, 「아직 연동 안 됨」은 <b>위탁 주소가 비어 있는 것</b>으로만 표현된다.
 * 그래서 이 시험은 이제 <b>그 키의 부재</b>와 <b>주소 기본값</b>을 고정한다.
 */
class AugmentExternalConfigTest {

    /** 폐기된 미연동 모드 토글 키. */
    private static final String RETIRED_MODE_KEY = "authoring.augment.external.mode";
    private static final String BASE_URL_KEY = "authoring.augment.external.base-url";
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
    @DisplayName("★폐기된_미연동_모드_토글_키가_어느_프로파일_yml_에도_남아있지_않다")
    void retiredModeKeyIsGoneFromEveryProfile() throws IOException {
        for (String yml : List.of("application.yml", "application-local.yml", "application-dev.yml",
                "application-stg.yml", "application-prd.yml")) {
            assertThat(property(yml, RETIRED_MODE_KEY))
                    .as("%s 에 폐기된 미연동 모드 토글이 되살아났습니다 — 그 우회 때문에 dev/stg/prd 가 "
                            + "전부 미연동으로 도망가 위탁이 한 번도 나간 적이 없었다", yml)
                    .isNull();
            // 오중첩되어 있던 구 키(ENV-ISSUE-01)도 함께 고정한다.
            assertThat(property(yml, "kpst.augment.external.mode")).isNull();
        }
    }

    @Test
    @DisplayName("공통_기본_위탁주소는_빈값이다 — 목업 기본값이 운영 형상에 상주하지 않는다")
    void commonDefaultBaseUrlIsBlank() throws IOException {
        assertThat(defaultOf(property("application.yml", BASE_URL_KEY))).isEmpty();
        assertThat(property("application.yml", "authoring.augment.external.max-input-files"))
                .isNotNull();
    }

    @Test
    @DisplayName("★local_과_dev_는_목업_벤더_서버로_실제_위탁한다")
    void localAndDevPointAtMockVendor() throws IOException {
        // local-must-use-mock-server 원칙 — 본 프로그램이 스스로 결과를 만들면 연동 성립을 검증할 수 없다.
        assertThat(defaultOf(property("application-local.yml", BASE_URL_KEY)))
                .isEqualTo("http://localhost:9400");
        assertThat(defaultOf(property("application-dev.yml", BASE_URL_KEY)))
                .isEqualTo("http://klid-mock-server:9400");
    }

    @Test
    @DisplayName("★dev_는_위탁_주소와_콜백_allowlist_의_짝이_맞는다 — 한쪽만_열면_위탁이_막힌다")
    void devPairsBaseUrlWithCallbackAllowlist() throws IOException {
        // 위탁이 활성인데 콜백 allowlist 가 비면 GenAiIntegrationWiringGuard 가 <위탁을> 막는다
        //   (2026-09-03 — 구 동작은 기동 차단이었고 그 자리가 옮겨졌다. ADR-062).
        //   dev 는 위탁 주소를 갖게 됐으므로 allowlist 도 함께 명시해야 한다.
        assertThat(defaultOf(property("application-dev.yml", "webhook.genai.allowed-ip-cidrs")))
                .as("dev 위탁 주소를 넣었으면 콜백 allowlist 도 명시해야 한다")
                .isNotBlank()
                .isNotEqualTo("none");
    }

    @Test
    @DisplayName("stg_prd_는_위탁주소를_주입하지_않는다 — 미연동이 주소로만 표현된다")
    void stgAndPrdLeaveBaseUrlUnset() throws IOException {
        for (String yml : List.of("application-stg.yml", "application-prd.yml")) {
            Object raw = property(yml, BASE_URL_KEY);
            // 프로파일이 아예 선언하지 않고 공통 기본값(빈 값)을 상속하는 것이 정상이다.
            assertThat(raw == null || defaultOf(raw).isEmpty())
                    .as("%s 는 벤더 주소를 배포 형상에 상주시키지 않는다(운영자가 주입한다)", yml)
                    .isTrue();
        }
    }
}
