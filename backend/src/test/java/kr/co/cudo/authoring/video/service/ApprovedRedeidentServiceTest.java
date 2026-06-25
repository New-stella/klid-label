package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.dto.RedeidentResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 / UC018 — ApprovedRedeidentService 오케스트레이션 단위 테스트.
 *
 * <p>검수완료 영상 재비식별 요청의 전제조건 가드(APPROVED/기비식별/락) + KPST 위탁(REDEIDENT 표시) 검증.
 */
class ApprovedRedeidentServiceTest {

    private static final Long RAW_SN = 9001L;

    private VideoRepository videoRepository;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private WorkLockService workLockService;
    private KpstDeidentService kpstDeidentService;
    private ApprovedRedeidentService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        workLockService = mock(WorkLockService.class);
        kpstDeidentService = mock(KpstDeidentService.class);
        service = new ApprovedRedeidentService(
                videoRepository, rawDataStatusRepository, workLockService, kpstDeidentService);
    }

    private TokenClaims reviewer() {
        return new TokenClaims("100", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LsDataRaw rawWithDeident(String deIdentYn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_UNKNOWN, "/raw/clip.mp4", null, 60);
        setField(raw, "rawSn", RAW_SN);
        raw.markDeidentified(deIdentYn);
        return raw;
    }

    private void stubApproved() {
        LsRawDataStatus status = LsRawDataStatus.initial(RAW_SN);
        status.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of(status));
    }

    private void stubStatus(String stts) {
        LsRawDataStatus status = LsRawDataStatus.initial(RAW_SN);
        status.transitionTo(stts);
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of(status));
    }

    private LsDeidentProcLog submittedRedeident() {
        LsDeidentProcLog p = LsDeidentProcLog.request(RAW_SN, null, "/raw/clip.mp4", "batch");
        setField(p, "procLogSn", 1L);
        p.markRedeident();
        p.markKpstSubmitted(101L, null);
        return p;
    }

    @Test
    @DisplayName("APPROVED아닌_영상_요청시_CONFLICT")
    void notApprovedConflict() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(rawWithDeident("N")));
        stubStatus(LsRawDataStatus.STTS_IN_REVIEW);

        CustomException ex = catchThrowableOfType(
                () -> service.requestRedeident(RAW_SN, reviewer()), CustomException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(kpstDeidentService, never()).submit(any(), eq(true));
    }

    @Test
    @DisplayName("검수상태_row없으면_미검수로_간주_CONFLICT")
    void noStatusRowConflict() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(rawWithDeident("N")));
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of());

        assertThatThrownBy(() -> service.requestRedeident(RAW_SN, reviewer()))
                .isInstanceOf(CustomException.class);
        verify(kpstDeidentService, never()).submit(any(), eq(true));
    }

    @Test
    @DisplayName("영상_없으면_NOT_FOUND")
    void notFound() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        CustomException ex = catchThrowableOfType(
                () -> service.requestRedeident(RAW_SN, reviewer()), CustomException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("이미_비식별된_deIdentY_영상_요청시_CONFLICT_네이티브배제")
    void alreadyDeidentConflict() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(rawWithDeident("Y")));
        stubApproved();

        CustomException ex = catchThrowableOfType(
                () -> service.requestRedeident(RAW_SN, reviewer()), CustomException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(kpstDeidentService, never()).submit(any(), eq(true));
        verify(workLockService, never()).lockRawForRedeident(any(), any());
    }

    @Test
    @DisplayName("이미_잠긴_영상_요청시_CONFLICT_멱등")
    void alreadyLockedConflict() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(rawWithDeident("N")));
        stubApproved();
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(true);

        CustomException ex = catchThrowableOfType(
                () -> service.requestRedeident(RAW_SN, reviewer()), CustomException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(workLockService, never()).lockRawForRedeident(any(), any());
        verify(kpstDeidentService, never()).submit(any(), eq(true));
    }

    @Test
    @DisplayName("요청시_KPST_submit되고_락선점되며_REDEIDENT로_표시된다")
    void requestSubmitsRedeident() {
        LsDataRaw raw = rawWithDeident("N");
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        stubApproved();
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(kpstDeidentService.submit(eq(raw), eq(true))).thenReturn(submittedRedeident());

        RedeidentResponse res = service.requestRedeident(RAW_SN, reviewer());

        verify(workLockService).lockRawForRedeident(eq(RAW_SN), eq("100"));
        verify(kpstDeidentService).submit(eq(raw), eq(true));
        assertThat(res.status()).isEqualTo(RedeidentResponse.STATUS_ACCEPTED);
        assertThat(res.rawSn()).isEqualTo(RAW_SN);
        assertThat(res.procLogSn()).isEqualTo(1L);
        assertThat(res.kpstPrjId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("동시_재비식별_DB유니크충돌시_CONFLICT_이중위탁차단")
    void concurrentLockUniqueViolationConflict() {
        // given: 선제 isRawLocked 검사는 통과(false)했으나, 동시 두 요청 중 하나가 먼저 락 INSERT
        //        에 성공해 V69 partial unique index 가 두 번째 INSERT 를 원자적으로 거부하는 상황.
        LsDataRaw raw = rawWithDeident("N");
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        stubApproved();
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        org.mockito.Mockito.doThrow(
                        new org.springframework.dao.DataIntegrityViolationException("UX_LS_AUTH_WORK_LOCK_RAW_ACTIVE"))
                .when(workLockService).lockRawForRedeident(eq(RAW_SN), eq("100"));

        // when
        CustomException ex = catchThrowableOfType(
                () -> service.requestRedeident(RAW_SN, reviewer()), CustomException.class);

        // then: DB 유니크 충돌이 409 CONFLICT 로 변환되고, KPST 이중 위탁(submit)은 시작되지 않는다.
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(kpstDeidentService, never()).submit(any(), eq(true));
    }

    @Test
    @DisplayName("M3_actor_null이면_UNAUTHORIZED_락선점_위탁_미수행_fail_closed")
    void actorNullUnauthorized() {
        LsDataRaw raw = rawWithDeident("N");
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        stubApproved();
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);

        CustomException ex = catchThrowableOfType(
                () -> service.requestRedeident(RAW_SN, null), CustomException.class);

        // 폴백("reviewer")으로 통과시키지 않고 인증 거부 — 감사 추적 보장.
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(workLockService, never()).lockRawForRedeident(any(), any());
        verify(kpstDeidentService, never()).submit(any(), eq(true));
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
}
