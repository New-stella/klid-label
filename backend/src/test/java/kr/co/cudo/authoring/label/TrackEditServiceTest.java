package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep.TouchedFrames;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.dto.TrackDeleteResponse;
import kr.co.cudo.authoring.label.dto.TrackSplitResponse;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.TrackEditService;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
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
 * TrackEditService 단위 테스트 (Mockito) — Phase 3 트랙 삭제(R4)/split(R5).
 *
 * <p>HIGH 시나리오 방어 검증: IDOR(401/403) · 트랙 부재(404) · 락 충돌(409) · FK 고아 방지(삭제 순서) ·
 * 범위 밖 no-op · split 유니크 채번(max+1) · 좌표 불변 · 재보간 정합.
 */
class TrackEditServiceTest {

    private static final Long RAW_SN = 9001L;
    private static final String ACTOR_SUB = "1001";
    private static final String TRACK = "t-1";

    private LsDataLblRepository labelRepository;
    private LsDataLblAiInfoRepository aiInfoRepository;
    private LsDataLblAttrValRepository attrValRepository;
    private LsDataAugLblMapRepository augLblMapRepository;
    private LabelAccessGuard accessGuard;
    private WorkLockService workLockService;
    private TrackInterpolationStep trackInterpolationStep;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private ApplicationEventPublisher eventPublisher;
    private LsDataLblHstryRepository lblHstryRepository;
    /** C-ISSUE-21 라벨셋 버전 bump(= 프레임 행 락 선점) 검증용 — 락 순서 회귀 방지. */
    private kr.co.cudo.authoring.batch.repository.LsDataSrcRepository srcRepository;
    private TrackEditService service;

    @BeforeEach
    void setUp() {
        labelRepository = mock(LsDataLblRepository.class);
        aiInfoRepository = mock(LsDataLblAiInfoRepository.class);
        attrValRepository = mock(LsDataLblAttrValRepository.class);
        augLblMapRepository = mock(LsDataAugLblMapRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        workLockService = mock(WorkLockService.class);
        trackInterpolationStep = mock(TrackInterpolationStep.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        lblHstryRepository = mock(LsDataLblHstryRepository.class);
        srcRepository = mock(kr.co.cudo.authoring.batch.repository.LsDataSrcRepository.class);
        service = new TrackEditService(labelRepository, aiInfoRepository, attrValRepository, augLblMapRepository,
                accessGuard, workLockService, trackInterpolationStep, rawDataStatusRepository, eventPublisher,
                lblHstryRepository, srcRepository);
        when(accessGuard.parseUserNo(any())).thenReturn(1001L);
        // 재보간 기본 스텁 — 터치 프레임 없음. 개별 테스트가 필요 시 재정의.
        when(trackInterpolationStep.interpolateSingleTrackTouched(anyLong(), anyString(), anyString()))
                .thenReturn(new TouchedFrames(0, Set.of()));
    }

    private TokenClaims worker() {
        return new TokenClaims(ACTOR_SUB, Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LsDataLbl lbl(long lblSn, long srcSn, String trackId) {
        LsDataLbl l = LsDataLbl.createAutoBbox(srcSn, null, "person", "[[1,1],[2,2]]", BigDecimal.ZERO, trackId);
        ReflectionTestUtils.setField(l, "lblSn", lblSn);
        return l;
    }

    private void approved() {
        LsRawDataStatus status = LsRawDataStatus.initial(RAW_SN);
        status.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).thenReturn(List.of(status));
    }

    // ---------- R4 트랙 삭제 ----------

    @Test
    @DisplayName("트랙삭제_지정프레임이후_해당트랙만_삭제_자식먼저_부모나중_재보간")
    void 트랙삭제_성공_삭제순서_재보간() {
        LsDataLbl f10 = lbl(11L, 110L, TRACK);
        LsDataLbl f11 = lbl(12L, 111L, TRACK);
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(lbl(1L, 100L, TRACK), f10, f11));
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 10L)).thenReturn(List.of(f10, f11));
        when(trackInterpolationStep.interpolateSingleTrackTouched(RAW_SN, TRACK, TRACK))
                .thenReturn(new TouchedFrames(2, Set.of()));

        TrackDeleteResponse res = service.deleteTrackFrom(RAW_SN, TRACK, 10, worker());

        assertThat(res.deletedCount()).isEqualTo(2);
        assertThat(res.trackId()).isEqualTo(TRACK);
        assertThat(res.fromFrameNo()).isEqualTo(10);
        // FK 고아 방지 — 자식(ATTR_VAL) → 자식(AI_INFO) → 증강맵 → 부모(LBL) → 재보간. 락 선점/해제 순서.
        InOrder order = inOrder(workLockService, attrValRepository, aiInfoRepository, augLblMapRepository,
                labelRepository, trackInterpolationStep);
        order.verify(workLockService).lockRawExclusiveInNewTx(eq(RAW_SN), anyString());
        order.verify(attrValRepository).deleteByLblSnIn(List.of(11L, 12L));
        order.verify(aiInfoRepository).deleteByDataLblSnIn(List.of(11L, 12L));
        order.verify(augLblMapRepository).deleteByLabelReferencesIn(List.of(11L, 12L));
        order.verify(labelRepository).deleteAllByIdInBatch(List.of(11L, 12L));
        order.verify(trackInterpolationStep).interpolateSingleTrackTouched(RAW_SN, TRACK, TRACK);
        order.verify(workLockService).releaseRawInNewTx(eq(RAW_SN), anyString(), anyString());
    }

    @Test
    @DisplayName("트랙삭제는_라벨행을_지우기_전에_프레임_버전bump로_프레임락을_먼저_잡는다")
    void 트랙삭제_락순서_프레임먼저() {
        // DEV_FIX(H2①) — 라벨 삭제(=라벨 행 락) 뒤에 bump(=프레임 행 락) 를 두면, "프레임 락 → 라벨 락"
        //   순서로 도는 라벨 저장 경로(LabelService.bulkUpsert)와 역순이 되어 ABBA 데드락(PG 40P01)이 열린다.
        //   규약: 프레임 락을 항상 먼저. 이 테스트가 그 순서를 고정한다.
        LsDataLbl f10 = lbl(11L, 110L, TRACK);
        LsDataLbl f11 = lbl(12L, 111L, TRACK);
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(f10, f11));
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 10L)).thenReturn(List.of(f10, f11));

        service.deleteTrackFrom(RAW_SN, TRACK, 10, worker());

        InOrder order = inOrder(srcRepository, attrValRepository, aiInfoRepository, labelRepository);
        order.verify(srcRepository).bumpLabelVersionIn(Set.of(110L, 111L));
        order.verify(attrValRepository).deleteByLblSnIn(List.of(11L, 12L));
        order.verify(aiInfoRepository).deleteByDataLblSnIn(List.of(11L, 12L));
        order.verify(labelRepository).deleteAllByIdInBatch(List.of(11L, 12L));
    }

    @Test
    @DisplayName("트랙split은_라벨행을_수정하기_전에_프레임_버전bump로_프레임락을_먼저_잡는다")
    void 트랙split_락순서_프레임먼저() {
        LsDataLbl f10 = lbl(11L, 110L, TRACK);
        LsDataLbl f11 = lbl(12L, 111L, TRACK);
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(f10, f11));
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 10L)).thenReturn(List.of(f10, f11));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());

        service.splitTrack(RAW_SN, TRACK, 10, worker());

        InOrder order = inOrder(srcRepository, labelRepository);
        order.verify(srcRepository).bumpLabelVersionIn(Set.of(110L, 111L));
        order.verify(labelRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("H4_트랙삭제는_모든_라벨조회·변경보다_먼저_프레임락을_1회_선점한다")
    void 트랙삭제_프레임락_단일선점() {
        // DEV_FIX H4 — 한 트랜잭션이 서로 다른 프레임 집합을 2회 이상 bump(=락) 하면 문장 사이 ABBA 가
        //   성립해 PG 40P01(500) 이 난다(T1 {5,9}→{3,5} vs T2 {3}→{5,7}). 이제 트랜잭션 맨 앞에서
        //   영상 전 프레임 락을 SRC_SN 오름차순 단일 문장으로 선점하므로, 이후 bump 는 새 락을 얻지 않는다.
        LsDataLbl f10 = lbl(11L, 110L, TRACK);
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(f10));
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 10L)).thenReturn(List.of(f10));

        service.deleteTrackFrom(RAW_SN, TRACK, 10, worker());

        InOrder order = inOrder(srcRepository, labelRepository);
        // ① 선점이 가장 먼저 — 라벨을 읽기도 전에.
        order.verify(srcRepository).lockFramesByRawSn(RAW_SN);
        order.verify(labelRepository).findByRawSnAndTrackId(RAW_SN, TRACK);
        // ② 선점은 트랜잭션당 정확히 1회.
        verify(srcRepository).lockFramesByRawSn(RAW_SN);
    }

    @Test
    @DisplayName("H4_트랙split도_모든_라벨조회·변경보다_먼저_프레임락을_1회_선점한다")
    void 트랙split_프레임락_단일선점() {
        LsDataLbl f10 = lbl(11L, 110L, TRACK);
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(f10));
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 10L)).thenReturn(List.of(f10));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());

        service.splitTrack(RAW_SN, TRACK, 10, worker());

        InOrder order = inOrder(srcRepository, labelRepository);
        order.verify(srcRepository).lockFramesByRawSn(RAW_SN);
        order.verify(labelRepository).findByRawSnAndTrackId(RAW_SN, TRACK);
        verify(srcRepository).lockFramesByRawSn(RAW_SN);
    }

    @Test
    @DisplayName("트랙삭제_이력은_프레임당_DELETED_이벤트로_기록된다")
    void 트랙삭제_DELETED_히스토리_기록() {
        LsDataLbl f10 = lbl(11L, 110L, TRACK);
        LsDataLbl f11 = lbl(12L, 111L, TRACK);
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(lbl(1L, 100L, TRACK), f10, f11));
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 10L)).thenReturn(List.of(f10, f11));

        service.deleteTrackFrom(RAW_SN, TRACK, 10, worker());

        // V114 — 서로 다른 2개 프레임(110/111) 삭제 → 프레임당 저장 이벤트 1건 = 이벤트 2건, saveAll 1회.
        // 각 이벤트는 delCnt=1(add/mdfcn=0), regId=행위자, srcSn=해당 프레임.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLblHstry>> cap = ArgumentCaptor.forClass(List.class);
        verify(lblHstryRepository, org.mockito.Mockito.times(1)).saveAll(cap.capture());
        List<LsDataLblHstry> hist = cap.getValue();
        assertThat(hist).hasSize(2);
        assertThat(hist).allSatisfy(h -> {
            assertThat(h.getDelCnt()).isEqualTo(1);
            assertThat(h.getAddCnt()).isEqualTo(0);
            assertThat(h.getMdfcnCnt()).isEqualTo(0);
            assertThat(h.getRegId()).isEqualTo("1001"); // String.valueOf(actorNo) — 행위자 감사
            assertThat(h.getChgDtlCn()).contains("DELETED");
        });
        assertThat(hist).extracting(LsDataLblHstry::getSrcSn).containsExactly(110L, 111L);
        // 원자성 — 이력 기록이 라벨 row 삭제(부모)보다 먼저(같은 tx).
        InOrder order = inOrder(lblHstryRepository, labelRepository);
        order.verify(lblHstryRepository).saveAll(anyList());
        order.verify(labelRepository).deleteAllByIdInBatch(anyList());
    }

    @Test
    @DisplayName("트랙삭제_같은프레임_다건은_단일이벤트_delCnt집계로_기록된다")
    void 트랙삭제_같은프레임_단일이벤트() {
        LsDataLbl a = lbl(21L, 200L, TRACK);
        LsDataLbl b = lbl(22L, 200L, TRACK); // 같은 프레임 200L
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(a, b));
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 10L)).thenReturn(List.of(a, b));

        service.deleteTrackFrom(RAW_SN, TRACK, 10, worker());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLblHstry>> cap = ArgumentCaptor.forClass(List.class);
        verify(lblHstryRepository).saveAll(cap.capture());
        List<LsDataLblHstry> hist = cap.getValue();
        assertThat(hist).hasSize(1);
        assertThat(hist.get(0).getSrcSn()).isEqualTo(200L);
        assertThat(hist.get(0).getDelCnt()).isEqualTo(2);
    }

    @Test
    @DisplayName("트랙삭제_범위밖_변경없으면_DELETED_히스토리도_없음")
    void 트랙삭제_범위밖_히스토리없음() {
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(lbl(1L, 100L, TRACK)));
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 999L)).thenReturn(List.of());

        service.deleteTrackFrom(RAW_SN, TRACK, 999, worker());

        verify(lblHstryRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("트랙삭제_범위밖_deletedCount0_변경없음")
    void 트랙삭제_범위밖_noop() {
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(lbl(1L, 100L, TRACK)));
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 999L)).thenReturn(List.of());

        TrackDeleteResponse res = service.deleteTrackFrom(RAW_SN, TRACK, 999, worker());

        assertThat(res.deletedCount()).isZero();
        verify(labelRepository, never()).deleteAllByIdInBatch(anyList());
        verify(attrValRepository, never()).deleteByLblSnIn(anyList());
        verify(trackInterpolationStep, never()).interpolateSingleTrackTouched(anyLong(), anyString(), anyString());
        verify(workLockService).releaseRawInNewTx(eq(RAW_SN), anyString(), anyString());
    }

    @Test
    @DisplayName("트랙삭제_존재하지않는_트랙_404_락해제")
    void 트랙삭제_트랙부재_404() {
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of());

        CustomException ex = catchThrowableOfType(
                () -> service.deleteTrackFrom(RAW_SN, TRACK, 0, worker()), CustomException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(labelRepository, never()).deleteAllByIdInBatch(anyList());
        verify(workLockService).releaseRawInNewTx(eq(RAW_SN), anyString(), anyString());
    }

    @Test
    @DisplayName("트랙삭제_타인배정_403_락_미획득")
    void 트랙삭제_타인배정_403() {
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."))
                .when(accessGuard).verifyRawAccess(eq(RAW_SN), any());
        CustomException ex = catchThrowableOfType(
                () -> service.deleteTrackFrom(RAW_SN, TRACK, 0, worker()), CustomException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(workLockService, never()).lockRawExclusiveInNewTx(anyLong(), anyString());
    }

    @Test
    @DisplayName("트랙삭제_잠금중_배타락_409_변경없음")
    void 트랙삭제_락충돌_409() {
        doThrow(new CustomException(ErrorCode.CONFLICT, "이미 편집/재처리 중인 영상입니다."))
                .when(workLockService).lockRawExclusiveInNewTx(eq(RAW_SN), anyString());
        CustomException ex = catchThrowableOfType(
                () -> service.deleteTrackFrom(RAW_SN, TRACK, 0, worker()), CustomException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(labelRepository, never()).deleteAllByIdInBatch(anyList());
        verify(workLockService, never()).releaseRawInNewTx(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("트랙삭제_APPROVED_통지_변경프레임_삭제프레임∪재보간터치_union")
    void 트랙삭제_APPROVED_통지() {
        approved();
        LsDataLbl f10 = lbl(11L, 110L, TRACK);
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(f10));
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 0L)).thenReturn(List.of(f10));
        // 재보간이 삭제 프레임(110) 외 프레임(999)을 건드림 → 통지 union 대상.
        when(trackInterpolationStep.interpolateSingleTrackTouched(RAW_SN, TRACK, TRACK))
                .thenReturn(new TouchedFrames(0, Set.of(999L)));

        service.deleteTrackFrom(RAW_SN, TRACK, 0, worker());

        ArgumentCaptor<TaskModifiedEvent> cap = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(e -> assertThat(e.changeType()).isEqualTo("LABEL_DELETED"));
        // 삭제 프레임(110) + 재보간 터치 프레임(999) 모두 통지(데이터마트 드리프트 방지).
        assertThat(cap.getAllValues()).extracting(TaskModifiedEvent::srcSn).containsExactlyInAnyOrder(110L, 999L);
        // Phase 5C 회귀 방어 — 승인 후 트랙 편집은 export JSON 을 바꾸므로 exportRegenerated=true 로 발행돼야
        //   디바운스 flush 가 export 를 새 버전으로 재생성한다. 4-arg(false)로 되돌리면 실패한다.
        assertThat(cap.getAllValues()).allMatch(TaskModifiedEvent::exportRegenerated);
    }

    // ---------- R5 트랙 split ----------

    @Test
    @DisplayName("트랙split_atFrame이후_새trackId재지정_이전_원트랙유지_좌표불변")
    void 트랙split_성공() {
        LsDataLbl front = lbl(1L, 100L, TRACK);   // atFrameNo 이전 (이동 대상 아님)
        LsDataLbl back = lbl(2L, 105L, TRACK);    // atFrameNo 이후 (이동 대상)
        String pointsBefore = back.getPointCn();
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(front, back));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 5L)).thenReturn(List.of(back));
        when(labelRepository.findDistinctTrackIdsByRawSn(RAW_SN)).thenReturn(List.of("3", "5"));

        TrackSplitResponse res = service.splitTrack(RAW_SN, TRACK, 5, worker());

        // 새 trackId = max(정수 트랙ID)+1 = 6
        assertThat(res.newTrackId()).isEqualTo("6");
        assertThat(res.originalTrackId()).isEqualTo(TRACK);
        assertThat(res.movedCount()).isEqualTo(1);
        // 이동 대상만 새 트랙, 이전 프레임은 원 트랙 유지, 좌표 불변.
        assertThat(back.getTrackId()).isEqualTo("6");
        assertThat(front.getTrackId()).isEqualTo(TRACK);
        assertThat(back.getPointCn()).isEqualTo(pointsBefore);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(labelRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(back);
    }

    @Test
    @DisplayName("트랙split_정수트랙없으면_새trackId_1")
    void 트랙split_채번_정수없음() {
        LsDataLbl back = lbl(2L, 105L, "abc");
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, "abc")).thenReturn(List.of(back));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, "abc", 0L)).thenReturn(List.of(back));
        when(labelRepository.findDistinctTrackIdsByRawSn(RAW_SN)).thenReturn(List.of("abc"));

        TrackSplitResponse res = service.splitTrack(RAW_SN, "abc", 0, worker());
        assertThat(res.newTrackId()).isEqualTo("1");
    }

    @Test
    @DisplayName("트랙split_경계밖_movedCount0_변경없음")
    void 트랙split_경계밖_noop() {
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(lbl(1L, 100L, TRACK)));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of());
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 999L)).thenReturn(List.of());
        when(labelRepository.findDistinctTrackIdsByRawSn(RAW_SN)).thenReturn(List.of("1"));

        TrackSplitResponse res = service.splitTrack(RAW_SN, TRACK, 999, worker());

        assertThat(res.movedCount()).isZero();
        verify(labelRepository, never()).saveAll(anyList());
        verify(trackInterpolationStep, never()).interpolateSingleTrackTouched(anyLong(), anyString(), anyString());
        verify(workLockService).releaseRawInNewTx(eq(RAW_SN), anyString(), anyString());
    }

    @Test
    @DisplayName("트랙split_보간산출물_이동제외_원키프레임만")
    void 트랙split_보간제외() {
        LsDataLbl keyframe = lbl(2L, 105L, TRACK);
        LsDataLbl interpolated = lbl(3L, 106L, TRACK);
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of(keyframe, interpolated));
        when(labelRepository.findInterpolatedLblSnsByRawSn(RAW_SN)).thenReturn(List.of(3L));
        when(labelRepository.findByRawSnAndTrackIdFromFrameNo(RAW_SN, TRACK, 5L))
                .thenReturn(List.of(keyframe, interpolated));
        when(labelRepository.findDistinctTrackIdsByRawSn(RAW_SN)).thenReturn(List.of("1"));

        TrackSplitResponse res = service.splitTrack(RAW_SN, TRACK, 5, worker());

        // 원 키프레임만 이동, 보간 산출물은 trackId 불변(재보간 정리단계가 처리).
        assertThat(res.movedCount()).isEqualTo(1);
        assertThat(keyframe.getTrackId()).isEqualTo("2");
        assertThat(interpolated.getTrackId()).isEqualTo(TRACK);
    }

    @Test
    @DisplayName("트랙split_존재하지않는_트랙_404")
    void 트랙split_트랙부재_404() {
        when(labelRepository.findByRawSnAndTrackId(RAW_SN, TRACK)).thenReturn(List.of());
        CustomException ex = catchThrowableOfType(
                () -> service.splitTrack(RAW_SN, TRACK, 0, worker()), CustomException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(workLockService).releaseRawInNewTx(eq(RAW_SN), anyString(), anyString());
    }

    @Test
    @DisplayName("트랙split_미인증_401_락_미획득")
    void 트랙split_미인증_401() {
        doThrow(new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다."))
                .when(accessGuard).verifyRawAccess(eq(RAW_SN), any());
        CustomException ex = catchThrowableOfType(
                () -> service.splitTrack(RAW_SN, TRACK, 0, worker()), CustomException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(workLockService, never()).lockRawExclusiveInNewTx(anyLong(), anyString());
    }
}
