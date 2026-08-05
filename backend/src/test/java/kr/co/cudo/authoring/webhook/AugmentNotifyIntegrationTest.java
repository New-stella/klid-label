package kr.co.cudo.authoring.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H3 — 증강 새 영상의 검수 완료 시 관제서버 통지(ReviewApprovedEvent) 발행 검증.
 *
 * <p>증강으로 생성된 새 영상(orgnlRawSn != null)도 기존 검수 흐름을 따르므로,
 * approve() 호출 시 ReviewApprovedEvent 가 <b>새 영상의 rawSn</b>으로 발행되어야 한다.
 * 별도 코드 추가 없이 기존 흐름이 자동 적용되는지 확인한다.
 */
class AugmentNotifyIntegrationTest {

    private ReviewRepository reviewRepository;
    private ApplicationEventPublisher eventPublisher;
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
        VersionService versionService = mock(VersionService.class);
        kr.co.cudo.authoring.dataset.service.DatasetVideoMetaSnapshotService datasetVideoMetaSnapshotService =
                mock(kr.co.cudo.authoring.dataset.service.DatasetVideoMetaSnapshotService.class);
        kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService evntAnnoReviewService =
                mock(kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService.class);
        kr.co.cudo.authoring.meta.service.MetaService metaService =
                mock(kr.co.cudo.authoring.meta.service.MetaService.class);

        reviewService = new ReviewService(
                reviewRepository,
                // 목록 검색/집계 전용 — 본 테스트(단건 승인/반려 경로)에서는 호출되지 않는다.
                mock(kr.co.cudo.authoring.review.repository.ReviewQueryRepository.class),
                issueRepository, authrtRepository, taskEventLogRepository,
                stateMachine, srcRepository, labelRepository, videoRepository,
                new kr.co.cudo.authoring.user.service.UserNameResolver(userRepository),
                objectMapper, eventPublisher, versionService, datasetVideoMetaSnapshotService,
                evntAnnoReviewService, metaService);

        // enrichOne lookup stubs — 빈 결과
        when(videoRepository.findCctvNamesByRawSns(any())).thenReturn(Collections.emptyList());
        when(authrtRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(any(), any()))
                .thenReturn(Collections.emptyList());
        when(labelRepository.countLabelsByRawSnIn(any())).thenReturn(Collections.emptyList());
        // D-ISSUE-04 — 승인 사전 게이트(라벨 0건이면 409). 증강 영상은 원본 라벨을 복사받아 보유하므로
        // 정상 승인 경로를 재현하려면 라벨 보유 상태여야 한다(게이트 자체는 ReviewApproveLabelGateIT 담당).
        when(labelRepository.existsAnyByRawSn(any())).thenReturn(true);
        when(videoRepository.findEventInfoByRawSns(any())).thenReturn(Collections.emptyList());
        when(taskEventLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // commitApproved 는 스냅샷 집계 결과(record)를 반환 — 기본은 스킵 없음(M-2).
        when(versionService.commitApproved(any(), any()))
                .thenReturn(new VersionService.CommitResult(1, 0));
    }

    @Test
    @DisplayName("증강_새영상_검수_완료시_ReviewApprovedEvent_새영상_rawSn으로_발행")
    void 증강_새영상_검수_완료시_ReviewApprovedEvent_발행() {
        // given — 증강으로 생성된 새 영상 (rawSn=9001, orgnlRawSn=100)
        Long augmentedVideoId = 9001L;
        TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(3600));

        // LsRawDataStatus 는 rawSn 기준으로 관리됨 — 증강 영상도 고유 rawSn 보유
        LsRawDataStatus stts = mock(LsRawDataStatus.class);
        when(stts.getRawDataId()).thenReturn(augmentedVideoId);
        when(stts.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_IN_REVIEW);
        when(reviewRepository.findByRawDataId(augmentedVideoId)).thenReturn(Optional.of(stts));

        // when — REVIEWER 가 증강 영상을 승인
        reviewService.approve(augmentedVideoId, reviewer);

        // then — ReviewApprovedEvent 가 증강 영상의 rawSn(9001)으로 발행됨
        ArgumentCaptor<ReviewApprovedEvent> captor = ArgumentCaptor.forClass(ReviewApprovedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        ReviewApprovedEvent event = captor.getValue();

        assertThat(event.rawSn()).isEqualTo(augmentedVideoId);
        assertThat(event.reviewerNo()).isEqualTo(1L);
        assertThat(event.approvedAt()).isNotNull();
    }
}
