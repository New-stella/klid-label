package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;

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
     * {@code LS_DATA_RAW} 를 쓰는 <b>기본 리포지토리</b> 호출 — {@code JpaRepository} 상속분이라
     * 리플렉션으로 잡히지 않으므로 여기서 열거한다.
     *
     * <p>커스텀 {@code @Modifying @Query} 쓰기({@code claimForProcessing} 등)는
     * {@link #rawRepositoryModifyingCalls()} 가 리플렉션으로 <b>자동 수집</b>하고, 엔티티 mutator 는
     * {@link #rawEntityMutatorCalls()} 가 자동 수집한다. 손으로 적어두면 새 쓰기 경로가 생겼을 때 가드가
     * 조용히 구멍난다(이 프로젝트의 "가드가 가드를 멈춘 사고" 패턴 — 실제로 B-ISSUE-01 이 신설한
     * {@code claimForProcessing} 이 하드코딩 목록에서 빠져 탐지 대상 밖이었다).
     */
    private static final List<String> RAW_REPOSITORY_WRITE_CALLS = List.of(
            "videoRepository.flush(", "videoRepository.save(", "videoRepository.saveAndFlush(");

    @Test
    @DisplayName("LS_DATA_RAW_쓰기_이후_LS_RAW_DATA_STATUS_행잠금을_잡지_않는다 — 배치와_역순=교착")
    void noRawWriteThenStatusRowLock() {
        // given — 프로덕션 소스(주석 제거) + LsDataRaw mutator·커스텀 @Modifying 쓰기 자동 수집
        List<String> rawWriteMarkers = new ArrayList<>(RAW_REPOSITORY_WRITE_CALLS);
        rawWriteMarkers.addAll(rawEntityMutatorCalls());
        rawWriteMarkers.addAll(rawRepositoryModifyingCalls());

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

    /**
     * 영상 범위로 여러 프레임을 잠그는 경로는 <b>{@code SRC_SN} 축 선점</b>({@code lockFramesByRawSn})을
     * 트랜잭션 맨 앞에서 1회 쓴다 — 프레임 행 축의 잠금 순서 불변식.
     *
     * <h3>무엇을 막나 (실제로 들어온 결함)</h3>
     * 영상 단위 확정 저장(API-196)이 처음 들어올 때 <b>{@code FRM_NO} 오름차순으로 프레임마다 개별
     * {@code FOR UPDATE}</b> 를 잡았다. 그런데 이 저장소의 다른 영상 범위 다중 프레임 경로
     * ({@code TrackEditService} · {@code TrackMergeService} · {@code TrackInterpolationStep})는 전부
     * {@code lockFramesByRawSn}({@code ORDER BY SRC_SN}) 로 선점한다. 추출 순번({@code FRM_NO})과 PK
     * 순서({@code SRC_SN})가 어긋나는 영상에서는 두 순서가 교차해 <b>{@code 40P01}(deadlock) → 500</b> 이
     * 되고 영상 전체 저장이 통째로 롤백된다.
     *
     * <h3>왜 정적 스캔인가 — 그리고 이 가드가 무엇을 <b>보지 않는지</b></h3>
     * 동시 트랜잭션 재현 IT 는 타이밍 의존이라 flaky 한데, "선점을 쓰는가"는 코드 존재로 100% 판정된다.
     * {@code LockOrderGuardTest} 가 그동안 <b>raw→status·advisory 축만</b> 검사해 프레임 행 축이 가드
     * 사각이었고, 그 사각으로 결함이 실제로 들어왔다.
     *
     * <p><b>이 가드는 "선점 호출의 존재"만 본다 — 트랜잭션 내 위치(순서)는 보지 않는다.</b> 텍스트상
     * 첫 등장 위치로 순서를 판정하려 했더니 {@code TrackEditService} 를 <b>오탐</b>했다: 그 파일은
     * 선점을 헬퍼({@code lockFramesForRaw})로 감싸 두었고 그 헬퍼 <b>선언</b>이 bump 헬퍼보다 파일에서
     * 뒤에 있을 뿐, 실제 실행 순서는 선점이 먼저였다. 호출 그래프를 모르는 텍스트 스캔으로 순서를
     * 판정하면 정확할 수 없다 — 그래서 <b>순서는 행위 테스트가 고정</b>한다
     * ({@code VideoLabelSaveTxServiceTest.영상_전_프레임_락을_먼저_선점한다} — Mockito {@code InOrder}
     * 로 선점과 프레임별 저장의 실제 호출 순서를 단언하며, mutation 으로 검증됨).
     * 두 축을 합쳐 <b>존재(정적) + 순서(행위)</b>가 모두 고정된다.
     */
    @Test
    @DisplayName("영상범위_다중프레임_잠금은_SRC_SN축_선점을_쓴다 — 선점_부재=교착")
    void videoScopedMultiFrameLockUsesSrcSnPreemption() {
        // given — 영상 단위로 여러 프레임을 저장/수정하는 프로덕션 경로(파일명 고정)
        List<String> videoScopedWriters = List.of(
                "VideoLabelSaveTxService.java", "TrackEditService.java", "TrackMergeService.java",
                "TrackInterpolationStep.java");

        Set<String> violations = new TreeSet<>();
        for (String fileName : videoScopedWriters) {
            JavaSource src = loadMainSources().stream()
                    .filter(s -> s.path().endsWith(fileName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "소스를 찾을 수 없다(파일 이동·개명 시 이 목록을 갱신할 것): " + fileName));
            if (!src.content().contains("lockFramesByRawSn(")) {
                violations.add(fileName);
            }
        }

        // then
        assertThat(violations)
                .as("영상 범위로 여러 프레임을 잠그는 경로는 lockFramesByRawSn"
                        + "(ORDER BY SRC_SN, FOR NO KEY UPDATE) 로 선점해야 한다. 선점 없이 프레임마다"
                        + " 개별 락을 잡으면(특히 FRM_NO 순서로) SRC_SN 축을 쓰는 나머지 경로와 순환 대기 →"
                        + " 40P01 → 500 이 되어 영상 전체 저장이 통째로 롤백된다. 발견: %s", violations)
                .isEmpty();
    }

    // ---------- 내부 ----------

    /**
     * {@code VideoRepository} 의 커스텀 쓰기({@code @Modifying @Query}) 호출 표현 —
     * {@code videoRepository.메서드명(}.
     *
     * <p>{@code claimForProcessing}/{@code claimReprocessFromFailed}/{@code compensateReprocessClaim}/
     * {@code updateStatus} 같은 조건부 UPDATE 는 {@code save}/{@code flush} 를 거치지 않지만
     * <b>{@code LS_DATA_RAW} 행을 잠그는 쓰기</b>다. 하드코딩 목록이 아니라 리플렉션으로 수집해,
     * 새 조건부 UPDATE 가 추가돼도 가드가 자동으로 따라간다.
     *
     * <p>필드명 접두({@code videoRepository.})를 붙이는 이유: 메서드명만으로 매칭하면 다른 리포지토리의
     * 동명 메서드({@code rawDataStatusRepository.claimReprocessFromFailed})까지 오탐한다. 프로덕션 소스의
     * {@code VideoRepository} 주입 필드명은 56곳 전부 {@code videoRepository} 로 통일돼 있다.
     */
    private static List<String> rawRepositoryModifyingCalls() {
        List<String> calls = new ArrayList<>();
        for (Method m : VideoRepository.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Modifying.class)) {
                calls.add("videoRepository." + m.getName() + "(");
            }
        }
        assertThat(calls)
                .as("VideoRepository 의 @Modifying 쓰기를 하나도 못 찾았다 — 리플렉션 수집이 깨졌다(가드 무력화)")
                .isNotEmpty()
                .contains("videoRepository.claimForProcessing(");
        return calls;
    }

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
