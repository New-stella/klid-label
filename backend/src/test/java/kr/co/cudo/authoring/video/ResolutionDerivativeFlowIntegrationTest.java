package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentJobStatus;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugLblMap;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.service.AugmentReviewService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.video.dto.ResolutionDerivativeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.runner.AsyncResolutionRunner;
import kr.co.cudo.authoring.video.service.ResolutionDerivativeService;
import kr.co.cudo.authoring.video.service.ResolutionFileMaterializer;
import kr.co.cudo.authoring.video.service.ResolutionSnapshot;
import kr.co.cudo.authoring.video.service.ResolutionSnapshotService;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import kr.co.cudo.authoring.video.service.port.VideoFileCopier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해상도 파생영상 전체 플로우 통합 검증(Testcontainers PostgreSQL) — Phase 1 (증강 저장모델 통합).
 *
 * <p>동기 예약({@code ResolutionDerivativeService.createDerivative}) → AFTER_COMMIT 비동기 확정
 * ({@code ResolutionDerivativeFinalizer})까지 실제 DB 로 검증한다. 파생 적재가 구 LS_RESOLUTION_EXPORT/
 * LS_RESOLUTION_LBL_MAP 이 아니라 LS_DATA_AUG(RES_*) + LS_DATA_AUG_LBL_MAP 으로 이뤄지는지 확인한다.
 * 해상도 aug 상태는 생성 라이프사이클과 일치한다 — 예약=PENDING, finalize 성공 확정 후=ACCEPTED.
 * 이미지/비디오 파일 I/O 는 {@link ImageResizer}/{@link VideoFileCopier} 를 @MockBean 으로 격리한다.
 *
 * <p>공유 PG 오염 방지 — 시드 clipId 는 {@code RESIT-} 고유 접두사를 쓰고 단언은 시드한 rawSn 으로만 좁힌다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "authoring.storage.raw-path=/tmp/klid-res-it"
})
class ResolutionDerivativeFlowIntegrationTest {

    private static final String BASE = "/tmp/klid-res-it";

    @MockBean private ImageResizer imageResizer;
    @MockBean private VideoFileCopier videoFileCopier;
    // 비-트랜잭션 순수 파일 서비스라 spy 로 감싸도 트랜잭션 프록시가 깨지지 않는다 — 실제 A~C 창 재현용.
    @SpyBean private ResolutionFileMaterializer fileMaterializer;

    @Autowired private ResolutionDerivativeService service;
    @Autowired private ResolutionSnapshotService snapshotService;
    @Autowired private VideoStreamService videoStreamService;
    @Autowired private AsyncResolutionRunner asyncResolutionRunner;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository statusRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDeidentProcLogRepository procLogRepository;
    @Autowired private LsDeidentReportRepository deidentReportRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugLblMapRepository lblMapRepository;
    @Autowired private AugmentReviewService augService;

    @BeforeEach
    void setup() {
        when(videoFileCopier.exists(any())).thenReturn(true);
        // resize/copy 는 no-op (파일 미생성). readDimensions 는 각 테스트에서 스텁.
    }

    private record Seed(LsDataRaw parent, LsDataSrc frame0, LsDataSrc frame1, LsDataLbl label) {
    }

    /** 원본(APPROVED·비식별) + 프레임 2 + 라벨 1 + 비식별 성공 procLog 시드. deidVideoPath 를 지정 가능. */
    private Seed seed(String suffix, String deidVideoPath) {
        return seed(suffix, deidVideoPath, "Y");
    }

    private Seed seed(String suffix, String deidVideoPath, String deIdntfYn) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "RESIT-" + suffix, "CCTV-RESIT", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, BASE + "/videos/RESIT-" + suffix + "-orig.mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified(deIdntfYn);
        parent = videoRepository.save(parent);

        LsRawDataStatus st = LsRawDataStatus.initial(parent.getRawSn());
        st.transitionTo(LsRawDataStatus.STTS_APPROVED);
        statusRepository.save(st);

        LsDataSrc f0 = LsDataSrc.create(parent.getRawSn(), 0L, 0L,
                BASE + "/frames/" + suffix + "/f0.jpg", LocalDateTime.now());
        f0.attachDeidPath(BASE + "/frames/deid/" + suffix + "/f0.jpg");
        f0 = srcRepository.save(f0);
        LsDataSrc f1 = LsDataSrc.create(parent.getRawSn(), 1L, 30L,
                BASE + "/frames/" + suffix + "/f1.jpg", LocalDateTime.now());
        f1.attachDeidPath(BASE + "/frames/deid/" + suffix + "/f1.jpg");
        f1 = srcRepository.save(f1);

        LsDataLbl label = lblRepository.save(LsDataLbl.createAutoBbox(
                f0.getSrcSn(), null, "person", "[10,20,30,40]", BigDecimal.valueOf(0.9), null));

        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                parent.getRawSn(), null, parent.getRawFilePathNm(), "seed");
        procLog.succeed(deidVideoPath);
        procLogRepository.save(procLog);

        return new Seed(parent, f0, f1, label);
    }

    private LsDataRaw childOf(Long parentRawSn) {
        return videoRepository.findAll().stream()
                .filter(r -> parentRawSn.equals(r.getOrgnlRawSn()))
                .findFirst().orElse(null);
    }

    private long childCount(Long parentRawSn) {
        return videoRepository.findAll().stream()
                .filter(r -> parentRawSn.equals(r.getOrgnlRawSn()))
                .count();
    }

    private LsDataRaw awaitFinalized(Long parentRawSn) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    LsDataRaw c = childOf(parentRawSn);
                    return c != null
                            && LsDataRaw.DATA_STTS_MARKING_READY.equals(c.getDataSttsCd())
                            && "Y".equals(c.getDeIdntfYn());
                });
        return childOf(parentRawSn);
    }

    @Test
    @DisplayName("해상도변경_요청시_LS_DATA_AUG에_RESL_1080P_720P_480P_행이_생성되고_확정후_ACCEPTED_IDMP_OTSD_null이다")
    void createsResolutionAugRowsForThreePresets() {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{3840, 2160});
        Seed s = seed("3PRESET", BASE + "/videos/RESIT-3PRESET-deid.mp4");
        Long parentRawSn = s.parent().getRawSn();
        long f0Sn = s.frame0().getSrcSn();

        for (ResolutionPreset preset : List.of(
                ResolutionPreset.RESL_1080P, ResolutionPreset.RESL_720P, ResolutionPreset.RESL_480P)) {
            service.createDerivative(parentRawSn, preset, "rev1");
        }

        // 예약 시점엔 PENDING(생성 중)으로 커밋되고, async finalize 가 성공 확정하면 ACCEPTED(생성 완료)로
        // 전이된다. 3종 모두 확정될 때까지 대기 후 최종 상태를 단언한다(라이프사이클 정합).
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> augRepository.findBySrcSnOrderByAugTypeCd(f0Sn).stream()
                        .filter(a -> a.getAugTypeCd() != null && a.getAugTypeCd().startsWith("RESL_"))
                        .count() == 3
                        && augRepository.findBySrcSnOrderByAugTypeCd(f0Sn).stream()
                        .filter(a -> a.getAugTypeCd() != null && a.getAugTypeCd().startsWith("RESL_"))
                        .allMatch(a -> LsDataAug.STTS_ACCEPTED.equals(a.getAugProcSttsCd())));

        // 대표프레임 SRC_SN(f0) 로 3종 RES_ 증강행이 적재됨 — 확정 후 상태 ACCEPTED, IDMP/OTSD null.
        List<LsDataAug> augs = augRepository.findBySrcSnOrderByAugTypeCd(f0Sn);
        assertThat(augs).hasSize(3);
        assertThat(augs).extracting(LsDataAug::getAugTypeCd)
                .containsExactlyInAnyOrder("RESL_1080P", "RESL_720P", "RESL_480P");
        assertThat(augs).allSatisfy(a -> {
            assertThat(a.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
            assertThat(a.getIdempotencyKey()).isNull();
            assertThat(a.getExternalJobId()).isNull();
            // #3 — srcSn 은 measureFirstFrame 이 사용한 첫 프레임 SRC_SN 과 동일.
            assertThat(a.getSrcSn()).isEqualTo(f0Sn);
        });
    }

    @Test
    @DisplayName("해상도_파생_예약직후_finalize전에는_aug가_PENDING이라_집계가_COMPLETED가_아니다")
    void reservedResolutionAugIsPendingBeforeFinalize() {
        // given — 예약만 커밋되고 async 확정은 아직 미실행인 in-flight 상태를 직접 재현한다
        //         (createResolutionPending = 예약 시점 상태). 파생 RAW 는 PENDING(생성 중).
        Seed s = seed("INFLIGHT", BASE + "/videos/RESIT-INFLIGHT-deid.mp4");
        Long parentRawSn = s.parent().getRawSn();
        long f0Sn = s.frame0().getSrcSn();

        LsDataAug reserved = augRepository.save(LsDataAug.createResolutionPending(
                f0Sn, LsDataAug.AUG_RESL_720P, "rev1"));
        videoRepository.save(LsDataRaw.createFromResolution(
                s.parent(), BASE + "/resolution/" + parentRawSn + "/RESL_720P/video/RESL_720P.mp4", "RESL_720P"));

        // then — 예약행은 PENDING(non-terminal). 집계상 COMPLETED(생성 완료)로 오표기되지 않는다.
        assertThat(reserved.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
        var job = augService.findBySource(f0Sn).getContent().get(0);
        assertThat(job.status()).isNotEqualTo(AugmentJobStatus.COMPLETED.name());
        assertThat(job.completedAt()).isNull();
        assertThat(job.resolutionTypes()).containsExactly("RESL_720P");
    }

    @Test
    @DisplayName("해상도_파생_finalize성공후_aug가_ACCEPTED로_전이되어_집계가_COMPLETED다")
    void finalizedResolutionAugBecomesAcceptedAndCompleted() {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        Seed s = seed("DONE", BASE + "/videos/RESIT-DONE-deid.mp4");
        long f0Sn = s.frame0().getSrcSn();

        ResolutionDerivativeResponse res = service.createDerivative(
                s.parent().getRawSn(), ResolutionPreset.RESL_720P, "rev1");
        awaitFinalized(s.parent().getRawSn());

        // finalize 성공 후 예약 aug 가 ACCEPTED(생성 완료)로 전이된다.
        LsDataAug aug = augRepository.findById(res.dataAugSn()).orElseThrow();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);

        // 해상도만 있는 그룹은 확정 후 전부 terminal → COMPLETED.
        var job = augService.findBySource(f0Sn).getContent().get(0);
        assertThat(job.status()).isEqualTo(AugmentJobStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("Finalizer가_라벨을_배율스케일해_LS_DATA_AUG_LBL_MAP에_COORD_RECALC_Y로_적재한다")
    void downscaleCopiesVideoAndRescalesFramesAndLabels() {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        Seed s = seed("DOWN", BASE + "/videos/RESIT-DOWN-deid.mp4");

        ResolutionDerivativeResponse res = service.createDerivative(
                s.parent().getRawSn(), ResolutionPreset.RESL_720P, "rev1");

        LsDataRaw child = awaitFinalized(s.parent().getRawSn());
        assertThat(child.getOrgnlRawSn()).isEqualTo(s.parent().getRawSn());
        assertThat(child.getVmsClipId()).contains("_RESL_RESL_720P_");

        // 비디오 복사 — dst = 파생 RAW 경로
        verify(videoFileCopier, atLeastOnce()).copy(any(), any());
        // 프레임 리스케일 — 목표 1280x720 로 각 프레임 resize
        verify(imageResizer, atLeastOnce()).resize(any(), any(),
                org.mockito.ArgumentMatchers.eq(1280), org.mockito.ArgumentMatchers.eq(720));

        List<LsDataSrc> childFrames = srcRepository.findByRawSnOrderByFrameNoAsc(child.getRawSn());
        assertThat(childFrames).hasSize(2);
        assertThat(childFrames).extracting(LsDataSrc::getVideoFrameNo).containsExactly(0L, 30L);

        // 라벨 좌표 스케일 (sx=sy=1280/1920=720/1080=0.6667): [10,20,30,40] → [7,13,20,27]
        List<LsDataLbl> childLabels = lblRepository.findBySrcSn(childFrames.get(0).getSrcSn());
        assertThat(childLabels).hasSize(1);
        assertThat(childLabels.get(0).getPointCn()).isEqualTo("[7,13,20,27]");

        // LS_DATA_AUG_LBL_MAP — coordRecalc='Y' + scaleX/scaleY 기록 (구 LS_RESOLUTION_LBL_MAP 대체)
        List<LsDataAugLblMap> maps = lblMapRepository.findAllByDataAugSn(res.dataAugSn());
        assertThat(maps).hasSize(1);
        assertThat(maps.get(0).getCoordRecalcYn()).isEqualTo("Y");
        assertThat(maps.get(0).getScaleX()).isNotNull();
        assertThat(maps.get(0).getScaleX().doubleValue()).isCloseTo(1280d / 1920d, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    @DisplayName("업스케일_프리셋에서_프레임이_확대되어_저장된다")
    void upscaleEnlargesFrames() {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{640, 360});
        Seed s = seed("UP", BASE + "/videos/RESIT-UP-deid.mp4");

        service.createDerivative(s.parent().getRawSn(), ResolutionPreset.RESL_1080P, "rev1");

        LsDataRaw child = awaitFinalized(s.parent().getRawSn());
        assertThat(child).isNotNull();
        // 확대 — 목표 1920x1080 (원본 640x360 보다 큼)
        verify(imageResizer, atLeastOnce()).resize(any(), any(),
                org.mockito.ArgumentMatchers.eq(1920), org.mockito.ArgumentMatchers.eq(1080));
        assertThat(srcRepository.findByRawSnOrderByFrameNoAsc(child.getRawSn())).hasSize(2);
    }

    @Test
    @DisplayName("확정된_파생을_PhaseA가_멱등skip하고_프레임이_중복생성되지_않는다")
    void finalizeIsIdempotent() {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        Seed s = seed("IDEM", BASE + "/videos/RESIT-IDEM-deid.mp4");

        ResolutionDerivativeResponse res = service.createDerivative(
                s.parent().getRawSn(), ResolutionPreset.RESL_720P, "rev1");
        LsDataRaw child = awaitFinalized(s.parent().getRawSn());
        assertThat(srcRepository.countByRawSn(child.getRawSn())).isEqualTo(2L);

        // 중복 트리거 시뮬 — Phase A 가 이미 확정(deIdntfYn='Y')을 멱등 skip 한다(빈 스냅샷 → 재생성 없음).
        Optional<ResolutionSnapshot> reSnap = snapshotService.snapshot(
                child.getRawSn(), s.parent().getRawSn(), res.dataAugSn(), ResolutionPreset.RESL_720P);
        assertThat(reSnap).isEmpty();
        assertThat(srcRepository.countByRawSn(child.getRawSn())).isEqualTo(2L);
    }

    @Test
    @DisplayName("확정전_파생RAW는_deIdntfYn_N이라_스트리밍이_NOT_FOUND로_거부된다(#2_PII_TOCTOU)")
    void nonDeidentifiedDerivativeStreamRejected() {
        // given — 예약만 커밋(파생 RAW deIdntfYn='N', 확정 전). 마킹 스트림은 원본을 절대 노출하지 않는다.
        Seed s = seed("STREAM", BASE + "/videos/RESIT-STREAM-deid.mp4");
        LsDataRaw child = videoRepository.save(LsDataRaw.createFromResolution(
                s.parent(), BASE + "/resolution/" + s.parent().getRawSn() + "/RESL_720P/video/RESL_720P.mp4", "RESL_720P"));
        assertThat(child.getDeIdntfYn()).isNotEqualTo("Y"); // 확정 전 = 'N'

        // then — deIdntfYn != 'Y' 게이트에서 NOT_FOUND(내부 상태 미노출, 원본 노출 차단).
        assertThatThrownBy(() -> videoStreamService.stream(child.getRawSn(), new org.springframework.http.HttpHeaders()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("같은_파생RAW를_2스레드가_동시_finalize해도_프레임은_1회만_삽입된다(#5_승자보호)")
    void concurrentDuplicateFinalizeInsertsFramesOnce() throws Exception {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        Seed s = seed("DUPFIN", BASE + "/videos/RESIT-DUPFIN-deid.mp4");
        Long parentRawSn = s.parent().getRawSn();

        // 예약만 커밋(auto-finalize 우회) — 동일 파생 RAW/aug 를 2스레드가 동시에 확정하는 창을 재현한다.
        LsDataAug aug = augRepository.save(LsDataAug.createResolutionPending(
                s.frame0().getSrcSn(), LsDataAug.AUG_RESL_720P, "rev1"));
        LsDataRaw child = videoRepository.save(LsDataRaw.createFromResolution(
                s.parent(), BASE + "/resolution/" + parentRawSn + "/RESL_720P/video/RESL_720P.mp4", "RESL_720P"));
        Long childRawSn = child.getRawSn();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    asyncResolutionRunner.runAsync(childRawSn, parentRawSn, aug.getDataAugSn(), ResolutionPreset.RESL_720P);
                } catch (Exception ignored) {
                    // 러너는 예외를 삼키므로 여기 도달하지 않음(latch 대기 인터럽트만 무시).
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);

        // 확정 완료 대기 후 — 부모 프레임 2건이 정확히 1회만 삽입(중복 finalize 승자 보호, CAS 재확인).
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> "Y".equals(videoRepository.findById(childRawSn).orElseThrow().getDeIdntfYn()));
        assertThat(srcRepository.countByRawSn(childRawSn)).isEqualTo(2L);
        LsDataRaw finalizedChild = videoRepository.findById(childRawSn).orElseThrow();
        assertThat(finalizedChild.getDataSttsCd())
                .isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        // 패자의 실패정리가 승자를 FAILED 로 오표기하지 않는다(M-1 승자 산출물/상태 보호).
        assertThat(finalizedChild.getDataSttsCd())
                .as("동시 finalize 승자가 FAILED 로 오표기되면 안 됨")
                .isNotEqualTo(LsDataRaw.DATA_STTS_FAILED);
    }

    @Test
    @DisplayName("경로에_상위탈출_시도시_거부되고_RAW가_FAILED로_전이된다")
    void pathTraversalRejectedAndFailed() {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        // 비식별 비디오 경로가 base 를 벗어남(CWE-22) — finalizer 복사 단계에서 거부.
        Seed s = seed("TRAV", "/etc/passwd");

        service.createDerivative(s.parent().getRawSn(), ResolutionPreset.RESL_720P, "rev1");

        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    LsDataRaw c = childOf(s.parent().getRawSn());
                    return c != null && LsDataRaw.DATA_STTS_FAILED.equals(c.getDataSttsCd());
                });
        LsDataRaw child = childOf(s.parent().getRawSn());
        assertThat(child.getDeIdntfYn()).isNotEqualTo("Y"); // 확정 안 됨(PII 파생본 미노출)
    }

    @Test
    @DisplayName("같은영상_같은프리셋_동시요청시_1건성공_1건CONFLICT")
    void concurrentSameParentPresetCreatesExactlyOne() throws Exception {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        Seed s = seed("CONC", BASE + "/videos/RESIT-CONC-deid.mp4");
        Long parentRawSn = s.parent().getRawSn();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    service.createDerivative(parentRawSn, ResolutionPreset.RESL_720P, "rev1");
                    success.incrementAndGet();
                } catch (CustomException e) {
                    conflict.incrementAndGet();
                } catch (Exception ignored) {
                    // 잠금 경합 등 — count 로만 판정
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);

        // 부분 유니크(SRC_SN, AUG_TYPE_CD='RESL_*') + 부모 잠금 → 정확히 1건만 파생 RAW 생성
        assertThat(childCount(parentRawSn))
                .as("동일 parent+preset 동시요청은 1건만 생성되어야 함")
                .isEqualTo(1L);
        assertThat(success.get()).isEqualTo(1);
        // 대표프레임 기준 RESL_720P 증강행도 정확히 1건.
        assertThat(augRepository.findBySrcSnOrderByAugTypeCd(s.frame0().getSrcSn()))
                .filteredOn(a -> "RESL_720P".equals(a.getAugTypeCd()))
                .hasSize(1);
    }

    @Test
    @DisplayName("finalize_transient실패시_예약aug행이_삭제되고_새RAW는_FAILED이며_동일프리셋_재시도가_성공한다")
    void transientFinalizeFailureReleasesReservedAugAndAllowsRetry() {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        Seed s = seed("RETRY", BASE + "/videos/RESIT-RETRY-deid.mp4");
        Long parentRawSn = s.parent().getRawSn();
        long f0Sn = s.frame0().getSrcSn();

        // given — 첫 finalize 의 비디오 복사가 transient 로 1회 실패, 이후 호출은 정상.
        //         copyDeidentifiedVideo 는 rescale/label 이전 단계라 예약 aug 만 커밋된 채 finalize 가 롤백된다.
        doThrow(new RuntimeException("transient copy failure"))
                .doNothing()
                .when(videoFileCopier).copy(any(), any());

        // when — 1차 요청: 예약(LS_DATA_AUG RESL_720P + 새 RAW) 커밋 후 async finalize 가 실패.
        ResolutionDerivativeResponse first = service.createDerivative(
                parentRawSn, ResolutionPreset.RESL_720P, "rev1");
        Long failedChildRawSn = first.newRawSn();

        // then #1 — 파생 RAW 는 FAILED 로 전이되고, 예약 aug 슬롯이 해제(삭제)되어 잔존하지 않는다.
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> LsDataRaw.DATA_STTS_FAILED.equals(
                        videoRepository.findById(failedChildRawSn).orElseThrow().getDataSttsCd()));
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> augRepository.findBySrcSnOrderByAugTypeCd(f0Sn).stream()
                        .noneMatch(a -> "RESL_720P".equals(a.getAugTypeCd())));
        assertThat(videoRepository.findById(failedChildRawSn).orElseThrow().getDeIdntfYn())
                .isNotEqualTo("Y"); // PII 확정 위장 없음

        // when #2 — transient 원인 해소 후 동일 (부모,프리셋) 재요청: 슬롯이 해제됐으므로 409 없이 정상 생성.
        ResolutionDerivativeResponse retry = service.createDerivative(
                parentRawSn, ResolutionPreset.RESL_720P, "rev1");
        Long retriedChildRawSn = retry.newRawSn();

        // then #2 — 재요청 파생이 정상 확정되고, RESL_720P 예약행은 다시 정확히 1건 존재한다.
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    LsDataRaw c = videoRepository.findById(retriedChildRawSn).orElseThrow();
                    return LsDataRaw.DATA_STTS_MARKING_READY.equals(c.getDataSttsCd())
                            && "Y".equals(c.getDeIdntfYn());
                });
        assertThat(augRepository.findBySrcSnOrderByAugTypeCd(f0Sn))
                .filteredOn(a -> "RESL_720P".equals(a.getAugTypeCd()))
                .hasSize(1);
    }

    @Test
    @DisplayName("비식별미완료_deIdntfYn_아님_부모면_예약단계에서_거부된다")
    void notDeidentifiedParentRejectedAtReservation() {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        // 부모가 APPROVED 이나 비식별 미완료('N') — 예약 게이트(PII TOCTOU)에서 CONFLICT.
        Seed s = seed("NODEID", BASE + "/videos/RESIT-NODEID-deid.mp4", "N");

        assertThatThrownBy(() -> service.createDerivative(
                s.parent().getRawSn(), ResolutionPreset.RESL_720P, "rev1"))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        assertThat(childCount(s.parent().getRawSn())).isZero();
        assertThat(augRepository.findBySrcSnOrderByAugTypeCd(s.frame0().getSrcSn())).isEmpty();
    }

    @Test
    @DisplayName("예약후_async확정전에_부모가_비식별신고로_F전이되면_finalizer가_PII를_복사하지않고_파생이_FAILED된다")
    void parentDeidReportedFDuringAsyncWindowBlocksPiiCopyAndFailsDerivative() {
        // given — 예약 시점엔 부모가 비식별 완료('Y'). 증강행(LS_DATA_AUG) + 파생 RAW(PENDING·deIdntfYn='N')를
        //          직접 영속해 "예약 커밋됨 · async 확정 미실행" 상태를 재현한다(auto-finalize 우회 → F-전이 창 확보).
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        Seed s = seed("PIIWIN", BASE + "/videos/RESIT-PIIWIN-deid.mp4");
        Long parentRawSn = s.parent().getRawSn();

        // 예약 시점 상태(PENDING)로 시딩 — 예약 커밋됨·async 확정 미실행 상태를 정확히 재현.
        LsDataAug aug = augRepository.save(LsDataAug.createResolutionPending(
                s.frame0().getSrcSn(), LsDataAug.AUG_RESL_720P, "rev1"));
        LsDataRaw child = videoRepository.save(LsDataRaw.createFromResolution(
                s.parent(), BASE + "/resolution/" + parentRawSn + "/RESL_720P/video/RESL_720P.mp4", "RESL_720P"));
        Long childRawSn = child.getRawSn();

        // when — 예약 커밋 ~ async 확정 사이 창에서 부모가 비식별 누락 신고로 'F'(PII 노출 확정) 전이.
        LsDataRaw parent = videoRepository.findById(parentRawSn).orElseThrow();
        parent.markDeidentified("F");
        videoRepository.save(parent);

        // async 확정 트리거(러너 → finalizer 재잠금·재검증 → 'F' 관측 → 롤백 → FAILED 전이).
        asyncResolutionRunner.runAsync(childRawSn, parentRawSn, aug.getDataAugSn(), ResolutionPreset.RESL_720P);

        // then — 파생 RAW 는 FAILED 로 전이되고 PII 는 절대 복제되지 않는다.
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    LsDataRaw c = videoRepository.findById(childRawSn).orElseThrow();
                    return LsDataRaw.DATA_STTS_FAILED.equals(c.getDataSttsCd());
                });
        LsDataRaw finalized = videoRepository.findById(childRawSn).orElseThrow();
        assertThat(finalized.getDeIdntfYn()).isNotEqualTo("Y");          // 비식별 완료 위장 안 됨
        assertThat(srcRepository.countByRawSn(childRawSn)).isZero();     // 프레임(PII 픽셀) 미복사
        assertThat(lblMapRepository.findAllByDataAugSn(aug.getDataAugSn())).isEmpty();
        verify(videoFileCopier, never()).copy(any(), any());             // 비식별 비디오 미복사
        verify(imageResizer, never()).resize(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    /** 인자로 받은 목적 경로(dst)에 실제 파일을 산출하는 답변 — Phase B 가 진짜 파일을 남기도록 한다. */
    private static org.mockito.stubbing.Answer<Void> writesRealFileAtDst(int dstArgIndex) {
        return inv -> {
            Path dst = inv.getArgument(dstArgIndex);
            if (dst.getParent() != null) {
                Files.createDirectories(dst.getParent());
            }
            Files.write(dst, new byte[]{1, 2, 3, 4});
            return null;
        };
    }

    @Test
    @DisplayName("진짜_A~C창_PhaseB가_실제파일산출_후_스냅샷이후_부모_재비식별신고시_PhaseC가_CONFLICT하고_cleanup이_실제파일을_삭제하며_파생은_N유지_FAILED된다(H-1)")
    void realWindowPhaseCAbortsOnStaleAndCleanupDeletesRealFiles() throws Exception {
        // given — 예약 시점 상태(PENDING·deIdntfYn='N')로 시딩해 A~C 창을 직접 제어한다.
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        Seed s = seed("STALEWIN", BASE + "/videos/RESIT-STALEWIN-deid.mp4");
        Long parentRawSn = s.parent().getRawSn();
        LsDataAug aug = augRepository.save(LsDataAug.createResolutionPending(
                s.frame0().getSrcSn(), LsDataAug.AUG_RESL_720P, "rev1"));
        LsDataRaw child = videoRepository.save(LsDataRaw.createFromResolution(
                s.parent(), BASE + "/resolution/" + parentRawSn + "/RESL_720P/video/RESL_720P.mp4", "RESL_720P"));
        Long childRawSn = child.getRawSn();

        // Phase B 가 진짜 파일을 남기도록 port mock 을 파일 산출 답변으로 스텁(복사·리사이즈 dst = 인덱스 1).
        when(videoFileCopier.exists(any())).thenReturn(true);
        doAnswer(writesRealFileAtDst(1)).when(videoFileCopier).copy(any(), any());
        doAnswer(writesRealFileAtDst(1)).when(imageResizer).resize(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());

        // A~C 창 주입 — Phase B(materialize) 실행 순간(=Phase A capturedAt 이후)에 부모를 재비식별 신고한다.
        // 부모는 여전히 deIdntfYn='Y' 이므로 기존 'Y' 게이트로는 못 막고, H-1 stale 게이트(신고>capturedAt)만 막는다.
        doAnswer(inv -> {
            inv.callRealMethod(); // 실제 파일 산출(복사·리사이즈)
            deidentReportRepository.save(LsDeidentReport.createReport(parentRawSn, 1L, "PII 재노출 신고"));
            return null;
        }).when(fileMaterializer).materialize(any(ResolutionSnapshot.class));

        Path framesDir = Paths.get(BASE, "resolution", String.valueOf(childRawSn), "frames");
        Path videoDst = Paths.get(BASE, "resolution", String.valueOf(parentRawSn), "RESL_720P", "video", "RESL_720P.mp4");

        // when — 러너 전체 실행(A→B[실파일+신고]→C[stale abort]→cleanup+FAILED).
        asyncResolutionRunner.runAsync(childRawSn, parentRawSn, aug.getDataAugSn(), ResolutionPreset.RESL_720P);

        // then — 파생 RAW 가 FAILED 로 전이된다.
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> LsDataRaw.DATA_STTS_FAILED.equals(
                        videoRepository.findById(childRawSn).orElseThrow().getDataSttsCd()));

        LsDataRaw finalized = videoRepository.findById(childRawSn).orElseThrow();
        assertThat(finalized.getDeIdntfYn()).isNotEqualTo("Y");        // 파생 미서빙 보장(확정 위장 없음)
        assertThat(srcRepository.countByRawSn(childRawSn)).isZero();   // Phase C abort — 프레임 미영속
        assertThat(lblMapRepository.findAllByDataAugSn(aug.getDataAugSn())).isEmpty();
        // 실제 산출된 Phase B 파일이 cleanup 으로 실측 삭제된다(Files.exists 실측).
        assertThat(Files.exists(videoDst)).as("파생 비디오 파일이 cleanup 으로 삭제되어야 함").isFalse();
        assertThat(Files.exists(framesDir)).as("리스케일 프레임 디렉토리가 cleanup 으로 삭제되어야 함").isFalse();
    }
}
