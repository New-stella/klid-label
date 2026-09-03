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
 * <p><b>가장 중요한 것은 prd multipart 요청 총량</b>: prd 가 명시 override 를 잃는 순간
 * 요청 총량이 공통 1200MB 로 조용히 상향된다(자원 소진, OWASP API4).
 * <p>개당 한도는 2026-08-24(CO-008) 에 21MB → 500MB 로 올렸다 — 수동 업로드를 운영에 노출하면서
 * "prd 에는 dev 업로드 경로가 없다" 는 구 전제가 무너졌기 때문이다. 값이 공통과 같아졌지만
 * <b>명시 선언은 유지</b>한다: 공통값이 다시 움직여도 운영이 따라 올라가지 않게 고정하는 것이 이 가드의 목적이다.
 */
class ConfigProfileDriftGuardTest {

    private static final Path MAIN_RESOURCES = Paths.get("src/main/resources");

    private static final String COMMON_YML = "application.yml";
    private static final String MAX_FILE_SIZE = "spring.servlet.multipart.max-file-size";
    private static final String MAX_REQUEST_SIZE = "spring.servlet.multipart.max-request-size";

    /** IPv4 리터럴(내부 IP·벤더 IP 평문 커밋 탐지용). */
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");

    /**
     * ★ 예외 — <b>전면 허용 와일드카드 CIDR</b>({@code 0.0.0.0/0}) 하나만 허용한다 (2026-09-03).
     *
     * <p>이 가드가 막는 것은 <b>주소</b>(내부 DB 호스트·외부 벤더 IP)의 평문 커밋이다. 와일드카드는
     * 주소가 아니라 "출처를 가리지 않는다" 는 <b>정책 표기</b>라 내부 토폴로지를 유출하지 않고,
     * 환경변수로 옮겨도 숨길 것이 없다. dev 가 이 값을 갖게 된 것은 증강 위탁이 목업 벤더 서버로
     * 실제로 나가게 되면서(2026-09-03 — 미연동 모드 토글 폐기) <b>콜백 IP allowlist 를 짝으로
     * 명시해야</b> 하기 때문이며, 목 서버 발신 IP 는 docker 브리지 사설 IP 라 대역을 고정할 수 없다.
     *
     * <p>⚠ <b>이 예외를 넓히지 말 것</b> — 구체 주소는 종전대로 전부 걸린다. 정확히 이 한 값만이다.
     */
    private static final String WILDCARD_CIDR = "0.0.0.0/0";

    @Test
    @DisplayName("prd_프로파일_multipart_한도는_500MB_1100MB로_유지된다")
    void prdMultipartLimitsStayTight() {
        // given: 공통 기본값이 500MB 로 상향된 상태에서 prd 프로파일을 적용한다
        Environment env = MainResourceYaml.environment(COMMON_YML, "application-prd.yml");

        // when
        String maxFileSize = env.getProperty(MAX_FILE_SIZE);
        String maxRequestSize = env.getProperty(MAX_REQUEST_SIZE);

        // then: 운영 업로드 한도는 상향되면 안 된다(자원 소진 방어 — OWASP API4)
        // 개당 한도(CO-008, 2026-08-24): 21MB → 500MB. 수동 업로드를 운영에 노출하기로 하면서
        // "prd 에는 dev 업로드 경로가 없다" 는 구 전제가 무너졌다. 공통값과 같은 값이지만 명시 유지 —
        // 아래 keys() 단언이 요구하는 것은 "값이 공통과 다르다" 가 아니라 "prd 가 직접 선언한다" 다.
        assertThat(maxFileSize)
                .as("prd 는 multipart 개당 한도를 500MB 로 명시 override 해야 한다(CO-008 — CCTV 영상 실측 246MB 의 2배 여유)")
                .isEqualTo("500MB");
        // 요청 총량은 여전히 공통(1200MB)보다 좁게 조인다 — 이쪽이 자원 소진 방어의 주 축이다.
        assertThat(maxRequestSize)
                .as("prd 는 multipart 요청 한도를 1100MB 로 명시 override 해야 한다(공통 1200MB 보다 좁게)")
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
            // 와일드카드 CIDR 표기(0.0.0.0/0)는 주소가 아니라 정책 표기라 제외한다(위 상수 주석).
            if (devYml.startsWith(WILDCARD_CIDR, matcher.start())) {
                continue;
            }
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
        // ※ stg 의 dev **로그인**은 이 규칙에서 제외된다 — A-ISSUE-05 / DEV_FIX H-3 로 정책이 뒤집혀
        //   "끌 수 있어야 한다"가 아니라 "아예 켤 수 없어야 한다"가 됐다(아래 별도 테스트에서 고정).
        // prd 편입(CO-008): 운영도 기본 ON 이 됐다. 규칙 밖에 두면 누군가 리터럴 true 로 바꿔도
        //   끌 수단이 사라진 것을 아무도 못 잡는다 — R9 의 "나중에 비노출로 되돌릴 수 있어야 한다" 가 깨진다.
        for (String profileYml : List.of("application-dev.yml", "application-stg.yml", "application-prd.yml")) {
            // given
            Object upload = MainResourceYaml.rawValue(profileYml, "authoring.dev.upload.enabled");

            // then: 리터럴 true 는 끌 수단이 없다 → 환경변수 placeholder 형태여야 한다
            assertThat(String.valueOf(upload))
                    .as("%s 의 dev 업로드 토글은 DEV_UPLOAD_ENABLED 로 override 가능해야 한다", profileYml)
                    .contains("${DEV_UPLOAD_ENABLED");

            // and: 환경변수 미주입 시 기존 동작(ON)은 그대로 보존한다
            Environment env = MainResourceYaml.environment(COMMON_YML, profileYml);
            assertThat(env.getProperty("authoring.dev.upload.enabled"))
                    .as("%s 의 dev 업로드 토글 기본값(미주입 시 기존 동작 유지)", profileYml)
                    .isEqualTo("true");
        }

        // dev 프로파일의 로그인 토글: override 가능 + 미주입 시 ON 유지
        assertThat(String.valueOf(
                MainResourceYaml.rawValue("application-dev.yml", "authoring.dev.login.enabled")))
                .as("dev 의 dev 로그인 토글은 DEV_LOGIN_ENABLED 로 override 가능해야 한다")
                .contains("${DEV_LOGIN_ENABLED");
        assertThat(MainResourceYaml.environment(COMMON_YML, "application-dev.yml")
                        .getProperty("authoring.dev.login.enabled"))
                .as("dev 의 dev 로그인 토글 기본값(미주입 시 기존 동작 유지)")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("stg_dev로그인은_켤_수_없는_리터럴_false다")
    void stgDevLoginIsHardDisabled() {
        // given: /v1/dev/tokens 는 permitAll 이라 stg 에서 켜지면 인증 우회 + 관리자 권한 획득이 성립한다.
        Object login = MainResourceYaml.rawValue("application-stg.yml", "authoring.dev.login.enabled");

        // then: 환경변수 placeholder 가 아니라 리터럴 false (DevToggleProfileGuard 가 부팅에서 재차 강제)
        assertThat(String.valueOf(login))
                .as("stg 의 dev 로그인은 리터럴 false 여야 한다 — override 형태면 켤 수단이 생긴다")
                .isEqualTo("false");
        assertThat(MainResourceYaml.environment(COMMON_YML, "application-stg.yml")
                        .getProperty("authoring.dev.login.enabled"))
                .as("stg 의 dev 로그인 실효값")
                .isEqualTo("false");
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
