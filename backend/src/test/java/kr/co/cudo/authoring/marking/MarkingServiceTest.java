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
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
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
import static org.mockito.Mockito.lenient;
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

    @Mock
    private VideoFpsResolver fpsResolver;

    @InjectMocks
    private MarkingService markingService;

    /**
     * 기본: fps 미상 → 30.0 폴백. 기존 자동마킹 테스트가 30fps 고정 가정으로 작성됐으므로 이 기본 stub 이
     * 회귀를 방지한다. 실 fps 검증 테스트는 특정 rawSn 에 대해 개별 override 한다. (lenient — 자동마킹에
     * 도달하지 않는 가드 테스트가 있어 strict 불필요 stub 예외 회피.)
     */
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

    /** 비식별 완료(deIdntfYn='Y') + 마킹 준비(MARKING_READY) 영상 — 마킹 가드 통과 대상. */
    private LsDataRaw stubRaw(Long rawSn, int durationSec) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip_" + rawSn + ".mp4",
                LocalDateTime.now(), durationSec);
        raw.markDeidentified("Y");
        raw.markMarkingReady();
        return raw;
    }

    /** 비식별 완료 + 지정한 배치 단계(DATA_STTS_CD) 영상 — 배치 단계 가드 검증용. */
    private LsDataRaw stubRawWithStage(Long rawSn, int durationSec, String dataSttsCd) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip_" + rawSn + ".mp4",
                LocalDateTime.now(), durationSec);
        raw.markDeidentified("Y");
        raw.changeStatus(dataSttsCd);
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
        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then
        assertThat(result.markingMode()).isEqualTo("AUTO");
        // 이벤트명은 요청이 아니라 영상의 evntTypeCd(EVT-A)에서 자동 소싱된다.
        assertThat(result.eventName()).isEqualTo("EVT-A");
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
        MarkingRequest req = new MarkingRequest("MANUAL", null, marks);

        // when
        MarkingResponse result = markingService.create(rawSn, req, worker());

        // then
        assertThat(result.markingMode()).isEqualTo("MANUAL");
        // 이벤트명은 영상의 evntTypeCd(EVT-A)에서 자동 소싱된다.
        assertThat(result.eventName()).isEqualTo("EVT-A");
        assertThat(result.intervalFrames()).isNull();
        assertThat(result.marks()).hasSize(2);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(10);
        assertThat(result.marks().get(1).frameIndex()).isEqualTo(50);
        verify(markingRepository).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("미존재_영상_마킹생성시_NOT_FOUND")
    void createNonExistentVideoNotFound() {
        // given
        Long rawSn = 999L;
        when(videoRepository.findById(rawSn)).thenReturn(Optional.empty());
        MarkingRequest req = new MarkingRequest("AUTO", 30, null);

        // when / then
        assertThatThrownBy(() -> markingService.create(rawSn, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("마킹_생성시_MarkingCompletedEvent_발행")
    void createPublishesMarkingCompletedEvent() {
        // given
        Long rawSn = 10L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

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
        MarkingRequest req = new MarkingRequest("AUTO", 30, null);

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
        MarkingRequest req = new MarkingRequest("AUTO", 60, null);

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
        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when / then — 영상 조회 이전에 FORBIDDEN
        assertThatThrownBy(() -> markingService.create(rawSn, req, worker()))
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

        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

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

        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        assertThat(result.markingMode()).isEqualTo("AUTO");
        verify(markingRepository).save(any(LsMarking.class));
    }

    // ── 배치 단계(LsDataRaw.DATA_STTS_CD) 역전 차단 가드 (fail-fast) ──

    @Test
    @DisplayName("이미_COMPLETED_인_영상_마킹_생성_요청은_PRECONDITION_FAILED")
    void createOnCompletedVideoRejected() {
        // given — 배치 완료(COMPLETED) 영상에 마킹 생성 시도 (직접 호출로 도달 가능한 역전 경로)
        Long rawSn = 70L;
        LsDataRaw raw = stubRawWithStage(rawSn, 60, LsDataRaw.DATA_STTS_COMPLETED);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when / then — 재마킹 거부, 마킹 저장 안 됨
        assertThatThrownBy(() -> markingService.create(rawSn, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        verify(markingRepository, never()).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("이미_PROCESSING_인_영상_마킹_생성_요청은_PRECONDITION_FAILED")
    void createOnProcessingVideoRejected() {
        // given — 배치 진행 중(PROCESSING) 영상
        Long rawSn = 71L;
        LsDataRaw raw = stubRawWithStage(rawSn, 60, LsDataRaw.DATA_STTS_PROCESSING);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when / then
        assertThatThrownBy(() -> markingService.create(rawSn, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        verify(markingRepository, never()).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("MARKING_READY_영상_마킹_생성은_성공")
    void createOnMarkingReadyVideoSucceeds() {
        // given — 비식별 완료 + MARKING_READY 영상의 최초 마킹(회귀 가드)
        Long rawSn = 72L;
        LsDataRaw raw = stubRaw(rawSn, 30); // stubRaw 는 MARKING_READY 로 전이됨
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then
        assertThat(result.markingMode()).isEqualTo("AUTO");
        verify(markingRepository).save(any(LsMarking.class));
    }

    // ── 이벤트명 자동 소싱 (API-047) ──

    @Test
    @DisplayName("이벤트유형_null인_영상_마킹생성시_INVALID_INPUT")
    void createOnVideoWithoutEventTypeRejected() {
        // given — evntTypeCd 가 null 인 비식별 완료 영상
        Long rawSn = 60L;
        LsDataRaw raw = stubRaw(rawSn, 30);
        org.springframework.test.util.ReflectionTestUtils.setField(raw, "evntTypeCd", null);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when / then — 이벤트 유형 미지정 영상은 마킹 불가
        assertThatThrownBy(() -> markingService.create(rawSn, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(markingRepository, never()).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("이벤트유형_blank인_영상_마킹생성시_INVALID_INPUT")
    void createOnVideoWithBlankEventTypeRejected() {
        // given — evntTypeCd 가 공백인 비식별 완료 영상
        Long rawSn = 61L;
        LsDataRaw raw = stubRaw(rawSn, 30);
        org.springframework.test.util.ReflectionTestUtils.setField(raw, "evntTypeCd", "   ");
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when / then
        assertThatThrownBy(() -> markingService.create(rawSn, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(markingRepository, never()).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("자동모드_intervalFrames_0이하_INVALID_INPUT")
    void autoMode_intervalFramesZero_invalidInput() {
        // given
        Long rawSn = 22L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));

        MarkingRequest req = new MarkingRequest("AUTO", 0, null);

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

        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

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

        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

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

        MarkingRequest req = new MarkingRequest("AUTO", 300, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then — frameIndex 300(=totalFrames) 은 포함되지 않는다 (frameIndex < totalFrames)
        assertThat(result.marks()).hasSize(1);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(0);
        assertThat(result.marks()).noneMatch(m -> m.frameIndex() == 300);
    }

    // ── M-3: fps 미상 시 30 폴백 무회귀 (기존 30fps 고정 결과 보존) ──

    @Test
    @DisplayName("M3_자동마킹_fps미상_30폴백_totalFrames와_타임스탬프가_30fps기준으로_계산")
    void autoMarking_assumes30Fps() {
        // given — 60초 영상, intervalFrames=30(=30fps 기준 1초 간격).
        // M-3 수정: fps 미상 시 resolver 가 30.0 으로 폴백하므로 totalFrames(=durationSec×30)와
        // 타임스탬프(frameIndex/30초)가 기존 30fps 고정 결과와 동일함을 회귀 가드로 검증한다.
        Long rawSn = 50L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkingRequest req = new MarkingRequest("AUTO", 30, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then — 60초 × 30fps = 1800 totalFrames, interval=30 → 60개(0..1770).
        // fps 미상(폴백 30.0)이라 30fps 기준 계산 — 무회귀 가드: 첫/둘째 프레임과 타임스탬프를 검증.
        assertThat(result.marks()).hasSize(60);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(0);
        assertThat(result.marks().get(0).timestamp()).isEqualTo("00:00");
        // frameIndex 30 은 30fps 기준 1초 → "00:01".
        assertThat(result.marks().get(1).frameIndex()).isEqualTo(30);
        assertThat(result.marks().get(1).timestamp()).isEqualTo("00:01");
        // 마지막 프레임 1770 → 1770/30 = 59초 → "00:59".
        assertThat(result.marks().get(59).frameIndex()).isEqualTo(1770);
        assertThat(result.marks().get(59).timestamp()).isEqualTo("00:59");
    }

    // ── M-3 수정: 실 fps(video.fps) 사용 + 미상 폴백 무회귀 ──

    @Test
    @DisplayName("자동마킹_실fps25_totalFrames와_타임스탬프_25fps기준으로_정확계산")
    void autoMarking_realFps25_computesWith25() {
        // given — 60초 영상, 저장된 실 fps=25. resolver 가 25.0 을 반환.
        Long rawSn = 80L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(fpsResolver.resolveFps(rawSn)).thenReturn(25.0);
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        // intervalFrames=25 (25fps 기준 1초 간격)
        MarkingRequest req = new MarkingRequest("AUTO", 25, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then — 60초 × 25fps = 1500 totalFrames, interval=25 → 60개(0..1475).
        // 30fps 고정이었다면 1800/25=72개였을 것 — 실 fps 사용을 hasSize 로 입증.
        assertThat(result.marks()).hasSize(60);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(0);
        assertThat(result.marks().get(0).timestamp()).isEqualTo("00:00");
        // frameIndex 25 는 25fps 기준 정확히 1초 → "00:01".
        assertThat(result.marks().get(1).frameIndex()).isEqualTo(25);
        assertThat(result.marks().get(1).timestamp()).isEqualTo("00:01");
        // S1 정합성 근거: frameIndex 50 → 50/25 = 2초 → "00:02" (추출 seekMillis 2000ms 와 왕복 일치).
        assertThat(result.marks().get(2).frameIndex()).isEqualTo(50);
        assertThat(result.marks().get(2).timestamp()).isEqualTo("00:02");
        // 마지막 프레임 1475 → 1475/25 = 59초 → "00:59".
        assertThat(result.marks().get(59).frameIndex()).isEqualTo(1475);
        assertThat(result.marks().get(59).timestamp()).isEqualTo("00:59");
    }

    @Test
    @DisplayName("자동마킹_fps미상이면_30폴백으로_기존과_동일_무회귀")
    void autoMarking_fpsAbsent_fallback30_noRegression() {
        // given — resolver 가 폴백(30.0)을 반환(기본 stub). 60초 영상, interval=30.
        Long rawSn = 81L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        // fpsResolver 는 setUpFpsDefault 의 30.0 폴백을 그대로 사용.
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkingRequest req = new MarkingRequest("AUTO", 30, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then — 30fps 고정이던 기존 결과와 정확히 동일 (60초×30=1800, 60개, 0..1770).
        assertThat(result.marks()).hasSize(60);
        assertThat(result.marks().get(1).frameIndex()).isEqualTo(30);
        assertThat(result.marks().get(1).timestamp()).isEqualTo("00:01");
        assertThat(result.marks().get(59).frameIndex()).isEqualTo(1770);
    }

    // ── TOCTOU 근본 수정: 마킹 시점 fps pin(저장) 검증 ──

    @Test
    @DisplayName("자동마킹_해석한실fps25가_마킹레코드에_pin되어_저장된다")
    void autoMarking_pinsResolvedFps() {
        // given — 저장된 실 fps=25 → 마킹 레코드에 25.0 이 pin 되어야 한다(추출이 재조회 없이 사용).
        Long rawSn = 90L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(fpsResolver.resolveFps(rawSn)).thenReturn(25.0);
        org.mockito.ArgumentCaptor<LsMarking> captor = org.mockito.ArgumentCaptor.forClass(LsMarking.class);
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkingRequest req = new MarkingRequest("AUTO", 25, null);

        // when
        markingService.create(rawSn, req, reviewer());

        // then — 저장된 마킹의 fps 가 pin(25.0)
        verify(markingRepository).save(captor.capture());
        assertThat(captor.getValue().getFps()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("수동마킹도_해석한fps가_마킹레코드에_pin된다")
    void manualMarking_pinsResolvedFps() {
        // given — 수동 마킹은 marks 산출에 fps 를 쓰지 않지만, 추출단계 정합을 위해 fps 를 pin 해야 한다.
        Long rawSn = 91L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(fpsResolver.resolveFps(rawSn)).thenReturn(50.0);
        org.mockito.ArgumentCaptor<LsMarking> captor = org.mockito.ArgumentCaptor.forClass(LsMarking.class);
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkingRequest req = new MarkingRequest("MANUAL", null, List.of(new MarkItem(10, "00:00")));

        // when
        markingService.create(rawSn, req, reviewer());

        // then
        verify(markingRepository).save(captor.capture());
        assertThat(captor.getValue().getFps()).isEqualTo(50.0);
        assertThat(captor.getValue().getMarkModeCd()).isEqualTo(LsMarking.MODE_MANUAL);
    }

    @Test
    @DisplayName("자동마킹_실fps60_totalFrames와_타임스탬프_60fps기준_정확계산_및_pin")
    void autoMarking_realFps60_computesAndPins() {
        // given — 60초 영상, 저장된 실 fps=60.
        Long rawSn = 92L;
        LsDataRaw raw = stubRaw(rawSn, 60);
        when(videoRepository.findById(rawSn)).thenReturn(Optional.of(raw));
        when(fpsResolver.resolveFps(rawSn)).thenReturn(60.0);
        org.mockito.ArgumentCaptor<LsMarking> captor = org.mockito.ArgumentCaptor.forClass(LsMarking.class);
        when(markingRepository.save(any(LsMarking.class))).thenAnswer(inv -> inv.getArgument(0));

        // intervalFrames=60 (60fps 기준 1초 간격)
        MarkingRequest req = new MarkingRequest("AUTO", 60, null);

        // when
        MarkingResponse result = markingService.create(rawSn, req, reviewer());

        // then — 60초 × 60fps = 3600 totalFrames, interval=60 → 60개(0..3540).
        assertThat(result.marks()).hasSize(60);
        assertThat(result.marks().get(0).frameIndex()).isEqualTo(0);
        assertThat(result.marks().get(0).timestamp()).isEqualTo("00:00");
        // frameIndex 60 @ 60fps = 정확히 1초 → "00:01".
        assertThat(result.marks().get(1).frameIndex()).isEqualTo(60);
        assertThat(result.marks().get(1).timestamp()).isEqualTo("00:01");
        // 마지막 3540 @ 60fps = 59초 → "00:59".
        assertThat(result.marks().get(59).frameIndex()).isEqualTo(3540);
        assertThat(result.marks().get(59).timestamp()).isEqualTo("00:59");
        // fps 60.0 이 마킹에 pin 됨.
        verify(markingRepository).save(captor.capture());
        assertThat(captor.getValue().getFps()).isEqualTo(60.0);
    }
}
