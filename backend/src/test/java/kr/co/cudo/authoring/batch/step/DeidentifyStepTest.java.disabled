package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeidentifyStepTest {

    @TempDir
    Path tmp;

    private DeidentifyClient deidentifyClient;
    private LsDataSrcRepository srcRepository;
    private LsDataSrcHstryRepository hstryRepository;
    private VideoRepository videoRepository;
    private DeidentReportService deidentReportService;
    private NotificationService notificationService;
    private DeidentifyStep step;
    private Path baseDeid;

    @BeforeEach
    void setUp() throws Exception {
        deidentifyClient = mock(DeidentifyClient.class);
        srcRepository = mock(LsDataSrcRepository.class);
        hstryRepository = mock(LsDataSrcHstryRepository.class);
        videoRepository = mock(VideoRepository.class);
        deidentReportService = mock(DeidentReportService.class);
        notificationService = mock(NotificationService.class);

        baseDeid = tmp.resolve("deid");
        step = new DeidentifyStep(deidentifyClient, srcRepository, hstryRepository, videoRepository,
                deidentReportService, notificationService);
        setField(step, "deidPath", baseDeid.toString());
        invoke(step, "initBasePath");
    }

    private LsDataRaw newRaw(String prvc) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                prvc, "/var/raw/clip.mp4", null, 60);
        setField(raw, "rawSn", 9001L);
        return raw;
    }

    private LsDataSrc newSrc(long srcSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(9001L, frameNo, "/var/raw/frames/9001/frame-" + frameNo + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    @Test
    @DisplayName("정상_경로_응답이면_attachDeidPath_호출")
    void normalPath_attaches() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        LsDataSrc src = newSrc(1L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(src));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("frames").resolve("9001").resolve("frame-0.jpg").toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));
        when(hstryRepository.save(any(LsDataSrcHstry.class))).thenAnswer(inv -> inv.getArgument(0));

        step.run(raw);

        assertThat(src.getDeidFilePath()).isEqualTo(safeReturn.toString());
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("응답_resultPath가_baseDeidentifiedPath_밖이면_INVALID_INPUT_그리고_DE_IDNTF_YN_F_마킹")
    void escapingResultPath_rejected() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        LsDataSrc src = newSrc(2L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(src));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        // 외부 응답이 base 밖 경로 (path traversal 시도)
        Path escaping = tmp.resolve("other").resolve("frame-0.jpg").toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", escaping.toString())));

        assertThatThrownBy(() -> step.run(raw))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("EXTERNAL_API_ERROR"); // catch 블록에서 EXTERNAL_API_ERROR 로 래핑

        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        verify(hstryRepository, never()).save(any());
        assertThat(src.getDeidFilePath()).isNull();
    }

    @Test
    @DisplayName("Phase2_ANONY_영상도_DeidentifyStep_호출_프레임별_비식별_시도")
    void anonyAlsoInvokesDeidentify() {
        // Phase 2: needsDeidentify 분기 제거 — ANONY 영상도 무조건 비식별 호출
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_ANONY);
        LsDataSrc src = newSrc(7L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(src));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("frames").resolve("9001").resolve("frame-0.jpg").toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));
        when(hstryRepository.save(any(LsDataSrcHstry.class))).thenAnswer(inv -> inv.getArgument(0));

        step.run(raw);

        // ANONY 도 외부 비식별 호출이 발생해야 함
        verify(deidentifyClient).deidentify(any(DeidentifyRequest.class));
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("Phase3_LOCKED_FOR_REDEIDENT_영상_성공시_releaseLock_+_resolveOpenReports_호출_+_알림")
    void redeidentLockReleasedOnSuccess() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        raw.attachLockStts(LsDataRaw.LOCK_REDEIDENT);
        LsDataSrc src = newSrc(33L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(src));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("frames").resolve("9001").resolve("frame-0.jpg").toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));
        when(hstryRepository.save(any(LsDataSrcHstry.class))).thenAnswer(inv -> inv.getArgument(0));

        step.run(raw);

        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(raw.isLockedForRedeident()).isFalse();
        verify(deidentReportService).resolveOpenReports(9001L);
        verify(notificationService).notifyReviewersOnLockRelease(raw);
    }

    @Test
    @DisplayName("Phase3_정상_영상_lockSttsCd_NULL_성공시_resolveOpenReports_호출_안함")
    void normalVideoSuccessSkipsResolve() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        LsDataSrc src = newSrc(34L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(src));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("frames").resolve("9001").resolve("frame-0.jpg").toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));
        when(hstryRepository.save(any(LsDataSrcHstry.class))).thenAnswer(inv -> inv.getArgument(0));

        step.run(raw);

        verify(deidentReportService, never()).resolveOpenReports(any());
        verify(notificationService, never()).notifyReviewersOnLockRelease(any());
    }

    @Test
    @DisplayName("Phase2_PRVC_영상_정상_호출_성공_시_markDeidentified_Y_회귀")
    void prvcSuccessMarksY() {
        LsDataRaw raw = newRaw(LsDataRaw.PRVC_TYPE_PRVC);
        LsDataSrc src = newSrc(11L, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(9001L)).thenReturn(List.of(src));
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        Path safeReturn = baseDeid.resolve("frames").resolve("9001").resolve("frame-0.jpg").toAbsolutePath().normalize();
        when(deidentifyClient.deidentify(any(DeidentifyRequest.class)))
                .thenReturn(Mono.just(new DeidentifyResponse("OK", safeReturn.toString())));
        when(hstryRepository.save(any(LsDataSrcHstry.class))).thenAnswer(inv -> inv.getArgument(0));

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
