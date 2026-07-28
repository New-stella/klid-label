package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정 키(yml ↔ 코드) 정합 가드 — "yml 에 쓰여 있는데 코드가 안 읽는" 오배치 회귀 차단.
 *
 * <p>배경(2026-07-27 설정 전수조사 A1/A2): {@code kpst.augment.external.mode} 로 잘못 선언된
 * 키 때문에 local 증강 시뮬레이터가 한 번도 로드되지 않았고, {@code authoring.ffprobe.binary} 는
 * yml 에 정의되지 않은 채 코드만 읽어 {@code FFPROBE_BIN} 주입이 이 경로에만 반영되지 않았다.
 * 두 결함 모두 <b>기동은 성공하고 조용히 기본값으로 동작</b>해 테스트/리뷰에서 드러나지 않았다.
 *
 * <p><b>커버 범위(의도적으로 좁음)</b>
 * <ul>
 *   <li>(1) ffprobe 바이너리 경로는 단일 키로만 주입된다.</li>
 *   <li>(2) 기본값이 없는(`${key}`) 코드 참조 키는 반드시 yml 에 정의돼 있다 — 없으면 부팅 실패.</li>
 *   <li>(3) 오배치 탐지: yml 만 있고 아무도 안 읽는 키가, 코드만 읽고 yml 에 없는 키와
 *       <b>끝 3개 세그먼트가 같으면</b> prefix 오배치로 간주하고 실패시킨다.</li>
 * </ul>
 *
 * <p><b>커버하지 못하는 것</b>: 기본값이 있는 코드 키가 yml 에 없는 경우(암묵 기본값 — 조사
 * 리포트 D1, 정리는 별도 Phase)와, 아무도 안 읽는 유령 키 일반(리포트 B) 은 여기서 판정하지 않는다.
 * 세그먼트 3개 미만의 짧은 키 오배치도 탐지하지 않는다.
 *
 * <p>ArchUnit 미사용 프로젝트이므로 기존 {@code *GuardTest} 들과 동일하게 순수 파일 스캔을 쓴다.
 */
class ConfigPropertyKeyGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    private static final List<String> CONFIG_FILES = List.of(
            "application.yml", "application-local.yml", "application-dev.yml",
            "application-stg.yml", "application-prd.yml");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    /** {@code ${key}} / {@code ${key:default}} placeholder. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9._\\-]+)(:[^}]*)?}");
    private static final Pattern CONDITIONAL_ON_PROPERTY =
            Pattern.compile("@ConditionalOnProperty\\s*\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern ANNOTATION_PREFIX = Pattern.compile("prefix\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern ANNOTATION_NAME = Pattern.compile("(?:name|value)\\s*=\\s*\"([^\"]+)\"");

    /** ffprobe 바이너리 경로 단일 진실원 키. */
    private static final String FFPROBE_KEY = "authoring.ffmpeg.ffprobe-binary";

    /**
     * 2026-07-27 설정 전수조사 B — 코드 참조 0건(main+test+docs)으로 확인돼 제거한 사문화 키.
     * 값만 남아 "설정하면 먹는 손잡이"처럼 보이던 유령 키라, yml 재유입도 코드 참조도 모두 막는다.
     */
    private static final List<String> REMOVED_KEYS = List.of(
            "authoring.jwt.algorithm",
            "authoring.batch.rate-per-min",
            "authoring.batch.frame-interval-sec",
            "authoring.integration.deidentify.timeout-ms",
            "authoring.integration.ai-server.timeout-ms",
            "kpst.augment.external.mode");

    @Test
    @DisplayName("ffprobe_경로_설정이_모든_사용처에_동일_키로_주입된다")
    void ffprobeBinaryKeyIsUnified() {
        // given: 프로덕션 main 소스가 참조하는 설정 키 전체
        Set<String> codeKeys = codeReferencedKeys();

        // when: ffprobe 바이너리 경로 관련 키 추출
        List<String> ffprobeKeys = codeKeys.stream()
                .filter(k -> k.contains("ffprobe"))
                .sorted()
                .toList();

        // then: 단일 키로 통일 + 해당 키가 공통 yml 에 정의돼 있어야 한다
        assertThat(ffprobeKeys)
                .as("ffprobe 경로는 %s 단일 키로만 주입해야 한다(분열 시 FFPROBE_BIN 주입 누락)", FFPROBE_KEY)
                .containsExactly(FFPROBE_KEY);
        assertThat(MainResourceYaml.keys("application.yml"))
                .as("%s 는 공통 yml 에 정의돼 있어야 한다", FFPROBE_KEY)
                .contains(FFPROBE_KEY);
    }

    @Test
    @DisplayName("코드가_참조하는_설정키가_yml에_정의되어_있다")
    void codeReferencedKeysAreDeclaredInYml() {
        // given
        Set<String> ymlKeys = declaredYmlKeys();
        Set<String> codeKeys = codeReferencedKeys();
        Set<String> requiredKeys = codeKeysWithoutDefault();

        // when: (2) 기본값 없는 필수 키 누락 + (3) prefix 오배치 의심 페어 탐지
        List<String> missingRequired = requiredKeys.stream()
                .filter(k -> !ymlKeys.contains(k))
                .sorted()
                .toList();

        List<String> orphanYmlKeys = ymlKeys.stream()
                .filter(k -> !codeKeys.contains(k))
                .toList();
        List<String> misplaced = new ArrayList<>();
        for (String codeKey : codeKeys) {
            if (ymlKeys.contains(codeKey)) {
                continue;
            }
            for (String ymlKey : orphanYmlKeys) {
                if (!codeKey.equals(ymlKey) && lastSegments(codeKey).equals(lastSegments(ymlKey))) {
                    misplaced.add("코드=" + codeKey + " ↔ yml=" + ymlKey);
                }
            }
        }

        // then
        assertThat(missingRequired)
                .as("기본값이 없는 설정 키는 yml 에 정의돼야 한다(누락 시 부팅 실패). 누락: %s", missingRequired)
                .isEmpty();
        assertThat(misplaced)
                .as("yml 키가 코드가 읽는 키와 다른 prefix 에 선언돼 있다(오배치 — 조용히 무시됨): %s", misplaced)
                .isEmpty();
    }

    @Test
    @DisplayName("제거된_설정키는_코드에서_참조되지_않는다")
    void removedKeysAreNeitherDeclaredNorReferenced() {
        // given
        Set<String> ymlKeys = declaredYmlKeys();
        Set<String> codeKeys = codeReferencedKeys();

        // when
        List<String> stillDeclared = REMOVED_KEYS.stream().filter(ymlKeys::contains).toList();
        List<String> stillReferenced = REMOVED_KEYS.stream().filter(codeKeys::contains).toList();

        // then
        assertThat(stillReferenced)
                .as("제거 대상 키를 코드가 읽고 있다 — 제거하면 조용히 기본값으로 동작한다: %s", stillReferenced)
                .isEmpty();
        assertThat(stillDeclared)
                .as("아무도 읽지 않는 사문화 키가 yml 에 남아 있다(설정 가능한 손잡이로 오인됨): %s", stillDeclared)
                .isEmpty();
    }

    @Test
    @DisplayName("stg_프로파일에_KPST_비식별_연동이_명시적으로_배선돼있다")
    void stgProfileWiresKpstDeidExplicitly() {
        // given: 공통 기본 base-url 은 https://localhost:9201 이고, https 인데 ca-cert 가 비면
        //        KpstWebClientConfig 가 fail-closed 로 빈 생성에 실패한다(CWE-295) → 부팅 차단.
        Object baseUrl = MainResourceYaml.rawValue("application-stg.yml", "kpst.deid.base-url");

        // then: stg 는 환경변수 주입을 강제하는 형태로 base-url 을 명시해야 한다
        assertThat(baseUrl)
                .as("stg 프로파일에 kpst.deid.base-url 배선 필요(미배선 시 공통 https 기본값 상속 → 부팅 차단)")
                .isNotNull();
        assertThat(String.valueOf(baseUrl))
                .as("stg 의 KPST 주소는 환경변수(KPST_DEID_BASE_URL) 주입 형태여야 한다")
                .contains("${KPST_DEID_BASE_URL");
    }

    /** 끝 3개 세그먼트(세그먼트가 부족하면 전체 키). */
    private String lastSegments(String key) {
        String[] parts = key.split("\\.");
        if (parts.length < 3) {
            return key;
        }
        return String.join(".", parts[parts.length - 3], parts[parts.length - 2], parts[parts.length - 1]);
    }

    private Set<String> declaredYmlKeys() {
        Set<String> keys = new LinkedHashSet<>();
        CONFIG_FILES.forEach(file -> keys.addAll(MainResourceYaml.keys(file)));
        return keys;
    }

    /** {@code @Value} placeholder + {@code @ConditionalOnProperty} 로 코드가 읽는 키. */
    private Set<String> codeReferencedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (String source : loadMainSources()) {
            Matcher placeholder = PLACEHOLDER.matcher(source);
            while (placeholder.find()) {
                keys.add(placeholder.group(1));
            }
            Matcher conditional = CONDITIONAL_ON_PROPERTY.matcher(source);
            while (conditional.find()) {
                String args = conditional.group(1);
                Matcher name = ANNOTATION_NAME.matcher(args);
                if (!name.find()) {
                    continue;
                }
                Matcher prefix = ANNOTATION_PREFIX.matcher(args);
                keys.add(prefix.find() ? prefix.group(1) + "." + name.group(1) : name.group(1));
            }
        }
        return keys;
    }

    /** 기본값 없이 참조하는 키({@code ${key}}) — yml 에 없으면 기동이 실패한다. */
    private Set<String> codeKeysWithoutDefault() {
        Set<String> keys = new LinkedHashSet<>();
        for (String source : loadMainSources()) {
            Matcher placeholder = PLACEHOLDER.matcher(source);
            while (placeholder.find()) {
                if (placeholder.group(2) == null) {
                    keys.add(placeholder.group(1));
                }
            }
        }
        return keys;
    }

    private List<String> loadMainSources() {
        assertThat(Files.isDirectory(MAIN_SRC))
                .as("스캔 대상 디렉토리(%s)가 존재해야 한다", MAIN_SRC.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(this::read)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("main 소스 스캔 실패", e);
        }
    }

    private String read(Path path) {
        try {
            return stripComments(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }

    private String stripComments(String source) {
        String noBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return LINE_COMMENT.matcher(noBlock).replaceAll(" ");
    }
}
