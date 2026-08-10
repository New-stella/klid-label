package kr.co.cudo.authoring.label.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R3 — {@link DeidentArtifactCandidateFinder} 단위 테스트(실파일 기반).
 *
 * <p>여기서만 <b>디렉터리 열거</b>를 실제로 태운다. {@code DeidentReportServiceTest} 는 서비스 계약
 * (상태 전이·이벤트·응답 코드)을 다루며 열거 결과가 "현재 원장 산출물 1건"으로 수렴하는 시나리오를 쓴다.
 *
 * <p>고정하는 불변식:
 * <ul>
 *   <li>다른 이름으로 산출된 파일도 후보로 열거된다(이 기능의 존재 이유 — 구 판정은 원장 경로 1개만 봤다).</li>
 *   <li>목록 키는 언제나 <b>basename</b> 이다 → 경로 순회 입력은 어떤 항목과도 일치하지 않는다(CWE-22).</li>
 *   <li>심링크 엔트리는 후보가 되지 않는다(CWE-59).</li>
 *   <li>디렉터리 부재/빈 디렉터리는 <b>빈 목록</b>(예외 아님).</li>
 *   <li>자격 판정 = 무결성 + mtime &gt; 신고시각(<b>엄격</b>). 경계값(동일 시각)은 부적격.</li>
 *   <li>상한은 <b>둘</b>이다(CWE-770) — 결과 후보 수({@link DeidentArtifactCandidateFinder#MAX_CANDIDATES})와
 *       디렉터리 1개당 스캔 항목 수({@link DeidentArtifactCandidateFinder#MAX_SCAN_ENTRIES}).
 *       후자는 <b>적재·정렬 이전에</b> 걸려야 실제로 자원을 묶는다. 둘 다 걸리면 WARN 을 남긴다.</li>
 * </ul>
 */
class DeidentArtifactCandidateFinderTest {

    @TempDir
    Path tempDir;

    private VideoRepository videoRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private DeidentArtifactCandidateFinder finder;
    /** 절단 WARN 캡처 — 응답에 절단을 알릴 필드가 없어 로그가 유일한 관측 수단이다. */
    private ListAppender<ILoggingEvent> logAppender;

    private static final long RAW_SN = 4242L;
    private static final String ORGNL_PATH = "/nas/raw/clip.mp4";

    /** 신고시각 — 파일시스템 mtime 정밀도에 흔들리지 않도록 초 정렬한다. */
    private LocalDateTime reportTime;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        // 비식별 저장소 base = tempDir → 열거 대상은 {tempDir}/videos/{rawSn}/.
        // 원본 경로(/nas/raw/...)는 allowlist 밖이라 co-locate 후보는 조용히 빠진다(fail-secure 경로도 함께 탄다).
        finder = new DeidentArtifactCandidateFinder(videoRepository, procLogRepository,
                new VideoArtifactRootResolver("", "", tempDir.toString(), tempDir.toString(),
                        tempDir.resolve("labeling").toString(), "co-locate"));

        reportTime = LocalDateTime.now().minusHours(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "C-" + RAW_SN, "CCTV", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, ORGNL_PATH, LocalDateTime.now(), 30);
        setField(raw, "rawSn", RAW_SN);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        when(procLogRepository.findLatestSuccessByDataRawSn(anyLong())).thenReturn(Optional.empty());

        // 로거는 JVM 전역 싱글턴이라 테스트마다 붙였다 떼지 않으면 appender 가 누적된다(@AfterEach 참조).
        logAppender = new ListAppender<>();
        logAppender.start();
        finderLogger().addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        finderLogger().detachAppender(logAppender);
        logAppender.stop();
    }

    private static Logger finderLogger() {
        return (Logger) LoggerFactory.getLogger(DeidentArtifactCandidateFinder.class);
    }

    /** 캡처된 WARN 메시지(포맷 인자 전개 후). */
    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // ---------- 픽스처 ----------

    private LsDeidentReport report() {
        LsDeidentReport rep = LsDeidentReport.createReport(RAW_SN, 100L, "얼굴 미블러");
        setField(rep, "deidentReportSn", 1L);
        setField(rep, "reportDt", reportTime);
        return rep;
    }

    /** 비식별 영상 디렉터리({base}/videos/{rawSn}) 에 mtime 을 지정한 유효 mp4 를 만든다. */
    private Path artifact(String name, LocalDateTime mtime) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("videos").resolve(String.valueOf(RAW_SN)));
        Path file = TestVideoFixtures.writeTinyMp4(dir.resolve(name));
        Files.setLastModifiedTime(file, FileTime.from(mtime.atZone(ZoneId.systemDefault()).toInstant()));
        return file;
    }

    private void stubCurrent(Path path) {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(RAW_SN, "req", ORGNL_PATH, "system");
        procLog.succeed(path.toString());
        setField(procLog, "resDt", reportTime.minusMinutes(10)); // 옛 성공 이력
        when(procLogRepository.findLatestSuccessByDataRawSn(RAW_SN)).thenReturn(Optional.of(procLog));
    }

    // ---------- 테스트 ----------

    @Test
    @DisplayName("★다른_이름으로_산출된_파일도_후보로_열거된다_이_기능의_존재_이유")
    void listsArtifactWithDifferentName() throws Exception {
        // given — 원장은 옛 산출물(001.mp4)을 가리키는데, 외부 솔루션은 001-mask.mp4 로 만들었다.
        Path old = artifact("001.mp4", reportTime.minusMinutes(30));
        Path fresh = artifact("001-mask.mp4", reportTime.plusMinutes(5));
        stubCurrent(old);

        List<DeidentArtifactCandidateFinder.Candidate> found = finder.find(report());

        assertThat(found).extracting(DeidentArtifactCandidateFinder.Candidate::fileName)
                .containsExactly("001-mask.mp4", "001.mp4"); // 파일명 오름차순(결정적 순서)
        assertThat(found).filteredOn(c -> c.fileName().equals("001-mask.mp4"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.eligible()).isTrue();
                    assertThat(c.current()).isFalse();
                    assertThat(c.sizeBytes()).isEqualTo(Files.size(fresh));
                });
        // 옛 산출물은 목록에는 있지만(현재 원장 표시) 신고 이전이라 자격이 없다.
        assertThat(found).filteredOn(c -> c.fileName().equals("001.mp4"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.eligible()).isFalse();
                    assertThat(c.current()).isTrue();
                });
        assertThat(old).exists();
    }

    @Test
    @DisplayName("목록_키는_파일명뿐이라_경로_순회_입력은_어떤_항목과도_일치하지_않는다")
    void selectRejectsPathTraversalInputs() throws Exception {
        Path fresh = artifact("001-mask.mp4", reportTime.plusMinutes(5));

        assertThat(finder.select(report(), "001-mask.mp4")).isPresent();
        for (String attempt : new String[]{
                "../001-mask.mp4", "./001-mask.mp4", "videos/4242/001-mask.mp4",
                fresh.toString(), "..", "", "   ", null}) {
            assertThat(finder.select(report(), attempt))
                    .as("attempt=%s", attempt)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("심링크는_후보가_되지_않는다")
    void symlinkEntryIsNotACandidate() throws Exception {
        Path real = Files.write(tempDir.resolve("outside-original.mp4"),
                TestVideoFixtures.tinyMp4Bytes());
        Path dir = Files.createDirectories(tempDir.resolve("videos").resolve(String.valueOf(RAW_SN)));
        Path link = dir.resolve("looks-like-deid.mp4");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // 심링크 미지원 파일시스템 — 이 가드는 해당 환경에서 의미가 없다.
        }
        Files.setLastModifiedTime(link, FileTime.from(
                reportTime.plusMinutes(5).atZone(ZoneId.systemDefault()).toInstant()));

        assertThat(finder.find(report())).isEmpty();
    }

    @Test
    @DisplayName("디렉터리가_없거나_비어_있으면_빈_목록이다_예외가_아니다")
    void emptyOrMissingDirectoryYieldsEmptyList() throws Exception {
        assertThat(finder.find(report())).isEmpty(); // 디렉터리 자체가 없음

        Files.createDirectories(tempDir.resolve("videos").resolve(String.valueOf(RAW_SN)));
        assertThat(finder.find(report())).isEmpty(); // 빈 디렉터리
    }

    @Test
    @DisplayName("무결성_미달_산출물은_목록에는_있으나_자격이_없다")
    void integrityFailureIsListedButIneligible() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("videos").resolve(String.valueOf(RAW_SN)));
        Path stub = Files.write(dir.resolve("stub.mp4"), "MOCK_DEIDENTIFIED\n".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(stub, FileTime.from(
                reportTime.plusMinutes(5).atZone(ZoneId.systemDefault()).toInstant()));

        assertThat(finder.find(report())).singleElement()
                .satisfies(c -> {
                    assertThat(c.fileName()).isEqualTo("stub.mp4");
                    assertThat(c.eligible()).isFalse();
                });
    }

    @Test
    @DisplayName("mtime이_신고시각과_같으면_부적격이다_엄격_비교_경계값")
    void mtimeEqualToReportTimeIsIneligible() throws Exception {
        artifact("boundary.mp4", reportTime);

        assertThat(finder.find(report())).singleElement()
                .satisfies(c -> assertThat(c.eligible()).isFalse());
    }

    @Test
    @DisplayName("신고시각이_없으면_모든_후보가_부적격이다_fail_closed")
    void missingReportTimeMakesEverythingIneligible() throws Exception {
        artifact("fresh.mp4", reportTime.plusMinutes(5));
        LsDeidentReport rep = report();
        setField(rep, "reportDt", null);

        assertThat(finder.find(rep)).singleElement()
                .satisfies(c -> assertThat(c.eligible()).isFalse());
    }

    @Test
    @DisplayName("열거는_상한에서_멈춘다_무제한_나열_금지")
    void enumerationStopsAtLimit() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("videos").resolve(String.valueOf(RAW_SN)));
        int over = DeidentArtifactCandidateFinder.MAX_CANDIDATES + 25;
        for (int i = 0; i < over; i++) {
            Files.write(dir.resolve(String.format("f%04d.mp4", i)), new byte[]{1, 2, 3});
        }

        assertThat(finder.find(report()))
                .hasSize(DeidentArtifactCandidateFinder.MAX_CANDIDATES);
        // 절단은 조용히 일어나지 않는다 — 응답에 알릴 필드가 없으므로 WARN 이 유일한 관측 수단이다.
        assertThat(warnMessages()).anyMatch(m -> m.contains("candidate scan truncated"));
    }

    @Test
    @DisplayName("★스캔은_상한_항목에서_끊긴다_디렉터리를_전량_적재_정렬하지_않는다_CWE_770")
    void scanStopsBeforeCollectingWholeDirectory() throws Exception {
        // given — 스캔 상한 4, 후보 상한은 넉넉히(20). 파일은 40개 = 상한의 10배.
        //   구 구현은 40개를 전부 ArrayList 에 담고 정렬한 뒤에야 후보 상한(20)에서 잘랐으므로
        //   결과가 40건이 되어 이 단언에서 실패한다(= 이 테스트가 그 결함의 회귀 가드다).
        DeidentArtifactCandidateFinder bounded = boundedFinder(20, 4);
        Path dir = Files.createDirectories(tempDir.resolve("videos").resolve(String.valueOf(RAW_SN)));
        for (int i = 0; i < 40; i++) {
            Files.write(dir.resolve(String.format("f%04d.mp4", i)), new byte[]{1, 2, 3});
        }

        List<DeidentArtifactCandidateFinder.Candidate> found = bounded.find(report());

        // 살펴본 항목이 4개뿐이므로(모두 정규 파일 = 모두 후보로 기술됨) 결과도 정확히 4건이다.
        //   후보 상한(20)에는 닿지도 않는다 → 절단이 "적재·정렬 이전"에 걸렸다는 증거.
        assertThat(found).hasSize(4);
        assertThat(found).extracting(DeidentArtifactCandidateFinder.Candidate::fileName)
                .allMatch(n -> n.startsWith("f") && n.endsWith(".mp4"));
        assertThat(warnMessages()).anyMatch(m -> m.contains("candidate dir scan truncated")
                && m.contains("scanLimit=4"));
    }

    @Test
    @DisplayName("절단_WARN에_내부_저장_경로가_실리지_않는다_CWE_117_209")
    void truncationWarnDoesNotLeakInternalPath() throws Exception {
        DeidentArtifactCandidateFinder bounded = boundedFinder(2, 3);
        Path dir = Files.createDirectories(tempDir.resolve("videos").resolve(String.valueOf(RAW_SN)));
        for (int i = 0; i < 10; i++) {
            Files.write(dir.resolve(String.format("f%04d.mp4", i)), new byte[]{1, 2, 3});
        }

        assertThat(bounded.find(report())).hasSize(2); // 후보 상한(2)이 먼저 걸린다

        List<String> warns = warnMessages();
        assertThat(warns).isNotEmpty();
        assertThat(warns).allSatisfy(m -> {
            assertThat(m).doesNotContain(tempDir.toString());
            assertThat(m).doesNotContain(dir.toString());
            assertThat(m).doesNotContain(ORGNL_PATH);
            assertThat(m).doesNotContain("videos" + java.io.File.separator);
        });
    }

    /** 상한을 낮게 주입한 열거기 — 파일 수천 개를 만들지 않고 절단 경로를 태운다(테스트 전용 생성자). */
    private DeidentArtifactCandidateFinder boundedFinder(int maxCandidates, int maxScanEntries) {
        return new DeidentArtifactCandidateFinder(videoRepository, procLogRepository,
                new VideoArtifactRootResolver("", "", tempDir.toString(), tempDir.toString(),
                        tempDir.resolve("labeling").toString(), "co-locate"),
                maxCandidates, maxScanEntries);
    }

    @Test
    @DisplayName("원장이_가리키는_산출물은_열거_디렉터리_밖이어도_후보에_남는다_제자리_덮어쓰기_보존")
    void currentArtifactOutsideScannedDirsIsStillListed() throws Exception {
        // 전략 전환 이전 산출물처럼 열거 대상 밖에 있는 경우 — 목록에서 빠지면 정상 해소가 막힌다.
        Path outside = TestVideoFixtures.writeTinyMp4(tempDir.resolve("legacy-deid.mp4"));
        Files.setLastModifiedTime(outside, FileTime.from(
                reportTime.plusMinutes(5).atZone(ZoneId.systemDefault()).toInstant()));
        stubCurrent(outside);

        assertThat(finder.find(report())).singleElement()
                .satisfies(c -> {
                    assertThat(c.fileName()).isEqualTo("legacy-deid.mp4");
                    assertThat(c.current()).isTrue();
                    assertThat(c.eligible()).isTrue(); // 제자리 교체(mtime 최신) → 정상 해소 가능
                });
    }

    @Test
    @DisplayName("원장_산출물이_열거_결과와_같은_파일이면_중복되지_않고_current로_표시된다")
    void currentArtifactInsideScannedDirIsNotDuplicated() throws Exception {
        Path fresh = artifact("001-mask.mp4", reportTime.plusMinutes(5));
        stubCurrent(fresh);

        assertThat(finder.find(report())).singleElement()
                .satisfies(c -> {
                    assertThat(c.fileName()).isEqualTo("001-mask.mp4");
                    assertThat(c.current()).isTrue();
                });
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> c = target.getClass();
            Field f = null;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                throw new NoSuchFieldException(name);
            }
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
