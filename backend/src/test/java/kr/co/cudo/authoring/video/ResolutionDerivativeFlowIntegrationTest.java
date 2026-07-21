package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.dto.ResolutionDerivativeResponse;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.LsResolutionLblMap;
import kr.co.cudo.authoring.video.repository.LsResolutionExportRepository;
import kr.co.cudo.authoring.video.repository.LsResolutionLblMapRepository;
import kr.co.cudo.authoring.video.entity.LsResolutionExport;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.runner.AsyncResolutionRunner;
import kr.co.cudo.authoring.video.service.ResolutionDerivativeFinalizer;
import kr.co.cudo.authoring.video.service.ResolutionDerivativeService;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import kr.co.cudo.authoring.video.service.port.VideoFileCopier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해상도 파생영상 전체 플로우 통합 검증(Testcontainers PostgreSQL) — Phase 2.
 *
 * <p>동기 예약({@code ResolutionDerivativeService.createDerivative}) → AFTER_COMMIT 비동기 확정
 * ({@code ResolutionDerivativeFinalizer})까지 실제 DB 로 검증한다. 이미지/비디오 파일 I/O 는
 * {@link ImageResizer}/{@link VideoFileCopier} 를 @MockBean 으로 격리한다(바이너리·실파일 비의존).
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

    @Autowired private ResolutionDerivativeService service;
    @Autowired private ResolutionDerivativeFinalizer finalizer;
    @Autowired private AsyncResolutionRunner asyncResolutionRunner;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsRawDataStatusRepository statusRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDeidentProcLogRepository procLogRepository;
    @Autowired private LsResolutionExportRepository exportRepository;
    @Autowired private LsResolutionLblMapRepository lblMapRepository;

    @BeforeEach
    void setup() {
        when(videoFileCopier.exists(any())).thenReturn(true);
        // resize/copy 는 no-op (파일 미생성). readDimensions 는 각 테스트에서 스텁.
    }

    private record Seed(LsDataRaw parent, LsDataSrc frame0, LsDataSrc frame1, LsDataLbl label) {
    }

    /** 원본(APPROVED·비식별) + 프레임 2 + 라벨 1 + 비식별 성공 procLog 시드. deidVideoPath 를 지정 가능. */
    private Seed seed(String suffix, String deidVideoPath) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "RESIT-" + suffix, "CCTV-RESIT", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, BASE + "/videos/RESIT-" + suffix + "-orig.mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
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
    @DisplayName("비디오파일이_원본_복사본으로_파생경로에_존재하고_프레임이_목표해상도로_리스케일_저장된다")
    void downscaleCopiesVideoAndRescalesFramesAndLabels() {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        Seed s = seed("DOWN", BASE + "/videos/RESIT-DOWN-deid.mp4");

        ResolutionDerivativeResponse res = service.createDerivative(
                s.parent().getRawSn(), ResolutionPreset.RES_720P, "rev1");

        LsDataRaw child = awaitFinalized(s.parent().getRawSn());
        assertThat(child.getOrgnlRawSn()).isEqualTo(s.parent().getRawSn());
        assertThat(child.getVmsClipId()).contains("_RES_RES_720P_");

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

        // LS_RESOLUTION_LBL_MAP — coordRecalc='Y' + scaleX/scaleY 기록
        List<LsResolutionLblMap> maps = lblMapRepository.findByResExportSn(res.exportSn());
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

        service.createDerivative(s.parent().getRawSn(), ResolutionPreset.RES_1080P, "rev1");

        LsDataRaw child = awaitFinalized(s.parent().getRawSn());
        assertThat(child).isNotNull();
        // 확대 — 목표 1920x1080 (원본 640x360 보다 큼)
        verify(imageResizer, atLeastOnce()).resize(any(), any(),
                org.mockito.ArgumentMatchers.eq(1920), org.mockito.ArgumentMatchers.eq(1080));
        assertThat(srcRepository.findByRawSnOrderByFrameNoAsc(child.getRawSn())).hasSize(2);
    }

    @Test
    @DisplayName("비동기러너_중복호출시_프레임이_중복생성되지_않는다")
    void finalizeIsIdempotent() {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        Seed s = seed("IDEM", BASE + "/videos/RESIT-IDEM-deid.mp4");

        ResolutionDerivativeResponse res = service.createDerivative(
                s.parent().getRawSn(), ResolutionPreset.RES_720P, "rev1");
        LsDataRaw child = awaitFinalized(s.parent().getRawSn());
        assertThat(srcRepository.countByRawSn(child.getRawSn())).isEqualTo(2L);

        // 중복 트리거 시뮬 — 이미 확정(deIdntfYn='Y')이면 skip, 프레임 재생성 없음
        finalizer.finalizeDerivative(child.getRawSn(), s.parent().getRawSn(), res.exportSn(), ResolutionPreset.RES_720P);

        assertThat(srcRepository.countByRawSn(child.getRawSn())).isEqualTo(2L);
    }

    @Test
    @DisplayName("경로에_상위탈출_시도시_거부되고_RAW가_FAILED로_전이된다")
    void pathTraversalRejectedAndFailed() {
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        // 비식별 비디오 경로가 base 를 벗어남(CWE-22) — finalizer 복사 단계에서 거부.
        Seed s = seed("TRAV", "/etc/passwd");

        service.createDerivative(s.parent().getRawSn(), ResolutionPreset.RES_720P, "rev1");

        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    LsDataRaw c = childOf(s.parent().getRawSn());
                    return c != null && LsDataRaw.DATA_STTS_FAILED.equals(c.getDataSttsCd());
                });
        LsDataRaw child = childOf(s.parent().getRawSn());
        assertThat(child.getDeIdntfYn()).isNotEqualTo("Y"); // 확정 안 됨(PII 파생본 미노출)
    }

    @Test
    @DisplayName("동일_parent_preset_동시요청시_정확히_1건만_생성된다")
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
                    service.createDerivative(parentRawSn, ResolutionPreset.RES_720P, "rev1");
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

        // UK(DATA_RAW_SN, GOAL_RES_CD) + 부모 잠금 → 정확히 1건만 파생 RAW 생성
        assertThat(childCount(parentRawSn))
                .as("동일 parent+preset 동시요청은 1건만 생성되어야 함")
                .isEqualTo(1L);
        assertThat(success.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("예약후_async확정전에_부모가_비식별신고로_F전이되면_finalizer가_PII를_복사하지않고_파생이_FAILED된다")
    void parentDeidReportedFDuringAsyncWindowBlocksPiiCopyAndFailsDerivative() {
        // given — 예약 시점엔 부모가 비식별 완료('Y'). 예약행(EXPORT) + 파생 RAW(PENDING·deIdntfYn='N')를
        //          직접 영속해 "예약 커밋됨 · async 확정 미실행" 상태를 재현한다(auto-finalize 우회 → F-전이 창 확보).
        when(imageResizer.readDimensions(any())).thenReturn(new int[]{1920, 1080});
        Seed s = seed("PIIWIN", BASE + "/videos/RESIT-PIIWIN-deid.mp4");
        Long parentRawSn = s.parent().getRawSn();

        LsResolutionExport export = exportRepository.save(LsResolutionExport.createReservation(
                parentRawSn, ResolutionPreset.RES_720P.name(), 1920, 1080, 1280, 720,
                BASE + "/resolution/" + parentRawSn + "/RES_720P", "rev1"));
        LsDataRaw child = videoRepository.save(LsDataRaw.createFromResolution(
                s.parent(), BASE + "/resolution/" + parentRawSn + "/RES_720P/video/RES_720P.mp4", "RES_720P"));
        export.attachNewRawSn(child.getRawSn());
        exportRepository.save(export);
        Long childRawSn = child.getRawSn();

        // when — 예약 커밋 ~ async 확정 사이 창에서 부모가 비식별 누락 신고로 'F'(PII 노출 확정) 전이.
        LsDataRaw parent = videoRepository.findById(parentRawSn).orElseThrow();
        parent.markDeidentified("F");
        videoRepository.save(parent);

        // async 확정 트리거(러너 → finalizer 재잠금·재검증 → 'F' 관측 → 롤백 → FAILED 전이).
        asyncResolutionRunner.runAsync(childRawSn, parentRawSn, export.getResExportSn(), ResolutionPreset.RES_720P);

        // then — 파생 RAW 는 FAILED 로 전이되고 PII 는 절대 복제되지 않는다.
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    LsDataRaw c = videoRepository.findById(childRawSn).orElseThrow();
                    return LsDataRaw.DATA_STTS_FAILED.equals(c.getDataSttsCd());
                });
        LsDataRaw finalized = videoRepository.findById(childRawSn).orElseThrow();
        assertThat(finalized.getDeIdntfYn()).isNotEqualTo("Y");          // 비식별 완료 위장 안 됨
        assertThat(srcRepository.countByRawSn(childRawSn)).isZero();     // 프레임(PII 픽셀) 미복사
        assertThat(lblMapRepository.findByResExportSn(export.getResExportSn())).isEmpty();
        verify(videoFileCopier, never()).copy(any(), any());             // 비식별 비디오 미복사
        verify(imageResizer, never()).resize(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }
}
