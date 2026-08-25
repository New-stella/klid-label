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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스트림 nonce 쿠키 {@code Secure} 의 <b>실효 기본값이 OFF</b> 임을 고정하는 가드.
 *
 * <h2>왜 이 가드가 필요한가</h2>
 * <p>이 설정의 기본값은 취향 문제가 아니라 <b>영상이 재생되는가</b>를 가른다. 기본을 {@code true} 로
 * 바꾸면 <b>평문 HTTP 로 서비스되는 배포에서 브라우저가 nonce 쿠키를 저장하지 않고</b>, 그 쿠키는
 * 서명 검증의 필수 입력이라 스트림 요청이 <b>전건 401</b> 이 되어 마킹·라벨링 화면에서 영상이 아예
 * 재생되지 않는다. 온프렘 운영 배포도 여기 해당한다 — 프로파일은 {@code prd} 인데 프런트는 평문
 * HTTP({@code listen 80})로 서비스되고, 폐쇄망이라 TLS 를 걸지 않는 것이 설치 기본 형상이다.
 *
 * <p>실제로 이 클래스의 결함이 한 번 발생했다. 구 구현은 {@code Secure} 를 <b>프로파일로 유추</b>해
 * ({@code local} 이 아니면 무조건 부여) "평문 HTTP 개발 서버 = local" 을 전제했는데, 그 전제가
 * 깨지는 배포(평문 HTTP + 비-local 프로파일)에서 위 증상이 그대로 났다. 판정 축을 설정 키로 옮긴
 * 지금, 남은 재발 경로는 <b>기본값이 조용히 뒤집히는 것</b> 하나다.
 *
 * <h2>왜 단위테스트로는 못 잡는가</h2>
 * <p>{@code StreamNonceCookieSecureFlagTest} 는 생성자에 값을 <b>직접 주입</b>해 동작을 고정한다.
 * 그래서 yml 값도 {@code @Value} 의 기본값 문자열도 한 번도 실행되지 않는다 — 실측으로
 * {@code application.yml} 을 {@code :true} 로 뒤집어도 그 테스트를 포함한 대량 회귀가 전건 통과했다.
 * {@code ConfigPropertyKeyGuardTest} 도 못 잡는다. 그 클래스는 javadoc 에서 커버 범위를 명시하며
 * <b>"기본값이 있는 코드 키"는 판정하지 않는다</b>고 스스로 선언한다(키의 존재만 본다).
 *
 * <p>따라서 이 가드는 값 자체를 본다. Spring 컨텍스트를 띄우지 않고 yml 을 파싱하는
 * 순수 파일 스캔이며, 기존 {@code *GuardTest} 들과 동일 골격이다(ArchUnit 미사용 프로젝트).
 *
 * <h2>바꾸려면</h2>
 * <p><b>기본값을 {@code true} 로 되돌리지 말 것.</b> HTTPS 로 서비스되는 개별 배포는
 * 환경변수 {@code STREAM_COOKIE_SECURE=true} 로 <b>그 배포에서만</b> 켠다 — 그것이 이 키가 존재하는
 * 이유이고, 공통 기본값을 올리면 평문 HTTP 배포가 다시 전건 401 로 죽는다.
 *
 * @see kr.co.cudo.authoring.common.security.StreamNonceCookie
 * @see ConfigPropertyKeyGuardTest
 */
class StreamCookieSecureDefaultGuardTest {

    private static final String COMMON_YML = "application.yml";
    private static final String KEY = "authoring.stream.cookie-secure";
    private static final String ENV_VAR = "STREAM_COOKIE_SECURE";
    private static final String EXPECTED_RAW = "${" + ENV_VAR + ":false}";

    /** 프로파일별 override 도 함께 본다 — 공통 기본이 OFF 여도 prd 가 덮으면 같은 결함이다. */
    private static final List<String> PROFILE_YMLS = List.of(
            "application-local.yml", "application-dev.yml",
            "application-stg.yml", "application-prd.yml");

    private static final Path COOKIE_SOURCE = Paths.get(
            "src/main/java/kr/co/cudo/authoring/common/security/StreamNonceCookie.java");

    @Test
    @DisplayName("공통_yml의_nonce쿠키_Secure_는_환경변수_override_가능하고_기본값이_false다")
    void commonYmlDeclaresEnvOverridableDefaultOff() {
        Object raw = MainResourceYaml.rawValue(COMMON_YML, KEY);

        assertThat(raw)
                .as("%s 가 공통 yml 에 없으면 운영자가 켤 손잡이가 사라진다", KEY)
                .isNotNull();
        assertThat(String.valueOf(raw))
                .as("기본을 true 로 올리면 평문 HTTP 배포(온프렘 prd 포함)가 전건 401 로 영상 재생 불가가 된다")
                .isEqualTo(EXPECTED_RAW);
    }

    @Test
    @DisplayName("환경변수_미주입_시_실효값이_false_로_해석된다")
    void resolvedDefaultIsFalseWithoutEnvironmentOverride() {
        // MainResourceYaml.environment 는 시스템 프로퍼티/환경변수 소스를 제거하므로,
        // 실행 머신에 STREAM_COOKIE_SECURE 가 있어도 yml 기본값만 반영된다.
        Environment environment = MainResourceYaml.environment(COMMON_YML);

        assertThat(environment.getProperty(KEY, Boolean.class))
                .as("운영자가 아무것도 넣지 않았을 때 실제로 적용되는 값")
                .isFalse();
    }

    @Test
    @DisplayName("어떤_프로파일_yml도_nonce쿠키_Secure_를_켜두지_않는다")
    void noProfileYmlTurnsItOn() {
        for (String profileYml : PROFILE_YMLS) {
            Object raw = MainResourceYaml.rawValue(profileYml, KEY);
            if (raw == null) {
                continue; // 미선언 = 공통 기본(false) 상속 — 정상
            }
            assertThat(String.valueOf(raw))
                    .as("%s 가 %s 를 켜면 그 프로파일의 평문 HTTP 배포가 전건 401 이 된다", profileYml, KEY)
                    .doesNotContain("true");
        }
    }

    @Test
    @DisplayName("코드의_기본값도_false_라_yml이_없어도_OFF_로_떨어진다")
    void codeSideDefaultMatchesYml() {
        // yml 과 코드 기본값이 갈리면 "yml 을 지웠더니 Secure 가 켜졌다" 는 조용한 회귀가 열린다.
        String source = read(COOKIE_SOURCE);

        assertThat(source)
                .as("@Value 기본값이 yml 기본값과 같아야 한다")
                .contains("@Value(\"${" + KEY + ":false}\")");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("소스를 읽을 수 없습니다: " + path.toAbsolutePath(), e);
        }
    }
}
