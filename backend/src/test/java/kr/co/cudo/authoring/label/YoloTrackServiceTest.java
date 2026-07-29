package kr.co.cudo.authoring.label;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.YoloTrackRequest;
import kr.co.cudo.authoring.label.dto.YoloTrackResponseDto;
import kr.co.cudo.authoring.label.service.YoloTrackService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 인터랙티브 YOLO 객체 트랙 추론 서비스 테스트.
 *
 * 순수 추론 프록시(DB 저장 없음)이므로 라벨 저장 검증 대신
 * ai-server 호출 순서(frameIndex 0-base, clipId 동일)와 응답 매핑을 검증한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class YoloTrackServiceTest {

    @Autowired private YoloTrackService yoloTrackService;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    @MockBean private AiServerClient aiServerClient;

    private ListAppender<ILoggingEvent> logAppender;

    private static Path tmpRawDir;

    @DynamicPropertySource
    static void overrideStorageRawPath(DynamicPropertyRegistry registry) throws IOException {
        tmpRawDir = Files.createTempDirectory("yolo-track-raw-");
        registry.add("authoring.storage.raw-path", () -> tmpRawDir.toAbsolutePath().toString());
    }

    private Long src0;
    private Long src1;
    private Long src2;
    private long rawSn;

    private TokenClaims reviewer;
    private TokenClaims workerAssigned;
    private TokenClaims workerNotAssigned;

    @BeforeEach
    void setup() throws IOException {
        labelRepository.deleteAll();
        authrtRepository.deleteAll();
        srcRepository.deleteAll();
        rawSn = 9101L;

        Files.write(tmpRawDir.resolve("0.jpg"), new byte[]{0x01, 0x02});
        Files.write(tmpRawDir.resolve("1.jpg"), new byte[]{0x03, 0x04});
        Files.write(tmpRawDir.resolve("2.jpg"), new byte[]{0x05, 0x06});
        src0 = srcRepository.save(LsDataSrc.create(rawSn, 0, "0.jpg", LocalDateTime.now())).getSrcSn();
        src1 = srcRepository.save(LsDataSrc.create(rawSn, 1, "1.jpg", LocalDateTime.now())).getSrcSn();
        src2 = srcRepository.save(LsDataSrc.create(rawSn, 2, "2.jpg", LocalDateTime.now())).getSrcSn();

        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));

        Instant exp = Instant.now().plusSeconds(60);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, exp);
        workerAssigned = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, exp);
        workerNotAssigned = new TokenClaims("101", Role.WORKER, Channel.INTERNAL, exp);

        Logger logger = (Logger) LoggerFactory.getLogger(YoloTrackService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (logAppender != null) {
            Logger logger = (Logger) LoggerFactory.getLogger(YoloTrackService.class);
            logger.detachAppender(logAppender);
            logAppender.stop();
        }
        labelRepository.deleteAll();
        authrtRepository.deleteAll();
        srcRepository.deleteAll();
    }

    private YoloResponse detectionResponse() {
        return new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 7)));
    }

    @Test
    @DisplayName("정상_트랙_시퀀스_프레임별_detection_반환됨")
    void tracksSequenceReturnsPerFrameDetections() {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(detectionResponse()));

        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1, src2));
        YoloTrackResponseDto res = yoloTrackService.track(req, reviewer);

        // 3 프레임(시작 + 후속 2) 각각 detection 반환
        assertThat(res.frames()).hasSize(3);
        assertThat(res.frames().get(0).srcSn()).isEqualTo(src0);
        assertThat(res.frames().get(0).frameIndex()).isEqualTo(0);
        assertThat(res.frames().get(1).srcSn()).isEqualTo(src1);
        assertThat(res.frames().get(1).frameIndex()).isEqualTo(1);
        assertThat(res.frames().get(2).srcSn()).isEqualTo(src2);
        assertThat(res.frames().get(2).frameIndex()).isEqualTo(2);
        assertThat(res.frames().get(0).detections()).hasSize(1);
        assertThat(res.frames().get(0).detections().get(0).label()).isEqualTo("person");
        assertThat(res.frames().get(0).detections().get(0).trackId()).isEqualTo(7);

        // ai-server 는 frameIndex 0-base, 동일 clipId 로 순서대로 3회 호출
        ArgumentCaptor<kr.co.cudo.authoring.common.client.dto.YoloTrackRequest> cap =
                ArgumentCaptor.forClass(kr.co.cudo.authoring.common.client.dto.YoloTrackRequest.class);
        verify(aiServerClient, times(3)).predictYoloTrack(cap.capture());
        List<kr.co.cudo.authoring.common.client.dto.YoloTrackRequest> calls = cap.getAllValues();
        assertThat(calls.get(0).frameIndex()).isEqualTo(0);
        assertThat(calls.get(1).frameIndex()).isEqualTo(1);
        assertThat(calls.get(2).frameIndex()).isEqualTo(2);
        // A-1: 요청 단위 고유 clipId — rawSn 접두 + 요청 내 모든 프레임이 동일 clipId 공유.
        String firstClipId = calls.get(0).clipId();
        assertThat(firstClipId).startsWith(rawSn + ":");
        assertThat(calls).allSatisfy(c -> assertThat(c.clipId()).isEqualTo(firstClipId));

        // DB 저장 없음
        assertThat(labelRepository.findBySrcSn(src1)).isEmpty();
        assertThat(labelRepository.findBySrcSn(src2)).isEmpty();
    }

    @Test
    @DisplayName("시작프레임_없으면_NOT_FOUND")
    void startFrameNotFound() {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(detectionResponse()));
        Long missing = 88_888L;
        // 미배정이 아니라 존재하지 않는 프레임 — REVIEWER 로 IDOR 우회 후 조회 단계 NOT_FOUND
        YoloTrackRequest req = new YoloTrackRequest(missing, List.of(src1));

        assertThatThrownBy(() -> yoloTrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(aiServerClient, never()).predictYoloTrack(any());
    }

    @Test
    @DisplayName("후속프레임_없으면_NOT_FOUND")
    void nextFrameNotFound() {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(detectionResponse()));
        Long missing = 77_777L;
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(missing));

        assertThatThrownBy(() -> yoloTrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("본인_배정_아닌_프레임_트랙_시_403_시작프레임")
    void notAssignedWorkerForbiddenOnStart() {
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1));
        assertThatThrownBy(() -> yoloTrackService.track(req, workerNotAssigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(aiServerClient, never()).predictYoloTrack(any());
    }

    @Test
    @DisplayName("배정된_WORKER는_정상_동작")
    void assignedWorkerCanTrack() {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(detectionResponse()));
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1));
        YoloTrackResponseDto res = yoloTrackService.track(req, workerAssigned);
        assertThat(res.frames()).hasSize(2);
    }

    @Test
    @DisplayName("후속프레임이_다른_영상이면_INVALID_INPUT")
    void nextFrameFromDifferentVideoRejected() {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(detectionResponse()));
        // 다른 영상(rawSn)에 속한 프레임을 후속 시퀀스에 혼입.
        long otherRawSn = 9202L;
        Long otherSrc = srcRepository.save(
                LsDataSrc.create(otherRawSn, 0, "other.jpg", LocalDateTime.now())).getSrcSn();

        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(otherSrc));

        assertThatThrownBy(() -> yoloTrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("aiserver_실패_시_EXTERNAL_API_ERROR")
    void aiServerFailureMapsToExternalApiError() {
        when(aiServerClient.predictYoloTrack(any()))
                .thenThrow(new RuntimeException("connection refused"));
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1));

        assertThatThrownBy(() -> yoloTrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("aiserver_응답_points가_4개가_아니면_INVALID_INPUT")
    void invalidPointsRejected() {
        // points 3개 — 형식 위반
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(
                new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0), 0.8, 1)))));
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1));

        assertThatThrownBy(() -> yoloTrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ── DEV_FIX(H-1) — 좌표 정책을 배치·AI 탐지와 동일한 DetectionBoxNormalizer 공용 규칙으로 통일 ──

    @Test
    @DisplayName("aiserver_응답_음수좌표는_거부되지_않고_0으로_clamp되어_반환된다")
    void negativePointsClampedNotRejected() {
        // given — C-ISSUE-41 실측값. 이 서비스는 배치·AI 탐지와 같은 모델(/infer/yolo/track)을 호출하므로
        //         같은 경계 좌표가 온다. 구 구현은 여기서만 음수를 400 으로 거부했다(C-41 이 폐기한 정책).
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(
                new YoloResponse(List.of(
                        new YoloResponse.Detection("person",
                                List.of(-1.5731448368773044, 10.0, 40.0, 60.0), 0.8, 1)))));
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1));

        // when
        YoloTrackResponseDto res = yoloTrackService.track(req, reviewer);

        // then — 하한 clamp 적용(공용 규칙). clamp 가 항등함수가 되면 -1.57… 이 그대로 남아 실패한다.
        assertThat(res.frames().get(0).detections()).hasSize(1);
        assertThat(res.frames().get(0).detections().get(0).points())
                .containsExactly(0.0, 10.0, 40.0, 60.0);
    }

    @Test
    @DisplayName("aiserver_응답_좌표는_프레임_실측_해상도_상한으로_clamp된다")
    void pointsClampedToMeasuredFrameBounds() throws IOException {
        // given — 시작 프레임 이미지를 실제 100x50 PNG 로 교체(FrameBoundsResolver 가 실측 가능해진다).
        //         구 구현은 상한을 아예 검증하지 않아 1000x900 이 그대로 응답에 실렸다.
        writeImage(tmpRawDir.resolve("0.jpg"), 100, 50);
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(
                new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(10.0, 10.0, 1000.0, 900.0), 0.8, 1)))));
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1));

        // when
        YoloTrackResponseDto res = yoloTrackService.track(req, reviewer);

        // then — 실측 경계로 clamp. 공용 유틸을 경유하지 않으면(항등) 1000.0/900.0 이 남아 실패한다.
        assertThat(res.frames().get(0).detections().get(0).points())
                .containsExactly(10.0, 10.0, 100.0, 50.0);
    }

    @Test
    @DisplayName("이미지_전체밖_퇴화박스는_해당_검출만_스킵되고_시퀀스_전체는_유지된다")
    void degenerateBoxSkippedWithoutDiscardingSequence() {
        // given — 프레임 루프 안에서 400 을 던지면 최대 50프레임 시퀀스가 통째로 폐기된다.
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(
                new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(-40.0, 10.0, -5.0, 60.0), 0.8, 1),
                        new YoloResponse.Detection("car", List.of(10.0, 10.0, 40.0, 60.0), 0.7, 2)))));
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1, src2));

        // when
        YoloTrackResponseDto res = yoloTrackService.track(req, reviewer);

        // then — 3 프레임 모두 살아 있고, 퇴화 검출만 빠지고 정상 검출은 남는다.
        assertThat(res.frames()).hasSize(3);
        assertThat(res.frames()).allSatisfy(f -> {
            assertThat(f.detections()).hasSize(1);
            assertThat(f.detections().get(0).label()).isEqualTo("car");
        });
    }

    @Test
    @DisplayName("aiserver_응답_좌표가_NaN이면_INVALID_INPUT — NaN이_음수검사를_통과해_응답에_실리던_결함")
    void nanPointsRejected() {
        // given — 구 구현에는 isFinite 가드가 없었고 (NaN < 0) == false 라 NaN 이 그대로 DTO 에 실렸다.
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(
                new YoloResponse(List.of(
                        new YoloResponse.Detection("person",
                                List.of(Double.NaN, 10.0, 40.0, 60.0), 0.8, 1)))));
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1));

        assertThatThrownBy(() -> yoloTrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    /** 실측 해상도 검증용 실제 이미지 파일 생성(확장자와 무관하게 내용으로 판독된다). */
    private static void writeImage(Path path, int width, int height) throws IOException {
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(width, height,
                        java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png", path.toFile());
    }

    @Test
    @DisplayName("aiserver_score_1_초과면_1_0_으로_clamp")
    void scoreAboveOneClamped() {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(
                new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 1.2, 1)))));
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1));

        YoloTrackResponseDto res = yoloTrackService.track(req, reviewer);
        assertThat(res.frames().get(0).detections().get(0).score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("aiserver_score_음수면_0_0_으로_clamp")
    void negativeScoreClamped() {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(
                new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), -0.1, 1)))));
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1));

        YoloTrackResponseDto res = yoloTrackService.track(req, reviewer);
        assertThat(res.frames().get(0).detections().get(0).score()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("aiserver_빈_응답이면_해당_프레임_detections_빈리스트")
    void emptyResponseYieldsEmptyDetections() {
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> Mono.just(
                new YoloResponse((List<YoloResponse.Detection>) null)));
        YoloTrackRequest req = new YoloTrackRequest(src0, List.of(src1));

        YoloTrackResponseDto res = yoloTrackService.track(req, reviewer);
        assertThat(res.frames()).hasSize(2);
        assertThat(res.frames()).allSatisfy(f -> assertThat(f.detections()).isEmpty());
    }
}
