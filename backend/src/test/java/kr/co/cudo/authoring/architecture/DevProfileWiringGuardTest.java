package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dev 프로파일 실연동 배선 가드 — "설정은 있는데 외부를 한 번도 호출하지 않는" 상태를 차단한다.
 *
 * <p>배경(2026-07-27 설정 전수조사 E + Phase 4): base {@code docker-compose.yml} 이
 * {@code SPRING_PROFILES_ACTIVE=local} 이라 <b>{@code application-dev.yml} 이 한 번도 적용된 적이
 * 없었고</b>, dev 배포가 local 설정으로 돌면서
 * <ul>
 *   <li>비식별은 {@code mock-mode=true} 로 외부 무접촉 자체 복사(self-fill)로 "성공"했고,</li>
 *   <li>VLM 은 설정 토글이 꺼져 있어 단계가 통째로 {@code SKIPPED} 되어 비었다(그 토글은 이후
 *       ADR-049 로 <b>폐지</b>됐고, 지금은 연동 주소 주입 여부가 미연동을 판정한다).</li>
 * </ul>
 * 둘 다 기동·파이프라인이 <b>정상처럼 보이는</b> 실패라 사람이 로그를 파헤치기 전엔 드러나지 않는다.
 *
 * <p><b>구속 원칙</b>: 본 프로그램이 스스로 결과를 채우는 경로는 쓰지 않는다. 모든 외부 연동은
 * 실제 HTTP 로 실서버(dev/local = mock-server)를 바라본다.
 *
 * <p>ArchUnit 미사용 프로젝트이므로 기존 {@code architecture/*GuardTest} 들과 동일하게 순수 파일
 * 스캔 + yml 로딩만 사용한다(컨텍스트 기동 없음).
 */
class DevProfileWiringGuardTest {

    private static final String COMMON_YML = "application.yml";
    private static final String DEV_YML = "application-dev.yml";
    private static final String LOCAL_YML = "application-local.yml";

    /** 목업 벤더 서버(mock-server) 컨테이너 주소 — dev/local 외부 연동의 기본 대상. */
    private static final String MOCK_SERVER_URL = "http://klid-mock-server:9400";

    private static final String MOCK_MODE_KEY = "authoring.integration.deidentify.mock-mode";
    private static final String KPST_ENABLED_KEY = "kpst.deid.enabled";
    private static final String KPST_BASE_URL_KEY = "kpst.deid.base-url";
    private static final String VLM_URL_KEY = "vlm.client.url";

    /** 리포지토리 루트(테스트 작업 디렉토리 = backend 모듈 루트). */
    private static final Path REPO_ROOT = Paths.get("..");
    private static final Path BASE_COMPOSE = REPO_ROOT.resolve("docker-compose.yml");
    private static final Path LOCAL_COMPOSE = REPO_ROOT.resolve("docker-compose.local.yml");
    private static final Path ENV_EXAMPLE = Paths.get(".env.example");
    private static final Path KEY_CHANGES_DOC =
            REPO_ROOT.resolve("docs/operations/config-key-changes-20260727.md");

    /** 배포 체크리스트의 기계 판독 구간 — 이 사이의 `KEY` 목록이 dev 필수 환경변수 정본이다. */
    private static final String DOC_MARKER_START = "<!-- DEV_REQUIRED_ENV:START -->";
    private static final String DOC_MARKER_END = "<!-- DEV_REQUIRED_ENV:END -->";

    /** 기본값이 <b>없는</b> 환경변수 placeholder({@code ${VAR}}) — 미주입 시 기동 실패. */
    private static final Pattern ENV_WITHOUT_DEFAULT = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)}");
    /** 문서의 백틱 키 토큰(`KEY`). */
    private static final Pattern DOC_KEY_TOKEN = Pattern.compile("`([A-Z][A-Z0-9_]{2,})`");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");

    /**
     * 기본값이 <b>빈 값</b>이라 미주입이어도 기동은 되지만 기능이 조용히 죽는 키.
     *
     * <p>기동 실패(위 {@code ${VAR}})보다 오히려 위험하다 — dev 로 전환되는 순간 local 프로파일이
     * 갖고 있던 개발용 기본값을 더 이상 상속하지 않기 때문이다.
     */
    private static final List<String> FAIL_CLOSED_ENV = List.of(
            "STREAM_SIGN_SECRET",          // 영상 스트림 서명 URL 발급·검증 전면 fail-closed(재생 불가)
            "CORS_ALLOWED_ORIGINS",        // 빈 값 = 외부 origin 전면 차단(FE 403)
            "ADMIN_CLAIM_PASSWORD_HASH",   // 빈 값 = 관리자 role-claim 불가
            "WEBHOOK_HMAC_SECRET_AUGMENT"  // 빈 값 = 증강 콜백 401(HmacWebhookFilter fail-closed)
    );

    @Test
    @DisplayName("비식별_자체채움_설정키가_어느_프로파일에도_존재하지_않는다")
    void selfFillPropertyIsGone() {
        // ★ 이 단언은 뒤집힌 것이다. 과거에는 "dev 기본값이 false 인가" 를 물었고 되돌릴 수단
        //   (DEIDENTIFY_MOCK_MODE override)이 남아 있기를 요구했다. 그 되돌릴 수단이 곧 위험이었다 —
        //   켜는 순간 외부 호출 없이 <원본을 비식별 경로로 복사>하고 'Y' 로 마킹해, 마스킹되지 않은
        //   원본이 비식별본으로 통과했다. 게다가 허용 범위가 local/dev/stg 라 납품과 같은 계열인
        //   stg 에서도 성립했다.
        //
        //   이제 그 경로는 코드째 폐지됐으므로, 설정 키가 <어디에도 없어야> 한다. 키가 되살아나면
        //   구현이 없어 조용히 무시되는 죽은 설정이 되거나, 구현과 함께 되살아난 것이다. 둘 다 막는다.
        for (String yml : List.of(COMMON_YML, DEV_YML, "application-local.yml",
                "application-stg.yml", "application-prd.yml")) {
            assertThat(MainResourceYaml.rawValue(yml, MOCK_MODE_KEY))
                    .as("%s 에 자체 채움 설정키(%s)가 되살아났다 — 이 경로는 폐지됐다", yml, MOCK_MODE_KEY)
                    .isNull();
        }
        assertThat(MainResourceYaml.environment(COMMON_YML, DEV_YML).getProperty(MOCK_MODE_KEY))
                .as("어떤 프로파일 조합으로도 해석되지 않아야 한다")
                .isNull();
    }

    @Test
    @DisplayName("dev_프로파일_kpst_base_url이_mock서버를_가리킨다")
    void devProfileKpstPointsToMockServer() {
        // given
        Environment env = MainResourceYaml.environment(COMMON_YML, DEV_YML);

        // when
        String baseUrl = env.getProperty(KPST_BASE_URL_KEY);
        String enabled = env.getProperty(KPST_ENABLED_KEY);

        // then
        assertThat(enabled)
                .as("비식별 위탁 경로(KPST 폴링)가 켜져 있어야 한다 — 꺼지고 mock 도 아니면 DeidentifyStep 이 설정 오류로 거부한다")
                .isEqualTo("true");
        assertThat(baseUrl)
                .as("dev 기본 위탁 대상은 목업 벤더 서버여야 한다(벤더 실서버 전환은 KPST_DEID_BASE_URL 주입만으로)")
                .isEqualTo(MOCK_SERVER_URL);
        assertThat(String.valueOf(MainResourceYaml.rawValue(DEV_YML, KPST_BASE_URL_KEY)))
                .as("KPST 주소는 KPST_DEID_BASE_URL 로 override 가능해야 한다")
                .contains("${KPST_DEID_BASE_URL");
        assertThat(baseUrl)
                .as("https 기본값은 KPST 자체 CA 경로(KPST_DEID_CA_CERT_PATH) 없이는 부팅이 차단된다(CWE-295 fail-closed)")
                .doesNotStartWith("https://");
    }

    /**
     * ★ 판정 축이 뒤집혔다 — 구 단언은 "활성 토글이 {@code true} 인가" 였다.
     *
     * <p>그 토글은 폐지됐다(ADR-049). 이제 <b>연동 주소가 목 서버를 가리키는지</b>가 곧 실연동 여부이며,
     * 주소가 비면 위탁이 실패한다(조용한 SKIPPED 는 없다). 이 가드가 막으려는 것 — "설정은 있는데
     * 외부를 한 번도 호출하지 않는 상태" — 는 그대로다. 축만 토글에서 주소로 옮겼다.
     */
    @Test
    @DisplayName("dev_프로파일_VLM_위탁대상이_mock서버다")
    void devProfilePointsVlmClientAtMockServer() {
        // given
        Environment env = MainResourceYaml.environment(COMMON_YML, DEV_YML);

        // when
        String url = env.getProperty(VLM_URL_KEY);

        // then: 주소가 비면 위탁이 전건 실패해 결과 0건이 된다
        assertThat(url)
                .as("VLM 위탁 대상은 목업 벤더 서버여야 한다(코드가 읽는 키는 vlm.base-url 이 아니라 vlm.client.url)")
                .isEqualTo(MOCK_SERVER_URL);
        assertThat(String.valueOf(MainResourceYaml.rawValue(DEV_YML, VLM_URL_KEY)))
                .as("VLM 주소는 VLM_SERVICE_URL 로 override 가능해야 한다(벤더 실서버 전환은 주입만으로)")
                .contains("${VLM_SERVICE_URL");
    }

    @Test
    @DisplayName("dev_프로파일_필수_환경변수_목록이_문서와_일치한다")
    void devRequiredEnvVariablesMatchDeploymentChecklist() {
        // given: dev 로 기동할 때 실제로 요구되는 환경변수
        //   (1) 기본값 없는 placeholder = 미주입 시 기동 실패
        //   (2) 빈 기본값 = 기동은 되지만 해당 기능이 조용히 죽음(local 기본값을 더 이상 상속하지 않음)
        Set<String> required = new TreeSet<>(envKeysWithoutDefault(COMMON_YML, DEV_YML));
        required.addAll(FAIL_CLOSED_ENV);

        // when: 배포 체크리스트(정본)에 기재된 목록
        Set<String> documented = documentedRequiredEnv();

        // then: 리포지토리 변경만으로 끝나지 않는다 — 서버 .env 갱신 목록이 설정과 어긋나면 안 된다
        assertThat(documented)
                .as("dev 필수 환경변수 목록이 %s 의 배포 체크리스트와 어긋난다(설정만 바뀌고 운영 인수인계가 누락되는 전형적 사고)",
                        KEY_CHANGES_DOC.normalize())
                .containsExactlyInAnyOrderElementsOf(required);

        // and: 운영자가 채울 템플릿(.env.example)에도 전부 존재해야 한다
        String envExample = read(ENV_EXAMPLE);
        List<String> missingInTemplate = required.stream()
                .filter(key -> !envExample.contains(key))
                .toList();
        assertThat(missingInTemplate)
                .as("backend/.env.example 에 dev 필수 키가 누락됐다: %s", missingInTemplate)
                .isEmpty();
    }

    @Test
    @DisplayName("local_프로파일_외부연동은_mock서버를_가리킨다")
    void localProfileExternalIntegrationPointsToMockServer() {
        // given
        Environment env = MainResourceYaml.environment(COMMON_YML, LOCAL_YML);

        // when
        String baseUrl = env.getProperty(KPST_BASE_URL_KEY);
        List<String> externalIpLiterals = new ArrayList<>();
        Matcher matcher = IPV4.matcher(read(Paths.get("src/main/resources").resolve(LOCAL_YML)));
        while (matcher.find()) {
            String literal = matcher.group();
            // 루프백(번들 DB·로컬 FE origin)은 로컬 자족 기동의 정상 구성이므로 제외한다
            if (!literal.startsWith("127.") && !literal.equals("0.0.0.0")) {
                externalIpLiterals.add(literal);
            }
        }

        // then: 로컬은 벤더 실서버가 아니라 목업 서버를 바라본다(구속 정책)
        assertThat(baseUrl)
                .as("local KPST 위탁 대상은 목업 벤더 서버여야 한다")
                .isEqualTo(MOCK_SERVER_URL);
        assertThat(externalIpLiterals)
                .as("application-local.yml 에 외부(벤더/내부망) IP 리터럴이 평문으로 남아 있다: %s", externalIpLiterals)
                .isEmpty();
    }

    /** ★ 위 dev 케이스와 같은 이유로 판정 축이 토글에서 주소로 옮겨졌다(ADR-049). */
    @Test
    @DisplayName("local_프로파일_VLM_위탁대상이_mock서버다")
    void localProfilePointsVlmClientAtMockServer() {
        // given
        Environment env = MainResourceYaml.environment(COMMON_YML, LOCAL_YML);

        // when
        String url = env.getProperty(VLM_URL_KEY);

        // then: 공통 기본값(빈 값)을 그대로 상속하면 위탁이 전건 실패한다 —
        //   로컬도 목 서버로 실제 위탁한다(구속 정책: 자체 결과채움 금지)
        assertThat(url)
                .as("local VLM 위탁 대상은 목업 벤더 서버여야 한다(코드가 읽는 키는 vlm.base-url 이 아니라 vlm.client.url)")
                .isEqualTo(MOCK_SERVER_URL);
        assertThat(String.valueOf(MainResourceYaml.rawValue(LOCAL_YML, VLM_URL_KEY)))
                .as("네이티브 bootRun 은 컨테이너명을 해석하지 못하므로 VLM_SERVICE_URL 로 override 가능해야 한다")
                .contains("${VLM_SERVICE_URL");
    }

    @Test
    @DisplayName("docker_compose_기본_프로파일은_dev이고_로컬_override는_local이다")
    void composeProfilesAreWiredPerFile() {
        // given
        String profileKey = "services.klid-backend.environment.SPRING_PROFILES_ACTIVE";

        // when
        String baseProfile = String.valueOf(yamlValue(BASE_COMPOSE, profileKey));
        String localProfile = String.valueOf(yamlValue(LOCAL_COMPOSE, profileKey));

        // then: base 는 dev(롤백 가능한 형태), local override 는 반드시 local 로 되돌린다
        assertThat(baseProfile)
                .as("base compose 는 dev 프로파일로 기동해야 한다(과거 local 고정이라 application-dev.yml 이 미적용이었다)")
                .isEqualTo("${SPRING_PROFILES_ACTIVE:-dev}");
        assertThat(localProfile)
                .as("local override 가 프로파일을 local 로 되돌리지 않으면 로컬 자족 기동이 dev 로 떠서 "
                        + "시드(DevSeedRunner @Profile(\"local\"))·로컬 기본 시크릿이 전부 사라진다")
                .isEqualTo("local");
    }

    @Test
    @DisplayName("mock서버는_base_compose에_단일_정의되고_외부연동이_주입된다")
    void mockServerIsDefinedOnceInBaseCompose() {
        // given
        Set<String> baseKeys = yamlKeys(BASE_COMPOSE);
        Set<String> localKeys = yamlKeys(LOCAL_COMPOSE);

        // when
        List<String> duplicated = localKeys.stream()
                .filter(k -> k.startsWith("services.mock-server."))
                .filter(k -> !k.startsWith("services.mock-server.volumes"))
                .toList();

        // then: 서비스 정의는 base 단일 — override 에는 차이분(볼륨)만 남긴다
        assertThat(baseKeys)
                .as("mock-server 서비스는 base compose 에 정의돼야 한다(dev 배포에도 필요)")
                .contains("services.mock-server.image", "services.mock-server.environment.MOCK_OUTPUT_BASE");
        assertThat(duplicated)
                .as("local override 가 mock-server 정의를 중복 선언했다(설정이 두 파일로 갈라진다): %s", duplicated)
                .isEmpty();
        // MOCK_OUTPUT_BASE 는 BE 의 STORAGE_RAW_MOUNT_ROOTS 와 <한 세트>다 — co-locate 산출(Phase 5A)
        //   이후 비식별 export_path 가 {dirname(원본)}/{rawSn}/deid/ 라 원본 마운트 루트까지 허용해야
        //   목이 산출물을 쓴다. 구 값(/app/storage/deidentified 단독)이면 목이 base 밖으로 판정해
        //   더미를 만들지 않고 BE 가 비식별을 영구 실패시킨다.
        String mockOutputBase =
                String.valueOf(yamlValue(BASE_COMPOSE, "services.mock-server.environment.MOCK_OUTPUT_BASE"));
        assertThat(mockOutputBase)
                .as("MOCK_OUTPUT_BASE 미설정 시 목이 어떤 파일도 만들지 않아(fail-closed) 비식별 결과 검증이 실패한다")
                .isNotBlank()
                .contains("/app/storage/raw")
                .contains("/app/storage/deidentified");
        assertThat(mockOutputBase)
                .as("MOCK_OUTPUT_BASE 는 BE 의 STORAGE_RAW_MOUNT_ROOTS 와 같은 값이어야 한다(한 세트)")
                .isEqualTo(String.valueOf(yamlValue(
                        BASE_COMPOSE, "services.klid-backend.environment.STORAGE_RAW_MOUNT_ROOTS")));

        // and: backend 가 외부 벤더를 실제로 호출하도록 배선돼 있어야 한다
        String backendEnv = "services.klid-backend.environment.";
        // ★ 지켜야 할 불변식은 그대로다 — <compose 가 내부 self-fill 을 켜면 안 된다>. 다만 그 경로는
        //   코드째 폐지됐고(2026-08-19) compose 도 그 키를 더는 주입하지 않는다. 구 단언은 값이
        //   ":-false" 이기를 요구했는데, 그건 <되돌릴 수단(override)이 남아 있기를 요구>하는 형태라
        //   키가 사라진 지금 형상을 결함으로 잡았다(같은 커밋이 자매 단언
        //   selfFillPropertyIsGone 만 뒤집고 이 줄을 놓쳐 실제로 깨져 있었다).
        //   그래서 판정을 뒤집는다 — <없거나(기본값 false 상속), 있다면 결코 true 일 수 없어야 한다>.
        //   단언을 지우면 누가 DEIDENTIFY_MOCK_MODE=true 를 넣어도 아무도 막지 못한다.
        Object selfFillEnv = yamlValue(BASE_COMPOSE, backendEnv + "DEIDENTIFY_MOCK_MODE");
        if (selfFillEnv != null) {
            assertThat(String.valueOf(selfFillEnv))
                    .as("compose 가 내부 self-fill 을 켜면 안 된다 — 폐지된 경로라 되살아나면 "
                            + "조용히 무시되는 죽은 설정이거나, 구현과 함께 부활한 것이다")
                    .doesNotContain("true")
                    .contains("false");
        }
        assertThat(String.valueOf(yamlValue(BASE_COMPOSE, backendEnv + "KPST_DEID_BASE_URL")))
                .contains("klid-mock-server:9400");
        assertThat(String.valueOf(yamlValue(BASE_COMPOSE, backendEnv + "VLM_SERVICE_URL")))
                .contains("klid-mock-server:9400");
        assertThat(String.valueOf(yamlValue(BASE_COMPOSE, backendEnv + "WEBHOOK_CALLBACK_BASE_URL")))
                .as("콜백 수신 주소가 localhost 면 mock-server 가 자기 자신에게 POST 해 결과가 전부 유실된다")
                .contains("klid-backend:8080");

        // and: 빈 값 override 함정 방어 — .env 에 없을 때 프로파일 기본값까지 빈 값으로 덮어쓰는 형태 금지
        List<String> emptyOverrides = baseKeys.stream()
                .filter(k -> k.startsWith(backendEnv))
                .filter(k -> String.valueOf(yamlValue(BASE_COMPOSE, k)).matches("\\$\\{[A-Z0-9_]+:-}"))
                .toList();
        assertThat(emptyOverrides)
                .as("compose 가 ${VAR:-} 로 재선언하면 .env 미설정 시 '빈 값이 설정된' 상태가 되어 "
                        + "프로파일 yml 기본값(${VAR:default})까지 빈 값으로 덮인다: %s", emptyOverrides)
                .isEmpty();
    }

    @Test
    @DisplayName("genai_산출물_트리가_BE_읽기_allowlist와_볼륨_양쪽으로_배선돼있다")
    void genAiOutputTreeIsReadableByBackend() {
        // given: 목/벤더는 증강 결과를 <별개 트리>(MOCK_GENAI_OUTPUT_BASE)에 쓰고 그 절대경로를 콜백으로
        //   준다. BE 가 ①그 루트를 읽기 allowlist 로 알고 ②그 볼륨을 실제로 마운트해야 반입이 성립한다.
        //   둘 중 하나만 빠지면 콜백이 400(경로 거부) 또는 파일 부재로 실패하고, 그 400 은 상태를
        //   바꾸지 않으므로 증강 1건이 PENDING 에 <영구 고착>된다(DEV_FIX 2차 HIGH-1 재발 차단).
        String genaiOutputBase = String.valueOf(
                yamlValue(BASE_COMPOSE, "services.mock-server.environment.MOCK_GENAI_OUTPUT_BASE"));
        String readRoots = String.valueOf(yamlValue(
                BASE_COMPOSE, "services.klid-backend.environment.STORAGE_EXTERNAL_READ_ROOTS"));

        // when
        List<String> baseBackendVolumes = composeVolumes(BASE_COMPOSE, "klid-backend");
        List<String> localBackendVolumes = composeVolumes(LOCAL_COMPOSE, "klid-backend");
        List<String> localMockVolumes = composeVolumes(LOCAL_COMPOSE, "mock-server");

        // then: ① 읽기 allowlist 가 목 산출 트리를 덮는다
        assertThat(genaiOutputBase).as("목 산출 트리(MOCK_GENAI_OUTPUT_BASE)가 배선돼 있어야 한다").isNotBlank();
        assertThat(readRoots)
                .as("BE 읽기 allowlist(STORAGE_EXTERNAL_READ_ROOTS)가 목 산출 트리를 포함해야 한다")
                .contains(genaiOutputBase);
        // and: ② 쓰기 allowlist 는 넓어지지 않는다(PII 격리 축)
        assertThat(String.valueOf(yamlValue(
                BASE_COMPOSE, "services.klid-backend.environment.STORAGE_RAW_MOUNT_ROOTS")))
                .as("벤더 산출 트리를 쓰기 allowlist 에 넣으면 산출물 쓰기 범위가 벤더 트리까지 넓어진다")
                .doesNotContain(genaiOutputBase);
        // and: ③ BE 가 그 트리를 <읽기 전용>으로 마운트한다
        assertThat(baseBackendVolumes)
                .as("BE 가 목 산출 볼륨을 마운트하지 않으면 경로 검증은 통과해도 파일이 보이지 않는다")
                .anyMatch(v -> v.contains(":" + genaiOutputBase + ":ro"));
        // and: ④ local override 도 같은 named volume 을 가리킨다(목과 다른 위치를 보면 파일 0건)
        assertThat(localBackendVolumes)
                .as("local override 가 genai 볼륨을 교체하지 않으면 BE 는 호스트 바인드를, 목은 named volume 을 본다")
                .anyMatch(v -> v.contains(":" + genaiOutputBase + ":ro"));
        assertThat(localMockVolumes)
                .anyMatch(v -> v.endsWith(":" + genaiOutputBase));
        String backendVolume = localBackendVolumes.stream()
                .filter(v -> v.contains(":" + genaiOutputBase + ":ro")).findFirst().orElseThrow();
        String mockVolume = localMockVolumes.stream()
                .filter(v -> v.endsWith(":" + genaiOutputBase)).findFirst().orElseThrow();
        assertThat(backendVolume.split(":")[0])
                .as("BE 와 목이 같은 볼륨을 봐야 한다(BE=%s, mock=%s)", backendVolume, mockVolume)
                .isEqualTo(mockVolume.split(":")[0]);
    }

    /** compose 서비스의 volumes 항목({@code src:dst[:opt]}) 목록. */
    private List<String> composeVolumes(Path compose, String service) {
        String prefix = "services." + service + ".volumes[";
        return yamlKeys(compose).stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> String.valueOf(yamlValue(compose, k)))
                .toList();
    }

    /** yml 값(placeholder 미해석)에서 기본값 없는 환경변수 이름을 모은다. 주석은 로딩 시점에 제외된다. */
    private Set<String> envKeysWithoutDefault(String... fileNames) {
        Set<String> names = new LinkedHashSet<>();
        for (String fileName : fileNames) {
            for (PropertySource<?> source : MainResourceYaml.load(fileName)) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String key : enumerable.getPropertyNames()) {
                    Matcher matcher = ENV_WITHOUT_DEFAULT.matcher(String.valueOf(enumerable.getProperty(key)));
                    while (matcher.find()) {
                        names.add(matcher.group(1));
                    }
                }
            }
        }
        return names;
    }

    /** 배포 체크리스트의 마커 구간에 백틱으로 기재된 환경변수 목록. */
    private Set<String> documentedRequiredEnv() {
        String doc = read(KEY_CHANGES_DOC);
        int start = doc.indexOf(DOC_MARKER_START);
        int end = doc.indexOf(DOC_MARKER_END);
        assertThat(start).as("배포 체크리스트에 %s 마커가 있어야 한다", DOC_MARKER_START).isNotNegative();
        assertThat(end).as("배포 체크리스트에 %s 마커가 있어야 한다", DOC_MARKER_END).isGreaterThan(start);

        Set<String> keys = new TreeSet<>();
        Matcher matcher = DOC_KEY_TOKEN.matcher(doc.substring(start, end));
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        return keys;
    }

    private Object yamlValue(Path path, String key) {
        for (PropertySource<?> source : loadYaml(path)) {
            if (source.containsProperty(key)) {
                return source.getProperty(key);
            }
        }
        return null;
    }

    private Set<String> yamlKeys(Path path) {
        Set<String> keys = new LinkedHashSet<>();
        for (PropertySource<?> source : loadYaml(path)) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                keys.addAll(List.of(enumerable.getPropertyNames()));
            }
        }
        return keys;
    }

    private List<PropertySource<?>> loadYaml(Path path) {
        assertThat(Files.isReadable(path)).as("파일을 읽을 수 없습니다: %s", path.toAbsolutePath()).isTrue();
        try {
            return new YamlPropertySourceLoader().load(path.toString(), new FileSystemResource(path));
        } catch (IOException e) {
            throw new UncheckedIOException("yml 로딩 실패: " + path, e);
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path.toAbsolutePath(), e);
        }
    }
}
