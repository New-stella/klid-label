package kr.co.cudo.authoring.observability.health;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.service.AiSrvrHealthPoller;
import kr.co.cudo.authoring.aiserver.service.AiSrvrHealthProbe;
import kr.co.cudo.authoring.aiserver.service.AiSrvrRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * ai-server 헬스 인디케이터의 <b>판정 분기와 상세 축</b> 검증. [@design ADR-057]
 *
 * <p>여기서 지키는 것은 둘이다.
 * <ul>
 *   <li><b>판정 모집단</b> — 배정을 받을 수 있는 노드(원장 「가용」)만 센다. 비활성·정비중 노드는
 *       프로세스가 살아 응답하지만 쓸 수 없으므로, 그것을 세면 실제 가용량이 0인데도 초록이 남는다.</li>
 *   <li><b>0건이 인스턴스를 죽이지 않는다</b> — 액추에이터 DOWN 은 오케스트레이터가 인스턴스를
 *       내리는 신호다. 형제 인디케이터가 판정 축 이전을 거부한 근거(「노드 0건 → 상시 DOWN」)와
 *       같은 실패형을 여기서도 만들지 않는다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiServerHealthIndicatorTest {

    @Mock private AiSrvrRegistry registry;
    @Mock private AiSrvrHealthProbe healthProbe;
    @Mock private AiSrvrHealthPoller poller;

    private AiServerHealthIndicator indicator() {
        return new AiServerHealthIndicator(registry, healthProbe, poller);
    }

    @Test
    @DisplayName("가용노드가_하나라도_응답하면_UP_이고_죽은_노드는_상세에_남는다")
    void 가용노드가_하나라도_응답하면_UP_이고_죽은_노드는_상세에_남는다() {
        // given — 한 대는 응답하고 한 대는 죽었다. 노드 하나가 죽어도 AI 기능은 계속 동작한다.
        given(registry.findAll()).willReturn(List.of(
                node("gpu01", AiSrvrStatus.AVAILABLE),
                node("gpu02", AiSrvrStatus.AVAILABLE)));
        given(healthProbe.ping(any())).willAnswer(call ->
                "gpu01".equals(((LsAiSrvr) call.getArgument(0)).getSrvrId()));

        // when
        Health health = indicator().health();

        // then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("usable", 2).containsEntry("reachable", 1);
        assertThat(health.getDetails().get("byNode").toString())
                .contains("gpu01=UP").contains("gpu02=DOWN");
    }

    @Test
    @DisplayName("가용노드가_전부_응답하지_않으면_DOWN_이다")
    void 가용노드가_전부_응답하지_않으면_DOWN_이다() {
        given(registry.findAll()).willReturn(List.of(node("gpu01", AiSrvrStatus.AVAILABLE)));
        given(healthProbe.ping(any())).willReturn(false);

        Health health = indicator().health();

        // 그때는 실제로 아무 추론도 되지 않는다 — 이 경우에만 DOWN 이다.
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reachable", 0);
    }

    @Test
    @DisplayName("쓸_수_없는_상태의_노드가_응답해도_가용량으로_세지_않는다")
    void 쓸_수_없는_상태의_노드가_응답해도_가용량으로_세지_않는다() {
        // given — 가용 1대는 무응답, 비활성 1대는 응답. ★실제로 쓸 수 있는 노드는 0 이다.
        given(registry.findAll()).willReturn(List.of(
                node("gpu01", AiSrvrStatus.AVAILABLE),
                node("gpu02", AiSrvrStatus.DISABLED)));
        given(healthProbe.ping(any())).willAnswer(call ->
                "gpu02".equals(((LsAiSrvr) call.getArgument(0)).getSrvrId()));

        // when
        Health health = indicator().health();

        // then — 응답한 비활성 노드가 죽은 가용 노드를 가려 초록으로 만들면 안 된다.
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("usable", 1).containsEntry("reachable", 0);
        // 세지 않는 노드도 진단을 위해 상세에는 그대로 남긴다.
        assertThat(health.getDetails().get("byNode").toString()).contains("gpu02=UP");
        assertThat(health.getDetails().get("ledgerStatus").toString()).contains("gpu02=DISABLED");
    }

    @Test
    @DisplayName("정비중_노드도_판정_모집단에서_빠진다")
    void 정비중_노드도_판정_모집단에서_빠진다() {
        // 정비중은 신규 배정을 받지 않는다 — 응답 여부와 무관하게 가용량이 아니다.
        given(registry.findAll()).willReturn(List.of(node("gpu01", AiSrvrStatus.DRAINING)));
        given(healthProbe.ping(any())).willReturn(true);

        Health health = indicator().health();

        assertThat(health.getDetails()).containsEntry("usable", 0);
        assertThat(health.getStatus()).isNotEqualTo(Status.UP);
    }

    @Test
    @DisplayName("셀_노드가_하나도_없으면_DOWN_이_아니다_인스턴스를_내리는_신호가_되면_안_된다")
    void 셀_노드가_하나도_없으면_DOWN_이_아니다_인스턴스를_내리는_신호가_되면_안_된다() {
        // given — 원장에 추론 노드가 없다(등록 전이거나 관리자가 전부 내려 둔 상태)
        given(registry.findAll()).willReturn(List.of());

        // when
        Health health = indicator().health();

        // then — ★DOWN 이면 오케스트레이터·LB 가 이 인스턴스를 회전에서 뺀다. 우리 WAS 의 이상이
        //        아니므로 그렇게 판정하지 않는다(형제 VLM 인디케이터가 든 근거와 같다).
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("nodes", 0).containsEntry("usable", 0);
        assertThat(health.getDetails()).containsEntry("reason", "no-usable-node");
    }

    @Test
    @DisplayName("시계열_노드는_이_인디케이터의_판정_대상이_아니다")
    void 시계열_노드는_이_인디케이터의_판정_대상이_아니다() {
        // 시계열 축은 논블로킹 제출 + 콜백이라 이 경로의 상태 개념이 성립하지 않는다.
        given(registry.findAll()).willReturn(List.of(node("vlm01", AiSrvrStatus.AVAILABLE,
                LsAiSrvr.SrvrType.TIMESERIES)));

        Health health = indicator().health();

        assertThat(health.getDetails()).containsEntry("nodes", 0);
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("원장을_읽지_못하면_DOWN_이고_예외는_클래스명만_남는다")
    void 원장을_읽지_못하면_DOWN_이고_예외는_클래스명만_남는다() {
        // given — 어디로 보낼지 자체를 모른다. 노드 문제가 아니라 우리 문제다.
        willThrow(new IllegalStateException("jdbc://secret@db/klid_at 접속 실패\nat kr.co.cudo..."))
                .given(registry).findAll();

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        // CWE-209 — 메시지·스택트레이스를 싣지 않는다.
        assertThat(health.getDetails()).containsEntry("error", "IllegalStateException");
        assertThat(health.getDetails().toString()).doesNotContain("\n", "jdbc", "at kr.co.cudo");
    }

    @Test
    @DisplayName("관측배치_켜짐표시는_상세일_뿐_핑을_막지_않는다")
    void 관측배치_켜짐표시는_상세일_뿐_핑을_막지_않는다() {
        // ★「꺼져 있으면 외부 호출이 한 건도 나가지 않는다」는 <관측 배치>에 대한 서술이다.
        //   이 인디케이터는 그 설정과 무관하게 매 호출 노드를 직접 핑한다(그래서 원장만 읽었을 때
        //   영원히 「가용」으로 보이는 문제를 피한다).
        given(registry.findAll()).willReturn(List.of(node("gpu01", AiSrvrStatus.AVAILABLE)));
        given(healthProbe.ping(any())).willReturn(true);
        given(poller.isPollEnabled()).willReturn(false);

        Health health = indicator().health();

        assertThat(health.getDetails()).containsEntry("observation", "disabled");
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("reachable", 1);
    }

    @Test
    @DisplayName("상세에_노드_주소를_싣지_않는다")
    void 상세에_노드_주소를_싣지_않는다() {
        given(registry.findAll()).willReturn(List.of(node("gpu01", AiSrvrStatus.AVAILABLE)));
        given(healthProbe.ping(any())).willReturn(true);

        // 내부 토폴로지는 노출하지 않는다(CWE-497) — 식별자만 남긴다.
        assertThat(indicator().health().getDetails().toString())
                .doesNotContain("http://").contains("gpu01");
    }

    // --- fixtures ----------------------------------------------------------------------------

    private static LsAiSrvr node(String srvrId, AiSrvrStatus status) {
        return node(srvrId, status, LsAiSrvr.SrvrType.INFERENCE);
    }

    private static LsAiSrvr node(String srvrId, AiSrvrStatus status, LsAiSrvr.SrvrType type) {
        LsAiSrvr node = LsAiSrvr.register(srvrId, null, "http://" + srvrId + ":9300", type,
                LocalDateTime.now());
        // 상태 전이는 원장 저장소의 조건부 UPDATE 가 소유한다(엔티티에 setter 가 없다) —
        // 여기서는 <읽기 전용 스냅샷>의 상태만 필요하므로 필드를 직접 세운다.
        ReflectionTestUtils.setField(node, "srvrSttsCd", status);
        return node;
    }
}
