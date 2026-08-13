package kr.co.cudo.authoring.label.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
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
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2b — <b>한번이라도 검수 완료된 영상</b>의 프레임 폐기·복원 차단과 <b>회차 적용 예외</b>.
 *
 * <h3>왜 막나 — 데이터마트 롤백 정합성</h3>
 * 프레임 이미지·JSON 은 회차별로 물리 분리돼 {@code v1} 폴더가 불변인데, <b>영상 파일은 회차별로
 * 분리되지 않아</b> 데이터마트 뷰가 항상 최신 비식별본을 가리킨다. 이미 산출되어 외부로 나간 회차에서
 * 프레임이 빠지거나 되살아나면 그 회차의 산출물과 어긋난다.
 *
 * <h3>거부 코드는 400 (412 가 아니다)</h3>
 * 이 저장소 전례는 <b>412 = 해소되면 되는 일시 조건</b>(비식별 신고 구간), <b>400 = 되돌아가지 않는
 * 영구 조건</b>(파생영상 차단)이다. "한번이라도 승인"은 영구 조건이라 재시도 여지가 없다. 같은 단계의
 * 신고 차단이 412 인 것과 코드가 갈리는 것은 <b>사유의 성질이 다르기 때문</b>이며 의도된 비대칭이다.
 *
 * <h3>★회차 적용은 예외다 (사용자 확정, 구속)</h3>
 * 확정 저장이 불러온 회차의 폐기 상태를 적용하는 것은 <b>새로 바꾸는 게 아니라 그 시점으로 되돌아가는
 * 것</b>이다. 막으면 라벨만 적용되고 폐기는 현재값으로 남아 <b>"한 영상 = 한 회차" 불변식이 깨진다.</b>
 *
 * <p>이 클래스가 {@code label.service} 패키지에 있는 이유: 경로 구분 축
 * ({@code LabelService.FrameSaveOptions})이 package-private 이라 <b>실제 경로를 그대로 태우려면</b>
 * 같은 패키지여야 한다(리플렉션으로 우회하지 않는다).
 *
 * <p>승인 영상의 폐기·복원 <b>통지 매핑</b>(재검토 표시·산출 재생성·변경종류)은 이제 이 경로에서만
 * 도달 가능하다 — 프레임 단위 저장은 400 으로 막히기 때문이다. 그래서 그 단언들이 이 클래스로
 * 옮겨져 있다({@code LabelServiceFrameDiscardTest} 의 구 3건).
 *
 * @req R4
 * @req R5
 */
class FrameDiscardApprovalGateTest {

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
        service = new LabelService(labelRepository, srcRepository,
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
        when(srcRepository.readDiscardFlag(SRC_SN)).thenReturn(Optional.of(LsDataSrc.DSCD_NO));
        when(srcRepository.applyDiscardFlag(eq(SRC_SN), any())).thenReturn(1);
    }

    /**
     * 승인 이력 판정 스텁 — 프로덕션이 호출하는 것은 <b>캐시 변형</b>이다(F-2).
     *
     * <p>프레임 루프가 프레임마다 이 판정을 부르므로 요청 스코프 캐시를 태운다. 목에서는 위임이
     * 실행되지 않으니 캐시 변형을 직접 스텁해야 한다 — 비캐시 변형만 스텁하면 목 기본값(false)이
     * 반환되어 게이트가 조용히 열린 채 테스트가 통과한다.
     */
    private void stubEverApproved(boolean everApproved) {
        when(approvalGate.hasEverApprovedCached(eq(RAW_SN), org.mockito.ArgumentMatchers.any()))
                .thenReturn(everApproved);
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

    /** 확정 저장(회차 적용) 경로로 저장 코어를 태운다 — 그 경로에서만 승인 영상 폐기가 도달 가능하다. */
    private void applyFromVersion(LabelBulkUpsertRequest req) {
        service.applyFrameSave(SRC_SN, frame, req, ACTOR_NO,
                LabelService.FrameSaveOptions.of(null, Map.of(), true));
    }

    /** 사람이 새로 폐기·복원하는 조작 — 확정 저장 경로여도 {@code edits} 로 명시하면 이 축이다. */
    private void applyAsNewOperation(LabelBulkUpsertRequest req) {
        service.applyFrameSave(SRC_SN, frame, req, ACTOR_NO,
                LabelService.FrameSaveOptions.of(null, Map.of(), false));
    }

    // ───────────── 차단 (사람의 새 조작) ─────────────

    @Test
    @DisplayName("승인_이력_영상의_새_폐기_조작은_400")
    void 승인_이력_영상의_새_폐기_조작은_400() {
        stubEverApproved(true);

        assertThatThrownBy(() -> service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_YES), worker()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        // 상태가 바뀌지 않는다(감사 이력도 남지 않는다).
        verify(srcRepository, never()).applyDiscardFlag(anyLong(), any());
        verify(taskEventLogRepository, never()).save(any());
        assertThat(frame.isDiscarded()).isFalse();
    }

    @Test
    @DisplayName("승인_이력_영상의_새_복원_조작도_400 — 되살리는_방향도_회차와_어긋난다")
    void 승인_이력_영상의_새_복원_조작도_400() {
        stubEverApproved(true);
        when(srcRepository.readDiscardFlag(SRC_SN)).thenReturn(Optional.of(LsDataSrc.DSCD_YES));

        assertThatThrownBy(() -> service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_NO), worker()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        verify(srcRepository, never()).applyDiscardFlag(anyLong(), any());
    }

    @Test
    @DisplayName("확정_저장이라도_사용자가_폐기를_새로_지정하면_400 — 회차를_불러오는_것만으로_우회되지_않는다")
    void 확정_저장이라도_사용자가_새로_지정하면_400() {
        // ⚠ 예외를 경로 단위로 뭉개면 회차를 한 번 불러오는 것만으로 폐기 차단이 통째로 우회된다.
        //   구분은 프레임마다다 — edits 로 명시한 값은 사람의 새 조작이다.
        stubEverApproved(true);

        assertThatThrownBy(() -> applyAsNewOperation(saveWith(LsDataSrc.DSCD_YES)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        verify(srcRepository, never()).applyDiscardFlag(anyLong(), any());
    }

    @Test
    @DisplayName("승인_이력이_없으면_폐기가_그대로_된다 — 과잉_차단_방지")
    void 승인_이력이_없으면_폐기가_그대로_된다() {
        stubEverApproved(false);

        service.bulkUpsert(SRC_SN, saveWith(LsDataSrc.DSCD_YES), worker());

        verify(srcRepository).applyDiscardFlag(SRC_SN, LsDataSrc.DSCD_YES);
        assertThat(frame.isDiscarded()).isTrue();
    }

    @Test
    @DisplayName("폐기여부를_보내지_않은_저장은_승인_이력_영상에서도_통과한다 — 라벨_수정은_계속_허용된다")
    void 폐기여부_미첨부_저장은_통과한다() {
        stubEverApproved(true);

        service.bulkUpsert(SRC_SN, saveWith(null), worker());

        // 아무것도 바꾸지 않는 축이라 판정 자체를 하지 않는다(승인 영상의 라벨 수정은 재검토 축이 담당).
        verify(srcRepository, never()).applyDiscardFlag(anyLong(), any());
    }

    // ───────────── 회차 적용 예외 (구 3건이 지키던 성질을 그대로 단언) ─────────────

    @Test
    @DisplayName("승인_이력_영상도_회차_폐기_상태_적용은_허용한다")
    void 승인_이력_영상도_회차_폐기_상태_적용은_허용한다() {
        stubEverApproved(true);

        applyFromVersion(saveWith(LsDataSrc.DSCD_YES));

        // ★ 막으면 라벨만 적용되고 폐기는 현재값으로 남아 "한 영상 = 한 회차" 불변식이 깨진다.
        verify(srcRepository).applyDiscardFlag(SRC_SN, LsDataSrc.DSCD_YES);
        assertThat(frame.isDiscarded()).isTrue();
        // 폐기 감사 이력도 그대로 남는다(누가·어느 프레임을 — CWE-778).
        verify(taskEventLogRepository).save(any());
    }

    @Test
    @DisplayName("회차_폐기_적용시_재검토표시와_산출재생성을_요구하는_수정이벤트가_발행된다")
    void 회차_폐기_적용시_재검토표시와_산출재생성_이벤트가_발행된다() {
        // 구 `승인된_영상에서_폐기하면...`(프레임 단위 경로)이 지키던 성질 — 도달 가능한 경로로 이관.
        stubEverApproved(true);
        when(approvalGate.isApproved(RAW_SN)).thenReturn(true);

        applyFromVersion(saveWith(LsDataSrc.DSCD_YES));

        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        TaskModifiedEvent event = captor.getValue();
        assertThat(event.changeType()).isEqualTo(ChangeType.FRAME_DISCARDED);
        assertThat(event.srcSn()).isEqualTo(SRC_SN);
        assertThat(event.exportRegenerated()).isTrue();
        assertThat(event.needsRecheck()).isTrue();
    }

    @Test
    @DisplayName("회차_복원_적용시_복원_변경종류로_수정이벤트가_발행된다")
    void 회차_복원_적용시_복원_변경종류로_수정이벤트가_발행된다() {
        // 구 `승인된_영상에서_복원하면...` 이관.
        stubEverApproved(true);
        when(approvalGate.isApproved(RAW_SN)).thenReturn(true);
        when(srcRepository.readDiscardFlag(SRC_SN)).thenReturn(Optional.of(LsDataSrc.DSCD_YES));

        applyFromVersion(saveWith(LsDataSrc.DSCD_NO));

        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().changeType()).isEqualTo(ChangeType.FRAME_RESTORED);
        // 복원이 실제로 적용된다(변경종류만 맞고 상태가 그대로면 의미가 없다).
        verify(srcRepository).applyDiscardFlag(SRC_SN, LsDataSrc.DSCD_NO);
        assertThat(frame.isDiscarded()).isFalse();
    }

    @Test
    @DisplayName("라벨수정과_회차_폐기가_한_저장에_함께_오면_두_변경종류가_모두_발행된다")
    void 라벨수정과_회차_폐기가_함께_오면_두_변경종류가_발행된다() {
        // 구 `라벨수정과_폐기가_한_저장에_함께_오면...` 이관.
        stubEverApproved(true);
        when(approvalGate.isApproved(RAW_SN)).thenReturn(true);

        applyFromVersion(saveWithLabelAnd(LsDataSrc.DSCD_YES));

        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.atLeast(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(TaskModifiedEvent::changeType)
                .contains(ChangeType.LABEL_ADDED, ChangeType.FRAME_DISCARDED);
        // 폐기가 실제로 적용된다.
        verify(srcRepository).applyDiscardFlag(SRC_SN, LsDataSrc.DSCD_YES);
    }
}
