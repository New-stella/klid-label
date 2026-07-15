package kr.co.cudo.authoring.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AutolabelResponse;
import kr.co.cudo.authoring.label.service.AutolabelOnlineService;
import kr.co.cudo.authoring.label.service.AutolabelPersistService;
import kr.co.cudo.authoring.label.service.FrameImageEncoder;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 — YOLO 오토라벨 수동 트리거 온라인 서비스 단위 테스트 (순수 Mockito).
 *
 * <p>HIGH 시나리오 방어:
 * <ul>
 *   <li>IDOR(CWE-639): accessGuard.verifyAndGet 를 ai 호출/저장 전 최우선 수행.</li>
 *   <li>잠금(작업락): isRawLocked → CONFLICT(409), ai 미호출.</li>
 *   <li>동시성: 진행 중 재요청 차단(in-flight) → CONFLICT.</li>
 *   <li>수동 라벨 보존: 삭제 대상은 findAutoLblSnsBySrcSn(auto 만).</li>
 *   <li>idempotent: 재실행 시 기존 auto 라벨 선삭제 후 재삽입.</li>
 * </ul>
 * <p>MED: 좌표 검증, 출처(MANUAL_TRIGGER) 저장, mock 응답은 DB 미저장, ai 타임아웃 502.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutolabelOnlineServiceTest {

    @Mock private AiServerClient aiServerClient;
    @Mock private LsDataLblRepository lblRepository;
    @Mock private LsDataLblAiInfoRepository aiInfoRepository;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private LabelMasterService labelMasterService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private WorkLockService workLockService;
    @Mock private FrameImageEncoder frameImageEncoder;

    private AutolabelOnlineService service;
    private AutolabelPersistService persistService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong lblSnSeq = new AtomicLong(1);

    private static final Long SRC_SN = 5001L;
    private static final Long RAW_SN = 9001L;

    private TokenClaims worker;

    /** 저장 전담 트랜잭션 빈 — 실제 로직으로 위임(mock 리포지토리 주입)해 저장 동작 검증 유지. */
    private AutolabelPersistService buildPersistService() {
        return new AutolabelPersistService(lblRepository, aiInfoRepository,
                labelMasterService, workLockService, objectMapper);
    }

    /** 온라인 AI 경로 bulkhead — 기본은 넉넉한 크기(동시성 제한 테스트에서만 1로 재구성). */
    private AutolabelOnlineService buildService(Bulkhead bulkhead) {
        return new AutolabelOnlineService(aiServerClient, accessGuard, systemConfigService,
                workLockService, frameImageEncoder, persistService, bulkhead);
    }

    @BeforeEach
    void setup() {
        persistService = buildPersistService();
        Bulkhead defaultBulkhead = Bulkhead.of("aiOnlineTest", BulkheadConfig.custom()
                .maxConcurrentCalls(25).maxWaitDuration(Duration.ZERO).build());
        service = buildService(defaultBulkhead);

        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "0.jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", SRC_SN);

        // IDOR 가드 통과 시 프레임 반환.
        when(accessGuard.verifyAndGet(eq(SRC_SN), any())).thenReturn(src);
        when(frameImageEncoder.encodeToBase64(anyString())).thenReturn("BASE64IMG");
        when(systemConfigService.getInt(any())).thenReturn(null); // fallback conf/imgsz/iou
        when(labelMasterService.findLabelIdByName(anyString())).thenReturn(Optional.empty());
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(lblRepository.findAutoLblSnsBySrcSn(SRC_SN)).thenReturn(List.of());
        when(lblRepository.save(any(LsDataLbl.class))).thenAnswer(inv -> {
            LsDataLbl arg = inv.getArgument(0);
            ReflectionTestUtils.setField(arg, "lblSn", lblSnSeq.getAndIncrement());
            return arg;
        });

        Instant exp = Instant.now().plusSeconds(60);
        worker = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, exp);
    }

    private void stubAi(YoloResponse resp) {
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(resp));
    }

    private YoloResponse oneDetection() {
        return new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 3)));
    }

    @Test
    @DisplayName("오토라벨_정상_실행시_BBOX_저장하고_출처_MANUAL_TRIGGER")
    void autolabelSavesBboxWithManualSource() {
        stubAi(oneDetection());

        AutolabelOnlineService.AutolabelOutcome res = service.autolabel(SRC_SN, worker);

        assertThat(res.mock()).isFalse();
        assertThat(res.response().savedCount()).isEqualTo(1);

        ArgumentCaptor<LsDataLbl> lblCap = ArgumentCaptor.forClass(LsDataLbl.class);
        verify(lblRepository).save(lblCap.capture());
        assertThat(lblCap.getValue().getAutoLblYn()).isEqualTo("Y");
        assertThat(lblCap.getValue().getLblTypeCd()).isEqualTo("BBOX");

        var aiCap = ArgumentCaptor.forClass(kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo.class);
        verify(aiInfoRepository).save(aiCap.capture());
        assertThat(aiCap.getValue().getLblSrcCd())
                .isEqualTo(kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo.SRC_YOLO);
        // 출처 구분: 온라인 수동 트리거는 REG_ID = MANUAL_TRIGGER
        assertThat(aiCap.getValue().getRegId()).isEqualTo("MANUAL_TRIGGER");
    }

    @Test
    @DisplayName("잠긴_영상_오토라벨_409_이고_ai_미호출")
    void lockedRawConflict() {
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(true);

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(aiServerClient, never()).predictYoloTrack(any());
        verify(lblRepository, never()).save(any());
    }

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

    @Test
    @DisplayName("수동라벨_존재시_오토라벨_재실행해도_수동라벨_보존_auto만_선삭제")
    void manualLabelsPreservedOnRerun() {
        // findAutoLblSnsBySrcSn 는 auto 라벨(700,701)만 반환 — 수동 라벨은 목록에 없음.
        when(lblRepository.findAutoLblSnsBySrcSn(SRC_SN)).thenReturn(List.of(700L, 701L));
        stubAi(oneDetection());

        service.autolabel(SRC_SN, worker);

        // 삭제는 auto 라벨(700,701)만 — 자식(AI_INFO) → 부모(LBL) 순서.
        verify(aiInfoRepository).deleteByDataLblSnIn(List.of(700L, 701L));
        verify(lblRepository).deleteAllByIdInBatch(List.of(700L, 701L));
    }

    @Test
    @DisplayName("오토라벨_재실행_idempotent_기존_auto없으면_삭제_스킵하고_재삽입")
    void idempotentRerunNoExistingAuto() {
        when(lblRepository.findAutoLblSnsBySrcSn(SRC_SN)).thenReturn(List.of());
        stubAi(oneDetection());

        service.autolabel(SRC_SN, worker);

        // 삭제 대상 없음 → deleteAllByIdInBatch 미호출, 신규 1건 저장.
        verify(lblRepository, never()).deleteAllByIdInBatch(any());
        verify(lblRepository).save(any());
    }

    @Test
    @DisplayName("ai_응답_좌표_4개_아니면_INVALID_INPUT_저장안함")
    void invalidBboxRejected() {
        stubAi(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0), 0.9, 3))));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(lblRepository, never()).save(any());
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
    @DisplayName("ai서버_타임아웃시_502_EXTERNAL_API_ERROR")
    void aiTimeoutMapped() {
        when(aiServerClient.predictYoloTrack(any()))
                .thenReturn(Mono.error(new RuntimeException("read timeout")));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("AutolabelOnline_mock응답이면_DB저장_스킵되고_savedCount0")
    void mockResponseNotPersisted() {
        // 내부 YoloResponse.mock()=true 를 계속 읽어 skip-save 가드 유지 — FE DTO 에 mock 필드가
        // 없어도 savedCount=0 이 미저장 신호. (학습데이터 오염 방지 안전장치 회귀)
        stubAi(new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 3)),
                true, "mock", "weights_missing"));

        AutolabelOnlineService.AutolabelOutcome res = service.autolabel(SRC_SN, worker);

        assertThat(res.mock()).isTrue();
        assertThat(res.response().savedCount()).isZero();
        assertThat(res.response().labels()).isEmpty();
        verify(lblRepository, never()).save(any());
    }

    @Test
    @DisplayName("AutolabelResponse에_mock필드가_없다")
    void autolabelResponseHasNoMockField() {
        boolean hasMock = java.util.Arrays.stream(AutolabelResponse.class.getRecordComponents())
                .anyMatch(rc -> rc.getName().equals("mock"));
        assertThat(hasMock).isFalse();
    }

    @Test
    @DisplayName("검출_없으면_저장0건_정상반환")
    void noDetectionsSavesNothing() {
        stubAi(new YoloResponse(List.of()));

        AutolabelOnlineService.AutolabelOutcome res = service.autolabel(SRC_SN, worker);

        assertThat(res.mock()).isFalse();
        assertThat(res.response().savedCount()).isZero();
        verify(lblRepository, never()).save(any());
    }

    @Test
    @DisplayName("동일프레임_동시요청시_한쪽은_409_라벨중복_안됨")
    void concurrentDuplicateBlocked() throws Exception {
        CountDownLatch aiEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 첫 요청이 ai 호출 단계에서 대기하도록 — in-flight 락 점유 상태 유지.
        when(aiServerClient.predictYoloTrack(any())).thenAnswer(inv -> {
            aiEntered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return Mono.just(oneDetection());
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> service.autolabel(SRC_SN, worker));
            assertThat(aiEntered.await(3, TimeUnit.SECONDS)).isTrue();

            // 두 번째 요청은 in-flight 락에 막혀 CONFLICT.
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

    // ── F-1: AI 호출 트랜잭션 밖 분리 ────────────────────────────────────────────

    @Test
    @DisplayName("AI호출_트랜잭션_밖_오케스트레이션_비트랜잭셔널이고_저장만_트랜잭셔널")
    void aiCallOutsideTransaction() throws Exception {
        // given: 오케스트레이션 메서드/클래스에는 @Transactional 이 없어야 한다(커넥션 미점유 — F-1).
        var autolabelMethod = AutolabelOnlineService.class.getMethod("autolabel", Long.class, TokenClaims.class);
        assertThat(autolabelMethod.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(AutolabelOnlineService.class.isAnnotationPresent(Transactional.class)).isFalse();
        // 저장 전담 메서드에는 @Transactional 이 있어야 한다(삭제+삽입 원자성 — 별도 빈으로 프록시 적용).
        var persistMethod = AutolabelPersistService.class.getMethod(
                "persist", Long.class, Long.class, List.class, TokenClaims.class);
        assertThat(persistMethod.isAnnotationPresent(Transactional.class)).isTrue();

        // when: AI 호출이 저장(DB write)보다 먼저 수행됨을 순서로 증명.
        stubAi(oneDetection());
        service.autolabel(SRC_SN, worker);

        // then: 접근검증 → AI 호출 → 저장 순서 (AI 블로킹이 저장 트랜잭션 밖에서 선행).
        InOrder ord = inOrder(accessGuard, aiServerClient, lblRepository);
        ord.verify(accessGuard).verifyAndGet(eq(SRC_SN), any());
        ord.verify(aiServerClient).predictYoloTrack(any());
        ord.verify(lblRepository).save(any());
    }

    // ── #6 TOCTOU: 저장 직전 잠금 재확인 ─────────────────────────────────────────

    @Test
    @DisplayName("저장직전_잠금재확인_그사이_잠기면_409_저장안함")
    void toctouLockReCheckOnPersist() {
        // given: 오케스트레이션 잠금 체크는 false(통과), 저장 트랜잭션 재확인 시 true(그 사이 비식별 신고가 잠금).
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false, true);
        stubAi(oneDetection());

        // when / then: 저장 직전 재확인에서 잠금 감지 → CONFLICT, 저장 없음(프라이버시 퍼지 불변식 보호).
        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(lblRepository, never()).save(any());
    }

    // ── #5 / F-4: NaN/Infinity 좌표 거부 ─────────────────────────────────────────

    @Test
    @DisplayName("ai_응답_좌표_NaN이면_INVALID_INPUT_저장안함")
    void nanBboxRejected() {
        stubAi(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, Double.NaN, 60.0), 0.9, 3))));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(lblRepository, never()).save(any());
    }

    @Test
    @DisplayName("ai_응답_좌표_Infinity면_INVALID_INPUT_저장안함")
    void infinityBboxRejected() {
        stubAi(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, Double.POSITIVE_INFINITY, 60.0), 0.9, 3))));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(lblRepository, never()).save(any());
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

    // ── F-2: 동시 호출 제한(bulkhead) ────────────────────────────────────────────

    @Test
    @DisplayName("온라인_AI경로_동시_초과요청시_bulkhead_거부_429_TOO_MANY_REQUESTS")
    void bulkheadRejectsExcessConcurrent() throws Exception {
        // given: maxConcurrentCalls=1 bulkhead 로 서비스 재구성. 서로 다른 프레임(in-flight 락 무관).
        Bulkhead bulkhead = Bulkhead.of("aiOnlineTest1", BulkheadConfig.custom()
                .maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        service = buildService(bulkhead);

        Long srcSn2 = 5002L;
        LsDataSrc src2 = LsDataSrc.create(9002L, 1, "1.jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src2, "srcSn", srcSn2);
        when(accessGuard.verifyAndGet(eq(srcSn2), any())).thenReturn(src2);
        when(workLockService.isRawLocked(9002L)).thenReturn(false);
        when(lblRepository.findAutoLblSnsBySrcSn(srcSn2)).thenReturn(List.of());

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 첫 요청이 AI 구독 상태에서 대기 → bulkhead permit(1개) 점유 유지.
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.fromCallable(() -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return oneDetection();
        }));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> service.autolabel(SRC_SN, worker));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

            // 두 번째 요청(다른 프레임)은 bulkhead full → 즉시 거부(TOO_MANY_REQUESTS, 429).
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
