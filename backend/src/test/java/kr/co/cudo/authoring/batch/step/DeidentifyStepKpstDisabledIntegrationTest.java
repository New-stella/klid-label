package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.pipeline.BatchPipeline;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.runner.AsyncDeidentifyRunner;
import kr.co.cudo.authoring.batch.service.DeidentExclusionService;
import kr.co.cudo.authoring.batch.service.DeidentReservationHook;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DerivativeSourceVideoResolver;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UC018 — KPST 토글 OFF 회귀 통합 테스트.
 *
 * <p>{@code kpst.deid.enabled=false} 면 KPST 폴링 빈({@link KpstDeidentService})이 등록되지 않는다.
 * 그때 비식별은 <b>수행되지 않고 설정 오류로 거부</b>된다 — 폴백 경로가 없다.
 *
 * <p><b>이 테스트는 뒤집힌 것이다.</b> 과거에는 같은 조건에서 자체 채움(mock) 경로로 넘어가
 * "비식별 완료"가 됐다. 그 경로는 외부 호출 없이 <b>원본을 비식별 경로로 복사</b>하고
 * {@code DE_IDNTF_YN='Y'} 로 마킹했으므로, 마스킹되지 않은 원본이 비식별본으로 통과했다.
 * 자체 채움을 폐지하면서 이 테스트도 "폴백한다" 에서 <b>"폴백하지 않는다"</b> 로 바뀌었다.
 *
 * <p>이 단언이 있어야 폴백이 편의를 이유로 되살아나는 것을 막는다 — 되살아나면 아무도 모르게
 * 원본이 산출물로 나간다.
 *
 * <h3>출처유형 제외(ADR-066) 실 DB 시험도 이 클래스에 둔다</h3>
 * <p>제외 분기는 외부 연동이 꺼져 있어도 동작해야 하므로 이 컨텍스트가 그 조건 그대로다. 또 스프링 테스트
 * 컨텍스트 종류에 상한이 있어(<b>TestContextDiversityRatchetTest</b>) 새 컨텍스트를 만들지 않고 여기서
 * 재사용한다 — 이 클래스의 애노테이션(프로퍼티·목 빈 조합)을 바꾸면 컨텍스트가 하나 늘어난다.
 * 제외 분기의 외부 위탁 호출 0 은 단위 시험({@code DeidentifyStepTest})이, 프레임 추출 통과는
 * {@code FfmpegFrameExtractorDeidPersistIT} 가 고정한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "authoring.storage.raw-mount-roots=" + ArtifactRootTestSupport.IT_MOUNT_ROOT,
        "kpst.deid.enabled=false"
})
class DeidentifyStepKpstDisabledIntegrationTest {

    @Autowired
    private DeidentifyStep deidentifyStep;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private ApplicationContext applicationContext;

    @Autowired @Qualifier("preMarkingPipeline") private BatchPipeline preMarkingPipeline;
    @Autowired private BatchTransitionService batchTransitionService;
    @Autowired private DeidentReservationHook reservationHook;
    @Autowired private LsDeidentProcLogRepository procLogRepository;
    @Autowired private VideoStreamService videoStreamService;
    @Autowired private DerivativeSourceVideoResolver derivativeSourceVideoResolver;
    @Autowired @Qualifier("controlTransactionManager") private PlatformTransactionManager controlTxManager;

    /** 제외 시험이 심은 영상 — 정리 대상. */
    private final List<Long> seeded = new ArrayList<>();

    @AfterEach
    void cleanupExclusionSeeds() {
        if (seeded.isEmpty()) {
            return;
        }
        TransactionTemplate tx = tx();
        for (Long sn : seeded) {
            tx.executeWithoutResult(s -> {
                procLogRepository.findAllByDataRawSnOrderByReqDtDesc(sn)
                        .forEach(p -> procLogRepository.deleteById(p.getProcLogSn()));
                videoRepository.findById(sn).ifPresent(videoRepository::delete);
            });
        }
        seeded.clear();
    }

    @Test
    @DisplayName("KPST_토글_OFF면_폴백_없이_설정오류로_거부한다 — 자체_채움_경로가_되살아나지_않는다")
    void kpstDisabledRefusesWithoutFallback() throws Exception {
        // given — KPST 폴링 빈이 컨텍스트에 없다(토글 OFF).
        assertThat(applicationContext.getBeanNamesForType(KpstDeidentService.class)).isEmpty();

        // 원본 파일이 <실제로 존재>해도 마찬가지다. 과거에는 바로 이 조건에서 원본을 복사해
        // "비식별 완료" 를 만들어냈다.
        java.nio.file.Path rawFile = ArtifactRootTestSupport.seedOriginalVideo("no-fallback-raw");
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "clip-no-fallback", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60));

        // when / then — 임의 동작 대신 명확히 거부한다.
        assertThatThrownBy(() -> deidentifyStep.run(raw))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        // 그리고 <아무것도 비식별되지 않았다> — 원본이 비식별본으로 둔갑하지 않는다.
        LsDataRaw reloaded = videoRepository.findById(raw.getRawSn()).orElseThrow();
        assertThat(reloaded.getDeIdntfYn())
                .as("비식별이 수행되지 않았는데 'Y' 이면 자체 채움이 되살아난 것이다")
                .isNotEqualTo("Y");
    }

    /**
     * ADR-066 — 출처유형 제외는 외부 연동이 꺼져 있어도 동작한다. 위 거부 단언은 <b>목록 밖</b> 출처유형에
     * 그대로 유효하다(그 영상은 여전히 폴백 없이 거부된다).
     *
     * @design ADR-066
     */
    @Test
    @DisplayName("★KPST_토글_OFF여도_제외_출처유형_GENERATED_는_원본_복사로_비식별을_완료한다")
    void kpstDisabledStillCompletesExcludedSrcType() throws Exception {
        java.nio.file.Path rawFile = ArtifactRootTestSupport.seedOriginalVideo("excluded-kpst-off");
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "clip-excluded-kpst-off-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60, LsDataRaw.SRC_TYPE_GENERATED));
        seeded.add(raw.getRawSn());

        DeidentResult result = deidentifyStep.run(raw);

        assertThat(result.completed()).isTrue();
        assertThat(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(result.deidFilePath())))
                .isEqualTo(java.nio.file.Files.readAllBytes(rawFile));
        assertThat(videoRepository.findById(raw.getRawSn()).orElseThrow().getDeIdntfYn()).isEqualTo("Y");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 출처유형 제외(ADR-066) — 러너 → 복사 → 이력 → MARKING_READY → 뒤 소비처 무수정 통과
    // ─────────────────────────────────────────────────────────────────────────────

    private TransactionTemplate tx() {
        return new TransactionTemplate(controlTxManager);
    }

    /** {@code @Async} 프록시를 피하려고 실제 빈을 인자로 직접 생성한다(동기 실행). */
    private AsyncDeidentifyRunner runner() {
        return new AsyncDeidentifyRunner(preMarkingPipeline, batchTransitionService, videoRepository, reservationHook);
    }

    private Long seedRaw(Path rawFile, String srcType) {
        Long sn = tx().execute(s -> videoRepository.save(LsDataRaw.createFromIngest(
                "excl-it-" + System.nanoTime(), "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60, srcType)).getRawSn());
        seeded.add(sn);
        return sn;
    }

    private LsDataRaw reload(Long sn) {
        return tx().execute(s -> videoRepository.findById(sn).orElseThrow());
    }

    private List<LsDeidentProcLog> logs(Long sn) {
        return tx().execute(s -> procLogRepository.findAllByDataRawSnOrderByReqDtDesc(sn));
    }

    /**
     * @design ADR-066
     * @design DFEAT-041
     * @design AC-1020
     */
    @Test
    @DisplayName("★GENERATED_영상은_복사본_SUCCEEDED_EXCLUDED_이력과_Y_MARKING_READY_로_완료되고_마킹가드_스트림메타_파생부모경로를_통과한다")
    void generatedVideoCompletesByCopyAndPassesConsumers() throws Exception {
        Path rawFile = ArtifactRootTestSupport.seedOriginalVideo("excl-generated");
        Long sn = seedRaw(rawFile, LsDataRaw.SRC_TYPE_GENERATED);

        runner().runAsync(sn);

        LsDataRaw after = reload(sn);
        assertThat(after.getDeIdntfYn()).isEqualTo("Y");
        assertThat(after.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        assertThat(after.getRawFilePathNm()).as("원본 경로 불변").isEqualTo(rawFile.toString());

        LsDeidentProcLog latest = tx().execute(s -> procLogRepository.findLatestSuccessByDataRawSn(sn).orElseThrow());
        assertThat(latest.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(latest.getReqKindCd()).isEqualTo(LsDeidentProcLog.REQ_KIND_EXCLUDED);
        assertThat(latest.getPollSttsCd()).isNull();
        Path copy = Path.of(latest.getDeIdntfFilePathNm());
        assertThat(copy.getFileName().toString()).isEqualTo(rawFile.getFileName().toString());
        assertThat(Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(copy)).isFalse();
        assertThat(Files.isSameFile(copy, rawFile)).isFalse();
        assertThat(Files.readAllBytes(copy)).isEqualTo(Files.readAllBytes(rawFile));

        // ── 뒤 소비처 (코드 변경 없이 통과)
        // ① 마킹 진입 가드
        invokeMarkingPreconditions(after);
        // ② 스트림 메타 해석 — 비식별 허용 base 검사 통과 + 복사본을 가리킴
        VideoStreamService.StreamMeta meta = videoStreamService.resolveStreamMeta(sn);
        assertThat(meta).isNotNull();
        assertThat(meta.contentLength()).isEqualTo(Files.size(rawFile));
        assertThat(meta.path()).isEqualTo(copy.toRealPath());
        // ③ 파생 부모 경로 조회
        assertThat(derivativeSourceVideoResolver.requireSourceVideo(after)).isEqualTo(copy.normalize());
    }

    /** @design ADR-066 */
    @Test
    @DisplayName("★EXCLUDED_이력_행은_KPST_폴링_대상과_ACK_대기_회수_대상에_오르지_않는다")
    void excludedRowsAreNotPollingOrAckReclaimTargets() throws Exception {
        Path rawFile = ArtifactRootTestSupport.seedOriginalVideo("excl-poll");
        Long sn = seedRaw(rawFile, LsDataRaw.SRC_TYPE_GENERATED);
        runner().runAsync(sn);
        Long missingSn = seedRaw(rawFile.resolveSibling("excl-poll-absent-" + System.nanoTime() + ".mp4"),
                LsDataRaw.SRC_TYPE_GENERATED);
        runner().runAsync(missingSn);

        List<Long> excludedLogSns = new ArrayList<>();
        logs(sn).forEach(p -> excludedLogSns.add(p.getProcLogSn()));
        logs(missingSn).forEach(p -> excludedLogSns.add(p.getProcLogSn()));
        assertThat(excludedLogSns).hasSize(2);

        List<LsDeidentProcLog> pollTargets = tx().execute(s -> procLogRepository.findByPollSttsCdIn(
                List.of(LsDeidentProcLog.POLL_WAITING, LsDeidentProcLog.POLL_POLLING), PageRequest.of(0, 10_000)));
        assertThat(pollTargets).extracting(LsDeidentProcLog::getProcLogSn).doesNotContainAnyElementsOf(excludedLogSns);

        for (Long procLogSn : excludedLogSns) {
            Integer claimed = tx().execute(s -> procLogRepository.claimSubmitFailure(
                    procLogSn, "KPST_ACK_MISSING", "submit ack not received", LocalDateTime.now()));
            assertThat(claimed).as("ACK 대기 회수(WAITING 조건부 UPDATE)가 제외 행을 집으면 안 된다").isZero();
        }
    }

    /** @design ADR-066 */
    @Test
    @DisplayName("★원본이_없으면_F_와_FAILED_EXCLUDED_이력을_남기고_MARKING_READY_로_전이하지_않으며_임시파일이_없다")
    void missingSourceFailsWithoutMarkingReady() throws Exception {
        Path absent = ArtifactRootTestSupport.seedOriginalVideo("excl-absent-sibling")
                .resolveSibling("excl-absent-" + System.nanoTime() + ".mp4");
        Long sn = seedRaw(absent, LsDataRaw.SRC_TYPE_GENERATED);

        runner().runAsync(sn);

        LsDataRaw after = reload(sn);
        assertThat(after.getDeIdntfYn()).isEqualTo("F");
        assertThat(after.getDataSttsCd()).isNotEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        assertThat(after.getRawFilePathNm()).isEqualTo(absent.toString());

        List<LsDeidentProcLog> history = logs(sn);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
        assertThat(history.get(0).getReqKindCd()).isEqualTo(LsDeidentProcLog.REQ_KIND_EXCLUDED);
        assertThat(history.get(0).getErrorCd()).isEqualTo(DeidentExclusionService.SOURCE_MISSING_CODE);
        Optional<LsDeidentProcLog> latestSuccess = tx().execute(s -> procLogRepository.findLatestSuccessByDataRawSn(sn));
        assertThat(latestSuccess).isEmpty();

        Path deidDir = absent.getParent().resolve(String.valueOf(sn));
        if (Files.exists(deidDir)) {
            try (Stream<Path> walk = Files.walk(deidDir)) {
                assertThat(walk.filter(p -> p.getFileName().toString().startsWith(".deid-excluded-")).toList())
                        .isEmpty();
            }
        }
        assertThat(Files.exists(absent)).as("원본 자리에 아무것도 만들지 않는다").isFalse();
    }

    /** 마킹 진입 가드는 패키지 전용이라 리플렉션으로 호출한다 — 판정 로직을 복제하지 않기 위해서다. */
    private static void invokeMarkingPreconditions(LsDataRaw raw) throws Exception {
        Class<?> guards = Class.forName("kr.co.cudo.authoring.marking.service.MarkingGuards");
        Method m = guards.getDeclaredMethod("requirePreconditions", LsDataRaw.class);
        m.setAccessible(true);
        try {
            m.invoke(null, raw);
        } catch (InvocationTargetException e) {
            throw new AssertionError("마킹 진입 가드가 제외 완료 영상을 거부했다", e.getCause());
        }
    }
}
