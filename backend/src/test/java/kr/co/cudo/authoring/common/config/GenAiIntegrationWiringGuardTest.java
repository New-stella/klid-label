package kr.co.cudo.authoring.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.slf4j.LoggerFactory;

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
 * 증강이 PENDING 으로 영구 고착된다. 그 조합을 끊는지 검증한다.
 *
 * <h3>★★ 끊는 자리가 기동에서 <b>위탁 시점</b>으로 옮겨졌다 (2026-09-03 확정, 구속)</h3>
 * <p>구 기대값은 <b>「기동이 실패한다」</b>였다(폐기). 주소를 제대로 넣은 정상 배포가 <b>다른 설정
 * 한 줄이 비었다는 이유로</b> 뜨지 못했기 때문이다. <b>판정 규칙은 한 줄도 바뀌지 않았고</b>
 * 걸리는 자리만 옮겼으므로, 이 시험도 <b>무르게 하지 않고 단언을 옮긴다</b> — 판정은 여기서
 * 그대로 고정하고, <b>위탁이 실제로 거부되는지</b>는 {@code AugmentApiWebClientConfigTest} 와
 * {@code ExternalEndpointBootAndTransportTest} 가 함께 고정한다.
 *
 * <p>⚠ 「기동 통과」만 확인하고 「위탁 거부」를 확인하지 않으면 <b>이 가드가 사라진 것을 초록으로
 * 통과시킨다</b>. 두 단언은 한 쌍이다.
 *
 * <h3>순수 함수 검증만으로는 부족했다(DEV_FIX 3차 HIGH-1)</h3>
 * <p>{@link GenAiIntegrationWiringGuard#verify} 단위 검증은 <b>정책</b>만 고정하고 <b>배선</b>은 고정하지
 * 못했다. 실제로는 {@code .env.example} 이 {@code WEBHOOK_GENAI_ALLOWED_IP_CIDRS=} 로 <b>빈 값을
 * 대입</b>하고 base compose 가 그 값을 {@code env_file} 로 주입해(set-but-empty) 프로파일 yml 기본값
 * ({@code ${VAR:0.0.0.0/0}})이 무력화됐고, local override 가 위탁을 켜고 있었으므로
 * <b>문서대로 띄운 정상 로컬 형상이 이 가드에 걸려 기동 불가</b>였다. 테스트가
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
    private static final String ENV_BASE_URL = "AUGMENT_API_BASE_URL";
    private static final String ENV_ALLOWLIST = "WEBHOOK_GENAI_ALLOWED_IP_CIDRS";

    /** compose 보간 형태 — {@code ${VAR}}, {@code ${VAR:-default}}, {@code ${VAR:?err}}. */
    private static final Pattern COMPOSE_INTERPOLATION =
            Pattern.compile("^\\$\\{([A-Z][A-Z0-9_]*)(?::-(.*)|:\\?.*)?}$");

    @Test
    @DisplayName("위탁주소_주입인데_genai_allowlist_가_비면_짝_위반으로_판정한다 — 규칙은 그대로다")
    void linkedWithoutAllowlist_isRejected() {
        // given / when / then — 미설정과 명시적 none 둘 다 "허용 IP 없음" 이다.
        for (String allowlist : new String[]{null, "", "   ", "none", "NONE"}) {
            GenAiIntegrationWiringGuard.Verdict verdict =
                    GenAiIntegrationWiringGuard.inspect("http://genai.vendor.io:9400", allowlist);

            assertThat(verdict.rejected()).as("allowlist=%s", allowlist).isTrue();
            assertThat(verdict.rejectionLabel())
                    .isEqualTo(GenAiIntegrationWiringGuard.REJECTION_LABEL);
            // 상세(서버 로그 전용)에는 운영자가 고칠 설정 지점이 그대로 남는다.
            assertThat(verdict.detail())
                    .contains(GenAiIntegrationWiringGuard.KEY_ALLOWLIST)
                    .contains("AUGMENT_API_BASE_URL");
            // 예외를 던지는 형태도 같은 규칙이다 — 두 경로가 갈릴 여지가 없다.
            assertThatThrownBy(() ->
                    GenAiIntegrationWiringGuard.verify("http://genai.vendor.io:9400", allowlist))
                    .as("allowlist=%s", allowlist)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(GenAiIntegrationWiringGuard.KEY_ALLOWLIST);
        }
    }

    @Test
    @DisplayName("★★짝이_안_맞아도_기동은_통과하고_위탁_거부사유만_남는다 — 구 기동차단 폐기")
    void mismatchedPairingBootsButBlocksCommission() {
        // ★ 이 단언을 뒤집으면(=check() 가 다시 예외를 던지게 되돌리면) 빨개진다.
        //   주소를 제대로 넣은 정상 배포가 allowlist 한 줄 때문에 못 뜨던 것이 구 동작이다.
        // 주소는 <해석이 필요 없는 루프백>을 쓴다 — 실 DNS 에 의존하면 오프라인에서 흔들린다
        //   (내부망 정책이라 루프백은 정상 통과값이다).
        GenAiIntegrationWiringGuard guard = new GenAiIntegrationWiringGuard(
                "http://127.0.0.1:9400", "", new AugmentUrlPolicy());

        assertThatCode(guard::check).doesNotThrowAnyException();
        // 그러나 <위탁은 거부된다> — 기동만 통과시키고 이게 없으면 가드가 사라진 것이다.
        assertThat(guard.commissionRejectionLabel())
                .isEqualTo(GenAiIntegrationWiringGuard.REJECTION_LABEL);
        // 거부 사유에는 설정값(대역·주소·설정 키)이 실리지 않는다 — CWE-209.
        assertThat(guard.commissionRejectionLabel())
                .doesNotContain("127.0.0.1")
                .doesNotContain(GenAiIntegrationWiringGuard.KEY_ALLOWLIST)
                .doesNotContain(GenAiIntegrationWiringGuard.KEY_BASE_URL);
    }

    @Test
    @DisplayName("★짝이_맞으면_위탁_거부사유가_없다")
    void pairedGuardBlocksNothing() {
        GenAiIntegrationWiringGuard guard = new GenAiIntegrationWiringGuard(
                "http://127.0.0.1:9400", "203.0.113.0/24", new AugmentUrlPolicy());

        assertThatCode(guard::check).doesNotThrowAnyException();
        assertThat(guard.commissionRejectionLabel()).isNull();
    }

    @Test
    @DisplayName("★★막지_않는_대신_알린다 — 짝이_어긋난_형상은_기동_시_ERROR_를_남긴다")
    void mismatchedPairingLogsErrorAtStartup() {
        // ADR-062 가 위험으로 명시한 축 — 「막지 않는다」가 「알리지 않는다」가 되면 안 된다.
        //   기동을 통과시키는 대신 남기는 이 기록이 없으면, 잘못 배선된 배포가 조용히 떠서
        //   증강만 전건 실패하는 상태를 아무도 알아채지 못한다.
        //   ★ check() 의 log.error 를 지우거나 수준을 낮추면 이 시험이 빨개진다.
        List<ILoggingEvent> events = captureBootLogs(new GenAiIntegrationWiringGuard(
                "http://127.0.0.1:9400", "", new AugmentUrlPolicy()));

        assertThat(events)
                .as("짝이 어긋난 형상인데 기동 기록이 조용하다 — 그러면 아무도 알아채지 못한다")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    // 서버 기록에는 운영자가 고칠 <설정 지점>이 그대로 남아야 한다(응답과 반대다).
                    assertThat(event.getFormattedMessage())
                            .contains(GenAiIntegrationWiringGuard.KEY_ALLOWLIST)
                            .contains(GenAiIntegrationWiringGuard.KEY_BASE_URL);
                });
    }

    @Test
    @DisplayName("★짝이_맞는_형상은_기동_시_ERROR_를_남기지_않는다 — 진짜 오류가 묻히지 않게")
    void pairedFormationLogsNoError() {
        List<ILoggingEvent> events = captureBootLogs(new GenAiIntegrationWiringGuard(
                "http://127.0.0.1:9400", "203.0.113.0/24", new AugmentUrlPolicy()));

        assertThat(events).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("★위탁주소_미주입은_사고가_아니라_상태다 — 기동_ERROR_를_남기지_않는다")
    void unlinkedFormationLogsNoError() {
        // 미연동이 정상인 배포가 실재한다(벤더 주소 확정 전 · 그 연동을 쓰지 않는 채널).
        //   여기에 ERROR 를 남기면 매 기동 오류가 뿜어져 <진짜 오류가 묻힌다>.
        List<ILoggingEvent> events = captureBootLogs(
                new GenAiIntegrationWiringGuard("", "", new AugmentUrlPolicy()));

        assertThat(events).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    /**
     * 기동 시점 기록을 캡처한다 — {@code check()} 는 {@code @PostConstruct} 라 컨테이너 없이는
     * 자동 호출되지 않으므로 직접 부른다(판정은 이미 생성자에서 끝나 있다).
     *
     * <p>{@code ListAppender} 는 이 저장소의 기존 관례를 그대로 따른다
     * ({@code AdminPasswordServiceTest} 동형). <b>반드시 detach</b> 해야 다른 시험에 새지 않는다.
     */
    private List<ILoggingEvent> captureBootLogs(GenAiIntegrationWiringGuard guard) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ch.qos.logback.classic.Logger logger = ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(GenAiIntegrationWiringGuard.class);
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            guard.check();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    @Test
    @DisplayName("★위탁주소가_정책에_거부되면_allowlist_가_비어도_짝_위반이_아니다 — 위탁 자체가 불가하다")
    void rejectedBaseUrlMakesPairingMoot() {
        // 거부된 주소는 위탁이 한 건도 나갈 수 없으므로 콜백도 오지 않는다. 여기서 짝을 요구하면
        //   걷어낸 기동 의존이 <다른 이름으로> 되살아난다(주소 판정이 전송 시점으로 옮겨간 뒤의 함정).
        for (String baseUrl : new String[]{
                "http://169.254.169.254", "https://your-service.example.com", "ftp://vendor.io"}) {
            GenAiIntegrationWiringGuard guard =
                    new GenAiIntegrationWiringGuard(baseUrl, "", new AugmentUrlPolicy());

            assertThatCode(guard::check).as("baseUrl=%s", baseUrl).doesNotThrowAnyException();
            assertThat(guard.commissionRejectionLabel()).as("baseUrl=%s", baseUrl).isNull();
        }
    }

    @Test
    @DisplayName("★위탁주소_미주입이면_allowlist_가_비어도_기동한다 — 콜백이 애초에 오지 않는다")
    void unlinkedAllowsEmptyAllowlist() {
        // ★ 판정 축이 「모드 토글」에서 「위탁 주소 주입 여부」로 바뀌었다(2026-09-03).
        //   모드 키를 계속 읽으면 기본값이 http 라 <주소도 없는 미연동 배포>가 전부 위탁 활성으로
        //   판정되어, 이번에 걷어낸 기동 의존이 다른 이름으로 되살아난다.
        for (String baseUrl : new String[]{null, "", "   "}) {
            assertThat(GenAiIntegrationWiringGuard.inspect(baseUrl, "").rejected())
                    .as("baseUrl=[%s]", baseUrl).isFalse();
            assertThat(GenAiIntegrationWiringGuard.inspect(baseUrl, "none").rejected())
                    .as("baseUrl=[%s]", baseUrl).isFalse();
            assertThat(new GenAiIntegrationWiringGuard(baseUrl, "", new AugmentUrlPolicy())
                    .commissionRejectionLabel()).as("baseUrl=[%s]", baseUrl).isNull();
        }
    }

    @Test
    @DisplayName("위탁주소와_allowlist_명시가_짝이면_위탁이_그대로_나간다")
    void linkedWithAllowlist_boots() {
        assertThatCode(() -> GenAiIntegrationWiringGuard.verify(
                "http://genai.vendor.io:9400", "203.0.113.0/24")).doesNotThrowAnyException();
        assertThatCode(() -> GenAiIntegrationWiringGuard.verify(
                "http://klid-mock-server:9400", "0.0.0.0/0")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("문서대로_띄운_docker_형상이_짝_가드에_걸려_위탁이_막히지_않는다")
    void documentedComposeFormationsPassTheGuard() {
        // given: `cp .env.example .env` 후 로컬 규칙(두 compose 파일 동시 지정)으로 띄운 형상 그대로 재현
        Map<String, String> dotenv = readDotEnv(ROOT_ENV_EXAMPLE);

        // when: 컨테이너 환경변수(env_file → environment 우선) → 없으면 프로파일 yml 기본값
        String localBaseUrl = effective(
                containerValue(dotenv, ENV_BASE_URL, BASE_COMPOSE, LOCAL_COMPOSE), LOCAL_YML,
                GenAiIntegrationWiringGuard.KEY_BASE_URL);
        String localAllowlist = effective(
                containerValue(dotenv, ENV_ALLOWLIST, BASE_COMPOSE, LOCAL_COMPOSE), LOCAL_YML,
                GenAiIntegrationWiringGuard.KEY_ALLOWLIST);
        String devBaseUrl = effective(
                containerValue(dotenv, ENV_BASE_URL, BASE_COMPOSE), DEV_YML,
                GenAiIntegrationWiringGuard.KEY_BASE_URL);
        String devAllowlist = effective(
                containerValue(dotenv, ENV_ALLOWLIST, BASE_COMPOSE), DEV_YML,
                GenAiIntegrationWiringGuard.KEY_ALLOWLIST);

        // then: 로컬은 목 서버로 <실제 위탁>하는 형상이어야 한다 — 주소가 비면 아래 검증이 공허해진다
        assertThat(localBaseUrl)
                .as("로컬은 목 서버로 실제 위탁한다(local-must-use-mock-server 원칙)")
                .isNotBlank();
        // and: 그 형상이 <프로덕션 판정 함수> 를 그대로 통과해야 한다(정책 완화 아님 — 배선 검증)
        assertThatCode(() -> GenAiIntegrationWiringGuard.verify(localBaseUrl, localAllowlist))
                .as("정상 로컬 형상(.env.example + compose 2종)이 짝 가드에 걸린다 — allowlist=[%s]",
                        localAllowlist)
                .doesNotThrowAnyException();
        // and: base 단독(dev) 형상도 위탁이 활성이며(2026-09-03 — 미연동 토글 폐기) 짝이 맞아야 한다
        assertThat(devBaseUrl)
                .as("dev 도 목 서버로 실제 위탁한다 — 구 형상은 미연동 토글로 꺼져 있었고 그 축은 폐기됐다")
                .isNotBlank();
        assertThatCode(() -> GenAiIntegrationWiringGuard.verify(devBaseUrl, devAllowlist))
                .as("dev 형상(base compose 단독)이 짝 가드에 걸린다 — allowlist=[%s]", devAllowlist)
                .doesNotThrowAnyException();
        assertThat(devAllowlist)
                .as("dev 는 위탁이 활성이므로 콜백 allowlist 를 <명시>해 짝을 맞춘다(목 발신 IP 는 "
                        + "docker 브리지 사설 IP 라 대역 고정 불가)")
                .isNotBlank()
                .isNotEqualTo("none");
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
