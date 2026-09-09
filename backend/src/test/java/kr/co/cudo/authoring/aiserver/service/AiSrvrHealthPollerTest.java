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

    /**
     * ⚠ <b>구 시험 폐기(2026-09-08)</b> — 여기 {@code 추론_서버가_아닌_노드는_상태점검_대상이_아니다}
     * 가 있었고 시계열 노드에 대해 {@code verifyNoInteractions(healthProbe, ...)} 를 걸었다.
     * <b>그 계약이 뒤집혔다</b> — 상태는 두 계통 다 잰다. 시험을 그대로 두면 새 계약이 RED 로 잡힌다.
     * 아래 두 시험이 그 자리를 대신하며, <b>「상태는 잰다 / 부하는 안 잰다」를 갈라서</b> 고정한다.
     */
    @Test
    @DisplayName("★시계열_노드도_상태점검_대상이다_구_제외필터_폐기")
    void 시계열_노드도_상태점검_대상이다() {
        // given — 시계열 장비가 죽어도 「가용」으로 남으면 위탁이 죽은 주소로 계속 나간다.
        LsAiSrvr timeseries = timeseriesNode("vendor1");
        given(repository.findAll()).willReturn(List.of(timeseries));
        given(healthProbe.ping(any())).willReturn(false);

        poller(true).pollAll();

        verify(healthProbe).ping(timeseries);
        verify(txService).applyHealth("vendor1", false, NOW);
    }

    @Test
    @DisplayName("★시계열_노드에는_부하를_묻지_않는다_상태와_부하는_다른_축이다")
    void 시계열_노드에는_부하를_묻지_않는다() {
        // given — 제출 후 콜백이라 「처리 대기」 개념이 없다. 그 축의 부하 원천은 우리 위탁 원장이다.
        LsAiSrvr timeseries = timeseriesNode("vendor1");
        given(repository.findAll()).willReturn(List.of(timeseries));
        given(healthProbe.ping(any())).willReturn(true);

        poller(true).pollAll();

        verify(txService).applyHealth("vendor1", true, NOW);
        verifyNoInteractions(loadProbe);
    }

    @Test
    @DisplayName("★추론_노드에는_상태와_부하를_모두_묻는다")
    void 추론_노드에는_상태와_부하를_모두_묻는다() {
        LsAiSrvr inference = node("gpu01");
        given(repository.findAll()).willReturn(List.of(inference));
        given(healthProbe.ping(any())).willReturn(true);
        given(loadProbe.probe(any())).willReturn(AiSrvrLoadReport.notReporting());

        poller(true).pollAll();

        verify(healthProbe).ping(inference);
        verify(loadProbe).probe(inference);
    }

    @Test
    @DisplayName("★두_계통이_섞여_있어도_상태는_둘_다_재고_부하는_추론만_잰다")
    void 두_계통이_섞여_있어도_상태는_둘_다_재고_부하는_추론만_잰다() {
        LsAiSrvr inference = node("gpu01");
        LsAiSrvr timeseries = timeseriesNode("vendor1");
        given(repository.findAll()).willReturn(List.of(inference, timeseries));
        given(healthProbe.ping(any())).willReturn(true);
        given(loadProbe.probe(any())).willReturn(AiSrvrLoadReport.notReporting());

        poller(true).pollAll();

        verify(txService).applyHealth("gpu01", true, NOW);
        verify(txService).applyHealth("vendor1", true, NOW);
        verify(loadProbe).probe(inference);
        verify(loadProbe, never()).probe(timeseries);
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

    // --- 계통별 틱 [@design AC-1099] --------------------------------------------------------------

    /**
     * ★ 계통을 지정하면 <b>그 계통만</b> 관측한다 — 주기를 계통마다 두려면 틱도 계통별이어야 한다.
     */
    @Test
    @DisplayName("★계통을_지정하면_그_계통만_관측한다")
    void 계통을_지정하면_그_계통만_관측한다() {
        LsAiSrvr inference = node("gpu01");
        LsAiSrvr timeseries = timeseriesNode("vendor1");
        given(repository.findAll()).willReturn(List.of(inference, timeseries));
        given(healthProbe.ping(any())).willReturn(true);
        given(loadProbe.probe(any())).willReturn(AiSrvrLoadReport.notReporting());

        poller(true).poll(LsAiSrvr.SrvrType.TIMESERIES);

        verify(healthProbe).ping(timeseries);
        verify(healthProbe, never()).ping(inference);
    }

    /** 대칭 — 추론 틱은 시계열 장비를 건드리지 않는다(그쪽 벤더를 우리 주기로 두드리지 않는다). */
    @Test
    @DisplayName("★추론_틱은_시계열_장비를_두드리지_않는다")
    void 추론_틱은_시계열_장비를_두드리지_않는다() {
        LsAiSrvr inference = node("gpu01");
        LsAiSrvr timeseries = timeseriesNode("vendor1");
        given(repository.findAll()).willReturn(List.of(inference, timeseries));
        given(healthProbe.ping(any())).willReturn(true);
        given(loadProbe.probe(any())).willReturn(AiSrvrLoadReport.notReporting());

        poller(true).poll(LsAiSrvr.SrvrType.INFERENCE);

        verify(healthProbe).ping(inference);
        verify(healthProbe, never()).ping(timeseries);
    }

    /**
     * ★★ 평활 표본 정리는 <b>전 계통</b> 기준이다 — 계통으로 좁히면 다른 계통 표본이 매 틱 지워진다.
     *
     * <p>부하 표본을 갖는 것은 추론뿐이라, 시계열 틱이 자기 계통만 기준으로 정리하면 <b>추론의 평활
     * 창이 통째로 비고</b> 바쁜 장비가 가장 한가한 장비로 보여 요청을 빨아들인다(재기동 직후와 같은
     * 상태가 시계열 주기마다 재현된다).
     */
    @Test
    @DisplayName("★시계열_틱이_추론의_평활_표본을_지우지_않는다")
    void 시계열_틱이_추론의_평활_표본을_지우지_않는다() {
        given(repository.findAll()).willReturn(List.of(node("gpu01"), timeseriesNode("vendor1")));
        given(healthProbe.ping(any())).willReturn(true);
        given(loadProbe.probe(any())).willReturn(AiSrvrLoadReport.notReporting());

        poller(true).poll(LsAiSrvr.SrvrType.TIMESERIES);

        verify(smoother).retainOnly(Set.of("gpu01", "vendor1"));
    }

    /** 그 계통에 장비가 하나도 없어도 정리는 <b>먼저</b> 한다(기존 성질을 계통 축에서도 유지). */
    @Test
    @DisplayName("그_계통에_장비가_없어도_평활_표본_정리는_수행한다")
    void 그_계통에_장비가_없어도_평활_표본_정리는_수행한다() {
        given(repository.findAll()).willReturn(List.of(node("gpu01")));

        poller(true).poll(LsAiSrvr.SrvrType.TIMESERIES);

        verify(smoother).retainOnly(Set.of("gpu01"));
        verifyNoInteractions(healthProbe);
    }

    /**
     * ★★ 계통을 모르면 <b>아무것도 하지 않는다</b> (fail-closed · 2026-09-08).
     *
     * <p>⚠ <b>구 시험 폐기</b> — <i>「계통을 모르면(구 등록 행) 전 계통을 훑는다 — 안 도는 쪽이 더
     * 위험하다」</i>를 고정하고 있었다. 계통 전용 잡이 따로 등록된 뒤로 그 폴백은 <b>두 경로가 같은
     * 장비를 함께 훑게</b> 만들고, 두 경로는 이름이 달라 서로를 막지 못해 같은 연속 실패 계수를 각각
     * 읽고 각각 써 <b>한쪽 갱신이 유실</b>된다 — 죽은 장비가 가용으로 남는다. 즉 그 시험의 근거였던
     * 「안 도는 쪽이 더 위험하다」의 전제가 뒤집혔다.
     */
    @Test
    @DisplayName("★★계통을_주지_않으면_아무것도_하지_않는다_구_전계통_폴백_폐기")
    void 계통을_주지_않으면_아무것도_하지_않는다() {
        LsAiSrvr inference = node("gpu01");
        LsAiSrvr timeseries = timeseriesNode("vendor1");
        given(repository.findAll()).willReturn(List.of(inference, timeseries));

        poller(true).poll(null);

        verifyNoInteractions(healthProbe);
        verify(repository, never()).findAll();
    }

    /**
     * ★ 대조 — <b>전 계통을 훑는 길 자체는 남아 있다</b>(명시 호출).
     *
     * <p>위험한 것은 그 동작이 아니라 <b>모를 때 그리로 떨어지는 것</b>이다. 이 대조가 없으면 다음
     * 사람이 fail-closed 를 「전 계통 스캔을 없앤 것」으로 읽는다.
     */
    @Test
    @DisplayName("★대조_전_계통은_명시_호출로만_훑는다")
    void 전_계통은_명시_호출로만_훑는다() {
        LsAiSrvr inference = node("gpu01");
        LsAiSrvr timeseries = timeseriesNode("vendor1");
        given(repository.findAll()).willReturn(List.of(inference, timeseries));
        given(healthProbe.ping(any())).willReturn(true);
        given(loadProbe.probe(any())).willReturn(AiSrvrLoadReport.notReporting());

        poller(true).pollAll();

        verify(healthProbe).ping(inference);
        verify(healthProbe).ping(timeseries);
    }

    private static boolean anyBooleanArg() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    private static LsAiSrvr timeseriesNode(String srvrId) {
        return LsAiSrvr.register(srvrId, null, "https://vendor.example",
                LsAiSrvr.SrvrType.TIMESERIES, NOW);
    }

    private static LsAiSrvr node(String srvrId) {
        return LsAiSrvr.register(srvrId, null, "http://ai.internal:9300",
                LsAiSrvr.SrvrType.INFERENCE, NOW);
    }
}
