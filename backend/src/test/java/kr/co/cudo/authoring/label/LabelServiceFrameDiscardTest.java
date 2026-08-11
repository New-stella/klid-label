package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.FrameBoundsResolver;
import kr.co.cudo.authoring.label.service.FrameDiscardApplier;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R4·R5 — 프레임 폐기·복원이 <b>라벨 저장 계약</b>에 실려 확정되는 동작 검증 (Mockito 단위).
 *
 * <p>확정 설계(D8)상 폐기·복원 전용 엔드포인트를 두지 않는다 — 화면에서 한 모든 것은 저장을 눌러야
 * 확정되므로, 폐기 여부는 저장 요청의 <b>선택 필드</b>({@code dscdYn})로 실린다. 보내지 않으면
 * 현재 값을 유지한다(하위호환 — 폐기를 모르는 기존 호출자가 그대로 동작한다).
 *
 * @design D1
 * @design D3
 * @design D6
 * @design D8
 * @req R4
 * @req R5
 */
class LabelServiceFrameDiscardTest {

    private static final Long SRC_SN = 5001L;
    private static final Long RAW_SN = 9001L;
    private static final String ACTOR_SUB = "1001";
    private static final Long ACTOR_NO = 1001L;

    private LsDataLblRepository labelRepository;
    private LsDataSrcRepository srcRepository;
    private LabelAccessGuard accessGuard;
    private WorkLockService workLockService;
    private ApplicationEventPublisher eventPublisher;
    private ReviewApprovalGate approvalGate;
    private LsTaskEventLogRepository taskEventLogRepository;
    private LabelService service;
    private LsDataSrc frame;

    @BeforeEach
    void setUp() {
        labelRepository = mock(LsDataLblRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        workLockService = mock(WorkLockService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        approvalGate = mock(ReviewApprovalGate.class);
        taskEventLogRepository = mock(LsTaskEventLogRepository.class);

        FrameDiscardApplier applier = new FrameDiscardApplier(srcRepository, taskEventLogRepository);

        service = new LabelService(labelRepository, mock(LsDataLblAiInfoRepository.class), srcRepository,
                mock(VideoRepository.class), workLockService, accessGuard, new ObjectMapper(),
                mock(LsLabelRepository.class), eventPublisher, approvalGate,
                mock(LsDataLblHstryRepository.class), mock(LsDataLblAttrValRepository.class),
                mock(FrameBoundsResolver.class), mock(UserNameResolver.class), applier);

        frame = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(accessGuard.verifyAndGet(any(), any())).thenReturn(frame);
        when(accessGuard.parseUserNo(any())).thenReturn(ACTOR_NO);
        when(srcRepository.lockAndReadLabelVersion(any())).thenReturn(Optional.of(0L));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of());
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of());
        when(labelRepository.save(any(LsDataLbl.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labelRepository.findDistinctSrcSnsWithLabelIn(anyCollection())).thenReturn(List.of());
        // 기본: 현재 폐기 아님 + 상태 변경 UPDATE 는 1행 적중.
        when(srcRepository.readDiscardFlag(SRC_SN)).thenReturn(Optional.of(LsDataSrc.DSCD_NO));
        when(srcRepository.applyDiscardFlag(eq(SRC_SN), any())).thenReturn(1);
    }

    private TokenClaims worker() {
        return new TokenClaims(ACTOR_SUB, Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LabelBulkUpsertRequest saveWith(String dscdYn) {
        return new LabelBulkUpsertRequest(List.of(), null, dscdYn);
    }

    private LabelBulkUpsertRequest saveWithLabelAnd(String dscdYn) {
        LabelItemDto item = new LabelItemDto(null, "BBOX", null, "person",
                List.of(List.of(1.0, 1.0), List.of(2.0, 2.0)), null);
        return new LabelBulkUpsertRequest(List.of(item), null, dscdYn);
    }

    // ------------------------------------------------------------------ 폐기 (R4)

    @Test
    @DisplayName("저장요청에_폐기Y가_실리면_프레임이_폐기상태로_확정된다")
    void 저장요청에_폐기Y가_실리면_프레임이_폐기상태로_확정된다() {
        LabelResponse res = service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_YES), worker());

        verify(srcRepository).applyDiscardFlag(SRC_SN, LsDataSrc.DSCD_YES);
        assertThat(res.dscdYn()).isEqualTo(LsDataSrc.DSCD_YES);
        assertThat(frame.isDiscarded()).isTrue();
    }

    @Test
    @DisplayName("폐기하면_누가_어느_프레임을_폐기했는지_감사이력이_남는다")
    void 폐기하면_누가_어느_프레임을_폐기했는지_감사이력이_남는다() {
        service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_YES), worker());

        ArgumentCaptor<LsTaskEventLog> captor = ArgumentCaptor.forClass(LsTaskEventLog.class);
        verify(taskEventLogRepository).save(captor.capture());
        LsTaskEventLog log = captor.getValue();
        assertThat(log.getEventTypeCd()).isEqualTo(LsTaskEventLog.EVENT_FRAME_DISCARD);
        assertThat(log.getRawDataId()).isEqualTo(RAW_SN);
        assertThat(log.getActorUserNo()).isEqualTo(ACTOR_NO);
        assertThat(log.getRsn()).isEqualTo(LsTaskEventLog.RSN_FRAME_PREFIX + SRC_SN);
    }

    // ------------------------------------------------------------------ 복원 (R5)

    @Test
    @DisplayName("폐기된_프레임에_폐기N을_실어_저장하면_복원되고_복원_감사이력이_남는다")
    void 폐기된_프레임에_폐기N을_실어_저장하면_복원되고_복원_감사이력이_남는다() {
        when(srcRepository.readDiscardFlag(SRC_SN)).thenReturn(Optional.of(LsDataSrc.DSCD_YES));
        frame.discard();

        LabelResponse res = service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_NO), worker());

        verify(srcRepository).applyDiscardFlag(SRC_SN, LsDataSrc.DSCD_NO);
        assertThat(res.dscdYn()).isEqualTo(LsDataSrc.DSCD_NO);
        ArgumentCaptor<LsTaskEventLog> captor = ArgumentCaptor.forClass(LsTaskEventLog.class);
        verify(taskEventLogRepository).save(captor.capture());
        assertThat(captor.getValue().getEventTypeCd()).isEqualTo(LsTaskEventLog.EVENT_FRAME_RESTORE);
    }

    // ------------------------------------------------------------------ 하위호환 · 멱등

    @Test
    @DisplayName("폐기여부를_보내지_않으면_현재값을_그대로_두고_감사이력도_남기지_않는다")
    void 폐기여부를_보내지_않으면_현재값을_그대로_두고_감사이력도_남기지_않는다() {
        service.bulkUpsert(SRC_SN, saveWith(null), worker());

        verify(srcRepository, never()).applyDiscardFlag(any(), any());
        verify(taskEventLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미_폐기된_프레임에_폐기Y를_다시_보내면_아무것도_바뀌지_않는다")
    void 이미_폐기된_프레임에_폐기Y를_다시_보내면_아무것도_바뀌지_않는다() {
        when(srcRepository.readDiscardFlag(SRC_SN)).thenReturn(Optional.of(LsDataSrc.DSCD_YES));

        service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_YES), worker());

        verify(srcRepository, never()).applyDiscardFlag(any(), any());
        verify(taskEventLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("폐기여부_판정은_1차캐시가_아니라_행잠금_후_DB_현재값으로_한다")
    void 폐기여부_판정은_1차캐시가_아니라_행잠금_후_DB_현재값으로_한다() {
        // 엔티티(1차 캐시)는 '사용중'인데 DB 는 이미 '폐기' — 경쟁 트랜잭션이 먼저 커밋한 상태.
        // 엔티티 값을 믿으면 '폐기 Y' 요청이 변경으로 오판되어 감사이력이 중복된다.
        assertThat(frame.isDiscarded()).isFalse();
        when(srcRepository.readDiscardFlag(SRC_SN)).thenReturn(Optional.of(LsDataSrc.DSCD_YES));

        service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_YES), worker());

        verify(srcRepository, never()).applyDiscardFlag(any(), any());
    }

    // ------------------------------------------------------------------ 재검토·통지 (D6)

    @Test
    @DisplayName("승인된_영상에서_폐기하면_재검토표시와_산출재생성을_요구하는_수정이벤트가_발행된다")
    void 승인된_영상에서_폐기하면_재검토표시와_산출재생성을_요구하는_수정이벤트가_발행된다() {
        when(approvalGate.isApproved(RAW_SN)).thenReturn(true);

        service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_YES), worker());

        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        TaskModifiedEvent event = captor.getValue();
        assertThat(event.changeType()).isEqualTo(ChangeType.FRAME_DISCARDED);
        assertThat(event.srcSn()).isEqualTo(SRC_SN);
        assertThat(event.exportRegenerated()).isTrue();
        assertThat(event.needsRecheck()).isTrue();
    }

    @Test
    @DisplayName("승인된_영상에서_복원하면_복원_변경종류로_수정이벤트가_발행된다")
    void 승인된_영상에서_복원하면_복원_변경종류로_수정이벤트가_발행된다() {
        when(approvalGate.isApproved(RAW_SN)).thenReturn(true);
        when(srcRepository.readDiscardFlag(SRC_SN)).thenReturn(Optional.of(LsDataSrc.DSCD_YES));

        service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_NO), worker());

        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().changeType()).isEqualTo(ChangeType.FRAME_RESTORED);
    }

    @Test
    @DisplayName("미승인_영상에서_폐기하면_수정이벤트를_발행하지_않는다")
    void 미승인_영상에서_폐기하면_수정이벤트를_발행하지_않는다() {
        when(approvalGate.isApproved(RAW_SN)).thenReturn(false);

        service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_YES), worker());

        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("라벨수정과_폐기가_한_저장에_함께_오면_두_변경종류가_모두_발행된다")
    void 라벨수정과_폐기가_한_저장에_함께_오면_두_변경종류가_모두_발행된다() {
        when(approvalGate.isApproved(RAW_SN)).thenReturn(true);

        service.bulkUpsert(SRC_SN, saveWithLabelAnd(LsDataSrc.DSCD_YES), worker());

        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(TaskModifiedEvent::changeType)
                .contains(ChangeType.LABEL_ADDED, ChangeType.FRAME_DISCARDED);
    }

    // ------------------------------------------------------------------ 인가·게이트 (D3)

    @Test
    @DisplayName("타인_배정_프레임은_폐기할_수_없다")
    void 타인_배정_프레임은_폐기할_수_없다() {
        when(accessGuard.verifyAndGet(any(), any()))
                .thenThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."));

        assertThatThrownBy(() -> service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_YES), worker()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(srcRepository, never()).applyDiscardFlag(any(), any());
    }

    @Test
    @DisplayName("미인증이면_폐기할_수_없다")
    void 미인증이면_폐기할_수_없다() {
        when(accessGuard.verifyAndGet(any(), any()))
                .thenThrow(new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다."));

        assertThatThrownBy(() -> service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_YES), null))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(srcRepository, never()).applyDiscardFlag(any(), any());
    }

    @Test
    @DisplayName("비식별_신고가_열린_영상은_폐기도_412로_막힌다")
    void 비식별_신고가_열린_영상은_폐기도_412로_막힌다() {
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED,
                        "비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_YES), worker()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);
        verify(srcRepository, never()).applyDiscardFlag(any(), any());
    }
}
