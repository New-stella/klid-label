package kr.co.cudo.authoring.label.service;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * S7 (DEV_FIX-D) — {@link AutolabelOnlineService} 의 <b>신고 게이트 배선 3지점</b>을 각각 못박는다.
 *
 * <h3>왜 이 파일이 따로 필요한가</h3>
 * 온라인 오토라벨은 {@code FrameImageEncoder.encodeFrame} 안에도 같은 게이트를 갖고 있어서,
 * 실제 인코더를 끼운 테스트({@code AiInferenceDeidentReportGateTest})에서는 <b>서비스 쪽 게이트 3줄을
 * 전부 지워도 전 테스트가 통과</b>했다(인코더 게이트에 가려짐). 그러면 회귀 방어가 명목상이다.
 * 그래서 여기서는 <b>인코더를 mock 으로 대체</b>해 인코더 게이트를 무력화하고, 서비스 배선만이 유일한
 * 방어선인 상태에서 각 지점을 검증한다.
 *
 * <p>배선 3지점과 각각을 지웠을 때 실패하는 테스트:
 * <ul>
 *   <li><b>진입</b>({@code autolabel} 최상단 {@code requireNotBlocked}) →
 *       {@link #entryGateBlocksBeforeAnyAiCall} — 지우면 YOLO 가 호출되어
 *       {@code verifyNoInteractions(aiServerClient)} 가 깨진다.</li>
 *   <li><b>폴리곤 루프 중</b>({@code polygonAutolabel} 반복문 선두) →
 *       {@link #midLoopGateStopsFurtherSamCallsWhenReportedDuringBatch} — 지우면 SAM 이 2회 호출된다.
 *       이 경로는 이미지 1회 인코딩 후 박스마다 <b>재전송</b>하므로 루프 중 게이트가 유일한 중간 차단이다.</li>
 *   <li><b>마감 직전</b>({@code reCheckLock}) →
 *       {@link #finalGateBlocksCoordinateReturnWhenReportedAfterAiCall} — 지우면 좌표가 반환된다.</li>
 * </ul>
 */
class AutolabelGateWiringTest {

    private static final long RAW_SN = 7100L;
    private static final long SRC_SN = 7101L;

    private AiServerClient aiServerClient;
    private LabelAccessGuard accessGuard;
    private SystemConfigService systemConfigService;
    private WorkLockService workLockService;
    private FrameImageEncoder frameImageEncoder;
    private LabelMasterService labelMasterService;
    private DeidentReportGate deidentReportGate;

    private AutolabelOnlineService service;
    private TokenClaims worker;

    @BeforeEach
    void setUp() {
        aiServerClient = mock(AiServerClient.class);
        accessGuard = mock(LabelAccessGuard.class);
        systemConfigService = mock(SystemConfigService.class);
        workLockService = mock(WorkLockService.class);
        // ★ 인코더는 mock — 인코더 내부 게이트를 무력화해 "서비스 배선만" 남긴다.
        frameImageEncoder = mock(FrameImageEncoder.class);
        labelMasterService = mock(LabelMasterService.class);
        deidentReportGate = mock(DeidentReportGate.class);

        Bulkhead bulkhead = Bulkhead.of("aiOnlineGateWiring", BulkheadConfig.custom()
                .maxConcurrentCalls(25).maxWaitDuration(Duration.ZERO).build());
        service = new AutolabelOnlineService(aiServerClient, accessGuard, systemConfigService,
                workLockService, frameImageEncoder, labelMasterService, deidentReportGate, bulkhead);
        ReflectionTestUtils.setField(service, "polygonTotalBudget", Duration.ofSeconds(30));

        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "0.jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", SRC_SN);
        when(accessGuard.verifyAndGet(eq(SRC_SN), any())).thenReturn(src);
        when(frameImageEncoder.encodeFrame(any())).thenReturn("BASE64IMG");
        when(systemConfigService.getInt(any())).thenReturn(null);
        when(systemConfigService.getDouble(any())).thenReturn(null);
        when(labelMasterService.findLabelIdByDtctType(anyString())).thenReturn(Optional.empty());
        when(labelMasterService.mappedDetectClasses())
                .thenReturn(new LinkedHashSet<>(List.of("person")));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);

        worker = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    /** {@code requireNotBlocked} 호출 순번마다 다른 판정을 돌려주는 게이트(신고 시점 시뮬레이션). */
    private void gateBlocksFromNthCall(int nth) {
        AtomicInteger calls = new AtomicInteger();
        when(deidentReportGate.isUnderDeidentReport(RAW_SN))
                .thenAnswer(inv -> calls.incrementAndGet() >= nth);
    }

    private YoloResponse detections(int n) {
        List<YoloResponse.Detection> ds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double base = 10.0 + i * 50;
            ds.add(new YoloResponse.Detection("person",
                    List.of(base, base, base + 30, base + 40), 0.9, i));
        }
        return new YoloResponse(ds);
    }

    private void stubSam() {
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(1.0, 1.0), List.of(9.0, 1.0), List.of(9.0, 9.0)),
                0.9, false, "model", null)));
    }

    // ─────────────── 배선 ① 진입 게이트 ───────────────

    @Test
    @DisplayName("진입_게이트가_있으면_신고구간_오토라벨은_ai_server를_한번도_호출하지_않는다")
    void entryGateBlocksBeforeAnyAiCall() {
        // given — 진입 시점부터 신고 구간(자기 영상은 잠기지 않아 409 로도 걸리지 않는 파생 케이스).
        gateBlocksFromNthCall(1);
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(detections(1)));

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // ★ 진입 배선을 지우면 마감 게이트가 여전히 412 를 던져 예외 단언은 통과하지만,
        //   YOLO 가 이미 호출되어 이 단언이 깨진다(= 배선 삭제 감지).
        verifyNoInteractions(aiServerClient);
    }

    // ─────────────── 배선 ② 폴리곤 루프 중 게이트 ───────────────

    @Test
    @DisplayName("폴리곤_루프중_게이트가_있으면_배치_도중_신고시_남은_박스는_전송되지_않는다")
    void midLoopGateStopsFurtherSamCallsWhenReportedDuringBatch() {
        // given — 박스 2개. 게이트 호출 순번: 1=진입, 2=루프 i0, 3=루프 i1, 4=마감.
        //         3번째부터 차단 → SAM 은 i0 에 대해 1회만 나가야 한다.
        gateBlocksFromNthCall(3);
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(detections(2)));
        stubSam();

        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker, List.of("person"), AutolabelShape.POLYGON))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // ★ 루프 중 배선을 지우면 두 박스 모두 전송된다(같은 PII 이미지 재전송) → 이 단언이 깨진다.
        verify(aiServerClient, times(1)).segment(any());
    }

    // ─────────────── 배선 ③ 마감 직전 게이트 ───────────────

    @Test
    @DisplayName("마감_게이트가_있으면_AI_호출_후_신고가_들어와도_좌표를_반환하지_않는다")
    void finalGateBlocksCoordinateReturnWhenReportedAfterAiCall() {
        // given — 진입은 통과(1번째 false), AI 블로킹 호출 도중 신고가 커밋된 상황(2번째 true).
        gateBlocksFromNthCall(2);
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(detections(1)));

        // ★ 마감 배선을 지우면 예외 없이 좌표 1건이 반환되어 이 단언이 깨진다.
        assertThatThrownBy(() -> service.autolabel(SRC_SN, worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        verify(aiServerClient).predictYoloTrack(any()); // TOCTOU 구간 재현 — AI 는 실제로 호출됐다
    }

    // ─────────────── 회귀 — 신고가 없으면 기존 동작 유지 ───────────────

    @Test
    @DisplayName("신고가_없으면_세_배선_모두_통과해_기존대로_좌표를_반환한다")
    void allGatesPassWhenNotReported() {
        when(deidentReportGate.isUnderDeidentReport(RAW_SN)).thenReturn(false);
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(detections(2)));
        stubSam();

        var outcome = service.autolabel(SRC_SN, worker, List.of("person"), AutolabelShape.POLYGON);

        assertThat(outcome.response().labels()).hasSize(2);
        verify(aiServerClient, times(2)).segment(any());
    }
}
