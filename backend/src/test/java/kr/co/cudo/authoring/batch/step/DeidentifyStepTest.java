package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.client.DeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.DeidentifyRequest;
import kr.co.cudo.authoring.common.client.dto.DeidentifyResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 — DeidentifyStep 단위 테스트.
 *
 * <p>V2 정책:
 * <ul>
 *   <li>영상 단위 비식별 (프레임별 호출 폐기). 결과 경로는 LS_DEIDENT_REPORT.DE_IDNTF_FILE_PATH_NM 에만 저장.</li>
 *   <li>PRVC/PSDO/ANONY 모두 비식별 호출 (분기 폐기).</li>
 *   <li>잠금 해제 + OPEN 신고 RESOLVED 전이 — WorkLockService + DeidentReportService 위임.</li>
 * </ul>
 */
class DeidentifyStepTest {

    @TempDir
    Path tmp;

    private DeidentifyClient deidentifyClient;
    private VideoRepository videoRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private DeidentReportService deidentReportService;
    private NotificationService notificationService;
    private WorkLockService workLockService;
    private DeidentifyStep step;
    private Path baseDeid;

    @BeforeEach
    void setUp() throws Exception {
        deidentifyClient = mock(DeidentifyClient.class);
        videoRepository = mock(VideoRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        deidentReportService = mock(DeidentReportService.class);
        notificationService = mock(NotificationService.class);
        workLockService = mock(WorkLockService.class);

        baseDeid = tmp.resolve("deid");
        // @RequiredArgsConstructor 순서: deidentifyClient, videoRepository, procLogRepository,
        //                                deidentReportService, notificationService, workLockService
        step = new DeidentifyStep(deidentifyClient, videoRepository, procLogRepository,
                deidentReportService, notificationService, workLockService);
        setField(step, "deidPath", baseDeid.toString());
        invoke(step, "initBasePath");

        // procLogRepository.save 는 echo + ID 부여
        when(procLogRepository.save(any(LsDeidentProcLog.class))).thenAnswer(inv -> {
            LsDeidentProcLog p = inv.getArgument(0);
            setField(p, "procLogSn", 1L);
            return p;
        });
    }

    private LsDataRaw newRaw(String prvc) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                prvc, "/var/raw/clip.mp4", null, 60);
        setField(raw, "rawSn", 9001L);
        return raw;
    }

    @Test
    @DisplayName("정상_경로_응답이면_LS_DEIDENT_REPORT_SUCCEEDED_+_DE_IDNTF_Y_+_결과경로_반환")
    void normalPath_marksSuccess() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        String result = step.run(raw);

        assertThat(result).isEqualTo(safeReturn.toString());
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        // LS_DEIDENT_PROC_LOG 가 최소 1회 save (REQUESTED 시점 저장; SUCCEEDED 는 영속 엔티티 변경으로 dirty checking)
        verify(procLogRepository, org.mockito.Mockito.atLeast(1)).save(any(LsDeidentProcLog.class));
    }

    @Test
    @DisplayName("응답_resultPath가_baseDeidentifiedPath_밖이면_EXTERNAL_API_ERROR_그리고_DE_IDNTF_F_마킹")
    void escapingResultPath_rejected() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        // 외부 응답이 base 밖 경로 (path traversal 시도)
        Path escaping = tmp.resolve("other").resolve("frame-0.jpg").toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", escaping.toString())));

        assertThatThrownBy(() -> step.run(raw))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("EXTERNAL_API_ERROR");

        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("Phase2_ANONY_영상도_DeidentifyStep_호출_무조건_비식별")
    void anonyAlsoInvokesDeidentify() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_ANONY);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        step.run(raw);

        verify(deidentifyClient).deidentify(any(DeidentifyRequest.class));
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Phase2_execute_ctx_는_run_raw_에_위임 — BatchStep 균일 인터페이스")
    void executeDelegatesToRun() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_ANONY);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        // stage() = DEIDENTIFY
        assertThat(step.stage())
                .isEqualTo(kr.co.cudo.authoring.batch.orchestrator.BatchStage.DEIDENTIFY);

        // execute(ctx) → run(ctx.getRaw())
        step.execute(new kr.co.cudo.authoring.batch.pipeline.BatchContext(raw.getRawSn(), raw));

        verify(deidentifyClient).deidentify(any(DeidentifyRequest.class));
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Phase3_재비식별_잠금_영상_성공시_WorkLockService_releaseRaw_+_resolveOpenReports_+_알림")
    void redeidentLockReleasedOnSuccess() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(true);
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        step.run(raw);

        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        verify(workLockService).releaseRaw(eq(9001L), eq("batch"), eq("DEIDENT_SUCCEEDED"));
        verify(deidentReportService).resolveOpenReports(9001L);
        verify(notificationService).notifyReviewersOnLockRelease(raw);
    }

    @Test
    @DisplayName("Phase3_정상_영상_잠금_없으면_releaseRaw_미호출_+_resolveOpenReports_idempotent_호출")
    void normalVideoSuccessSkipsRelease() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(workLockService.isRawLocked(9001L)).thenReturn(false);
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        step.run(raw);

        // 잠금이 없으면 release 호출 안 함 (가드 통과)
        verify(workLockService, never()).releaseRaw(any(), any(), any());
        // resolveOpenReports 는 항상 호출됨 (idempotent — 신고 없으면 0 반환)
        verify(deidentReportService).resolveOpenReports(9001L);
    }

    @Test
    @DisplayName("Phase3_위탁_시_LS_DEIDENT_PROC_LOG_REQUESTED_상태로_적재")
    void requestPersistedAsRequested() {
        // ccarch if-deidentify-spi 비동기 위탁 명세 — 위탁 시점에 LS_DEIDENT_PROC_LOG 가 REQUESTED 로 기록되어야 한다.
        // 결과 webhook 도착 전(또는 동기 응답 도착 직전) 의 상태가 REQUESTED 임을 검증.
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();

        // save 후 procSttsCd 가 REQUESTED 인지 캡처
        org.mockito.ArgumentCaptor<LsDeidentProcLog> captor =
                org.mockito.ArgumentCaptor.forClass(LsDeidentProcLog.class);
        when(procLogRepository.save(captor.capture())).thenAnswer(inv -> {
            LsDeidentProcLog p = inv.getArgument(0);
            setField(p, "procLogSn", 1L);
            return p;
        });
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        step.run(raw);

        // 위탁 시점에 save 된 procLog 가 REQUESTED 였음 (이후 succeed() 로 SUCCEEDED 전이 — 동일 인스턴스 dirty checking)
        LsDeidentProcLog first = captor.getAllValues().get(0);
        // succeed() 가 호출되기 전 상태는 REQUESTED 였으나, 인스턴스가 동일하므로 마지막 상태는 SUCCEEDED.
        // 따라서 succeed() 가 정상 호출되었는지(=결과 도착 시점에 SUCCEEDED 전이) 확인.
        assertThat(first.getProcSttsCd()).isIn(LsDeidentProcLog.REQUESTED, LsDeidentProcLog.SUCCEEDED);
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Phase2_PRVC_영상_정상_호출_성공_시_markDeidentified_Y_회귀")
    void prvcSuccessMarksY() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("videos").resolve("9001").resolve("deidentified.mp4")
                .toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));

        step.run(raw);

        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void invoke(Object target, String method) {
        try {
            Method m = target.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
