package kr.co.cudo.authoring.label;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AutolabelResponse;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 1 — YOLO 오토라벨 수동(온라인) 트리거 서비스 단위 테스트 (순수 Mockito).
 *
 * <p><b>미저장 전환(Phase 1)</b>: 온라인 오토라벨은 DB 에 저장하지 않고 ai-server 검출 좌표만 반환한다
 * (SAM2 분할과 동일 stateless 프록시). 저장 관련 검증(save/delete/선삭제)은 제거되고, 대신 "DB 미변경 +
 * 좌표만 반환 + AI 후 작업락 TOCTOU 재확인" 을 검증한다.
 *
 * <p>HIGH 시나리오 방어:
 * <ul>
 *   <li>IDOR(CWE-639): accessGuard.verifyAndGet 를 ai 호출 전 최우선 수행.</li>
 *   <li>작업락(#3): isRawLocked → CONFLICT(409), ai 미호출.</li>
 *   <li>TOCTOU(#4): AI 호출 완료 후 응답 조립 직전 isRawLocked 재확인 — 그사이 잠기면 좌표 미반환·409.</li>
 *   <li>동시성(CWE-362): 진행 중 재요청 차단(in-flight) → CONFLICT. 400/502/409 모든 경로에서 락 해제.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutolabelOnlineServiceTest {

    @Mock private AiServerClient aiServerClient;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private LabelMasterService labelMasterService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private WorkLockService workLockService;
    @Mock private FrameImageEncoder frameImageEncoder;

    private AutolabelOnlineService service;

    private static final Long SRC_SN = 5001L;
    private static final Long RAW_SN = 9001L;

    private TokenClaims worker;

    /** 온라인 AI 경로 bulkhead — 기본은 넉넉한 크기(동시성 제한 테스트에서만 1로 재구성). */
    private AutolabelOnlineService buildService(Bulkhead bulkhead) {
        return new AutolabelOnlineService(aiServerClient, accessGuard, systemConfigService,
                workLockService, frameImageEncoder, labelMasterService, bulkhead);
    }

    @BeforeEach
    void setup() {
        Bulkhead defaultBulkhead = Bulkhead.of("aiOnlineTest", BulkheadConfig.custom()
                .maxConcurrentCalls(25).maxWaitDuration(Duration.ZERO).build());
        service = buildService(defaultBulkhead);

        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "0.jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", SRC_SN);

        // IDOR 가드 통과 시 프레임 반환.
        when(accessGuard.verifyAndGet(eq(SRC_SN), any())).thenReturn(src);
        when(frameImageEncoder.encodeToBase64(anyString())).thenReturn("BASE64IMG");
        when(systemConfigService.getInt(any())).thenReturn(null); // fallback conf/imgsz/iou
        when(labelMasterService.findLabelIdByDtctType(anyString())).thenReturn(Optional.empty());
        // HIGH#1 — 검출 대상 재구성용 매핑 allowlist. 기본 person/car 매핑(검출 진행 허용).
        when(labelMasterService.mappedDetectClasses())
                .thenReturn(new java.util.LinkedHashSet<>(java.util.List.of("person", "car")));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);

        worker = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    private void stubAi(YoloResponse resp) {
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(resp));
    }

    private YoloResponse oneDetection() {
        return new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 3)));
    }

    // ── 미저장 + 좌표만 반환 (AC7) ───────────────────────────────────────────────

    @Test
    @DisplayName("온라인_오토라벨은_DB에_저장하지_않고_좌표만_반환한다")
    void detectsAndReturnsCoordinatesWithoutPersisting() {
        stubAi(oneDetection());

        AutolabelOnlineService.AutolabelOutcome res = service.autolabel(SRC_SN, worker);

        assertThat(res.mock()).isFalse();
        assertThat(res.response().detectedCount()).isEqualTo(1);
        AutolabelResponse.Item item = res.response().labels().get(0);
        // 검출 좌표 그대로 반환.
        assertThat(item.label()).isEqualTo("person");
        assertThat(item.points()).containsExactly(10.0, 10.0, 40.0, 60.0);
        assertThat(item.score()).isEqualTo(0.9);
        assertThat(item.trackId()).isEqualTo(3);
        // 미저장 신호 — lblSn 은 항상 null (DB PK 미발급).
        assertThat(item.lblSn()).isNull();
    }

    @Test
    @DisplayName("오토라벨_실행해도_기존_라벨_row가_삭제되지_않는다")
    void doesNotTouchAnyLabelRow() {
        // 미저장 전환으로 서비스는 라벨 리포지토리에 의존하지 않는다 — 작업락 조회만 수행.
        // (실제 저장/선삭제 경로가 사라졌음을 아키텍처 수준에서 검증.)
        boolean hasLblRepoDependency = java.util.Arrays.stream(
                        AutolabelOnlineService.class.getDeclaredFields())
                .anyMatch(f -> f.getType().getSimpleName().contains("Lbl")
                        || f.getType().getSimpleName().contains("PersistService"));
        assertThat(hasLblRepoDependency).isFalse();

        stubAi(oneDetection());
        AutolabelOnlineService.AutolabelOutcome res = service.autolabel(SRC_SN, worker);
        assertThat(res.response().detectedCount()).isEqualTo(1);
    }

    // ── #4 TOCTOU: AI 호출 완료 후 작업락 재확인 ──────────────────────────────────

    @Test
    @DisplayName("AI호출_완료_후_작업락이_걸리면_409로_차단하고_좌표를_반환하지_않는다")
    void toctouLockReCheckAfterAi() {
        // given: 진입 시 잠금 체크는 false(통과), AI 호출 후 재확인 시 true(그 사이 비식별 신고가 잠금).
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false, true);
        stubAi(oneDetection());

        // when / then: 재확인에서 잠금 감지 → CONFLICT. 좌표 반환 안 함(프라이버시 불변식 보호).
        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        // AI 는 호출됐지만 응답은 조립되지 않음. 재진입 시 in-flight 락은 해제되어 있어야 한다.
        verify(aiServerClient).predictYoloTrack(any());
    }

    @Test
    @DisplayName("TOCTOU_409_이후_inflight_락이_해제되어_재요청_가능하다")
    void toctouReleasesInFlight() {
        // given: 1차 호출은 AI 성공 후 재확인에서 잠금(true) 감지 → 409. 2차 호출은 진입/재확인 모두 false.
        //        (isRawLocked 호출 순서: 1차 진입=false, 1차 재확인=true, 2차 진입=false, 2차 재확인=false)
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false, true, false, false);
        stubAi(oneDetection());

        // when: 1차 → TOCTOU 재확인에서 잠금 → CONFLICT.
        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        // then: finally 로 in-flight 해제 → 같은 srcSn 2차 요청이 락 잔류 없이 정상 좌표 반환(해제 실증).
        AutolabelOnlineService.AutolabelOutcome retry = service.autolabel(SRC_SN, worker);
        assertThat(retry.response().detectedCount()).isEqualTo(1);
    }

    // ── #3 작업락(진입 전) ───────────────────────────────────────────────────────

    @Test
    @DisplayName("잠긴_영상_오토라벨_409_이고_ai_미호출")
    void lockedRawConflict() {
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(true);

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(aiServerClient, never()).predictYoloTrack(any());
    }

    // ── IDOR ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("타인배정_프레임_오토라벨_403_이고_ai_미호출")
    void otherAssignmentForbidden() {
        when(accessGuard.verifyAndGet(eq(SRC_SN), any()))
                .thenThrow(new CustomException(ErrorCode.FORBIDDEN, "본인 배정 아님"));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verify(aiServerClient, never()).predictYoloTrack(any());
        verify(workLockService, never()).isRawLocked(any());
    }

    // ── 좌표 검증(all-or-nothing) + in-flight 락 해제 ────────────────────────────

    @Test
    @DisplayName("좌표검증_실패시_400이며_inflight_락이_해제된다")
    void invalidBboxReleasesInFlight() {
        stubAi(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0), 0.9, 3))));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        // finally 로 in-flight 해제 → 정상 응답으로 재요청 가능(락 잔류 없음).
        stubAi(oneDetection());
        AutolabelOnlineService.AutolabelOutcome retry = service.autolabel(SRC_SN, worker);
        assertThat(retry.response().detectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ai_응답_좌표_음수면_INVALID_INPUT")
    void negativeBboxRejected() {
        stubAi(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(-1.0, 10.0, 40.0, 60.0), 0.9, 3))));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("ai_응답_좌표_NaN이면_INVALID_INPUT")
    void nanBboxRejected() {
        stubAi(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, Double.NaN, 60.0), 0.9, 3))));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("ai_응답_좌표_Infinity면_INVALID_INPUT")
    void infinityBboxRejected() {
        stubAi(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, Double.POSITIVE_INFINITY, 60.0), 0.9, 3))));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("ai_응답_좌표_순서역전_x2작거나같으면_INVALID_INPUT")
    void degenerateBboxRejected() {
        stubAi(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(40.0, 10.0, 40.0, 60.0), 0.9, 3))));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("좌표검증은_all_or_nothing_하나라도_비정상이면_전부_미반환")
    void partialReturnForbidden() {
        // 첫 detection 정상, 둘째 좌표 4개 아님 → 전체 400 (부분 반환 금지).
        stubAi(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 3),
                new YoloResponse.Detection("car", List.of(1.0, 2.0, 3.0), 0.8, 1))));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ── AI 실패 502 + in-flight 락 해제 ─────────────────────────────────────────

    @Test
    @DisplayName("AI_실패시_502이며_inflight_락이_해제된다")
    void aiFailureReleasesInFlight() {
        when(aiServerClient.predictYoloTrack(any()))
                .thenReturn(Mono.error(new RuntimeException("read timeout")));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);

        // finally 로 in-flight 해제 → 재요청 가능.
        stubAi(oneDetection());
        AutolabelOnlineService.AutolabelOutcome retry = service.autolabel(SRC_SN, worker);
        assertThat(retry.response().detectedCount()).isEqualTo(1);
    }

    // ── mock 안전장치 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mock_응답이면_빈_결과와_안내메시지를_반환한다")
    void mockResponseReturnsEmpty() {
        stubAi(new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 3)),
                true, "mock", "weights_missing"));

        AutolabelOnlineService.AutolabelOutcome res = service.autolabel(SRC_SN, worker);

        assertThat(res.mock()).isTrue();
        assertThat(res.response().detectedCount()).isZero();
        assertThat(res.response().labels()).isEmpty();
    }

    @Test
    @DisplayName("AutolabelResponse에_mock필드가_없다")
    void autolabelResponseHasNoMockField() {
        boolean hasMock = java.util.Arrays.stream(AutolabelResponse.class.getRecordComponents())
                .anyMatch(rc -> rc.getName().equals("mock"));
        assertThat(hasMock).isFalse();
    }

    @Test
    @DisplayName("검출_없으면_0건_정상반환")
    void noDetectionsReturnsEmpty() {
        stubAi(new YoloResponse(List.of()));

        AutolabelOnlineService.AutolabelOutcome res = service.autolabel(SRC_SN, worker);

        assertThat(res.mock()).isFalse();
        assertThat(res.response().detectedCount()).isZero();
        assertThat(res.response().labels()).isEmpty();
    }

    // ── 과도기 mirror ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("응답에_detectedCount와_savedCount가_동일값으로_노출된다")
    void detectedCountMirrorsSavedCount() {
        stubAi(oneDetection());

        AutolabelResponse res = service.autolabel(SRC_SN, worker).response();

        assertThat(res.detectedCount()).isEqualTo(1);
        assertThat(res.savedCount()).isEqualTo(res.detectedCount());
    }

    // ── 동시 중복 트리거 차단(in-flight) ────────────────────────────────────────

    @Test
    @DisplayName("동일프레임_동시요청시_한쪽은_409_라벨중복_안됨")
    void concurrentDuplicateBlocked() throws Exception {
        CountDownLatch aiEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> {
            aiEntered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return Mono.just(oneDetection());
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> service.autolabel(SRC_SN, worker));
            assertThat(aiEntered.await(3, TimeUnit.SECONDS)).isTrue();

            Future<?> second = pool.submit(() -> service.autolabel(SRC_SN, worker));
            assertThatThrownBy(second::get)
                    .cause()
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONFLICT);

            release.countDown();
            first.get(3, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── Phase 4: 클래스 필터(R3 AC3) ─────────────────────────────────────────────

    @Test
    @DisplayName("classes_전달시_매핑된_클래스만_ai서버_YoloTrackRequest에_포함")
    void classesForwardedToAiRequest() {
        stubAi(oneDetection());
        ArgumentCaptor<kr.co.cudo.authoring.common.client.dto.YoloTrackRequest> cap =
                ArgumentCaptor.forClass(kr.co.cudo.authoring.common.client.dto.YoloTrackRequest.class);

        service.autolabel(SRC_SN, worker, List.of("person", "car"));

        verify(aiServerClient).predictYoloTrack(cap.capture());
        assertThat(cap.getValue().classes()).containsExactlyInAnyOrder("person", "car");
    }

    @Test
    @DisplayName("classes_없이_호출시_매핑된_전체_클래스로_검출한다_매핑라벨만")
    void noClassesMeansAllMapped() {
        // HIGH#1 — '전체' 선택도 매핑된 라벨(person/car)만 검출 대상으로 재구성한다(COCO 80 전체 금지).
        stubAi(oneDetection());
        ArgumentCaptor<kr.co.cudo.authoring.common.client.dto.YoloTrackRequest> cap =
                ArgumentCaptor.forClass(kr.co.cudo.authoring.common.client.dto.YoloTrackRequest.class);

        service.autolabel(SRC_SN, worker);

        verify(aiServerClient).predictYoloTrack(cap.capture());
        assertThat(cap.getValue().classes()).containsExactlyInAnyOrder("person", "car");
    }

    @Test
    @DisplayName("classes_빈리스트면_매핑된_전체_클래스로_검출한다")
    void emptyClassesMeansAllMapped() {
        stubAi(oneDetection());
        ArgumentCaptor<kr.co.cudo.authoring.common.client.dto.YoloTrackRequest> cap =
                ArgumentCaptor.forClass(kr.co.cudo.authoring.common.client.dto.YoloTrackRequest.class);

        service.autolabel(SRC_SN, worker, List.of());

        verify(aiServerClient).predictYoloTrack(cap.capture());
        assertThat(cap.getValue().classes()).containsExactlyInAnyOrder("person", "car");
    }

    // ── HIGH#1: BE 화이트리스트 재검증 (매핑된 라벨만 검출) ─────────────────────────

    @Test
    @DisplayName("미매핑_클래스가_요청에_섞이면_제외하고_매핑된것만_ai전달")
    void unmappedClassesDroppedByServer() {
        stubAi(oneDetection());
        ArgumentCaptor<kr.co.cudo.authoring.common.client.dto.YoloTrackRequest> cap =
                ArgumentCaptor.forClass(kr.co.cudo.authoring.common.client.dto.YoloTrackRequest.class);

        // person(매핑됨) + dog(미매핑) 요청 → 서버가 dog 를 제외하고 person 만 전달.
        service.autolabel(SRC_SN, worker, List.of("person", "dog"));

        verify(aiServerClient).predictYoloTrack(cap.capture());
        assertThat(cap.getValue().classes()).containsExactly("person");
    }

    @Test
    @DisplayName("요청_전부_미매핑이면_ai_미호출_빈결과_안내메시지")
    void allUnmappedSkipsAi() {
        AutolabelOnlineService.AutolabelOutcome res =
                service.autolabel(SRC_SN, worker, List.of("dog", "cat"));

        assertThat(res.response().detectedCount()).isZero();
        assertThat(res.message()).isNotNull();
        verify(aiServerClient, never()).predictYoloTrack(any());
    }

    @Test
    @DisplayName("매핑된_라벨이_하나도_없으면_ai_미호출_빈결과_안내메시지")
    void noMappedLabelsSkipsAi() {
        when(labelMasterService.mappedDetectClasses()).thenReturn(new java.util.LinkedHashSet<>());

        AutolabelOnlineService.AutolabelOutcome res = service.autolabel(SRC_SN, worker);

        assertThat(res.response().detectedCount()).isZero();
        assertThat(res.message()).isNotNull();
        verify(aiServerClient, never()).predictYoloTrack(any());
    }

    // ── FEAT-007: 인식 민감도(conf) per-request override + 폴백(무회귀) ──────────

    @Test
    @DisplayName("AI탐지_confThreshold_요청에있으면_그값으로_ai호출한다")
    void confThresholdOverrideForwarded() {
        stubAi(oneDetection());
        ArgumentCaptor<kr.co.cudo.authoring.common.client.dto.YoloTrackRequest> cap =
                ArgumentCaptor.forClass(kr.co.cudo.authoring.common.client.dto.YoloTrackRequest.class);

        service.autolabel(SRC_SN, worker, null, null, 0.75, null);

        verify(aiServerClient).predictYoloTrack(cap.capture());
        assertThat(cap.getValue().confThreshold()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("AI탐지_confThreshold_없으면_시스템설정_기본값으로_호출한다")
    void confThresholdNullUsesSystemConfig() {
        // 시스템설정 YOLO_CONF_THRESHOLD=50(정수 백분율) → 0.50 으로 호출(기존 폴백 경로 유지).
        when(systemConfigService.getInt(ConfigKeys.YOLO_CONF_THRESHOLD)).thenReturn(50);
        stubAi(oneDetection());
        ArgumentCaptor<kr.co.cudo.authoring.common.client.dto.YoloTrackRequest> cap =
                ArgumentCaptor.forClass(kr.co.cudo.authoring.common.client.dto.YoloTrackRequest.class);

        service.autolabel(SRC_SN, worker, null, null, null, null);

        verify(aiServerClient).predictYoloTrack(cap.capture());
        assertThat(cap.getValue().confThreshold()).isEqualTo(0.50);
    }

    @Test
    @DisplayName("AI탐지_시스템설정_조회실패시_코드상수로_폴백한다_회귀방지")
    void confThresholdSystemConfigFailureFallsBackToConstant() {
        // 시스템설정 조회가 예외를 던져도 코드 상수(0.4)로 폴백해야 한다(무회귀).
        when(systemConfigService.getInt(ConfigKeys.YOLO_CONF_THRESHOLD))
                .thenThrow(new RuntimeException("db down"));
        stubAi(oneDetection());
        ArgumentCaptor<kr.co.cudo.authoring.common.client.dto.YoloTrackRequest> cap =
                ArgumentCaptor.forClass(kr.co.cudo.authoring.common.client.dto.YoloTrackRequest.class);

        service.autolabel(SRC_SN, worker, null, null, null, null);

        verify(aiServerClient).predictYoloTrack(cap.capture());
        assertThat(cap.getValue().confThreshold()).isEqualTo(0.4);
    }

    // ── F-1: 오케스트레이션 비트랜잭셔널(커넥션 미점유) ─────────────────────────

    @Test
    @DisplayName("오케스트레이션은_비트랜잭셔널이다_AI블로킹동안_DB커넥션_미점유")
    void orchestrationIsNonTransactional() throws Exception {
        var autolabelMethod = AutolabelOnlineService.class.getMethod("autolabel", Long.class, TokenClaims.class);
        assertThat(autolabelMethod.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(AutolabelOnlineService.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    // ── F-2: 동시 호출 제한(bulkhead) ────────────────────────────────────────────

    @Test
    @DisplayName("온라인_AI경로_동시_초과요청시_bulkhead_거부_429_TOO_MANY_REQUESTS")
    void bulkheadRejectsExcessConcurrent() throws Exception {
        Bulkhead bulkhead = Bulkhead.of("aiOnlineTest1", BulkheadConfig.custom()
                .maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        service = buildService(bulkhead);

        Long srcSn2 = 5002L;
        LsDataSrc src2 = LsDataSrc.create(9002L, 1, "1.jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src2, "srcSn", srcSn2);
        when(accessGuard.verifyAndGet(eq(srcSn2), any())).thenReturn(src2);
        when(workLockService.isRawLocked(9002L)).thenReturn(false);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.fromCallable(() -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return oneDetection();
        }));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> service.autolabel(SRC_SN, worker));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

            Future<?> second = pool.submit(() -> service.autolabel(srcSn2, worker));
            assertThatThrownBy(second::get)
                    .cause()
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

            release.countDown();
            first.get(3, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
