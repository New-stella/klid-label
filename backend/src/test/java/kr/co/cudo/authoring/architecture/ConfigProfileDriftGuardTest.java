package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로파일 간 설정 드리프트 가드 — 환경별 값이 조용히 갈라지는 것을 차단한다.
 *
 * <p>배경(2026-07-27 설정 전수조사 C): multipart 한도가 local/dev/stg 에 완전히 동일한 값으로
 * 3중 복붙돼 한 곳만 고치면 갈라졌고, dev 에만 내부 IP·벤더 IP 기본값이 평문 커밋돼 있었으며,
 * dev/stg 의 dev 토글은 리터럴 {@code true} 라 환경변수로 끌 수단이 없었다.
 *
 * <p><b>가장 중요한 것은 prd multipart 한도</b>: 공통값을 500MB 로 올렸으므로 prd 가 명시
 * override 를 잃는 순간 운영 업로드 한도가 21MB → 500MB 로 조용히 상향된다(자원 소진, OWASP API4).
 */
class ConfigProfileDriftGuardTest {

    private static final Path MAIN_RESOURCES = Paths.get("src/main/resources");

    private static final String COMMON_YML = "application.yml";
    private static final String MAX_FILE_SIZE = "spring.servlet.multipart.max-file-size";
    private static final String MAX_REQUEST_SIZE = "spring.servlet.multipart.max-request-size";

    /** IPv4 리터럴(내부 IP·벤더 IP 평문 커밋 탐지용). */
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");

    @Test
    @DisplayName("prd_프로파일_multipart_한도는_21MB_1100MB로_유지된다")
    void prdMultipartLimitsStayTight() {
        // given: 공통 기본값이 500MB 로 상향된 상태에서 prd 프로파일을 적용한다
        Environment env = MainResourceYaml.environment(COMMON_YML, "application-prd.yml");

        // when
        String maxFileSize = env.getProperty(MAX_FILE_SIZE);
        String maxRequestSize = env.getProperty(MAX_REQUEST_SIZE);

        // then: 운영 업로드 한도는 상향되면 안 된다(자원 소진 방어 — OWASP API4)
        assertThat(maxFileSize)
                .as("prd 는 multipart 개당 한도를 21MB 로 명시 override 해야 한다(미명시 시 공통 500MB 로 조용히 상향)")
                .isEqualTo("21MB");
        assertThat(maxRequestSize)
                .as("prd 는 multipart 요청 한도를 1100MB 로 명시 override 해야 한다")
                .isEqualTo("1100MB");

        // and: 공통 상속이 아니라 prd yml 이 직접 선언해야 한다
        //      (공통값이 바뀌어도 운영 한도가 따라 올라가지 않도록 고정)
        assertThat(MainResourceYaml.keys("application-prd.yml"))
                .as("prd 의 multipart 한도는 공통 상속이 아닌 명시 override 여야 한다")
                .contains(MAX_FILE_SIZE, MAX_REQUEST_SIZE);
    }

    @Test
    @DisplayName("local_dev_stg_프로파일_multipart_한도는_500MB_1200MB이다")
    void nonProdMultipartLimitsComeFromCommon() {
        for (String profileYml : List.of("application-local.yml", "application-dev.yml", "application-stg.yml")) {
            // given
            Environment env = MainResourceYaml.environment(COMMON_YML, profileYml);

            // when / then: 값은 공통에서 상속하고 프로파일에는 복붙이 남아 있지 않아야 한다
            assertThat(env.getProperty(MAX_FILE_SIZE))
                    .as("%s 의 multipart 개당 한도", profileYml)
                    .isEqualTo("500MB");
            assertThat(env.getProperty(MAX_REQUEST_SIZE))
                    .as("%s 의 multipart 요청 한도", profileYml)
                    .isEqualTo("1200MB");
            assertThat(MainResourceYaml.keys(profileYml))
                    .as("%s 에 multipart 한도가 복붙돼 있다(공통 승격 후 중복 — 한쪽만 고치면 갈라진다)", profileYml)
                    .doesNotContain(MAX_FILE_SIZE, MAX_REQUEST_SIZE);
        }
    }

    @Test
    @DisplayName("dev_프로파일에_내부IP_기본값이_남아있지_않는다")
    void devProfileHasNoHardcodedIpLiteral() {
        // given
        String devYml = readMainResource("application-dev.yml");

        // when
        List<String> literals = new ArrayList<>();
        Matcher matcher = IPV4.matcher(devYml);
        while (matcher.find()) {
            literals.add(matcher.group());
        }

        // then: 내부 DB 호스트·외부 벤더 주소는 환경변수로만 주입한다(평문 커밋 금지)
        assertThat(literals)
                .as("application-dev.yml 에 IP 리터럴이 평문으로 남아 있다: %s", literals)
                .isEmpty();
    }

    @Test
    @DisplayName("dev토글은_환경변수로_override_가능하다")
    void devTogglesAreOverridableByEnvVariables() {
        for (String profileYml : List.of("application-dev.yml", "application-stg.yml")) {
            // given
            Object login = MainResourceYaml.rawValue(profileYml, "authoring.dev.login.enabled");
            Object upload = MainResourceYaml.rawValue(profileYml, "authoring.dev.upload.enabled");

            // then: 리터럴 true 는 끌 수단이 없다 → 환경변수 placeholder 형태여야 한다
            assertThat(String.valueOf(login))
                    .as("%s 의 dev 로그인 토글은 DEV_LOGIN_ENABLED 로 override 가능해야 한다", profileYml)
                    .contains("${DEV_LOGIN_ENABLED");
            assertThat(String.valueOf(upload))
                    .as("%s 의 dev 업로드 토글은 DEV_UPLOAD_ENABLED 로 override 가능해야 한다", profileYml)
                    .contains("${DEV_UPLOAD_ENABLED");

            // and: 환경변수 미주입 시 기존 동작(ON)은 그대로 보존한다
            Environment env = MainResourceYaml.environment(COMMON_YML, profileYml);
            assertThat(env.getProperty("authoring.dev.login.enabled"))
                    .as("%s 의 dev 로그인 토글 기본값(미주입 시 기존 동작 유지)", profileYml)
                    .isEqualTo("true");
            assertThat(env.getProperty("authoring.dev.upload.enabled"))
                    .as("%s 의 dev 업로드 토글 기본값(미주입 시 기존 동작 유지)", profileYml)
                    .isEqualTo("true");
        }
    }

    private String readMainResource(String fileName) {
        Path path = MAIN_RESOURCES.resolve(fileName);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("main 리소스 읽기 실패: " + path.toAbsolutePath(), e);
        }
    }
}
