package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 잠금 순서(lock ordering) 가드 — 프로덕션 소스 스캔 (DEV_FIX 2차 [1]).
 *
 * <h3>고정하는 불변식</h3>
 * <b>{@code LS_DATA_RAW} 를 쓴 트랜잭션은 그 뒤에 {@code LS_RAW_DATA_STATUS} 행 잠금을 잡지 않는다.</b>
 * 두 테이블을 반대 순서로 잠그는 상대가 이미 존재하기 때문이다:
 * <pre>
 *   BatchTransitionService#markRawDataProcessingBlocked / markRawDataCompleted / markRawDataFailed
 *     → ① 조건부 벌크 UPDATE LS_RAW_DATA_STATUS   ② 같은 트랜잭션에서 dirty checking UPDATE LS_DATA_RAW
 * </pre>
 * 즉 배치는 <b>status → raw</b> 로 잠근다. 어떤 화면 저장 경로가 <b>raw → status</b> 로 잠그면
 * 순환 대기가 성립해 PostgreSQL 이 한쪽을 {@code 40P01}(deadlock detected) 로 죽인다. 배치 진입점은
 * 주기 배치·수동 재처리 등 <b>모든 배치 시작</b>에서 돌기 때문에 드문 사고가 아니다.
 *
 * <h3>대신 무엇을 쓰나</h3>
 * 동시 승인과의 직렬화가 필요하면 {@code LsDatasetVideoMetaRepository#acquireRawLock}
 * ({@code pg_advisory_xact_lock})을 쓴다 — 기존 불변식 <b>raw 행락 → advisory</b> 단방향에 합류하므로
 * 새 간선을 만들지 않는다({@code EnvironmentMetaService} · {@code VideoPrivacyMetaService} 선례).
 *
 * <h3>왜 정적 스캔인가</h3>
 * 실제 교착을 재현하는 동시 트랜잭션 IT 는 타이밍 의존이라 flaky 하고 비싸다. 반면 이 결함은
 * <b>코드에 적힌 순서</b>로 100% 판정된다. "판정 원천인 상태 행을 잠가야 정확하다"는 직관이 강해
 * 되돌리기 쉬운 지점이므로(1차 DEV_FIX 가 실제로 그렇게 했다) 구조로 고정한다.
 */
class LockOrderGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n\\r]*");

    /**
     * {@code LS_RAW_DATA_STATUS} 행을 <b>잠그며</b> 읽는 유일한 메서드
     * ({@code @Lock(PESSIMISTIC_READ)} = PostgreSQL {@code FOR SHARE}).
     * 새 잠금 조회가 생기면 이 목록에 함께 추가해야 한다(리포지토리 파일 자체는 스캔 대상에서 제외).
     */
    private static final List<String> STATUS_ROW_LOCK_CALLS = List.of("findByRawDataIdForShare(");

    /** 잠금 메서드 <b>선언</b>이 있는 파일 — 선언은 위반이 아니다. */
    private static final String STATUS_LOCK_DECLARING_FILE = "LsRawDataStatusRepository.java";

    /**
     * {@code LS_DATA_RAW} 를 쓰는 호출 — 리포지토리 write + 엔티티 mutator 전체.
     *
     * <p>mutator 목록은 {@link LsDataRaw} 리플렉션으로 <b>자동 수집</b>한다. 손으로 적어두면 새 mutator
     * 가 생겼을 때 가드가 조용히 구멍난다(이 프로젝트의 "가드가 가드를 멈춘 사고" 패턴).
     */
    private static final List<String> RAW_REPOSITORY_WRITE_CALLS = List.of(
            "videoRepository.flush(", "videoRepository.save(", "videoRepository.saveAndFlush(");

    @Test
    @DisplayName("LS_DATA_RAW_쓰기_이후_LS_RAW_DATA_STATUS_행잠금을_잡지_않는다 — 배치와_역순=교착")
    void noRawWriteThenStatusRowLock() {
        // given — 프로덕션 소스(주석 제거) + LsDataRaw mutator 자동 수집
        List<String> rawWriteMarkers = new ArrayList<>(RAW_REPOSITORY_WRITE_CALLS);
        rawWriteMarkers.addAll(rawEntityMutatorCalls());

        // when — "raw 쓰기 → status 행잠금" 순서로 등장하는 파일 수집
        Set<String> violations = new TreeSet<>();
        for (JavaSource src : loadMainSources()) {
            if (src.path().endsWith(STATUS_LOCK_DECLARING_FILE)) {
                continue; // 잠금 메서드 선언부 — 순서 개념이 없다
            }
            int lockAt = firstIndexOf(src.content(), STATUS_ROW_LOCK_CALLS);
            if (lockAt < 0) {
                continue;
            }
            int rawWriteAt = firstIndexOf(src.content(), rawWriteMarkers);
            if (rawWriteAt >= 0 && rawWriteAt < lockAt) {
                violations.add(src.path());
            }
        }

        // then
        assertThat(violations)
                .as("LS_DATA_RAW 를 쓴 뒤 LS_RAW_DATA_STATUS 행을 FOR SHARE 로 잠그면"
                        + " BatchTransitionService(status→raw)와 순환 대기가 되어 40P01 이 난다."
                        + " 동시 승인 직렬화가 필요하면 acquireRawLock(advisory)을 쓸 것. 발견: %s", violations)
                .isEmpty();
    }

    /**
     * 승인 직렬화가 필요한 <b>메타 저장 서비스</b>는 advisory 락을 쓴다 — 위 금지 가드의 짝(positive).
     *
     * <p>금지만 걸어두면 "잠금을 아예 빼버리는" 방향으로도 통과한다. 그러면 교착은 사라지지만
     * 동시 승인 경합 창(산출물 stale 고착)이 되살아난다. 두 서비스가 advisory 를 <b>계속</b> 잡는지도
     * 함께 고정한다.
     */
    @Test
    @DisplayName("영상축_메타_저장_서비스는_advisory락으로_동시승인을_직렬화한다")
    void metaWritersKeepAdvisoryLock() {
        for (String fileName : List.of("VideoPrivacyMetaService.java", "EnvironmentMetaService.java")) {
            JavaSource src = loadMainSources().stream()
                    .filter(s -> s.path().endsWith(fileName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("소스를 찾을 수 없다: " + fileName));
            int flushAt = src.content().indexOf("videoRepository.flush(");
            int advisoryAt = src.content().indexOf("acquireRawLock(");
            assertThat(advisoryAt)
                    .as("%s 는 동시 승인(materialize)과 같은 advisory 락을 잡아야 한다", fileName)
                    .isGreaterThanOrEqualTo(0);
            assertThat(flushAt)
                    .as("%s 는 advisory 이전에 flush 로 raw 행을 잠가야 한다(순서 고정)", fileName)
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(advisoryAt);
        }
    }

    // ---------- 내부 ----------

    /** {@link LsDataRaw} 의 public 인스턴스 mutator(void 반환) 호출 표현 — {@code .메서드명(}. */
    private static List<String> rawEntityMutatorCalls() {
        List<String> calls = new ArrayList<>();
        for (Method m : LsDataRaw.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())
                    || Modifier.isStatic(m.getModifiers())
                    || m.getReturnType() != void.class
                    || m.isSynthetic()) {
                continue;
            }
            calls.add("." + m.getName() + "(");
        }
        assertThat(calls)
                .as("LsDataRaw mutator 를 하나도 못 찾았다 — 리플렉션 수집이 깨졌다(가드 무력화)")
                .isNotEmpty();
        return calls;
    }

    private static int firstIndexOf(String content, List<String> needles) {
        int best = -1;
        for (String needle : needles) {
            int at = content.indexOf(needle);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    private static List<JavaSource> loadMainSources() {
        try (Stream<Path> paths = Files.walk(MAIN_SRC)) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .map(LockOrderGuardTest::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
