package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep.TouchedFrames;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.dto.TrackMergeResponse;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.TrackMergeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrackMergeService 단위 테스트 (Mockito) — Phase 4 트랙 병합.
 *
 * <p>HIGH 시나리오 방어 검증: IDOR(401/403) · from==to(400) · 부재(404) · 겹침 frame(409) ·
 * 보간 산출물 제외 · 수동트랙 interpolationApplied=false · 재보간 실패 롤백 · 통지 실패 격리 · 배타 락.
 */
class TrackMergeServiceTest {

    private static final Long RAW_SN = 9001L;
    private static final String ACTOR_SUB = "1001";
    private static final String T_FROM = "t-from";
    private static final String T_TO = "t-to";

    private LsDataLblRepository labelRepository;
    private LabelAccessGuard accessGuard;
    private WorkLockService workLockService;
    private TrackInterpolationStep trackInterpolationStep;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private ApplicationEventPublisher eventPublisher;
    /** C-ISSUE-21 버전 bump(= 프레임 행 락 선점) 검증용 — 락 순서·bump 범위 회귀 방지. */
    private kr.co.cudo.authoring.batch.repository.LsDataSrcRepository srcRepository;
    private TrackMergeService service;

    @BeforeEach
    void setUp() {
        labelRepository = mock(LsDataLblRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        workLockService = mock(WorkLockService.class);
        trackInterpolationStep = mock(TrackInterpolationStep.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        srcRepository = mock(kr.co.cudo.authoring.batch.repository.LsDataSrcRepository.class);
        service = new TrackMergeService(labelRepository, accessGuard, workLockService,
                trackInterpolationStep, rawDataStatusRepository, eventPublisher, srcRepository);
        when(accessGuard.parseUserNo(ACTOR_SUB)).thenReturn(1001L);
        when(accessGuard.parseUserNo(any())).thenReturn(1001L);
    }

    private TokenClaims worker() {
        return new TokenClaims(ACTOR_SUB, Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LsDataLbl lbl(long lblSn, long srcSn, String trackId) {
        LsDataLbl l = LsDataLbl.createAutoBbox(srcSn, null, "person", "[[1,1],[2,2]]",
                BigDecimal.ZERO, trackId);
        ReflectionTestUtils.setField(l, "lblSn", lblSn);
        return l;
    }

    private void approved() {
        LsRawDataStatus status = LsRawDataStatus.initial(RAW_SN);
        status.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of(status));
    }

    @Test
    @DisplayName("정상_머지_성공_trackId_재지정_재보간_적용")
    void 정상_머지_성공() {
        // given — from 원 키프레임 1건, to 원 키프레임 1건, 겹침 없음, 자동 트랙(재보간 대상)
        LsDataLbl from = lbl(1L, 10L, T_FROM);
        LsDataLbl to = lbl(2L, 20L, T_TO);
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_FROM)).thenReturn(List.of(from));
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(to));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());
        when(labelRepository.findAutoBboxByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(1L, 10L, T_TO)));
        when(trackInterpolationStep.interpolateSingleTrackTouched(RAW_SN, T_TO, T_FROM))
                .thenReturn(new TouchedFrames(3, Set.of(20L)));

        // when
        TrackMergeResponse res = service.merge(RAW_SN, T_FROM, T_TO, worker());

        // then — 원 키프레임 trackId 재지정 + 재보간 결과 반영
        assertThat(from.getTrackId()).isEqualTo(T_TO);
        assertThat(res.reassignedLabelCount()).isEqualTo(1);
        assertThat(res.interpolationApplied()).isTrue();
        assertThat(res.interpolatedRowCount()).isEqualTo(3);
        // 배타 락 선점 → 재지정 저장 → 재보간 → 락 해제 순서 (bulkUpsert/동시머지 차단)
        InOrder order = inOrder(workLockService, labelRepository, trackInterpolationStep);
        order.verify(workLockService).lockRawExclusiveInNewTx(eq(RAW_SN), anyString());
        order.verify(labelRepository).saveAll(anyList());
        order.verify(trackInterpolationStep).interpolateSingleTrackTouched(RAW_SN, T_TO, T_FROM);
        order.verify(workLockService).releaseRawInNewTx(eq(RAW_SN), anyString(), anyString());
    }

    @Test
    @DisplayName("머지는_라벨행_수정_전에_프레임_버전bump로_프레임락을_먼저_잡고_재보간_프레임만_추가로_올린다")
    void 머지_락순서_및_bump범위() {
        // DEV_FIX(H2① 락 순서 + H11 범위) — ①라벨 UPDATE(트랙 재지정) 전에 프레임 락을 선점하고
        //   ②구현이 영상 전 프레임을 무효화하던 것을 "재지정 프레임 + 재보간 터치 프레임"으로 좁힌다.
        LsDataLbl from = lbl(1L, 10L, T_FROM);
        LsDataLbl to = lbl(2L, 20L, T_TO);
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_FROM)).thenReturn(List.of(from));
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(to));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());
        when(labelRepository.findAutoBboxByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(1L, 10L, T_TO)));
        when(trackInterpolationStep.interpolateSingleTrackTouched(RAW_SN, T_TO, T_FROM))
                .thenReturn(new TouchedFrames(1, Set.of(20L)));

        service.merge(RAW_SN, T_FROM, T_TO, worker());

        InOrder order = inOrder(srcRepository, labelRepository, trackInterpolationStep);
        order.verify(srcRepository).bumpLabelVersionIn(Set.of(10L));   // 재지정 프레임 — 라벨 UPDATE 前
        order.verify(labelRepository).saveAll(anyList());
        order.verify(trackInterpolationStep).interpolateSingleTrackTouched(RAW_SN, T_TO, T_FROM);
        order.verify(srcRepository).bumpLabelVersionIn(Set.of(20L));   // 재보간 터치 프레임만 추가
        // 영상 전 프레임 무효화(과잉)는 더 이상 하지 않는다 — 손대지 않은 프레임의 편집자를 409 로 밀어내지 않음.
        verify(srcRepository, never()).bumpLabelVersionByRawSn(anyLong());
    }

    @Test
    @DisplayName("from_to_동일_400_락_미획득")
    void from_to_동일_400() {
        CustomException ex = catchThrowableOfType(
                () -> service.merge(RAW_SN, T_FROM, T_FROM, worker()), CustomException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        verify(workLockService, never()).lockRawExclusiveInNewTx(anyLong(), anyString());
    }

    @Test
    @DisplayName("존재하지_않는_fromTrack_404_락_해제")
    void 존재하지_않는_fromTrack_404() {
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_FROM)).thenReturn(List.of());
        CustomException ex = catchThrowableOfType(
                () -> service.merge(RAW_SN, T_FROM, T_TO, worker()), CustomException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(workLockService).releaseRawInNewTx(eq(RAW_SN), anyString(), anyString());
        verify(labelRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("겹침_frame_존재_409_겹침목록_노출_변경_없음")
    void 겹침_frame_존재_409() {
        // given — from·to 원 키프레임이 같은 frame(srcSn=10) 에 공존
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_FROM)).thenReturn(List.of(lbl(1L, 10L, T_FROM)));
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(2L, 10L, T_TO)));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());

        CustomException ex = catchThrowableOfType(
                () -> service.merge(RAW_SN, T_FROM, T_TO, worker()), CustomException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ex.getMessage()).contains("10");
        // 겹침이면 어떤 변경/재보간도 하지 않는다
        verify(labelRepository, never()).saveAll(anyList());
        verify(trackInterpolationStep, never()).interpolateSingleTrackTouched(anyLong(), anyString(), anyString());
        verify(workLockService).releaseRawInNewTx(eq(RAW_SN), anyString(), anyString());
    }

    @Test
    @DisplayName("보간산출물_제외_원키프레임만_이관")
    void 보간산출물_제외_원키프레임만_이관() {
        // given — from 트랙에 원 키프레임(lblSn=1) + 보간 산출물(lblSn=2)
        LsDataLbl origin = lbl(1L, 10L, T_FROM);
        LsDataLbl interpolated = lbl(2L, 11L, T_FROM);
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_FROM)).thenReturn(List.of(origin, interpolated));
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(3L, 20L, T_TO)));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of(2L));
        when(labelRepository.findAutoBboxByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(1L, 10L, T_TO)));
        when(trackInterpolationStep.interpolateSingleTrackTouched(RAW_SN, T_TO, T_FROM))
                .thenReturn(new TouchedFrames(2, Set.of(20L)));

        TrackMergeResponse res = service.merge(RAW_SN, T_FROM, T_TO, worker());

        // then — 원 키프레임만 재지정, 보간 산출물은 trackId 불변(재보간 정리단계가 삭제)
        assertThat(origin.getTrackId()).isEqualTo(T_TO);
        assertThat(interpolated.getTrackId()).isEqualTo(T_FROM);
        assertThat(res.reassignedLabelCount()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(labelRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(origin);
    }

    @Test
    @DisplayName("수동트랙_머지_interpolationApplied_false")
    void 수동트랙_머지_interpolationApplied_false() {
        // given — 자동 BBOX/POLYGON 트랙 아님 → findAutoBboxWithTrackId 0건
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_FROM)).thenReturn(List.of(lbl(1L, 10L, T_FROM)));
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(2L, 20L, T_TO)));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());
        when(labelRepository.findAutoBboxByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of());
        when(trackInterpolationStep.interpolateSingleTrackTouched(RAW_SN, T_TO, T_FROM))
                .thenReturn(new TouchedFrames(0, Set.of(20L)));

        TrackMergeResponse res = service.merge(RAW_SN, T_FROM, T_TO, worker());

        assertThat(res.interpolationApplied()).isFalse();
        assertThat(res.interpolatedRowCount()).isEqualTo(0);
        assertThat(res.reassignedLabelCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("재보간_실패_예외전파_머지_롤백")
    void 재보간_실패_머지_롤백() {
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_FROM)).thenReturn(List.of(lbl(1L, 10L, T_FROM)));
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(2L, 20L, T_TO)));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());
        when(labelRepository.findAutoBboxByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(1L, 10L, T_TO)));
        when(trackInterpolationStep.interpolateSingleTrackTouched(RAW_SN, T_TO, T_FROM))
                .thenThrow(new RuntimeException("boom"));

        // 재보간 예외는 흡수되지 않고 전파되어야 트랜잭션이 롤백된다 (원자성)
        assertThatThrownBy(() -> service.merge(RAW_SN, T_FROM, T_TO, worker()))
                .isInstanceOf(RuntimeException.class);
        verify(workLockService).releaseRawInNewTx(eq(RAW_SN), anyString(), anyString());
    }

    @Test
    @DisplayName("통지실패_머지_유지_APPROVED_이벤트예외_격리")
    void 통지실패_머지_유지() {
        approved();
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_FROM)).thenReturn(List.of(lbl(1L, 10L, T_FROM)));
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(2L, 20L, T_TO)));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());
        when(labelRepository.findAutoBboxByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(1L, 10L, T_TO)));
        when(trackInterpolationStep.interpolateSingleTrackTouched(RAW_SN, T_TO, T_FROM))
                .thenReturn(new TouchedFrames(1, Set.of(20L)));
        doThrow(new RuntimeException("notify down")).when(eventPublisher).publishEvent(any(TaskModifiedEvent.class));

        // 통지 실패는 병합 롤백 사유가 아니다 — 정상 응답 + 재지정 저장 유지
        TrackMergeResponse res = service.merge(RAW_SN, T_FROM, T_TO, worker());
        assertThat(res.reassignedLabelCount()).isEqualTo(1);
        verify(labelRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("APPROVED_머지_TaskModifiedEvent_발행")
    void APPROVED_머지_통지발행() {
        approved();
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_FROM)).thenReturn(List.of(lbl(1L, 10L, T_FROM)));
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(2L, 20L, T_TO)));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());
        when(labelRepository.findAutoBboxByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(1L, 10L, T_TO)));
        when(trackInterpolationStep.interpolateSingleTrackTouched(RAW_SN, T_TO, T_FROM))
                .thenReturn(new TouchedFrames(1, Set.of(20L)));

        service.merge(RAW_SN, T_FROM, T_TO, worker());
        verify(eventPublisher).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("미검수_머지_통지_미발행")
    void 미검수_통지_미발행() {
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_FROM)).thenReturn(List.of(lbl(1L, 10L, T_FROM)));
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(2L, 20L, T_TO)));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());
        when(labelRepository.findAutoBboxByRawSnAndTrackId(RAW_SN, T_TO)).thenReturn(List.of(lbl(1L, 10L, T_TO)));
        when(trackInterpolationStep.interpolateSingleTrackTouched(RAW_SN, T_TO, T_FROM))
                .thenReturn(new TouchedFrames(1, Set.of(20L)));
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of());

        service.merge(RAW_SN, T_FROM, T_TO, worker());
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("락충돌_동시머지_또는_편집중_409_변경없음")
    void 락충돌_409() {
        doThrow(new CustomException(ErrorCode.CONFLICT, "이미 편집/재처리 중인 영상입니다."))
                .when(workLockService).lockRawExclusiveInNewTx(eq(RAW_SN), anyString());

        CustomException ex = catchThrowableOfType(
                () -> service.merge(RAW_SN, T_FROM, T_TO, worker()), CustomException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(labelRepository, never()).saveAll(anyList());
        verify(workLockService, never()).releaseRawInNewTx(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("미인증_401_락_미획득")
    void 미인증_401() {
        doThrow(new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다."))
                .when(accessGuard).verifyRawAccess(eq(RAW_SN), any());
        CustomException ex = catchThrowableOfType(
                () -> service.merge(RAW_SN, T_FROM, T_TO, worker()), CustomException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(workLockService, never()).lockRawExclusiveInNewTx(anyLong(), anyString());
    }

    @Test
    @DisplayName("타인배정_403_락_미획득")
    void 타인배정_403() {
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."))
                .when(accessGuard).verifyRawAccess(eq(RAW_SN), any());
        CustomException ex = catchThrowableOfType(
                () -> service.merge(RAW_SN, T_FROM, T_TO, worker()), CustomException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(workLockService, never()).lockRawExclusiveInNewTx(anyLong(), anyString());
    }
}
