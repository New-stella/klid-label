package kr.co.cudo.authoring.common.config;

import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 증강 위탁 ↔ 콜백 수신 배선 짝 가드 — DEV_FIX 2차 LOW-4.
 *
 * <p>한쪽만 켜면(위탁 http + 콜백 allowlist 전면 차단) 위탁은 202 로 나가고 콜백은 전건 403 이라
 * 증강이 PENDING 으로 영구 고착된다. 그 조합을 기동 시점에 끊는지 검증한다.
 *
 * <h3>순수 함수 검증만으로는 부족했다(DEV_FIX 3차 HIGH-1)</h3>
 * <p>{@link GenAiIntegrationWiringGuard#verify} 단위 검증은 <b>정책</b>만 고정하고 <b>배선</b>은 고정하지
 * 못했다. 실제로는 {@code .env.example} 이 {@code WEBHOOK_GENAI_ALLOWED_IP_CIDRS=} 로 <b>빈 값을
 * 대입</b>하고 base compose 가 그 값을 {@code env_file} 로 주입해(set-but-empty) 프로파일 yml 기본값
 * ({@code ${VAR:0.0.0.0/0}})이 무력화됐고, local override 가 {@code AUGMENT_EXTERNAL_MODE=http} 를
 * 고정하고 있었으므로 <b>문서대로 띄운 정상 로컬 형상이 이 가드에 걸려 기동 불가</b>였다. 테스트가
 * 이를 못 잡은 이유는 {@code src/test/resources/application-local.yml} 이 allowlist 를
 * {@code 0.0.0.0/0} 으로 하드코딩해 모든 Spring 컨텍스트가 통과했기 때문이다.
 *
 * <p>그래서 {@code architecture/DevProfileWiringGuardTest} 와 동일한 방식으로 <b>실 형상 파일
 * ({@code .env.example} + compose 2종 + main yml)을 직접 파싱</b>해 컨테이너 실효값을 재현하고, 그 값을
 * <b>프로덕션 판정 함수에 그대로 통과시킨다</b> — 판정(assert 정책)은 손대지 않고 배선만 검증한다.
 */
class GenAiIntegrationWiringGuardTest {

    private static final Path REPO_ROOT = Paths.get("..");
    private static final Path BASE_COMPOSE = REPO_ROOT.resolve("docker-compose.yml");
    private static final Path LOCAL_COMPOSE = REPO_ROOT.resolve("docker-compose.local.yml");
    /** {@code cp .env.example .env} 로 만들어지는 compose 의 {@code env_file} 정본. */
    private static final Path ROOT_ENV_EXAMPLE = REPO_ROOT.resolve(".env.example");
    /** 네이티브 기동용 템플릿(테스트 작업 디렉토리 = backend 모듈 루트). */
    private static final Path BACKEND_ENV_EXAMPLE = Paths.get(".env.example");

    private static final String COMMON_YML = "application.yml";
    private static final String LOCAL_YML = "application-local.yml";
    private static final String DEV_YML = "application-dev.yml";

    private static final String BACKEND_ENV_PREFIX = "services.klid-backend.environment.";
    private static final String ENV_MODE = "AUGMENT_EXTERNAL_MODE";
    private static final String ENV_ALLOWLIST = "WEBHOOK_GENAI_ALLOWED_IP_CIDRS";

    /** compose 보간 형태 — {@code ${VAR}}, {@code ${VAR:-default}}, {@code ${VAR:?err}}. */
    private static final Pattern COMPOSE_INTERPOLATION =
            Pattern.compile("^\\$\\{([A-Z][A-Z0-9_]*)(?::-(.*)|:\\?.*)?}$");

    @Test
    @DisplayName("mode_http_인데_genai_allowlist_가_비면_기동이_실패한다")
    void httpModeWithoutAllowlist_failsStartup() {
        // given / when / then — 미설정과 명시적 none 둘 다 "허용 IP 없음" 이다.
        for (String allowlist : new String[]{null, "", "   ", "none", "NONE"}) {
            assertThatThrownBy(() -> GenAiIntegrationWiringGuard.verify("http", allowlist))
                    .as("allowlist=%s", allowlist)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(GenAiIntegrationWiringGuard.KEY_ALLOWLIST)
                    .hasMessageContaining("noop");
        }
    }

    @Test
    @DisplayName("mode_미설정도_http_로_보고_같은_짝을_요구한다")
    void missingModeIsTreatedAsHttp() {
        // given — @ConditionalOnProperty(matchIfMissing = true) 라 미설정 기본은 http 다.
        assertThatThrownBy(() -> GenAiIntegrationWiringGuard.verify(null, "none"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GenAiIntegrationWiringGuard.verify("", "none"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("noop_이면_allowlist_가_비어도_기동한다")
    void noopModeAllowsEmptyAllowlist() {
        // given — 미연동(noop)은 콜백이 애초에 오지 않으므로 allowlist 를 요구하지 않는다(dev/prd 기본).
        assertThatCode(() -> GenAiIntegrationWiringGuard.verify("noop", "")).doesNotThrowAnyException();
        assertThatCode(() -> GenAiIntegrationWiringGuard.verify("noop", "none")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("http_와_allowlist_명시가_짝이면_기동한다")
    void httpModeWithAllowlist_boots() {
        assertThatCode(() -> GenAiIntegrationWiringGuard.verify("http", "203.0.113.0/24"))
                .doesNotThrowAnyException();
        assertThatCode(() -> GenAiIntegrationWiringGuard.verify("http", "0.0.0.0/0"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("문서대로_띄운_docker_형상이_이_가드에_걸려_기동실패하지_않는다")
    void documentedComposeFormationsPassTheGuard() {
        // given: `cp .env.example .env` 후 로컬 규칙(두 compose 파일 동시 지정)으로 띄운 형상 그대로 재현
        Map<String, String> dotenv = readDotEnv(ROOT_ENV_EXAMPLE);

        // when: 컨테이너 환경변수(env_file → environment 우선) → 없으면 프로파일 yml 기본값
        String localMode = effective(
                containerValue(dotenv, ENV_MODE, BASE_COMPOSE, LOCAL_COMPOSE), LOCAL_YML,
                GenAiIntegrationWiringGuard.KEY_MODE);
        String localAllowlist = effective(
                containerValue(dotenv, ENV_ALLOWLIST, BASE_COMPOSE, LOCAL_COMPOSE), LOCAL_YML,
                GenAiIntegrationWiringGuard.KEY_ALLOWLIST);
        String devMode = effective(
                containerValue(dotenv, ENV_MODE, BASE_COMPOSE), DEV_YML,
                GenAiIntegrationWiringGuard.KEY_MODE);
        String devAllowlist = effective(
                containerValue(dotenv, ENV_ALLOWLIST, BASE_COMPOSE), DEV_YML,
                GenAiIntegrationWiringGuard.KEY_ALLOWLIST);

        // then: 로컬은 목 서버로 <실제 위탁>하는 형상이어야 한다 — noop 이면 아래 검증이 공허해진다
        assertThat(localMode)
                .as("로컬은 목 서버로 실제 위탁한다(local-must-use-mock-server 원칙)")
                .isEqualTo(GenAiIntegrationWiringGuard.MODE_HTTP);
        // and: 그 형상이 <프로덕션 판정 함수> 를 그대로 통과해야 한다(정책 완화 아님 — 배선 검증)
        assertThatCode(() -> GenAiIntegrationWiringGuard.verify(localMode, localAllowlist))
                .as("정상 로컬 형상(.env.example + compose 2종)이 기동 가드에 걸린다 — allowlist=[%s]",
                        localAllowlist)
                .doesNotThrowAnyException();
        // and: base 단독(dev) 형상도 기동 가능하되, 미연동이므로 콜백 allowlist 는 열지 않는다
        assertThatCode(() -> GenAiIntegrationWiringGuard.verify(devMode, devAllowlist))
                .as("dev 형상(base compose 단독)이 기동 가드에 걸린다 — mode=%s allowlist=[%s]",
                        devMode, devAllowlist)
                .doesNotThrowAnyException();
        assertThat(devAllowlist)
                .as("dev 는 증강 위탁이 noop 이다 — 콜백 allowlist 를 전면 개방하지 않는다(fail-closed 유지)")
                .isIn("none", "");
    }

    @Test
    @DisplayName("env_example_이_genai_allowlist_를_빈_값으로_대입하지_않는다")
    void envExampleDoesNotAssignEmptyAllowlist() {
        for (Path template : List.of(ROOT_ENV_EXAMPLE, BACKEND_ENV_EXAMPLE)) {
            // given / when
            String assigned = readDotEnv(template).get(ENV_ALLOWLIST);

            // then: 빈 값 대입은 "미설정" 이 아니라 set-but-empty 라 yml 기본값을 <덮어쓴다>
            assertThat(assigned)
                    .as("%s 의 빈 값 대입이 프로파일 yml 기본값을 무력화한다(주석 처리하거나 실제 대역을 채울 것)",
                            template.normalize())
                    .satisfiesAnyOf(
                            value -> assertThat(value).as("주석 처리(미설정)").isNull(),
                            value -> assertThat(value).as("실제 대역 명시").isNotBlank());
            // and: 키 자체는 운영자가 발견할 수 있게 템플릿에 남긴다
            assertThat(read(template)).contains(ENV_ALLOWLIST);
        }
    }

    /**
     * compose 컨테이너에 주입되는 값 — {@code env_file(.env)} 을 {@code environment} 가 덮어쓴다.
     * 반환 {@code null} 은 "환경변수 미설정"(=프로파일 yml 기본값이 살아 있음), 빈 문자열은
     * "set-but-empty"(=yml 기본값이 죽음)로 <구분>한다.
     */
    private String containerValue(Map<String, String> dotenv, String key, Path... composeFiles) {
        String value = dotenv.get(key);
        for (Path compose : composeFiles) {
            Object raw = yamlValue(compose, BACKEND_ENV_PREFIX + key);
            if (raw != null) {
                value = interpolate(String.valueOf(raw), dotenv);
            }
        }
        return value;
    }

    /** compose 보간 규칙 — {@code ${VAR:-default}} 는 .env 값이 <빈 값이어도> default 를 쓴다. */
    private String interpolate(String raw, Map<String, String> dotenv) {
        Matcher matcher = COMPOSE_INTERPOLATION.matcher(raw.trim());
        if (!matcher.matches()) {
            return raw;
        }
        String fromDotEnv = dotenv.get(matcher.group(1));
        if (fromDotEnv != null && !fromDotEnv.isBlank()) {
            return fromDotEnv;
        }
        return matcher.group(2) != null ? matcher.group(2) : "";
    }

    /** 환경변수가 없을 때만 프로파일 yml 기본값으로 떨어진다(Spring 우선순위 재현). */
    private String effective(String containerValue, String profileYml, String propertyKey) {
        if (containerValue != null) {
            return containerValue;
        }
        return MainResourceYaml.environment(COMMON_YML, profileYml).getProperty(propertyKey);
    }

    /** {@code KEY=VALUE} 만 읽는다(주석·빈 줄 제외). 빈 값 대입도 "설정됨" 으로 기록한다. */
    private Map<String, String> readDotEnv(Path path) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String line : read(path).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator > 0) {
                env.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
            }
        }
        return env;
    }

    private Object yamlValue(Path path, String key) {
        assertThat(Files.isReadable(path)).as("파일을 읽을 수 없습니다: %s", path.toAbsolutePath()).isTrue();
        try {
            for (PropertySource<?> source
                    : new YamlPropertySourceLoader().load(path.toString(), new FileSystemResource(path))) {
                if (source.containsProperty(key)) {
                    return source.getProperty(key);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("yml 로딩 실패: " + path, e);
        }
        return null;
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path.toAbsolutePath(), e);
        }
    }
}
