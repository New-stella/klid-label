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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 암묵 기본값(코드에만 있고 yml 에 없던 손잡이) 명시 가드 — 조사 리포트 D1.
 *
 * <p>배경(2026-07-27 설정 전수조사 D1): 아래 키들은 코드가 {@code ${key:default}} /
 * {@code @ConditionalOnProperty} 로 읽고 있었지만 yml 어디에도 흔적이 없어, <b>운영자가 존재
 * 자체를 발견할 수 없는 손잡이</b>였다. 이를 공통 yml 에 명시하되 <b>값은 코드 기본값 그대로</b>
 * 옮겨 동작 변경을 0 으로 만든다.
 *
 * <p>이 테스트가 지키는 두 가지:
 * <ol>
 *   <li><b>존재</b> — D1 키가 공통 yml({@code application.yml})에 선언돼 있다.</li>
 *   <li><b>동일값</b> — yml 이 해석되는 값이 <b>코드에 박힌 기본값과 완전히 같다</b>.
 *       (placeholder 기본값이 있는 키는 main 소스에서 실제 기본값을 긁어와 대조하므로,
 *       나중에 한쪽만 바뀌면 여기서 깨진다.)</li>
 * </ol>
 *
 * <p>{@code @ConditionalOnProperty} 로만 읽는 키는 소스에 문자열 기본값이 없으므로
 * ({@code matchIfMissing} / {@code havingValue} 로 결정) 기대값을 표에 명시하고 근거를 주석에 남긴다.
 */
class ImplicitDefaultConfigGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");
    private static final String COMMON_YML = "application.yml";

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    /**
     * D1 대상 키 → 코드 기본값. 값의 출처는 아래 주석 참조.
     * placeholder 기본값이 있는 키는 {@link #codeDefault(String)} 로 소스에서 재확인한다.
     */
    private static final Map<String, String> IMPLICIT_DEFAULTS = new LinkedHashMap<>();

    static {
        // ControlNotifyFallbackRetryJob — @Scheduled(fixedDelayString/initialDelayString)
        IMPLICIT_DEFAULTS.put("authoring.control-notify.retry.interval-ms", "300000");
        IMPLICIT_DEFAULTS.put("authoring.control-notify.retry.initial-delay-ms", "60000");
        // ResizeConcurrencyGate
        IMPLICIT_DEFAULTS.put("authoring.resolution.resize-max-concurrent", "2");
        IMPLICIT_DEFAULTS.put("authoring.resolution.resize-acquire-timeout-sec", "5");
        // Sam2SegmentService — 20MB
        IMPLICIT_DEFAULTS.put("authoring.sam2.max-image-bytes", "20971520");
        // TusUploadCleanupJob — @Scheduled(1h / 10m)
        IMPLICIT_DEFAULTS.put("authoring.upload.tus.cleanup.interval-ms", "3600000");
        IMPLICIT_DEFAULTS.put("authoring.upload.tus.cleanup.initial-delay-ms", "600000");
        // InMemoryWebhookIdempotencyLedger — @ConditionalOnProperty(havingValue="true"),
        //   matchIfMissing 없음 → 미선언 시 비활성(=false). 운영 기본은 DB 원장(Persistent).
        IMPLICIT_DEFAULTS.put("authoring.webhook.idempotency.in-memory", "false");
        // PortalUploadSweepJob — @Scheduled(30m / 10m). 스케줄러 어노테이션은 상수 표현식만
        //   허용하므로 record 로 옮길 수 없어 placeholder 를 유지하고 yml 명시만 한다.
        IMPLICIT_DEFAULTS.put("portal.upload.sweep.interval-ms", "1800000");
        IMPLICIT_DEFAULTS.put("portal.upload.sweep.initial-delay-ms", "600000");
    }

    @Test
    @DisplayName("암묵_기본값을_yml에_명시해도_기존_동작이_바뀌지_않는다")
    void implicitDefaultsAreDeclaredWithIdenticalValues() {
        // given: 공통 yml 만 로드(시스템 환경변수 배제 — placeholder 기본값만 반영)
        Environment env = MainResourceYaml.environment(COMMON_YML);
        Set<String> declared = MainResourceYaml.keys(COMMON_YML);

        // when
        List<String> undeclared = new ArrayList<>();
        List<String> valueDrift = new ArrayList<>();
        List<String> codeDrift = new ArrayList<>();
        IMPLICIT_DEFAULTS.forEach((key, expected) -> {
            if (!declared.contains(key)) {
                undeclared.add(key);
                return;
            }
            String resolved = env.getProperty(key);
            if (!expected.equals(resolved)) {
                valueDrift.add(key + ": yml=" + resolved + " ≠ 코드기본값=" + expected);
            }
            String fromCode = codeDefault(key);
            if (fromCode != null && !fromCode.equals(expected)) {
                codeDrift.add(key + ": 코드=" + fromCode + " ≠ 기대표=" + expected);
            }
        });

        // then
        assertThat(undeclared)
                .as("코드만 읽고 yml 에 없는 암묵 기본값(운영자가 발견 불가): %s", undeclared)
                .isEmpty();
        assertThat(valueDrift)
                .as("yml 에 명시한 값이 코드 기본값과 다르다 — 조용한 동작 변경: %s", valueDrift)
                .isEmpty();
        assertThat(codeDrift)
                .as("코드 placeholder 기본값이 바뀌었는데 yml/기대표가 따라오지 않았다: %s", codeDrift)
                .isEmpty();
    }

    /** main 소스에서 {@code ${key:default}} 의 default 를 찾는다(없으면 null). */
    private String codeDefault(String key) {
        Pattern pattern = Pattern.compile("\\$\\{" + Pattern.quote(key) + ":([^}]*)}");
        for (String source : loadMainSources()) {
            Matcher matcher = pattern.matcher(source);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private List<String> loadMainSources() {
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
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll(" ");
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }
}
