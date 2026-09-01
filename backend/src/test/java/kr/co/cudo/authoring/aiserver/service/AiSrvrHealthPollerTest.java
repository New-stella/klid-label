package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrSlotLoad;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 상태점검 폴러의 <b>순회·격리·게이팅</b> 검증. [@design ADR-057]
 *
 * <p>상태 전이 규칙 자체는 {@code AiSrvrHealthTxServiceTest} 가 갖는다. 여기서 지키는 것은
 * "누구에게 무엇을 묻고, 실패했을 때 어디까지 번지는가"다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiSrvrHealthPollerTest {

    @Mock private LsAiSrvrRepository repository;
    @Mock private AiSrvrHealthProbe healthProbe;
    @Mock private AiSrvrLoadProbe loadProbe;
    @Mock private AiSrvrLoadSmoother smoother;
    @Mock private AiSrvrHealthTxService txService;

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-01T04:12:33Z"), ZoneId.of("UTC"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private AiSrvrHealthPoller poller(boolean enabled) {
        return new AiSrvrHealthPoller(repository, healthProbe, loadProbe, smoother, txService,
                enabled, CLOCK);
    }

    @Test
    @DisplayName("폴링이_꺼져_있으면_외부호출이_한_건도_나가지_않는다")
    void 폴링이_꺼져_있으면_외부호출이_한_건도_나가지_않는다() {
        // given — ★슬롯 분리 전에는 추론이 서버의 처리 흐름을 통째로 붙잡아 상태 점검조차 늦다.
        //        그 시기에 켜면 바쁜 장비를 죽은 장비로 오판하고, 2대 이상이면 멀쩡한 장비를 하나 잃는다.
        given(repository.findAll()).willReturn(List.of(node("gpu01"), node("gpu02")));

        // when
        poller(false).pollAll();

        // then — 상태 점검 축까지 함께 꺼져 있어야 한다(부하 축만 끄면 오판이 그대로 남는다)
        verifyNoInteractions(healthProbe, loadProbe, txService, smoother, repository);
    }

    @Test
    @DisplayName("추론_노드가_한_건도_없어도_평활_표본을_정리한다")
    void 추론_노드가_한_건도_없어도_평활_표본을_정리한다() {
        // given — 원장의 추론 노드를 전부 지운 상태(시계열 노드만 남았거나 아예 비었다)
        given(repository.findAll()).willReturn(List.of());

        // when
        poller(true).pollAll();

        // then — ★정리를 건너뛰면 같은 식별자로 다시 세웠을 때 <죽은 장비의 혼잡 기억>이 새 장비로
        //        전이된다. 정리가 막겠다고 선언한 바로 그 상황이므로 노드 0건에서도 반드시 돈다.
        verify(smoother).retainOnly(Set.of());
        verifyNoInteractions(healthProbe, loadProbe, txService);
    }

    @Test
    @DisplayName("부하경로가_없는_경로라고_답하면_헬스실패로_세지_않는다")
    void 부하경로가_없는_경로라고_답하면_헬스실패로_세지_않는다() {
        // given — 되돌리기·배포 순서 어긋남으로 구 버전 노드가 섞인 구간
        given(repository.findAll()).willReturn(List.of(node("gpu01")));
        given(healthProbe.ping(any())).willReturn(true);
        given(loadProbe.probe(any())).willReturn(AiSrvrLoadReport.notReporting());

        // when
        poller(true).pollAll();

        // then — 헬스는 성공으로 기록된다. 「부하를 안 알린다」는 「이상하다」가 아니다.
        verify(txService).applyHealth("gpu01", true, NOW);
        verify(txService, never()).applyLoad(anyString(), any(), any());
    }

    @Test
    @DisplayName("부하조회가_타임아웃되면_직전값이_유지되고_과부하로_판정하지_않는다")
    void 부하조회가_타임아웃되면_직전값이_유지되고_과부하로_판정하지_않는다() {
        given(repository.findAll()).willReturn(List.of(node("gpu01")));
        given(healthProbe.ping(any())).willReturn(true);
        given(loadProbe.probe(any())).willReturn(AiSrvrLoadReport.unknown());

        poller(true).pollAll();

        // then — 저장된 값을 건드리지 않고(직전 값 유지), 평활기에 지어낸 값을 먹이지도 않는다
        verify(txService, never()).applyLoad(anyString(), any(), any());
        verify(smoother, never()).smooth(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("평활기에는_실효부하가_들어간다")
    void 평활기에는_실효부하가_들어간다() {
        given(repository.findAll()).willReturn(List.of(node("gpu01")));
        given(healthProbe.ping(any())).willReturn(true);
        given(loadProbe.probe(any())).willReturn(AiSrvrLoadReport.reported(Map.of(
                AiSrvrUsageType.BATCH, new AiSrvrSlotLoad(1, 12),
                AiSrvrUsageType.INTERACTIVE, new AiSrvrSlotLoad(0, 0))));

        poller(true).pollAll();

        verify(smoother).smooth("gpu01", AiSrvrUsageType.BATCH, 13);
        verify(smoother).smooth("gpu01", AiSrvrUsageType.INTERACTIVE, 0);
        verify(txService).applyLoad(eq("gpu01"), any(), eq(NOW));
    }

    @Test
    @DisplayName("헬스가_실패한_노드에는_부하를_묻지_않는다")
    void 헬스가_실패한_노드에는_부하를_묻지_않는다() {
        given(repository.findAll()).willReturn(List.of(node("gpu01")));
        given(healthProbe.ping(any())).willReturn(false);

        poller(true).pollAll();

        // 응답조차 못 하는 노드에 한 번 더 물어봐야 얻을 것이 없다(틱마다 상한만큼 더 기다린다)
        verifyNoInteractions(loadProbe);
        verify(txService).applyHealth("gpu01", false, NOW);
    }

    @Test
    @DisplayName("한_노드_갱신_실패가_다른_노드_갱신을_롤백하지_않는다")
    void 한_노드_갱신_실패가_다른_노드_갱신을_롤백하지_않는다() {
        // given — 첫 노드의 갱신이 터진다
        given(repository.findAll()).willReturn(List.of(node("gpu01"), node("gpu02")));
        given(healthProbe.ping(any())).willReturn(true);
        given(loadProbe.probe(any())).willReturn(AiSrvrLoadReport.notReporting());
        willThrow(new IllegalStateException("boom"))
                .given(txService).applyHealth(eq("gpu01"), anyBooleanArg(), any());

        // when
        poller(true).pollAll();

        // then — 두 번째 노드는 정상 갱신된다(노드마다 트랜잭션이 열린다)
        verify(txService).applyHealth("gpu02", true, NOW);
    }

    @Test
    @DisplayName("추론_서버가_아닌_노드는_상태점검_대상이_아니다")
    void 추론_서버가_아닌_노드는_상태점검_대상이_아니다() {
        // given — 시계열 축은 논블로킹 제출 + 콜백이라 이 경로의 부하 개념이 성립하지 않는다
        LsAiSrvr timeseries = LsAiSrvr.register("vendor1", null, "https://vendor.example",
                LsAiSrvr.SrvrType.TIMESERIES, NOW);
        given(repository.findAll()).willReturn(List.of(timeseries));

        poller(true).pollAll();

        verifyNoInteractions(healthProbe, loadProbe, txService);
    }

    @Test
    @DisplayName("살아있는_노드만_평활_표본으로_남긴다")
    void 살아있는_노드만_평활_표본으로_남긴다() {
        given(repository.findAll()).willReturn(List.of(node("gpu01")));
        given(healthProbe.ping(any())).willReturn(true);
        given(loadProbe.probe(any())).willReturn(AiSrvrLoadReport.notReporting());

        poller(true).pollAll();

        verify(smoother).retainOnly(java.util.Set.of("gpu01"));
    }

    private static boolean anyBooleanArg() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    private static LsAiSrvr node(String srvrId) {
        return LsAiSrvr.register(srvrId, null, "http://ai.internal:9300",
                LsAiSrvr.SrvrType.INFERENCE, NOW);
    }
}
