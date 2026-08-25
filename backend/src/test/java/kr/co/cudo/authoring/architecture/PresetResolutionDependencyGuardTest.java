package kr.co.cudo.authoring.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프리셋 해석축의 <b>역방향 의존 allowlist</b> 가드 — 프로덕션 소스 스캔. [@design ADR-054]
 *
 * <h3>왜 이 방향이 열려 있나 (지우지 말 것)</h3>
 * <p>프리셋이 실효하는지의 판정은 <b>{@code batch.policy} 가 소유하는 단일 진실원</b>이다
 * ({@code PresetLabelLookupService.resolve} → {@code PresetResolution}/{@code PresetResolutionStatus}).
 * 오토라벨 보류 여부와 관리 화면의 연결 상태 표시가 <b>같은 판정</b>이어야 하므로, 프리셋 화면
 * ({@code preset})과 이벤트유형 화면({@code eventtype})이 그 판정을 <b>호출</b>한다. 각자 프리셋을
 * 다시 조회해 실효성을 재유도하면 한쪽만 갱신되는 드리프트가 열린다(이 저장소의 반복 결함 패턴).
 *
 * <p>그 결과 패키지 수준에서는 {@code batch.policy → eventtype.service} 와 합쳐 <b>순환</b>이 된다.
 * 빈 수준은 여전히 DAG 라 기동에 문제가 없고, 이 결합은 <b>의도된 것</b>이다.
 *
 * <h3>그래서 무엇을 막나</h3>
 * <p>의도된 결합이라는 이유로 이 방향이 <b>조용히 넓어지는 것</b>을 막는다. 지금 열려 있는 것은
 * 판정 진실원 세 타입뿐이며, 그 밖의 {@code batch} 타입(스텝·오케스트레이터·상태 서비스·러너 …)을
 * 프리셋/이벤트유형 쪽에서 새로 참조하면 이 시험이 <b>깨지면서 판단을 요구한다</b>.
 *
 * <p>새 타입이 정말 필요하면 두 가지 중 하나다 — ① 그것도 판정 진실원의 일부라면 아래 allowlist 에
 * 추가하고 이 javadoc 에 근거를 남긴다 ② 아니라면 {@code batch} 쪽에 협력자를 두지 말고
 * <b>도메인이 소유한 인터페이스</b>로 뒤집는다.
 *
 * <h3>왜 정적 스캔인가</h3>
 * <p>이 프로젝트에는 의존 방향 가드가 없었고 ArchUnit 의존성도 들이지 않았다. 이 결함은
 * <b>소스에 적힌 import</b> 로 100% 판정되므로 기존 정적 스캔 가드({@code LockOrderGuardTest} ·
 * {@code *RemovalTest})와 같은 골격으로 충분하다.
 *
 * <p>⚠ <b>스캔이 조용히 0건이 되는 것</b>이 이런 가드의 전형적 실패 방식이다(경로 오타·디렉터리
 * 이동). 그래서 위반 목록만 보지 않고 <b>훑은 파일 수</b>와 <b>실제로 찾아낸 참조</b>를 함께 단언한다 —
 * 아무것도 못 찾으면 "위반 없음"이 아니라 "가드가 멈췄다"로 실패한다.
 */
class PresetResolutionDependencyGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");
    private static final Path BASE = MAIN_SRC.resolve("kr/co/cudo/authoring");

    /** 역방향 의존이 허용된 소비자 패키지 — 프리셋 화면축과 이벤트유형 화면축. */
    private static final List<String> CONSUMER_PACKAGES = List.of("preset", "eventtype");

    /**
     * 소비자가 참조해도 되는 {@code batch} 타입 — <b>프리셋 해석 판정의 단일 진실원</b> 그 자체뿐이다.
     * 중첩 타입({@code PresetLabelLookupService.AnnotationToggle})은 바깥 타입으로 판정한다.
     */
    private static final Set<String> ALLOWED_BATCH_TYPES = Set.of(
            "kr.co.cudo.authoring.batch.policy.PresetLabelLookupService",
            "kr.co.cudo.authoring.batch.policy.PresetResolution",
            "kr.co.cudo.authoring.batch.policy.PresetResolutionStatus");

    /** 소비자 패키지 소스에 등장하는 {@code batch} 패키지 참조(모든 표기 — import·FQN). */
    private static final Pattern BATCH_REFERENCE =
            Pattern.compile("kr\\.co\\.cudo\\.authoring\\.batch\\.[A-Za-z0-9_.]+");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    /** 소비자 패키지에 최소 이만큼은 있다 — 스캔이 통째로 빗나가면 여기서 먼저 깨진다. */
    private static final int MIN_SCANNED_FILES = 20;

    @Test
    @DisplayName("프리셋_이벤트유형이_참조하는_batch_타입은_해석_진실원_셋으로_고정된다")
    void consumersReferenceOnlyPresetResolutionTypes() {
        List<JavaSource> sources = loadConsumerSources();

        // ★스캔이 실제로 돌았는가 — "0건 = 위반 없음" 으로 읽히는 조용한 실패를 먼저 배제한다.
        assertThat(sources)
                .as("소비자 패키지 소스를 하나도 읽지 못했다 — 경로(%s)가 어긋나 가드가 멈춘 것이다", BASE)
                .hasSizeGreaterThanOrEqualTo(MIN_SCANNED_FILES);

        Set<String> found = new TreeSet<>();
        Set<String> violations = new TreeSet<>();
        for (JavaSource src : sources) {
            Matcher m = BATCH_REFERENCE.matcher(src.content());
            while (m.find()) {
                String type = outerType(m.group());
                found.add(type);
                if (!ALLOWED_BATCH_TYPES.contains(type)) {
                    violations.add(src.path() + " → " + type);
                }
            }
        }

        // ★탐지가 살아 있는가 — 알려진 참조를 실제로 집어내야 한다(정규식이 깨지면 여기서 깨진다).
        assertThat(found)
                .as("소비자 패키지에서 batch 참조를 하나도 찾지 못했다 — 참조가 정말 사라졌거나"
                        + " 탐지 정규식이 깨진 것이다. 후자라면 아래 위반 단언은 항상 통과한다")
                .containsAll(ALLOWED_BATCH_TYPES);

        assertThat(violations)
                .as("프리셋(preset)·이벤트유형(eventtype)이 batch 에서 참조해도 되는 것은 프리셋 해석"
                        + " 판정의 단일 진실원 %s 뿐이다. 이 방향은 「같은 판정을 두 곳에서 재유도하지"
                        + " 않는다」는 이유로만 열려 있고, 패키지 수준으로는 batch.policy → eventtype.service"
                        + " 와 순환이다. 새 타입이 필요하면 ①그것도 판정 진실원이면 이 가드의 allowlist 에"
                        + " 근거와 함께 추가하고 ②아니면 도메인이 소유한 인터페이스로 방향을 뒤집을 것."
                        + " 발견: %s", ALLOWED_BATCH_TYPES, violations)
                .isEmpty();
    }

    // ---------- 내부 ----------

    /**
     * 참조 문자열 → <b>바깥 타입</b> FQN. {@code ...policy.PresetLabelLookupService.AnnotationToggle}
     * 처럼 중첩 타입·정적 멤버까지 붙어 오므로, 첫 대문자 시작 세그먼트까지만 남긴다.
     */
    private static String outerType(String reference) {
        String[] parts = reference.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(part);
            if (!part.isEmpty() && Character.isUpperCase(part.charAt(0))) {
                break;
            }
        }
        return sb.toString();
    }

    private static List<JavaSource> loadConsumerSources() {
        List<JavaSource> sources = new ArrayList<>();
        for (String pkg : CONSUMER_PACKAGES) {
            Path root = BASE.resolve(pkg);
            assertThat(Files.isDirectory(root))
                    .as("소비자 패키지 디렉터리가 없다(이동·개명 시 이 가드를 갱신할 것): %s", root)
                    .isTrue();
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(p -> p.toString().endsWith(".java"))
                        .map(PresetResolutionDependencyGuardTest::read)
                        .forEach(sources::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return sources;
    }

    private static JavaSource read(Path path) {
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            String stripped = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(raw).replaceAll(""))
                    .replaceAll("");
            return new JavaSource(path.toString().replace('\\', '/'), stripped);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record JavaSource(String path, String content) {
    }
}
