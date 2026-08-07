package kr.co.cudo.authoring.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import kr.co.cudo.authoring.review.service.ReviewService;
import kr.co.cudo.authoring.review.service.ReviewStateMachine;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.version.service.VersionService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 -- ReviewService.approve() 가 ReviewApprovedEvent 를 발행하는지 검증.
 */
class ReviewServiceEventPublishTest {

    private ReviewRepository reviewRepository;
    private ApplicationEventPublisher eventPublisher;
    private VersionService versionService;
    private DatasetVideoMetaSnapshotService datasetVideoMetaSnapshotService;
    private EvntAnnoReviewService evntAnnoReviewService;
    private MetaService metaService;
    private LabelAccessGuard accessGuard;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        IssueRepository issueRepository = mock(IssueRepository.class);
        LsTaskAssignmentRepository authrtRepository = mock(LsTaskAssignmentRepository.class);
        LsTaskEventLogRepository taskEventLogRepository = mock(LsTaskEventLogRepository.class);
        ReviewStateMachine stateMachine = mock(ReviewStateMachine.class);
        LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
        LsDataLblRepository labelRepository = mock(LsDataLblRepository.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        eventPublisher = mock(ApplicationEventPublisher.class);
        versionService = mock(VersionService.class);
        datasetVideoMetaSnapshotService = mock(DatasetVideoMetaSnapshotService.class);
        evntAnnoReviewService = mock(EvntAnnoReviewService.class);
        metaService = mock(MetaService.class);
        // 비식별 누락 신고 게이트 — 목 기본값(아무 것도 안 함)이 "신고 없음" 통과를 뜻한다.
        accessGuard = mock(LabelAccessGuard.class);
        // Phase 7a-2b — 재승인 폴백 판정용. 본 테스트는 재승인(isReapproval) 경로를 타지 않으므로
        // (항상 최초 승인, stts.getDataSttsCd()=IN_REVIEW) 실제로 호출되지 않는다.
        ControlNotifyDebounceStore controlNotifyDebounceStore = mock(ControlNotifyDebounceStore.class);

        reviewService = new ReviewService(
                reviewRepository,
                // 목록 검색/집계 전용 — 본 테스트(단건 승인/반려 경로)에서는 호출되지 않는다.
                mock(kr.co.cudo.authoring.review.repository.ReviewQueryRepository.class),
                issueRepository, authrtRepository, taskEventLogRepository,
                stateMachine, srcRepository, labelRepository, videoRepository,
                new kr.co.cudo.authoring.user.service.UserNameResolver(userRepository),
                objectMapper, eventPublisher, versionService, datasetVideoMetaSnapshotService,
                evntAnnoReviewService, metaService, accessGuard, controlNotifyDebounceStore);

        // enrichOne 헬퍼에서 N+1 회피 lookup 들이 빈 결과를 반환하도록
        when(videoRepository.findCctvNamesByRawSns(any())).thenReturn(Collections.emptyList());
        when(authrtRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(any(), any()))
                .thenReturn(Collections.emptyList());
        when(labelRepository.countLabelsByRawSnIn(any())).thenReturn(Collections.emptyList());
        // D-ISSUE-04 — 승인 사전 게이트(라벨 0건이면 409). 이 테스트들은 정상 승인 경로 검증이므로
        // 라벨이 있는 영상으로 둔다(게이트 자체는 ReviewApproveLabelGateIT 가 검증).
        when(labelRepository.existsAnyByRawSn(anyLong())).thenReturn(true);
        when(videoRepository.findEventInfoByRawSns(any())).thenReturn(Collections.emptyList());
        when(taskEventLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // commitApproved 는 스냅샷 집계 결과(record)를 반환 — 기본은 스킵 없음.
        when(versionService.commitApproved(anyLong(), any()))
                .thenReturn(new VersionService.CommitResult(1, 0));
    }

    @Test
    @DisplayName("approve_성공시_ReviewApprovedEvent_발행됨")
    void approve_publishesReviewApprovedEvent() {
        // given
        Long videoId = 100L;
        TokenClaims actor = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_IN_REVIEW);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));

        // when
        reviewService.approve(videoId, actor);

        // then
        ArgumentCaptor<ReviewApprovedEvent> captor = ArgumentCaptor.forClass(ReviewApprovedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ReviewApprovedEvent event = captor.getValue();
        assertThat(event.rawSn()).isEqualTo(videoId);
        assertThat(event.reviewerNo()).isEqualTo(1L);
        assertThat(event.approvedAt()).isNotNull();
    }

    @Test
    @DisplayName("approve_성공시_영상_단위_버전_스냅샷_commitApproved_호출됨")
    void approve_triggersVersionSnapshot() {
        // given
        Long videoId = 100L;
        TokenClaims actor = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_IN_REVIEW);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));

        // when
        reviewService.approve(videoId, actor);

        // then — SFR-08: 검수 승인 시점에 영상(rawSn) 단위 학습데이터 버전 스냅샷 생성.
        verify(versionService).commitApproved(eq(videoId), eq(actor));
    }

    @Test
    @DisplayName("approve_성공시_event_annotation_자동승인이_materialize_직전에_호출됨")
    void approve_autoApprovesEventAnnotationBeforeMaterialize() {
        // given
        Long videoId = 100L;
        TokenClaims actor = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_IN_REVIEW);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));

        // when
        reviewService.approve(videoId, actor);

        // then — F: event_annotation 자동 승인 → 시계열 메타 자동 확정 → 통합 메타 동결(materialize) 순서로 호출.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                evntAnnoReviewService, metaService, datasetVideoMetaSnapshotService);
        inOrder.verify(evntAnnoReviewService).autoApproveOnVideoApproval(eq(videoId), eq(actor));
        inOrder.verify(metaService).autoApproveOnVideoApproval(eq(videoId), eq(actor));
        inOrder.verify(datasetVideoMetaSnapshotService).materialize(eq(videoId));
    }

    @Test
    @DisplayName("approve_성공시_시계열메타_검토행_자동확정이_materialize_직전에_호출됨")
    void approve_autoApprovesTimeseriesMetaBeforeMaterialize() {
        // given
        Long videoId = 100L;
        TokenClaims actor = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_IN_REVIEW);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));

        // when
        reviewService.approve(videoId, actor);

        // then — 버그 F 완성: 시계열 메타 검토행 자동 확정 → 그 뒤 materialize(V_COMPLETED_META 누락 방지).
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                metaService, datasetVideoMetaSnapshotService);
        inOrder.verify(metaService).autoApproveOnVideoApproval(eq(videoId), eq(actor));
        inOrder.verify(datasetVideoMetaSnapshotService).materialize(eq(videoId));
    }

    @Test
    @DisplayName("approve_성공시_통합메타_동결_materialize_호출됨")
    void approve_triggersDatasetMetaMaterialize() {
        // given
        Long videoId = 100L;
        TokenClaims actor = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_IN_REVIEW);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));

        // when
        reviewService.approve(videoId, actor);

        // then — Phase 2: 승인 트랜잭션 내에서 통합 메타 동결(materialize) 호출.
        verify(datasetVideoMetaSnapshotService).materialize(eq(videoId));
    }

    @Test
    @DisplayName("materialize_실패시_approve_예외_전파_동일트랜잭션_롤백")
    void approve_rollsBackWhenMaterializeFails() {
        // given — 통합 메타 동결이 실패(런타임 예외)한다.
        Long videoId = 100L;
        TokenClaims actor = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_IN_REVIEW);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));
        org.mockito.Mockito.doThrow(new IllegalStateException("materialize boom"))
                .when(datasetVideoMetaSnapshotService).materialize(eq(videoId));

        // when / then — 예외가 승인 메서드 밖으로 전파되어야 한다(동일 트랜잭션이 함께 롤백됨).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> reviewService.approve(videoId, actor))
                .isInstanceOf(IllegalStateException.class);
        // 승인 완료 이벤트는 materialize 이후 단계라 발행되지 않는다(부분 확정 방지).
        org.mockito.Mockito.verify(eventPublisher, org.mockito.Mockito.never())
                .publishEvent(any(ReviewApprovedEvent.class));
    }

    @Test
    @DisplayName("M2_스냅샷_스킵_발생시_WARN_로깅_경로_타고_승인은_정상_성공")
    void approve_withSnapshotSkips_logsWarnAndStillSucceeds() {
        // given — commitApproved 가 스킵 2건을 보고 (라벨 있으나 직렬화/크기초과로 누락된 프레임).
        Long videoId = 100L;
        TokenClaims actor = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_IN_REVIEW);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));
        when(versionService.commitApproved(eq(videoId), eq(actor)))
                .thenReturn(new VersionService.CommitResult(3, 2));

        // ReviewService 로거에 인메모리 appender 부착 — WARN 발생 가시화 검증.
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReviewService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // when — 스킵이 있어도 승인은 정상 성공해야 한다(기존 동작 유지).
            reviewService.approve(videoId, actor);

            // then — 스냅샷 스킵 WARN 1건이 기록되고, 본문/PII 없이 영상 ID + 스킵 프레임 수만 노출한다.
            boolean warned = appender.list.stream()
                    .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                            && e.getFormattedMessage().contains("snapshot skips")
                            && e.getFormattedMessage().contains("skippedFrames=2"));
            assertThat(warned).as("스냅샷 스킵 발생 시 WARN 로그가 남아야 한다").isTrue();
            // 승인 이벤트는 정상 발행 — 승인 자체는 계속 성공.
            verify(eventPublisher).publishEvent(any(ReviewApprovedEvent.class));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("M2_스냅샷_스킵_없으면_WARN_미발생")
    void approve_withoutSnapshotSkips_noWarn() {
        // given — 스킵 0건 (기본 setUp 스텁).
        Long videoId = 200L;
        TokenClaims actor = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(stts.getRawDataId()).thenReturn(videoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_IN_REVIEW);
        when(reviewRepository.findByRawDataId(videoId)).thenReturn(Optional.of(stts));
        when(versionService.commitApproved(eq(videoId), eq(actor)))
                .thenReturn(new VersionService.CommitResult(5, 0));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ReviewService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // when
            reviewService.approve(videoId, actor);

            // then — 스킵이 없으므로 스냅샷 스킵 WARN 미발생.
            boolean warned = appender.list.stream()
                    .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                            && e.getFormattedMessage().contains("snapshot skips"));
            assertThat(warned).as("스킵이 없으면 스냅샷 스킵 WARN 이 없어야 한다").isFalse();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
