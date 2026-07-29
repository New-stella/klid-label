package kr.co.cudo.authoring.label;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AutolabelResponse;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.label.service.AutolabelOnlineService;
import kr.co.cudo.authoring.label.service.FrameImageEncoder;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R12 (A) — 폴리곤 오토라벨(온라인) 서비스 단위 테스트 (순수 Mockito).
 *
 * <p>YOLO 검출 박스마다 SAM box-prompt 분할로 폴리곤을 산출하는 경로의 HIGH 시나리오 방어를 검증한다:
 * <ul>
 *   <li>#1/#5 박스 상한(sysconfig) 초과 시 상한까지만 처리 + message 고지.</li>
 *   <li>#4/#9 박스별 부분 실패/mock 은 스킵(성공분만 반환) + 로깅 + 신뢰불가 message.</li>
 *   <li>#3 YOLO 박스 0개면 SAM 미호출.</li>
 *   <li>#2 배치 중 작업락(TOCTOU) 시 409.</li>
 *   <li>AC7 폴리곤 경로도 DB 미저장.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutolabelPolygonServiceTest {

    @Mock private AiServerClient aiServerClient;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private LabelMasterService labelMasterService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private WorkLockService workLockService;
    @Mock private FrameImageEncoder frameImageEncoder;
    @Mock private kr.co.cudo.authoring.video.service.DeidentReportGate deidentReportGate;

    private AutolabelOnlineService service;

    private static final Long SRC_SN = 5001L;
    private static final Long RAW_SN = 9001L;

    private TokenClaims worker;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setup() {
        Bulkhead bulkhead = Bulkhead.of("aiOnlinePolyTest", BulkheadConfig.custom()
                .maxConcurrentCalls(25).maxWaitDuration(Duration.ZERO).build());
        service = new AutolabelOnlineService(aiServerClient, accessGuard, systemConfigService,
                workLockService, frameImageEncoder, labelMasterService, deidentReportGate, bulkhead);

        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "0.jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", SRC_SN);

        when(accessGuard.verifyAndGet(eq(SRC_SN), any())).thenReturn(src);
        when(frameImageEncoder.encodeFrame(any())).thenReturn("BASE64IMG");
        when(systemConfigService.getInt(any())).thenReturn(null);       // conf/imgsz/iou/max-boxes fallback
        when(labelMasterService.findLabelIdByDtctType(anyString())).thenReturn(Optional.empty());
        when(labelMasterService.mappedDetectClasses())
                .thenReturn(new java.util.LinkedHashSet<>(java.util.List.of("person")));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);

        worker = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));

        Logger logger = (Logger) LoggerFactory.getLogger(AutolabelOnlineService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void stubYolo(YoloResponse resp) {
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(resp));
    }

    private YoloResponse detections(int n) {
        List<YoloResponse.Detection> ds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double base = 10.0 + i * 50;
            ds.add(new YoloResponse.Detection("person",
                    List.of(base, base, base + 30, base + 40), 0.9, i));
        }
        return new YoloResponse(ds);
    }

    private Sam2Response samPolygon() {
        return new Sam2Response(List.of(
                List.of(10.0, 10.0), List.of(30.0, 10.0), List.of(30.0, 30.0), List.of(10.0, 30.0)), 0.88);
    }

    private Sam2Response samMock() {
        return new Sam2Response(List.of(
                List.of(10.0, 10.0), List.of(30.0, 10.0), List.of(30.0, 30.0), List.of(10.0, 30.0)),
                0.5, true, "mock", "weights_missing");
    }

    private void stubSam(Sam2Response resp) {
        when(aiServerClient.segment(any())).thenReturn(Mono.just(resp));
    }

    // ── POLYGON 정상 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shape_POLYGON이면_검출_박스마다_폴리곤을_반환한다")
    void polygonPerBox() {
        stubYolo(detections(2));
        stubSam(samPolygon());

        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON);

        assertThat(res.response().detectedCount()).isEqualTo(2);
        assertThat(res.response().labels()).allSatisfy(item -> {
            assertThat(item.shapeType()).isEqualTo("POLYGON");
            assertThat(item.polygon()).hasSize(4);      // 폴리곤 좌표
            assertThat(item.points()).isNull();          // BBOX flat 좌표 없음
            assertThat(item.lblSn()).isNull();           // 미저장
        });
        // 박스 2개 → SAM 2회 호출.
        verify(aiServerClient, times(2)).segment(any());
        assertThat(res.message()).isNull();
    }

    @Test
    @DisplayName("shape_미지정_또는_BBOX면_SAM_미호출_박스만_반환")
    void bboxDefaultNoSam() {
        stubYolo(detections(1));

        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.BBOX);

        assertThat(res.response().labels()).hasSize(1);
        assertThat(res.response().labels().get(0).shapeType()).isEqualTo("BBOX");
        assertThat(res.response().labels().get(0).points()).containsExactly(10.0, 10.0, 40.0, 50.0);
        verify(aiServerClient, never()).segment(any());
    }

    @Test
    @DisplayName("클래스_필터가_폴리곤_경로에도_반영된다")
    void classesForwardedInPolygon() {
        // 신뢰 경계(HIGH#1): FE 요청 classes 는 그대로 신뢰되지 않고 '매핑된 라벨(DTCT_TYPE_CD)' 과의
        // 교집합만 ai-server 로 전달된다. 요청 person/car 가 모두 검출 클래스로 매핑돼 있어야 둘 다 전달된다.
        when(labelMasterService.mappedDetectClasses())
                .thenReturn(new java.util.LinkedHashSet<>(java.util.List.of("person", "car")));
        stubYolo(detections(1));
        stubSam(samPolygon());
        ArgumentCaptor<YoloTrackRequest> cap = ArgumentCaptor.forClass(YoloTrackRequest.class);

        service.autolabel(SRC_SN, worker, List.of("person", "car"), AutolabelShape.POLYGON);

        verify(aiServerClient).predictYoloTrack(cap.capture());
        assertThat(cap.getValue().classes()).containsExactly("person", "car");
    }

    @Test
    @DisplayName("SAM_box_prompt로_YOLO검출_좌표가_전달된다")
    void samReceivesBoxPrompt() {
        stubYolo(detections(1));
        stubSam(samPolygon());
        ArgumentCaptor<Sam2Request> cap = ArgumentCaptor.forClass(Sam2Request.class);

        service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON);

        verify(aiServerClient).segment(cap.capture());
        assertThat(cap.getValue().box()).containsExactly(10.0, 10.0, 40.0, 50.0);
        assertThat(cap.getValue().points()).isNull();
    }

    // ── #1/#5 박스 상한 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("박스_개수가_상한을_초과하면_상한까지만_처리하고_message로_고지한다")
    void maxBoxesTruncated() {
        when(systemConfigService.getInt(ConfigKeys.AUTOLABEL_POLYGON_MAX_BOXES)).thenReturn(2);
        stubYolo(detections(5));   // 5개 검출, 상한 2
        stubSam(samPolygon());

        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON);

        assertThat(res.response().detectedCount()).isEqualTo(2);   // 상한까지만
        verify(aiServerClient, times(2)).segment(any());           // SAM 도 2회만
        assertThat(res.message()).isEqualTo(AutolabelResponse.polygonTruncatedMessage(5, 2));
    }

    // ── #4/#9 부분 실패 / mock ────────────────────────────────────────────────────

    @Test
    @DisplayName("일부_박스_SAM실패시_성공분만_반환하고_스킵을_로깅한다")
    void partialSamFailureSkips() {
        stubYolo(detections(2));
        // 첫 박스 성공, 둘째 박스 실패.
        when(aiServerClient.segment(any()))
                .thenReturn(Mono.just(samPolygon()))
                .thenReturn(Mono.error(new RuntimeException("read timeout")));

        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON);

        assertThat(res.response().detectedCount()).isEqualTo(1);   // 성공분만
        assertThat(logAppender.list).anyMatch(e ->
                e.getFormattedMessage().contains("polygon SAM call failed"));
        // adversarial MED — 비-mock 부분 실패(성공분 존재)도 안내 message 로 고지되어야 한다(일부).
        assertThat(res.message()).isEqualTo(AutolabelResponse.POLYGON_PARTIAL_MOCK_MESSAGE);
    }

    @Test
    @DisplayName("일부_박스_mock이면_해당박스_제외하고_message로_신뢰불가_고지한다")
    void partialMockExcluded() {
        stubYolo(detections(2));
        when(aiServerClient.segment(any()))
                .thenReturn(Mono.just(samPolygon()))
                .thenReturn(Mono.just(samMock()));      // 둘째 박스 mock

        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON);

        assertThat(res.response().detectedCount()).isEqualTo(1);   // mock 박스 제외
        assertThat(res.message()).contains(AutolabelResponse.POLYGON_PARTIAL_MOCK_MESSAGE);
    }

    @Test
    @DisplayName("전량_mock이면_빈결과와_신뢰불가_안내")
    void allMockEmpty() {
        stubYolo(detections(2));
        stubSam(samMock());

        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON);

        assertThat(res.response().detectedCount()).isZero();
        assertThat(res.response().labels()).isEmpty();
        // 전량 mock/실패(반환 0) → "일부" 가 아닌 "모든 결과" 신뢰 불가로 구분 고지(code-reviewer LOW).
        assertThat(res.message()).isEqualTo(AutolabelResponse.POLYGON_ALL_UNRELIABLE_MESSAGE);
    }

    // ── #3 박스 0개 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("YOLO_박스0개면_SAM호출없이_빈결과_반환한다")
    void noBoxesNoSam() {
        stubYolo(new YoloResponse(List.of()));

        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON);

        assertThat(res.response().detectedCount()).isZero();
        verify(aiServerClient, never()).segment(any());
    }

    // ── #2 TOCTOU (배치 중 작업락) ────────────────────────────────────────────────

    @Test
    @DisplayName("폴리곤_배치중_작업락걸리면_409로_차단한다")
    void toctouDuringBatch() {
        // 진입=false 통과, YOLO 후 배치 루프 재확인 시 true(그사이 비식별 신고 잠금).
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false, true);
        stubYolo(detections(2));
        stubSam(samPolygon());

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }

    // ── MED-1 bulkhead 429 fail-fast ─────────────────────────────────────────────

    @Test
    @DisplayName("폴리곤_배치중_bulkhead429는_즉시_전파되고_삼켜지지_않는다")
    void polygonBulkhead429RethrownNotSwallowed() {
        // maxConcurrentCalls=1 bulkhead 로 서비스 구성 — YOLO 는 정상 통과(호출 후 permit 반환),
        // SAM 호출 직전(=Mono 생성 시점)에 유일 permit 을 선점해 BulkheadOperator 가 permit 을 못 얻어
        // BulkheadFullException→429 를 결정론적으로 유발한다.
        Bulkhead saturating = Bulkhead.of("aiOnlineSat", BulkheadConfig.custom()
                .maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        AutolabelOnlineService svc = new AutolabelOnlineService(aiServerClient, accessGuard,
                systemConfigService, workLockService, frameImageEncoder, labelMasterService, deidentReportGate, saturating);

        stubYolo(detections(2));
        when(aiServerClient.segment(any())).thenAnswer(inv -> {
            saturating.acquirePermission();   // 유일 permit 선점 → 이후 SAM 구독이 429.
            return Mono.just(samPolygon());
        });

        // 429 는 부분 스킵으로 삼키지 않고 즉시 전파되어야 한다(fail-fast — 부하 차단 유지).
        assertThatThrownBy(() -> svc.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    // ── coverage HIGH 폴리곤 예산 소진 ────────────────────────────────────────────

    @Test
    @DisplayName("폴리곤_예산소진시_잔여박스_잘라_message_고지")
    void budgetExhaustedTruncates() {
        // 전체 wall-clock 예산을 0 으로 축소(package-private 필드) → 첫 박스 처리 전 데드라인 초과.
        ReflectionTestUtils.setField(service, "polygonTotalBudget", Duration.ZERO);
        stubYolo(detections(2));
        stubSam(samPolygon());

        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON);

        assertThat(res.response().detectedCount()).isZero();     // 예산 소진 — 아무 박스도 처리 못함.
        verify(aiServerClient, never()).segment(any());          // SAM 미호출(예산 0).
        assertThat(res.message()).isEqualTo(AutolabelResponse.polygonTruncatedMessage(2, 0));
    }

    // ── FEAT-007: 경계 세밀함(simplify) per-request override ──────────────────────

    /** 공선점(P1) 하나가 포함된 5점 폴리곤 — epsilon 1.0 단순화 시 4점으로 감소, 0.0 이면 원본 유지. */
    private Sam2Response samPolygon5() {
        return new Sam2Response(List.of(
                List.of(0.0, 0.0), List.of(5.0, 0.0), List.of(10.0, 0.0),
                List.of(10.0, 10.0), List.of(0.0, 10.0)), 0.9);
    }

    @Test
    @DisplayName("AI탐지_폴리곤_simplifyTolerance_요청값이_적용되어_점수가_감소한다")
    void polygonSimplifyOverrideReducesPoints() {
        stubYolo(detections(1));
        stubSam(samPolygon5());

        // override 0.0 → 단순화 비활성(원본 5점 그대로).
        AutolabelOnlineService.AutolabelOutcome raw =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON, null, 0.0);
        assertThat(raw.response().labels().get(0).polygon()).hasSize(5);

        // override 1.0 → 공선점 제거로 4점 감소 — 요청값이 실제 후처리에 반영됨(AC4).
        AutolabelOnlineService.AutolabelOutcome simplified =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON, null, 1.0);
        assertThat(simplified.response().labels().get(0).polygon()).hasSize(4);
    }

    @Test
    @DisplayName("AI탐지_폴리곤_simplifyTolerance_없으면_시스템설정값을_쓴다")
    void polygonSimplifyNullUsesSystemConfig() {
        // 시스템설정 POLYGON_SIMPLIFY_TOLERANCE=1.0 → 공선점 제거(4점). 요청 override 미지정.
        when(systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE)).thenReturn(1.0);
        stubYolo(detections(1));
        stubSam(samPolygon5());

        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON, null, null);

        assertThat(res.response().labels().get(0).polygon()).hasSize(4);
    }

    @Test
    @DisplayName("AI탐지_폴리곤_simplify_시스템설정_조회실패시_상수1.0으로_폴백한다")
    void polygonSimplifySystemConfigFailureFallsBack() {
        when(systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE))
                .thenThrow(new RuntimeException("db down"));
        stubYolo(detections(1));
        stubSam(samPolygon5());

        // 폴백 상수 1.0 적용 → 공선점 제거(4점). 조회 실패해도 예외 전파 없이 정상 반환(무회귀).
        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON, null, null);

        assertThat(res.response().labels().get(0).polygon()).hasSize(4);
    }

    @Test
    @DisplayName("AI탐지_폴리곤_simplify_결과가_3점미만이면_원본유지")
    void polygonSimplifyBelowMinKeepsOriginal() {
        stubYolo(detections(1));
        stubSam(samPolygon5());

        // 매우 큰 tolerance(100.0)로 Douglas-Peucker 가 시작/끝 2점(<MIN_POLYGON_POINTS=3)으로 축소된다.
        // DTO 검증(0.0~50.0) 밖 값이라 컨트롤러로는 도달 불가하므로 서비스 6-arg 진입점으로 직접 우회한다.
        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON, null, 100.0);

        // 3점 미만 축소 시 형태 보존을 위해 원본 5점 폴리곤이 그대로 반환된다(simplifyPolygon 방어 분기).
        assertThat(res.response().labels().get(0).polygon()).hasSize(5);
    }

    // ── AC7 미저장 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("폴리곤_경로도_DB에_저장하지_않는다")
    void polygonPathDoesNotPersist() {
        // 서비스는 라벨 리포지토리에 의존하지 않는다(미저장 아키텍처).
        boolean hasLblRepoDependency = java.util.Arrays.stream(
                        AutolabelOnlineService.class.getDeclaredFields())
                .anyMatch(f -> f.getType().getSimpleName().contains("Lbl")
                        || f.getType().getSimpleName().contains("PersistService"));
        assertThat(hasLblRepoDependency).isFalse();

        stubYolo(detections(1));
        stubSam(samPolygon());
        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, null, AutolabelShape.POLYGON);
        assertThat(res.response().labels().get(0).lblSn()).isNull();
    }
}
