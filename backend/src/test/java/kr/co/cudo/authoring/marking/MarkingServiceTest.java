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
import kr.co.cudo.authoring.marking.service.MarkingService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MarkingService 단위 테스트 (Mockito).
 */
@ExtendWith(MockitoExtension.class)
class MarkingServiceTest {

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

    @InjectMocks
    private MarkingService markingService;

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    private TokenClaims worker() {
        return new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    /** 비식별 완료(deIdntfYn='Y') 영상 — 마킹 가드 통과 대상. */
    private LsDataRaw stubRaw(Long rawSn, int durationSec) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip_" + rawSn + ".mp4",
                LocalDateTime.now(), durationSec);
        raw.markDeidentified("Y");
        return raw;
    }

    /** 비식별 미완료(deIdntfYn='N') 영상 — 마킹 가드에 걸려야 함. */
    private LsDataRaw stubRawNotDeidentified(Long rawSn, int durationSec) {
        return LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip_" + rawSn + ".mp4",
                LocalDateTime.now(), durationSec);
    }

    @Test
    @DisplayName("자동모드_마킹_생성_intervalFrames_기반_marks_자동생성")
    void createAutoMode() {
        // given
        Long rawSn = 1L;
        LsDataRaw raw = stubRaw(rawSn, 30);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        // intervalFrames=300 (30fps * 10sec)
        MarkingRequest req = new MarkingRequest("화재", "AUTO", 300, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then
        assertThat(result.markingMode()).isEqualTo("AUTO");
        assertThat(result.eventName()).isEqualTo("화재");
        assertThat(result.intervalFrames()).isEqualTo(300);
        // 30초 * 30fps = 900 totalFrames / 300 intervalFrames → 0, 300, 600 = 3개 마크
        // (BE-1 off-by-one 수정: 끝 경계 프레임 900 은 미포함 — frameIndex < totalFrames)
        assertThat(result.marks()).hasSize(3);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(0);
        assertThat(result.marks().get(2).frameIndex()).isEqualTo(600);
        assertThat(result.status()).isEqualTo(LsMarking.STATUS_PENDING);
        verify(markingRepository).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("수동모드_마킹_생성_marks_배열_저장")
    void createManualMode() {
        // given
        Long rawSn = 2L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        // WORKER(100) 본인 배정 영상 — 마킹 생성 허용 (CWE-639 가드 통과)
        when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(100L, LsTaskAssignment.TASK_LABELER, rawSn))
                .thenReturn(true);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        List<MarkItem> marks = List.of(
                new MarkItem(10, "00:05"),
                new MarkItem(50, "00:10")
        );
        MarkingRequest req = new MarkingRequest("침입", "MANUAL", null, marks);

        // when
        MarkingResponse result = markingService.create(rawSn, req, worker());

        // then
        assertThat(result.markingMode()).isEqualTo("MANUAL");
        assertThat(result.eventName()).isEqualTo("침입");
        assertThat(result.intervalFrames()).isNull();
        assertThat(result.marks()).hasSize(2);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(10);
        assertThat(result.marks().get(1).frameIndex()).isEqualTo(50);
        verify(markingRepository).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("마킹_조회_정상_영상별_목록")
    void listMarkings() {
        // given
        Long rawSn = 1L;
        LsDataRaw raw = stubRaw(rawSn, 30);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        LsMarking m1 = LsMarking.createAuto(rawSn, "화재", 5, "/path", "[]", 1L);
        LsMarking m2 = LsMarking.createManual(rawSn, "침입", "/path", "[]", 1L);
        when(markingRepository.findByRawSnOrderByRegDtDesc(rawSn)).thenReturn(List.of(m1, m2));

        // when
        List<MarkingResponse> result = markingService.list(rawSn, reviewer());

        // then
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("마킹_단건_조회_정상")
    void getSingleMarking() {
        // given
        Long rawSn = 1L;
        Long markingSn = 10L;
        LsMarking marking = LsMarking.createAuto(rawSn, "화재", 5, "/path", "[{\"frameIndex\":0,\"timestamp\":\"00:00\"}]", 1L);
        when(markingRepository.findById(markingSn)).thenReturn(Optional.of(marking));

        // when
        MarkingResponse result = markingService.get(rawSn, markingSn, reviewer());

        // then
        assertThat(result.eventName()).isEqualTo("화재");
        assertThat(result.rawSn()).isEqualTo(rawSn);
    }

    @Test
    @DisplayName("마킹_단건_조회_다른영상_마킹이면_FORBIDDEN")
    void getMarkingDifferentVideoForbidden() {
        // given
        Long rawSn = 1L;
        Long otherRawSn = 999L;
        Long markingSn = 10L;
        LsMarking marking = LsMarking.createAuto(otherRawSn, "화재", 5, "/path", "[]", 1L);
        when(markingRepository.findById(markingSn)).thenReturn(Optional.of(marking));

        // when / then
        assertThatThrownBy(() -> markingService.get(rawSn, markingSn, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("미존재_영상_마킹생성시_NOT_FOUND")
    void createNonExistentVideoNotFound() {
        // given
        Long rawSn = 999L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.empty());
        MarkingRequest req = new MarkingRequest("화재", "AUTO", 30, null);

        // when / then
        assertThatThrownBy(() -> markingService.create(rawSn, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("미존재_마킹_조회시_NOT_FOUND")
    void getNonExistentMarkingNotFound() {
        // given
        Long rawSn = 1L;
        Long markingSn = 999L;
        when(markingRepository.findById(markingSn)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> markingService.get(rawSn, markingSn, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("마킹_삭제_정상")
    void deleteMarking() {
        // given
        Long rawSn = 1L;
        Long markingSn = 10L;
        LsMarking marking = LsMarking.createAuto(rawSn, "화재", 5, "/path", "[]", 1L);
        when(markingRepository.findById(markingSn)).thenReturn(Optional.of(marking));

        // when
        markingService.delete(rawSn, markingSn);

        // then
        verify(markingRepository).delete(marking);
    }

    @Test
    @DisplayName("마킹_삭제_다른영상_마킹이면_FORBIDDEN")
    void deleteMarkingDifferentVideoForbidden() {
        // given
        Long rawSn = 1L;
        Long otherRawSn = 999L;
        Long markingSn = 10L;
        LsMarking marking = LsMarking.createAuto(otherRawSn, "화재", 5, "/path", "[]", 1L);
        when(markingRepository.findById(markingSn)).thenReturn(Optional.of(marking));

        // when / then
        assertThatThrownBy(() -> markingService.delete(rawSn, markingSn))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("마킹_생성시_MarkingCompletedEvent_발행")
    void createPublishesMarkingCompletedEvent() {
        // given
        Long rawSn = 10L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkingRequest req = new MarkingRequest("화재", "AUTO", 300, null);

        // when
        markingService.create(rawSn, req, reviewer());

        // then
        verify(eventPublisher).publishEvent(any(MarkingCompletedEvent.class));
    }

    // ── intervalFrames 변경 테스트 ──

    @Test
    @DisplayName("자동마킹_프레임간격_30프레임_120초영상_marks_생성")
    void autoMarking_intervalFrames30_120sec() {
        // given
        Long rawSn = 20L;
        LsDataRaw raw = stubRaw(rawSn, 120);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        // intervalFrames=30 (1초 단위)
        MarkingRequest req = new MarkingRequest("화재", "AUTO", 30, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then — 120초 * 30fps = 3600 totalFrames, 0..3570 (3600/30 = 120개)
        // (BE-1 off-by-one 수정: 끝 경계 프레임 3600 은 미포함)
        assertThat(result.marks()).hasSize(120);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(0);
        assertThat(result.marks().get(1).frameIndex()).isEqualTo(30);
        assertThat(result.marks().get(119).frameIndex()).isEqualTo(3570);
    }

    @Test
    @DisplayName("자동마킹_프레임간격_60프레임_120초영상_marks_생성")
    void autoMarking_intervalFrames60_120sec() {
        // given
        Long rawSn = 21L;
        LsDataRaw raw = stubRaw(rawSn, 120);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        // intervalFrames=60 (2초 단위)
        MarkingRequest req = new MarkingRequest("침입", "AUTO", 60, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then — 120초 * 30fps = 3600 totalFrames, 0..3540 (3600/60 = 60개)
        // (BE-1 off-by-one 수정: 끝 경계 프레임 3600 은 미포함)
        assertThat(result.marks()).hasSize(60);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(0);
        assertThat(result.marks().get(1).frameIndex()).isEqualTo(60);
        assertThat(result.marks().get(59).frameIndex()).isEqualTo(3540);
    }

    // ── I4 (CWE-639) 수평 권한 상승 가드 ──

    @Test
    @DisplayName("I4_미배정_WORKER_마킹생성_FORBIDDEN")
    void createUnassignedWorkerForbidden() {
        // given — WORKER(100) 가 미배정 영상에 마킹 생성 시도
        Long rawSn = 7L;
        when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(100L, LsTaskAssignment.TASK_LABELER, rawSn))
                .thenReturn(false);
        MarkingRequest req = new MarkingRequest("화재", "AUTO", 300, null);

        // when / then — 영상 조회 이전에 FORBIDDEN
        assertThatThrownBy(() -> markingService.create(rawSn, req, worker()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("I4_미배정_WORKER_마킹목록조회_FORBIDDEN")
    void listUnassignedWorkerForbidden() {
        // given — WORKER(100) 가 미배정 영상의 마킹 목록 조회 시도
        Long rawSn = 8L;
        when(assignmentRepository.existsByUserNoAndTaskTypeCdAndRawDataId(100L, LsTaskAssignment.TASK_LABELER, rawSn))
                .thenReturn(false);

        // when / then
        assertThatThrownBy(() -> markingService.list(rawSn, worker()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ── 비식별 완료 가드 ("마킹은 비식별 완료 영상 대상" — CLAUDE.md) ──

    @Test
    @DisplayName("비식별_미완료_영상_마킹생성시_PRECONDITION_FAILED")
    void createOnNonDeidentifiedVideoRejected() {
        // given — deIdntfYn='N' 영상에 REVIEWER 가 마킹 생성 시도
        Long rawSn = 30L;
        LsDataRaw raw = stubRawNotDeidentified(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        MarkingRequest req = new MarkingRequest("화재", "AUTO", 300, null);

        // when / then
        assertThatThrownBy(() -> markingService.create(rawSn, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("비식별_완료_영상_마킹생성_정상_가드_통과")
    void createOnDeidentifiedVideoPasses() {
        // given — deIdntfYn='Y' 영상은 가드 통과
        Long rawSn = 31L;
        LsDataRaw raw = stubRaw(rawSn, 30);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkingRequest req = new MarkingRequest("화재", "AUTO", 300, null);

        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        assertThat(result.markingMode()).isEqualTo("AUTO");
        verify(markingRepository).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("자동모드_intervalFrames_0이하_INVALID_INPUT")
    void autoMode_intervalFramesZero_invalidInput() {
        // given
        Long rawSn = 22L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        MarkingRequest req = new MarkingRequest("화재", "AUTO", 0, null);

        // when / then
        assertThatThrownBy(() -> markingService.create(rawSn, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ── BE-1: generateAutoMarks 경계/널 처리 (public create() 경로로 검증) ──

    @Test
    @DisplayName("자동마킹_durationSec_null이면_INVALID_INPUT_단건퇴화방지")
    void autoMarking_nullDuration_invalidInput() {
        // given — durationSec=null 영상에 자동 마킹 시도 (과거: totalFrames=0 → frame 0 단건만 생성되던 퇴화)
        Long rawSn = 40L;
        LsDataRaw raw = stubRaw(rawSn, 30);
        org.springframework.test.util.ReflectionTestUtils.setField(raw, "durationSec", null);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        MarkingRequest req = new MarkingRequest("화재", "AUTO", 300, null);

        // when / then — 퇴화(단건 생성) 대신 명시적 거부
        assertThatThrownBy(() -> markingService.create(rawSn, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        // 퇴화 마킹이 저장되지 않았음을 보장
        verify(markingRepository, never()).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("자동마킹_durationSec_0이하면_INVALID_INPUT")
    void autoMarking_zeroDuration_invalidInput() {
        // given — durationSec=0 영상
        Long rawSn = 41L;
        LsDataRaw raw = stubRaw(rawSn, 0);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        MarkingRequest req = new MarkingRequest("화재", "AUTO", 300, null);

        // when / then
        assertThatThrownBy(() -> markingService.create(rawSn, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(markingRepository, never()).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("자동마킹_끝경계프레임_미포함_off_by_one_검증")
    void autoMarking_excludesBoundaryFrame() {
        // given — 10초 * 30fps = 300 totalFrames, interval=300 → frame 0 만, 끝 경계 300 미포함
        Long rawSn = 42L;
        LsDataRaw raw = stubRaw(rawSn, 10);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkingRequest req = new MarkingRequest("화재", "AUTO", 300, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then — frameIndex 300(=totalFrames) 은 포함되지 않는다 (frameIndex < totalFrames)
        assertThat(result.marks()).hasSize(1);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(0);
        assertThat(result.marks()).noneMatch(m -> m.frameIndex() == 300);
    }
}
