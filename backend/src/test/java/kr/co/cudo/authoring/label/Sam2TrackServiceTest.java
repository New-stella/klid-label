package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.label.dto.Sam2TrackOutcome;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import kr.co.cudo.authoring.support.RawVideoFixture;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.label.service.Sam2TrackService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R12 (B) — SAM2 트랙 서비스 테스트. <b>미저장 전환</b>: propagation 결과를 저장하지 않고 좌표만 반환한다.
 *
 * <p>검증 초점:
 * <ul>
 *   <li>AC7 미저장 — track 후에도 {@code LS_DATA_LBL} 에 row 가 생기지 않는다.</li>
 *   <li>IDOR(CWE-639) — 미배정 WORKER 는 403, ai 미호출.</li>
 *   <li>R12 형태 — POLYGON(폴리곤 그대로) / BBOX(외접 bbox + 선택 라벨).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
class Sam2TrackServiceTest {

    @Autowired private Sam2TrackService sam2TrackService;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private AiServerClient aiServerClient;
    @MockBean private LabelMasterService labelMasterService;

    private static Path tmpRawDir;

    @DynamicPropertySource
    static void overrideStorageRawPath(DynamicPropertyRegistry registry) throws IOException {
        tmpRawDir = Files.createTempDirectory("sam2-track-raw-");
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
        // 프레임·배정이 참조할 <b>실재하는</b> 부모 영상을 만든다(V146 FK).
        rawSn = RawVideoFixture.seedRaw(jdbcTemplate, 9001L);
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
        // Sam2TrackService 는 라벨 매핑을 사용하지 않음 — labelMasterService 스텁 불필요(제거).
    }

    @AfterEach
    void tearDown() {
        labelRepository.deleteAll();
        authrtRepository.deleteAll();
        srcRepository.deleteAll();
        RawVideoFixture.deleteRaws(jdbcTemplate, 9001L);
    }

    private void stubTrack(String trackId, List<List<Double>> polygon, double score) {
        when(aiServerClient.track(any())).thenAnswer(inv ->
                Mono.just(new Sam2TrackResponse(trackId, polygon, score)));
    }

    private List<List<Double>> square(double x1, double y1, double x2, double y2) {
        return List.of(List.of(x1, y1), List.of(x2, y1), List.of(x2, y2), List.of(x1, y2));
    }

    // ── AC7 미저장 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("추적은_DB에_저장하지_않고_좌표만_반환한다")
    void trackReturnsCoordinatesWithoutPersisting() {
        stubTrack("track-AAA", square(11, 12, 31, 32), 0.92);

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-AAA",
                square(10, 10, 30, 30), "person", List.of(src1, src2));

        Sam2TrackResponseDto res = sam2TrackService.track(req, reviewer).response();

        assertThat(res.tracked()).hasSize(2);
        assertThat(res.tracked()).allSatisfy(item -> assertThat(item.trackId()).isEqualTo("track-AAA"));
        // ★ 미저장 — LS_DATA_LBL 에 어떤 row 도 생기지 않아야 한다.
        assertThat(labelRepository.findBySrcSn(src1)).isEmpty();
        assertThat(labelRepository.findBySrcSn(src2)).isEmpty();
    }

    @Test
    @DisplayName("배정된_WORKER는_sam2_track_정상동작하되_미저장")
    void assignedWorkerCanTrackNoPersist() {
        stubTrack("track-OK", square(20, 20, 40, 40), 0.85);

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-OK",
                square(10, 10, 30, 30), "car", List.of(src1));

        Sam2TrackResponseDto res = sam2TrackService.track(req, workerAssigned).response();

        assertThat(res.tracked()).hasSize(1);
        assertThat(labelRepository.findBySrcSn(src1)).isEmpty();
    }

    // ── IDOR ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("미배정_WORKER가_sam2_track_호출시_403_FORBIDDEN_ai미호출")
    void notAssignedWorkerForbidden() {
        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-XYZ",
                square(10, 10, 30, 30), "person", List.of(src1, src2));

        assertThatThrownBy(() -> sam2TrackService.track(req, workerNotAssigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verify(aiServerClient, never()).track(any());
        assertThat(labelRepository.findBySrcSn(src1)).isEmpty();
    }

    // ── R12 형태 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("추적_shape가_폴리곤이면_폴리곤을_반환한다")
    void polygonShapeReturnsPolygon() {
        stubTrack("track-P", square(11, 12, 31, 32), 0.9);

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-P",
                square(10, 10, 30, 30), "person", List.of(src1), AutolabelShape.POLYGON);

        Sam2TrackResponseDto res = sam2TrackService.track(req, reviewer).response();

        Sam2TrackResponseDto.TrackedItem item = res.tracked().get(0);
        assertThat(item.shapeType()).isEqualTo("POLYGON");
        assertThat(item.points()).hasSize(4);      // 폴리곤 정점
        assertThat(item.label()).isEqualTo("person");
    }

    @Test
    @DisplayName("추적_shape가_박스면_폴리곤_외접bbox로_반환하고_선택라벨을_부여한다")
    void bboxShapeReturnsCircumscribedBbox() {
        stubTrack("track-B", square(11, 12, 31, 42), 0.8);

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-B",
                square(10, 10, 30, 30), "car", List.of(src1), AutolabelShape.BBOX);

        Sam2TrackResponseDto res = sam2TrackService.track(req, reviewer).response();

        Sam2TrackResponseDto.TrackedItem item = res.tracked().get(0);
        assertThat(item.shapeType()).isEqualTo("BBOX");
        // 외접 bbox = [[minX,minY],[maxX,maxY]]
        assertThat(item.points()).containsExactly(List.of(11.0, 12.0), List.of(31.0, 42.0));
        assertThat(item.label()).isEqualTo("car");   // 선택 라벨 부여
    }

    @Test
    @DisplayName("추적_shape미지정이면_POLYGON_기본")
    void defaultShapeIsPolygon() {
        stubTrack("track-D", square(11, 12, 31, 32), 0.9);

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-D",
                square(10, 10, 30, 30), "person", List.of(src1));

        Sam2TrackResponseDto res = sam2TrackService.track(req, reviewer).response();

        assertThat(res.tracked().get(0).shapeType()).isEqualTo("POLYGON");
    }

    @Test
    @DisplayName("추적_박스형태_퇴화폴리곤은_해당프레임만_스킵한다")
    void degenerateBboxSkippedNotAborted() {
        // 폭/높이 0 인 퇴화 폴리곤 → BBOX 산출 불가 → 해당 프레임 스킵(예외 없음).
        when(aiServerClient.track(any())).thenAnswer(inv -> Mono.just(
                new Sam2TrackResponse("track-DG",
                        List.of(List.of(5.0, 5.0), List.of(5.0, 5.0), List.of(5.0, 5.0)), 0.7)));

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-DG",
                square(10, 10, 30, 30), "person", List.of(src1), AutolabelShape.BBOX);

        Sam2TrackResponseDto res = sam2TrackService.track(req, reviewer).response();

        assertThat(res.tracked()).isEmpty();   // 퇴화 프레임 스킵, 전체 추적은 정상 종료
    }

    // ── mock 게이트 (C-ISSUE-81, CWE-345) ────────────────────────────────────────

    @Test
    @DisplayName("추적_ai가_mock응답이면_해당프레임을_결과에서_제외하고_전량안내를_준다")
    void mockResponseFramesExcluded() {
        // given — ai-server 가 mock(모델 미로드) 으로 시드 폴리곤을 복사해 score 0.9 로 돌려준다.
        //         score 가 높아 FE 저신뢰 분기로는 걸러지지 않는다(C-ISSUE-81).
        when(aiServerClient.track(any())).thenAnswer(inv -> Mono.just(new Sam2TrackResponse(
                "track-MOCK", square(10, 10, 30, 30), 0.9, true, "mock", "weights_missing")));

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-MOCK",
                square(10, 10, 30, 30), "person", List.of(src1, src2));

        // when
        Sam2TrackOutcome outcome = sam2TrackService.track(req, reviewer);

        // then — mock 좌표는 자동 적용 대상에서 제외되고(학습데이터 오염 차단) 안내가 세팅된다.
        assertThat(outcome.response().tracked()).isEmpty();
        assertThat(outcome.mock()).isTrue();
        assertThat(outcome.message()).isEqualTo(Sam2TrackOutcome.MOCK_UNAVAILABLE_MESSAGE);
        // 미저장 규약도 유지 — mock 좌표가 DB 로 새지 않는다.
        assertThat(labelRepository.findBySrcSn(src1)).isEmpty();
        assertThat(labelRepository.findBySrcSn(src2)).isEmpty();
    }

    @Test
    @DisplayName("추적_일부프레임만_mock이면_실결과는_유지하고_부분안내를_준다")
    void partialMockKeepsRealFrames() {
        // given — 첫 프레임은 실모델, 두 번째 프레임만 mock(empty_mask 폴백).
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(aiServerClient.track(any())).thenAnswer(inv ->
                call.getAndIncrement() == 0
                        ? Mono.just(new Sam2TrackResponse("track-PM", square(11, 12, 31, 32), 0.88))
                        : Mono.just(new Sam2TrackResponse("track-PM", square(11, 12, 31, 32), 0.5,
                                true, "mock", "empty_mask")));

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-PM",
                square(10, 10, 30, 30), "person", List.of(src1, src2));

        // when
        Sam2TrackOutcome outcome = sam2TrackService.track(req, reviewer);

        // then — 실모델 프레임 1건만 남고, mock 프레임은 제외 + 부분 안내.
        assertThat(outcome.response().tracked()).hasSize(1);
        assertThat(outcome.response().tracked().get(0).srcSn()).isEqualTo(src1);
        assertThat(outcome.mock()).isTrue();
        assertThat(outcome.message()).isEqualTo(Sam2TrackOutcome.PARTIAL_MOCK_MESSAGE);
    }

    @Test
    @DisplayName("추적_ai가_mock메타를_생략하면_신뢰하지_않고_프레임을_제외한다")
    void omittedMockMetaIsUntrusted() throws Exception {
        // given (fail-open 회귀 가드, CWE-345) — ai-server 가 mock/source/mock_reason 을 <b>전부 생략</b>한
        //   응답을 보낸다. Jackson 이 primitive boolean 을 기본값 false 로 채우므로 mock() 만 보던 구
        //   판정에서는 "정상 응답"으로 통과해 시드 폴리곤 복사본이 전 프레임 자동 적용됐다.
        Sam2TrackResponse omitted = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"track_id\":\"track-OMIT\","
                        + "\"polygon\":[[10.0,10.0],[30.0,10.0],[30.0,30.0],[10.0,30.0]],"
                        + "\"score\":0.9}",
                Sam2TrackResponse.class);
        assertThat(omitted.mock()).isFalse();   // 부정 신호 없음 — fail-open 성립 조건
        assertThat(omitted.source()).isNull();  // 긍정 증명도 없음
        when(aiServerClient.track(any())).thenAnswer(inv -> Mono.just(omitted));

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-OMIT",
                square(10, 10, 30, 30), "person", List.of(src1, src2));

        // when
        Sam2TrackOutcome outcome = sam2TrackService.track(req, reviewer);

        // then — 실모델 출처(source="model") 증명이 없으므로 신뢰하지 않고 전량 제외 + 안내.
        assertThat(outcome.response().tracked()).isEmpty();
        assertThat(outcome.mock()).isTrue();
        assertThat(outcome.message()).isEqualTo(Sam2TrackOutcome.MOCK_UNAVAILABLE_MESSAGE);
        assertThat(labelRepository.findBySrcSn(src1)).isEmpty();
        assertThat(labelRepository.findBySrcSn(src2)).isEmpty();
    }

    @Test
    @DisplayName("추적_실모델(mock아님)_응답은_그대로_자동적용되고_안내가_없다")
    void realModelResponseHasNoMockSignal() {
        // given — source=model, mock=false 인 정상 응답(회귀 가드).
        when(aiServerClient.track(any())).thenAnswer(inv -> Mono.just(new Sam2TrackResponse(
                "track-REAL", square(11, 12, 31, 32), 0.93, false, "model", null)));

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-REAL",
                square(10, 10, 30, 30), "person", List.of(src1, src2));

        // when
        Sam2TrackOutcome outcome = sam2TrackService.track(req, reviewer);

        // then
        assertThat(outcome.response().tracked()).hasSize(2);
        assertThat(outcome.mock()).isFalse();
        assertThat(outcome.message()).isNull();
    }

    // ── 외부 응답 검증 / 오류 경로 (coverage HIGH) ──────────────────────────────────

    @Test
    @DisplayName("sam2_track_ai호출실패시_502")
    void aiCallFailure502() {
        when(aiServerClient.track(any())).thenReturn(Mono.error(new RuntimeException("boom")));

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-E",
                square(10, 10, 30, 30), "person", List.of(src1));

        assertThatThrownBy(() -> sam2TrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("sam2_track_ai응답_polygon_null이면_502")
    void aiPolygonNull502() {
        when(aiServerClient.track(any())).thenAnswer(inv ->
                Mono.just(new Sam2TrackResponse("track-N", null, 0.9)));

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-N",
                square(10, 10, 30, 30), "person", List.of(src1));

        assertThatThrownBy(() -> sam2TrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("sam2_track_ai응답폴리곤_검증실패시_400")
    void aiPolygonInvalid400() {
        // 외부(ai-server) 응답도 불신 — 음수 좌표 폴리곤은 400.
        when(aiServerClient.track(any())).thenAnswer(inv -> Mono.just(new Sam2TrackResponse(
                "track-I", List.of(List.of(-1.0, 5.0), List.of(5.0, 5.0), List.of(5.0, 10.0)), 0.9)));

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-I",
                square(10, 10, 30, 30), "person", List.of(src1));

        assertThatThrownBy(() -> sam2TrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("sam2_track_prevPolygon_검증실패시_400")
    void prevPolygonInvalid400() {
        // 요청 prevPolygon 음수 좌표 → AI 호출 전 400.
        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-PP",
                List.of(List.of(-1.0, 2.0), List.of(3.0, 4.0), List.of(5.0, 6.0)),
                "person", List.of(src1));

        assertThatThrownBy(() -> sam2TrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        verify(aiServerClient, never()).track(any());
    }

    @Test
    @DisplayName("sam2_track_시작프레임없으면_404")
    void startFrameNotFound404() {
        Sam2TrackRequest req = new Sam2TrackRequest(8888888L, "track-S",
                square(10, 10, 30, 30), "person", List.of(src1));

        assertThatThrownBy(() -> sam2TrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(aiServerClient, never()).track(any());
    }

    @Test
    @DisplayName("sam2_track_후속프레임없으면_404")
    void nextFrameNotFound404() {
        when(aiServerClient.track(any())).thenAnswer(inv ->
                Mono.just(new Sam2TrackResponse("track-NX", square(11, 12, 31, 32), 0.9)));

        Sam2TrackRequest req = new Sam2TrackRequest(src0, "track-NX",
                square(10, 10, 30, 30), "person", List.of(7777777L));

        assertThatThrownBy(() -> sam2TrackService.track(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(aiServerClient, never()).track(any());
    }
}
