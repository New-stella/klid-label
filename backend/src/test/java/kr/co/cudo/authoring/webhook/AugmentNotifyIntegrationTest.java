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
 * <p>증강으로 생성된 새 영상(parentRawSn != null)도 기존 검수 흐름을 따르므로,
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

        reviewService = new ReviewService(
                reviewRepository, issueRepository, authrtRepository, taskEventLogRepository,
                stateMachine, srcRepository, labelRepository, videoRepository, userRepository,
                objectMapper, eventPublisher);

        // enrichOne lookup stubs — 빈 결과
        when(videoRepository.findCctvNamesByRawSns(any())).thenReturn(Collections.emptyList());
        when(authrtRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(any(), any()))
                .thenReturn(Collections.emptyList());
        when(labelRepository.countLabelsByRawSnIn(any())).thenReturn(Collections.emptyList());
        when(videoRepository.findEventInfoByRawSns(any())).thenReturn(Collections.emptyList());
        when(taskEventLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("증강_새영상_검수_완료시_ReviewApprovedEvent_새영상_rawSn으로_발행")
    void 증강_새영상_검수_완료시_ReviewApprovedEvent_발행() {
        // given — 증강으로 생성된 새 영상 (rawSn=9001, parentRawSn=100)
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
