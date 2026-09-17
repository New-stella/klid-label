package kr.co.cudo.authoring.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.debounce.ControlNotifyDebounceStore;
import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.dataset.service.DatasetVideoMetaSnapshotService;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.meta.service.MetaService;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.review.repository.ReviewQueryRepository;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import kr.co.cudo.authoring.review.service.ReviewService;
import kr.co.cudo.authoring.review.service.ReviewStateMachine;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.version.service.VersionService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7a-2 — {@code ReviewService.approve()} 의 재검토 표시(REVLT_YN='Y') 기반 재승인 short-circuit
 * 경로 + 통지 타입 분기(ReviewApprovedEvent 미발행) 검증.
 *
 * <p>{@link ReviewStateMachine} 은 <b>실제 구현</b>을 쓴다(의존성 없는 순수 POJO) — 재검토 표시가 없는
 * {@code APPROVED→APPROVED} 시도가 실제로 거부되는지(가드가 유효한지)까지 확인하기 위함이다.
 */
class ReviewServiceReapprovalTest {

    private ReviewRepository reviewRepository;
    private ApplicationEventPublisher eventPublisher;
    private VersionService versionService;
    private LsTaskEventLogRepository taskEventLogRepository;
    private ControlNotifyDebounceStore controlNotifyDebounceStore;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        IssueRepository issueRepository = mock(IssueRepository.class);
        LsTaskAssignmentRepository authrtRepository = mock(LsTaskAssignmentRepository.class);
        taskEventLogRepository = mock(LsTaskEventLogRepository.class);
        LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
        LsDataLblRepository labelRepository = mock(LsDataLblRepository.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        eventPublisher = mock(ApplicationEventPublisher.class);
        versionService = mock(VersionService.class);
        DatasetVideoMetaSnapshotService datasetVideoMetaSnapshotService = mock(DatasetVideoMetaSnapshotService.class);
        EvntAnnoReviewService evntAnnoReviewService = mock(EvntAnnoReviewService.class);
        MetaService metaService = mock(MetaService.class);
        LabelAccessGuard accessGuard = mock(LabelAccessGuard.class);
        controlNotifyDebounceStore = mock(ControlNotifyDebounceStore.class);
        // 기본값 — 정상 경로(표시·윈도우 짝이 맞음)를 전제한다. 구멍2 폴백 전용 테스트만 이 스텁을
        // false 로 덮어써 짝이 깨진 상황을 재현한다.
        when(controlNotifyDebounceStore.hasOpenWindow(anyLong())).thenReturn(true);

        reviewService = new ReviewService(
                reviewRepository,
                mock(ReviewQueryRepository.class),
                issueRepository, authrtRepository, taskEventLogRepository,
                new ReviewStateMachine(), srcRepository, labelRepository, videoRepository,
                new UserNameResolver(userRepository),
                objectMapper, eventPublisher, versionService, datasetVideoMetaSnapshotService,
                evntAnnoReviewService, metaService, accessGuard, controlNotifyDebounceStore,
                // ADR-067 — 검수 점유 조회 단일 창구. 위 이벤트 로그 목이 빈 결과를 돌려주므로
                // 「아무도 점유하지 않음」 상태다(이 시험의 관심사는 점유가 아니다).
                new kr.co.cudo.authoring.assignment.service.ReviewClaimSupport(taskEventLogRepository, 30),
                // 일괄 승인 건수 상한 — 단건 경로를 쓰는 이 시험에서는 읽히지 않는다.
                new kr.co.cudo.authoring.review.service.ReviewBatchApprovePolicy(20));

        when(videoRepository.findCctvNamesByRawSns(any())).thenReturn(Collections.emptyList());
        when(authrtRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(any(), any()))
                .thenReturn(Collections.emptyList());
        when(labelRepository.countLabelsByRawSnIn(any())).thenReturn(Collections.emptyList());
        when(labelRepository.existsAnyByRawSn(anyLong())).thenReturn(true);
        when(videoRepository.findEventInfoByRawSns(any())).thenReturn(Collections.emptyList());
        when(taskEventLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionService.commitApproved(anyLong(), any()))
                .thenReturn(new VersionService.CommitResult(1, 0));
    }

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("재검토_표시가_없는_APPROVED_영상은_재승인_시도가_CONFLICT로_거부된다")
    void approvedWithoutRecheckFlag_reapprovalAttemptIsRejected() {
        // given — 이미 APPROVED, 재검토 표시(REVLT_YN) 없음(기본 false).
        Long videoId = 100L;
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        // V14 — 검수 승인은 비식별화완료여부가 Y 일 때만 통과한다. 실엔티티는 기본이 Y 라
        //   이 스텁이 곧 프로덕션 기본 상태이며, 그 축은 이 테스트의 관심사가 아니다.
        when(stts.isDeidentCompleted()).thenReturn(true);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_APPROVED);
        when(stts.needsRecheck()).thenReturn(false);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));

        // when / then — 기존 차단(409 CONFLICT)이 그대로 유지된다.
        assertThatThrownBy(() -> reviewService.approve(videoId, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(stts, never()).transitionTo(any());
        verify(stts, never()).clearNeedsRecheck();
        verify(eventPublisher, never()).publishEvent(any(ReviewApprovedEvent.class));
    }

    @Test
    @DisplayName("재검토_표시가_선_APPROVED_영상은_상태전이_없이_재승인되고_표시가_해제된다")
    void approvedWithRecheckFlag_reapprovalSkipsTransitionAndClearsFlag() {
        // given — 이미 APPROVED, 재검토 표시(REVLT_YN='Y')가 서 있고, 축적 윈도우도 정상적으로 있다
        //   (setUp 기본값 hasOpenWindow=true — 표시·윈도우 짝이 맞는 정상 경로).
        Long videoId = 200L;
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        // V14 — 검수 승인은 비식별화완료여부가 Y 일 때만 통과한다. 실엔티티는 기본이 Y 라
        //   이 스텁이 곧 프로덕션 기본 상태이며, 그 축은 이 테스트의 관심사가 아니다.
        when(stts.isDeidentCompleted()).thenReturn(true);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_APPROVED);
        when(stts.needsRecheck()).thenReturn(true);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));

        // when — 예외 없이 재승인이 성공한다(상태 전이 검증을 우회하는 short-circuit).
        reviewService.approve(videoId, reviewer());

        // then — 상태 전이는 일어나지 않는다(이미 APPROVED, 멱등).
        verify(stts, never()).transitionTo(any());
        // and — 재검토 표시는 해제된다.
        verify(stts).clearNeedsRecheck();
        // and — 버전 스냅샷 등 기존 승인 파이프라인은 그대로 재사용된다.
        verify(versionService).commitApproved(eq(videoId), any());
        // and — 윈도우 존재 여부를 확인했다(구멍2 폴백 판정 지점을 실제로 거쳤는지).
        verify(controlNotifyDebounceStore).hasOpenWindow(eq(videoId));
        // and — Phase 7a-2(EVT-006) — 최초 승인 전용 ReviewApprovedEvent(→TASK_COMPLETED)는 발행하지
        //   않는다. TASK_MODIFIED 는 이미 보류 중이던 디바운스 윈도우가 표시 해제 후 자연히 flush 되며
        //   나간다(디바운스 경로는 ControlNotifyDebounceRecheckHoldIT 가 별도로 검증). 윈도우가 실제로
        //   있으므로(hasOpenWindow=true) 구멍2 폴백도 발동하지 않는다 — 중복 export 없음.
        verify(eventPublisher, never()).publishEvent(any(ReviewApprovedEvent.class));
    }

    @Test
    @DisplayName("재승인_시_축적_윈도우가_없으면_최초승인과_동일하게_ReviewApprovedEvent로_폴백한다")
    void approvedWithRecheckFlag_reapprovalFallsBackWhenNoOpenWindow() {
        // given — 이미 APPROVED, 재검토 표시(REVLT_YN='Y')는 서 있지만 축적 윈도우가 없다(리스너 실패·
        //   클레임 경합 등으로 표시·윈도우 짝이 깨진 상황 — 구멍2).
        Long videoId = 210L;
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        // V14 — 검수 승인은 비식별화완료여부가 Y 일 때만 통과한다. 실엔티티는 기본이 Y 라
        //   이 스텁이 곧 프로덕션 기본 상태이며, 그 축은 이 테스트의 관심사가 아니다.
        when(stts.isDeidentCompleted()).thenReturn(true);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_APPROVED);
        when(stts.needsRecheck()).thenReturn(true);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));
        when(controlNotifyDebounceStore.hasOpenWindow(videoId)).thenReturn(false);

        // when
        reviewService.approve(videoId, reviewer());

        // then — 상태 전이 없이(멱등) 표시는 해제되지만,
        verify(stts, never()).transitionTo(any());
        verify(stts).clearNeedsRecheck();
        // and — 짝이 깨졌으므로 최초 승인과 동일한 경로로 강제 재생성+통지가 폴백 발행된다(아무것도
        //   안 나가는 것보다 낫다 — TASK_COMPLETED 로 나가도 수용, 관제 409 자기치유가 뒤를 받친다).
        verify(eventPublisher).publishEvent(any(ReviewApprovedEvent.class));
    }

    @Test
    @DisplayName("재승인_시_축적_윈도우가_있으면_폴백을_발행하지_않는다_중복export_없음")
    void approvedWithRecheckFlag_reapprovalDoesNotFallBackWhenWindowExists() {
        // given — 표시·윈도우 짝이 맞는 정상 경로(명시적으로도 true 로 스텁).
        Long videoId = 220L;
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        // V14 — 검수 승인은 비식별화완료여부가 Y 일 때만 통과한다. 실엔티티는 기본이 Y 라
        //   이 스텁이 곧 프로덕션 기본 상태이며, 그 축은 이 테스트의 관심사가 아니다.
        when(stts.isDeidentCompleted()).thenReturn(true);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_APPROVED);
        when(stts.needsRecheck()).thenReturn(true);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));
        when(controlNotifyDebounceStore.hasOpenWindow(videoId)).thenReturn(true);

        // when
        reviewService.approve(videoId, reviewer());

        // then — 정상 경로이므로 폴백 이벤트가 발행되지 않는다(디바운스 자연 flush 에 맡긴다 — 폴백까지
        //   함께 나가면 export 가 두 번 돈다).
        verify(eventPublisher, never()).publishEvent(any(ReviewApprovedEvent.class));
    }

    @Test
    @DisplayName("IN_REVIEW에서_APPROVED로_전이하는_일반_재검수_사이클도_재검토_표시가_있으면_함께_해제된다")
    void legacyFullReviewCycle_stillClearsStaleRecheckFlag() {
        // given — WORKER 가 APPROVED→PENDING→IN_REVIEW 전체 재검수 사이클을 거쳐 다시 승인 시점에
        //   도달했다. 재검토 표시는 submit() 이 지우지 않아 이전 값(Y)이 그대로 남아 있을 수 있다.
        Long videoId = 300L;
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        // V14 — 검수 승인은 비식별화완료여부가 Y 일 때만 통과한다. 실엔티티는 기본이 Y 라
        //   이 스텁이 곧 프로덕션 기본 상태이며, 그 축은 이 테스트의 관심사가 아니다.
        when(stts.isDeidentCompleted()).thenReturn(true);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_IN_REVIEW);
        when(stts.needsRecheck()).thenReturn(true);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));

        // when
        reviewService.approve(videoId, reviewer());

        // then — 정상적으로 IN_REVIEW→APPROVED 전이가 일어나고(레거시 경로는 그대로),
        verify(stts).transitionTo(LsRawDataStatus.STTS_APPROVED);
        // and — 잔존 재검토 표시도 함께 정리된다(정리하지 않으면 이후 모든 수정 통지가 영구 보류된다).
        verify(stts).clearNeedsRecheck();
        // and — 이 경로는 최초 승인과 동일한 완전한 신규 확정이므로 기존 계약대로 TASK_COMPLETED 트리거.
        verify(eventPublisher).publishEvent(any(ReviewApprovedEvent.class));
    }

    @Test
    @DisplayName("재검토_표시_없는_최초_승인은_기존_동작과_완전히_동일하다")
    void freshApproval_withoutRecheckFlag_behavesExactlyAsBefore() {
        // given
        Long videoId = 400L;
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        // V14 — 검수 승인은 비식별화완료여부가 Y 일 때만 통과한다. 실엔티티는 기본이 Y 라
        //   이 스텁이 곧 프로덕션 기본 상태이며, 그 축은 이 테스트의 관심사가 아니다.
        when(stts.isDeidentCompleted()).thenReturn(true);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_IN_REVIEW);
        when(stts.needsRecheck()).thenReturn(false);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));

        // when
        reviewService.approve(videoId, reviewer());

        // then
        verify(stts).transitionTo(LsRawDataStatus.STTS_APPROVED);
        verify(stts, never()).clearNeedsRecheck();
        verify(eventPublisher).publishEvent(any(ReviewApprovedEvent.class));
    }
}
