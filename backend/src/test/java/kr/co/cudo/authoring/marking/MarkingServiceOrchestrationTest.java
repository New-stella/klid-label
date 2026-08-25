package kr.co.cudo.authoring.marking;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.dto.MarkingResponse;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.marking.service.MarkingPrecheckReader;
import kr.co.cudo.authoring.marking.service.MarkingService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoDurationResolver;
import kr.co.cudo.authoring.sysconfig.service.VerificationEventQuestionResolver;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MarkingService <b>비트랜잭션 오케스트레이션</b> 단위 테스트 (HIGH 회귀 봉인 — CWE-862/400, OWASP API4).
 *
 * <p><b>핵심 불변식:</b> 고비용 ffprobe(영상 길이 해석기 {@link VideoDurationResolver})는 <b>사전 인가·
 * 프리컨디션({@link MarkingPrecheckReader}) 통과 이후에만</b> 트리거된다. 사전확인이 거부(FORBIDDEN/
 * PRECONDITION_FAILED 등)하면 <b>프로브가 한 번도 호출되지 않아야</b> 한다(미배정 WORKER 가 유효 역할 토큰만으로
 * 403 이전에 임의 rawSn 프로브를 무제한 트리거하는 리소스 소모 표면 제거). 프로브 호출 0회를 단언해 회귀를 봉인한다.
 *
 * <p>persist 자체 동작(marks 산출/가드 이중화/경계)은 {@link MarkingServiceTest}(4-arg persist 직접 호출)가
 * 담당하며, 본 테스트는 오케스트레이션의 <b>순서·트리거 계약</b>에 집중한다. 컨테이너 밖 단위 테스트라
 * {@code self} 프록시가 null → persist 는 {@code this} 로 폴백 호출된다.
 */
@ExtendWith(MockitoExtension.class)
class MarkingServiceOrchestrationTest {

    @Mock
    private LsMarkingRepository markingRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private LsTaskAssignmentRepository assignmentRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private VideoFpsResolver fpsResolver;

    /**
     * 검증 이벤트 유형 조달처 — 스텁하지 않으면 {@code null} 행을 돌려주므로 유형 미수신 경로가 되고,
     * 그 경로에서 마킹은 <b>질문 없이 그대로 저장</b>된다(질문 부재는 거부 사유가 아니다).
     */
    @Mock
    private IngestSourceRepository ingestSourceRepository;

    @Mock
    private VerificationEventQuestionResolver questionResolver;

    @Mock
    private MarkingPrecheckReader precheckReader;

    @Mock
    private VideoDurationResolver durationResolver;

    @InjectMocks
    private MarkingService markingService;

    @BeforeEach
    void setUpFpsDefault() {
        lenient().when(fpsResolver.resolveFps(anyLong())).thenReturn(VideoFpsResolver.DEFAULT_FPS);
    }

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    private TokenClaims worker() {
        return new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    /** 비식별 완료(deIdntfYn='Y') + MARKING_READY 영상 — 가드 통과 대상. */
    private LsDataRaw readyRaw(Long rawSn, int durationSec) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip_" + rawSn + ".mp4",
                LocalDateTime.now(), durationSec);
        raw.markDeidentified("Y");
        raw.markMarkingReady();
        return raw;
    }

    // ── HIGH 회귀 봉인: 사전 인가·프리컨디션 실패 시 프로브 미트리거 ──

    @Test
    @DisplayName("미배정_WORKER_AUTO요청_프로브_미트리거되고_FORBIDDEN")
    void unassignedWorkerAuto_doesNotTriggerProbe_forbidden() {
        // given — 사전확인이 미배정 WORKER 를 FORBIDDEN 으로 거부(프로브 이전 단계)
        // (TokenClaims 는 record equals 가 exp Instant 를 포함하므로 동일 인스턴스를 stub·호출에 공유한다.)
        Long rawSn = 1L;
        TokenClaims actor = worker();
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정된 영상의 마킹만 접근할 수 있습니다."))
                .when(precheckReader).precheck(rawSn, actor);
        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when / then — 403, 그리고 ffprobe(리졸버)는 한 번도 호출되지 않는다(리소스 소모 표면 제거).
        assertThatThrownBy(() -> markingService.create(rawSn, req, actor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(durationResolver, never()).resolveDurationSec(any());
        verify(markingRepository, never()).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("비식별_미완료_영상_AUTO요청_프로브_이전에_PRECONDITION_FAILED_프로브_미호출")
    void nonDeidentifiedAuto_precondition_beforeProbe() {
        // given — 사전확인이 비식별 미완료를 PRECONDITION_FAILED 로 거부
        Long rawSn = 2L;
        TokenClaims actor = reviewer();
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "비식별이 완료된 영상에서만 마킹할 수 있습니다."))
                .when(precheckReader).precheck(rawSn, actor);
        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when / then
        assertThatThrownBy(() -> markingService.create(rawSn, req, actor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        verify(durationResolver, never()).resolveDurationSec(any());
    }

    @Test
    @DisplayName("이미처리_비MARKING_READY_영상_AUTO요청_프로브_이전에_PRECONDITION_FAILED_프로브_미호출")
    void nonMarkingReadyAuto_precondition_beforeProbe() {
        // given — 사전확인이 이미 처리된 영상(PROCESSING/COMPLETED)을 PRECONDITION_FAILED 로 거부
        Long rawSn = 3L;
        TokenClaims actor = reviewer();
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "이미 처리된 영상은 재마킹할 수 없습니다."))
                .when(precheckReader).precheck(rawSn, actor);
        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when / then
        assertThatThrownBy(() -> markingService.create(rawSn, req, actor))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        verify(durationResolver, never()).resolveDurationSec(any());
    }

    // ── 정상 경로: 사전확인 통과 후에만 프로브 트리거 + 마킹 성공 ──

    @Test
    @DisplayName("정상_REVIEWER_AUTO_사전확인통과후_프로브호출되고_마킹성공")
    void reviewerAuto_probeAfterPrecheck_success() {
        // given — 사전확인 통과 → 프로브가 60초 해석 → persist 성공
        Long rawSn = 4L;
        TokenClaims actor = reviewer();
        doNothing().when(precheckReader).precheck(rawSn, actor);
        when(durationResolver.resolveDurationSec(rawSn)).thenReturn(60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(readyRaw(rawSn, 60)));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));
        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, actor);

        // then — 프로브 호출 + 마킹 생성. 순서: precheck → resolveDurationSec → save.
        verify(precheckReader).precheck(rawSn, actor);
        verify(durationResolver).resolveDurationSec(rawSn);
        assertThat(result.markingMode()).isEqualTo("AUTO");
        verify(markingRepository).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("정상_배정WORKER_AUTO_사전확인통과후_프로브호출되고_마킹성공")
    void assignedWorkerAuto_probeAfterPrecheck_success() {
        // given — 사전확인 통과. persist 의 방어적 인가 재확인을 위해 배정 stub(defense-in-depth).
        Long rawSn = 5L;
        TokenClaims actor = worker();
        doNothing().when(precheckReader).precheck(rawSn, actor);
        when(durationResolver.resolveDurationSec(rawSn)).thenReturn(30);
        when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(100L, LsTaskAssignment.TASK_LABELER, rawSn))
                .thenReturn(true);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(readyRaw(rawSn, 30)));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));
        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, actor);

        // then
        verify(durationResolver).resolveDurationSec(rawSn);
        assertThat(result.markingMode()).isEqualTo("AUTO");
        verify(markingRepository).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("MANUAL_모드는_사전확인통과해도_프로브_미트리거하고_마킹성공")
    void manualMode_noProbe_success() {
        // given — MANUAL 은 duration 불필요 → 프로브 미호출
        Long rawSn = 6L;
        TokenClaims actor = reviewer();
        doNothing().when(precheckReader).precheck(rawSn, actor);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(readyRaw(rawSn, 60)));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));
        MarkingRequest req = new MarkingRequest("MANUAL", null, List.of(new MarkItem(0, "00:00")));

        // when
        MarkingResponse result = markingService.create(rawSn, req, actor);

        // then — 사전확인은 하되 프로브는 트리거하지 않는다.
        verify(precheckReader).precheck(rawSn, actor);
        verify(durationResolver, never()).resolveDurationSec(any());
        assertThat(result.markingMode()).isEqualTo("MANUAL");
    }
}
