package kr.co.cudo.authoring.observability.health;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.service.AiSrvrHealthPoller;
import kr.co.cudo.authoring.aiserver.service.AiSrvrHealthProbe;
import kr.co.cudo.authoring.aiserver.service.AiSrvrRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ai-server 헬스 체크 — <b>원장 노드별 집계</b>. [@design ADR-057]
 *
 * <h3>단일 URL 에서 원장으로 바뀐 이유</h3>
 * <p>추론 서버가 장비 두 대로 늘었는데 이 인디케이터는 설정값 주소 <b>하나</b>만 핑하고 있었다.
 * 그 상태로는 둘 중 하나가 죽어도 화면이 초록으로 남는다 — 정확히 알아야 할 것을 못 보여 준다.
 *
 * <h3>왜 원장 값을 읽지 않고 <b>직접 핑</b> 하는가</h3>
 * <p>원장의 상태 컬럼은 상태점검 배치가 갱신하는데, 그 배치는 <b>기본적으로 꺼져 있다</b>
 * (추론 서버가 실행을 용도별로 나누기 전에는 관측이 바쁜 장비를 죽은 장비로 오판한다 —
 * {@link AiSrvrHealthPoller} 참조). 그래서 원장만 읽으면 <b>영원히 「가용」으로 보인다</b>.
 * 이 인디케이터는 사람이 볼 때마다 지금 상태를 물어보고, 배치가 켜지면 원장 상태를 <b>함께</b>
 * 보여 준다(둘이 어긋나 있다는 사실 자체가 진단 정보다).
 *
 * <h3>판정 — <b>쓸 수 있는 노드</b>만 센다</h3>
 * <p>판정 모집단은 원장이 「가용」이라 적어 둔 노드뿐이다. 정비중·비활성·이용불가 노드는 프로세스가
 * 살아 있어 핑에 200 을 주지만 <b>배정을 받지 못한다</b> — 그것을 「응답함」으로 세면 가용 노드가
 * 전부 죽었는데도 초록이 남는다(가용 1대 무응답 + 비활성 1대 응답 → 실제 쓸 수 있는 노드는 0).
 * 세지 않는 노드도 <b>상세에는 그대로 싣는다</b>(진단 정보다).
 *
 * <p>모집단 중 하나라도 응답하면 UP 이다. 노드 하나가 죽어도 <b>AI 기능은 계속 동작</b>하므로 그것을
 * DOWN 으로 올리면 집계 헬스가 실제 장애가 아닌데도 붉어진다. 전부 응답하지 않을 때만 DOWN 이다 —
 * 그때는 실제로 아무 추론도 되지 않는다.
 *
 * <h3>★ 셀 노드가 하나도 없으면 DOWN 이 아니라 {@code UNKNOWN} 이다</h3>
 * <p>액추에이터 DOWN 은 <b>오케스트레이터·로드밸런서가 이 인스턴스를 내리는 신호</b>다. 원장이 비어
 * 있거나(등록 전) 관리자가 노드를 전부 내려 둔 상태는 <b>우리 WAS 의 이상이 아니며</b>, 인스턴스를
 * 내려도 해결되지 않는다. 형제 인디케이터({@link VlmHealthIndicator})가 판정 축 이전을 거부한 근거가
 * 정확히 이것이다 — <b>"노드 0건이 되어 상시 DOWN"</b>. 같은 위험을 한쪽만 피하면 모순이다.
 *
 * <p>{@code UNKNOWN} 을 고른 이유: 「판정할 대상이 없다」는 사실 그대로이고, 기본 집계기는 UP 보다
 * 뒤에 두어 다른 인디케이터가 UP 이면 집계가 UP 으로 남으며, 기본 HTTP 매핑이 503 을 내지 않는다.
 * 사유는 {@code reason} 축으로 드러낸다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>CWE-497: <b>노드 주소를 싣지 않는다.</b> 내부 토폴로지이므로 식별자만 노출한다.</li>
 *   <li>CWE-209: 예외 본문·스택트레이스를 싣지 않는다(핑 구현이 예외를 밖으로 내보내지 않는다).</li>
 * </ul>
 *
 * <h3>⚠ 호출 비용과 그 방어</h3>
 * <p>호출 한 번이 노드 수만큼의 <b>순차 블로킹</b> 핑을 낸다(각 상한 2초). 그런데 이 엔드포인트는
 * <b>인증 없이 열려 있어</b>, 방치하면 익명 요청 하나가 노드 수 x 2초짜리 서블릿 스레드 점유가 되고
 * 동시 요청이 스레드 상한을 채우면 <b>앱 전체가 무응답</b>이 된다(CWE-770).
 *
 * <p>방어는 <b>엔드포인트 캐시</b>({@code management.endpoint.health.cache.time-to-live})다 — 병렬
 * 핑은 한 번의 소요를 줄일 뿐 <b>요청 수만큼의 증폭은 그대로 두는데</b>, 캐시는 증폭 자체를 없앤다
 * (익명 호출은 캐시 키가 같아 전부 한 항목을 공유한다). TTL 은 관측 주기와 같은 값으로 두어 그보다
 * 새로운 값이 나오지 않는 구간을 낭비하지 않는다.
 */
@Component("aiServerHealth")
public class AiServerHealthIndicator implements HealthIndicator {

    private static final String SERVICE = "ai-server";

    private final AiSrvrRegistry registry;
    private final AiSrvrHealthProbe healthProbe;
    private final AiSrvrHealthPoller poller;

    public AiServerHealthIndicator(AiSrvrRegistry registry,
                                   AiSrvrHealthProbe healthProbe,
                                   AiSrvrHealthPoller poller) {
        this.registry = registry;
        this.healthProbe = healthProbe;
        this.poller = poller;
    }

    @Override
    public Health health() {
        List<LsAiSrvr> nodes;
        try {
            nodes = registry.findAll().stream()
                    .filter(node -> node.getSrvrTypeCd() == LsAiSrvr.SrvrType.INFERENCE)
                    .toList();
        } catch (Exception ledgerUnavailable) {
            // 원장을 읽지 못하면 어디로 보낼지 자체를 모른다 — 노드 문제가 아니라 우리 문제다.
            return Health.down()
                    .withDetail("service", SERVICE)
                    .withDetail("error", ledgerUnavailable.getClass().getSimpleName())
                    .build();
        }

        Map<String, String> byNode = new LinkedHashMap<>();
        Map<String, String> ledger = new LinkedHashMap<>();
        int usable = 0;
        int reachable = 0;
        for (LsAiSrvr node : nodes) {
            boolean up = healthProbe.ping(node);
            byNode.put(node.getSrvrId(), up ? "UP" : "DOWN");
            ledger.put(node.getSrvrId(), node.getSrvrSttsCd().name());
            // ★배정을 받을 수 있는 노드만 판정에 센다. 그 밖의 노드는 응답하더라도 쓸 수 없으므로
            //   「응답함」으로 세면 실제 가용량이 0인데 초록이 남는다.
            if (node.getSrvrSttsCd() == AiSrvrStatus.AVAILABLE) {
                usable++;
                reachable += up ? 1 : 0;
            }
        }

        Health.Builder builder;
        if (usable == 0) {
            // 등록 전이거나 관리자가 전부 내려 둔 상태 — 우리 WAS 의 이상이 아니다. DOWN 을 내면
            // 인스턴스가 회전에서 빠지는데 그것으로 해결되지 않는다(형제 인디케이터와 같은 판단).
            builder = Health.unknown().withDetail("reason", "no-usable-node");
        } else {
            builder = reachable > 0 ? Health.up() : Health.down();
        }
        return builder
                .withDetail("service", SERVICE)
                .withDetail("nodes", nodes.size())
                // 판정 모집단(원장이 「가용」이라 적어 둔 노드 수)과 그중 응답한 수.
                .withDetail("usable", usable)
                .withDetail("reachable", reachable)
                // 식별자만 — 주소는 싣지 않는다(CWE-497).
                .withDetail("byNode", byNode)
                // 원장이 적어 둔 상태. 관측이 꺼져 있으면 이 값은 갱신되지 않는다.
                .withDetail("ledgerStatus", ledger)
                // ⚠ 이것은 <관측 배치>의 켜짐 여부다. 이 인디케이터의 핑은 이 값과 무관하게 나간다.
                .withDetail("observation", poller.isPollEnabled() ? "enabled" : "disabled")
                .build();
    }
}
