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
import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.review.repository.IssueRepository;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import kr.co.cudo.authoring.review.service.ReviewService;
import kr.co.cudo.authoring.review.service.ReviewStateMachine;
import kr.co.cudo.authoring.user.repository.UserRepository;
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

        reviewService = new ReviewService(
                reviewRepository, issueRepository, authrtRepository, taskEventLogRepository,
                stateMachine, srcRepository, labelRepository, videoRepository, userRepository,
                objectMapper, eventPublisher);

        // enrichOne 헬퍼에서 N+1 회피 lookup 들이 빈 결과를 반환하도록
        when(videoRepository.findCctvNamesByRawSns(any())).thenReturn(Collections.emptyList());
        when(authrtRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(any(), any()))
                .thenReturn(Collections.emptyList());
        when(labelRepository.countLabelsByRawSnIn(any())).thenReturn(Collections.emptyList());
        when(videoRepository.findEventInfoByRawSns(any())).thenReturn(Collections.emptyList());
        when(taskEventLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
}
