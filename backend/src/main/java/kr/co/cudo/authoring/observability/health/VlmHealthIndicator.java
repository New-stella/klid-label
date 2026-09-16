package kr.co.cudo.authoring.observability.health;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.service.AiSrvrIdPolicy;
import kr.co.cudo.authoring.aiserver.service.AiSrvrRegistry;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmServerStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.codec.DecodingException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.UnsupportedMediaTypeException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 외부 시계열 분석(VLM) 서버 헬스 체크.
 *
 * <h3>판정 = "응답이 오면 UP, 연결 자체가 안 되면 DOWN"</h3>
 * <p>HTTP 응답이 왔다는 것은 <b>서버가 살아 있다는 증거</b>다. 따라서 4xx·5xx 도, <b>응답 본문을
 * 우리가 해석하지 못한 경우</b>(리버스 프록시·LB 가 200 과 함께 HTML 오류 페이지를 준 형상)도 UP 으로
 * 본다. DOWN 은 연결 자체가 성립하지 않은 경우(타임아웃·커넥션 거부·DNS 실패)로 한정한다.
 *
 * <p>이 판정 기준은 관제서버 헬스 인디케이터가 남긴 실사고에서 나왔다 — 관제에 존재하지 않는
 * {@code /health} 를 핑해 403 을 받았고, 그 403 이 예외가 되어 <b>집계 {@code /actuator/health}
 * 전체가 상시 DOWN</b> 이 됐다(dev 실측). 응답이 왔는데 DOWN 으로 판정한 것이 원인이었다.
 *
 * <h3>핑 경로 = 규격이 보장하는 서버 상태 창구</h3>
 * <p>연동 규격(KLID 연동 API v1.1.0 §3.5)이 "요청 전 서버의 처리 가능 여부를 확인한다"는 용도로
 * 정의한 창구를 그대로 쓴다. 헬스 전용 경로·전용 WebClient 를 새로 만들지 않고 실제 위탁에 쓰는
 * {@link VlmClient#fetchStatus(String, boolean)} 를 (주기 점검 표식과 함께) 호출해, 주소·인증 헤더·TLS 구성이
 * 실행 경로와 갈라지지 않게 한다.
 *
 * <h3>미연동이면 DOWN 이 아니라 부재</h3>
 * <p>연동 주소가 주입되지 않았으면 핑할 대상 자체가 없다. 그 상태를 DOWN 으로 리포트하면 위 관제
 * 사고와 똑같이 집계 헬스가 상시 DOWN 이 되므로, <b>빈을 아예 등록하지 않는다</b>.
 *
 * <p>⚠ 등록 조건을 애너테이션으로 "단순화"하지 말 것 — 두 가지가 모두 막혀 있다.
 * <ul>
 *   <li>{@code @ConditionalOnProperty} — {@code application.yml} 이
 *       {@code vlm.client.url: ${VLM_SERVICE_URL:}} 로 <b>키를 항상 정의</b>하므로 미주입 시 값이
 *       "빈 문자열"이다. 이 애너테이션은 "키 존재 + 값이 false 가 아님"으로 판정해 빈 문자열도
 *       <b>매칭</b>시킨다 — 즉 미연동 환경에서 빈이 등록돼 목적을 달성하지 못한다.</li>
 *   <li>{@code @ConditionalOnExpression} — 값 비교는 되지만 <b>기동을 통째로 실패시킬 수 있다</b>.
 *       placeholder 가 SpEL 파싱 <b>이전에</b> 치환되므로 주소 값에 작은따옴표가 들어가면 표현식이
 *       깨져 {@code SpelParseException(EL1046E)} 으로 컨텍스트가 뜨지 않는다.</li>
 * </ul>
 * 그래서 문자열 파싱이 없는 {@link VlmUrlPresentCondition} 으로 판정한다(그 클래스 javadoc 이 근거의
 * 단일 지점이다). 판정 축은 "키 존재"가 아니라 <b>"값이 비지 않음"</b>이다.
 *
 * <h3>원장 노드는 <b>세어서 보여만</b> 준다 (ADR-057)</h3>
 * <p>노드 원장에는 시계열 축({@link LsAiSrvr.SrvrType#TIMESERIES})도 담을 수 있어, 이 인디케이터도
 * 「노드별 집계」로 바꾸는 것이 형제(ai-server)와 대칭이다. 다만 <b>판정은 여전히 연동 클라이언트가
 * 한다</b> — 이유는 셋이다.
 * <ul>
 *   <li>이 채널의 상태 창구는 규격이 정한 전용 경로이지 {@code /health} 가 아니다. 노드마다 그 경로를
 *       부르려면 주소·인증 헤더·TLS 구성을 노드별로 갈라야 하는데, 그 배선은 아직 없다.</li>
 *   <li>원장에 시계열 노드를 넣는 운영이 아직 시작되지 않았다. 지금 판정을 원장으로 옮기면 <b>노드 0건</b>
 *       이 되어 상시 DOWN 이 된다 — 관제 인디케이터가 겪은 사고와 정확히 같은 형태다.</li>
 *   <li>위탁은 실제로 이 클라이언트로 나간다. 헬스가 다른 경로를 보면 <b>실행 경로와 갈라진다</b>.</li>
 * </ul>
 * <p>그래서 원장에 등록된 시계열 노드는 <b>상세에만</b> 싣는다(등록 사실과 원장 상태). 등록이 시작되면
 * 그 목록이 드러나고, 노드별 위탁 배선이 생기는 시점에 판정 축을 옮긴다.
 *
 * <h3>★ 상세에는 <b>고를 수 있는지</b>까지 적는다 [@design ADR-062]</h3>
 * <p>원장 상태를 <b>그대로만</b> 적으면 식별자 형식({@link AiSrvrIdPolicy})을 어긴 노드가 「가용」으로
 * 보인다. 그런데 그 노드는 위탁 후보에서 빠지고, <b>그런 노드밖에 없으면 이 축의 위탁이 전건 거부</b>다
 * — 그 판정이 기동 차단에서 <b>장비를 고르는 시점</b>으로 옮겨졌기 때문이다(전에는 그런 행이 있으면
 * 앱이 뜨지 못해 이 자리에서 만날 수 없었다).
 *
 * <p>★ 이 축은 <b>지금 당장 도달한다</b> — 형제(추론)와 달리 시계열 위탁은 실제로 원장에서 장비를
 * 고른다. 즉 상세를 고치지 않으면 <b>위탁은 전건 거부인데 헬스는 UP</b> 인 창이 열린다.
 *
 * <p>⚠ <b>판정 축은 여전히 옮기지 않는다.</b> 여기서 DOWN 을 내면 위 §미연동 절이 막으려던 「상시
 * DOWN」이 되살아난다 — 원장을 고쳐야 풀리는 상태는 인스턴스를 내려서 해결되지 않는다. 드러내되
 * 판정하지 않는 것이 이 인디케이터의 성질이다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>CWE-209: 예외는 <b>클래스명만</b> 노출한다. 스택트레이스·주소·토큰·응답 본문을 싣지 않는다.</li>
 *   <li>CWE-497: 원장 노드는 <b>식별자만</b> 싣는다. 주소는 내부 토폴로지다.</li>
 * </ul>
 */
@Slf4j
@Component("vlmHealth")
@Conditional(VlmUrlPresentCondition.class)
public class VlmHealthIndicator implements HealthIndicator {

    /**
     * 헬스 핑 타임아웃.
     *
     * <p>{@link VlmClient#fetchStatus()} 자체 타임아웃은 {@code vlm.client.timeout-seconds}(기본 10s)라
     * 그대로 두면 헬스 한 번이 10초를 잡아먹는다. 형제 인디케이터와 동일하게 2초를 한 번 더 씌운다
     * (둘 중 먼저 만료되는 쪽이 이기므로 실효 상한이 2초가 된다).
     */
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

    private static final String SERVICE = "vlm";

    /**
     * 응답 본문 해석 성공 여부 detail 키.
     *
     * <p>{@code false} 는 "응답은 왔는데 우리가 기대한 규격(JSON)으로 읽지 못했다"는 뜻이다. 상태는
     * 그래도 UP 이며(응답 도달 = 생존), 운영자가 그 사실을 구분할 수 있도록 축만 남긴다.
     * <b>값에 응답 본문·예외 메시지를 담지 않는다</b>(CWE-209).
     */
    private static final String DECODED = "decoded";

    private final VlmClient vlmClient;
    private final AiSrvrRegistry registry;

    public VlmHealthIndicator(VlmClient vlmClient, AiSrvrRegistry registry) {
        this.vlmClient = vlmClient;
        this.registry = registry;
    }

    /**
     * 원장에 등록된 시계열 노드를 상세에 싣는다 — <b>식별자와 원장 상태만</b>.
     *
     * <p>원장 조회가 실패해도 헬스 판정을 바꾸지 않는다. 이것은 부가 정보이고, 여기서 DOWN 을 내면
     * 우리 DB 문제로 외부 시스템이 죽은 것처럼 보인다.
     */
    private Health.Builder withLedgerNodes(Health.Builder builder) {
        try {
            Map<String, String> byNode = new LinkedHashMap<>();
            int unselectable = 0;
            for (LsAiSrvr node : registry.findAll()) {
                if (node.getSrvrTypeCd() != LsAiSrvr.SrvrType.TIMESERIES) {
                    continue;
                }
                // ★원장 상태 + <고를 수 있는지>. 상태만 적으면 형식을 어긴 노드가 「가용」으로 보이고,
                //   그런 노드밖에 없으면 위탁은 전건 거부인데 상세는 정상으로 읽힌다.
                //   판정은 AiSrvrIdPolicy 를 <그대로> 부른다 — 규칙을 여기 옮겨 적지 않는다.
                boolean idOk = AiSrvrIdPolicy.isValid(node.getSrvrId());
                byNode.put(node.getSrvrId(),
                        idOk ? node.getSrvrSttsCd().name()
                             : node.getSrvrSttsCd().name()
                                     + AiServerHealthIndicator.UNSELECTABLE_MARK);
                if (!idOk) {
                    unselectable++;
                }
            }
            builder.withDetail("nodes", byNode.size());
            if (!byNode.isEmpty()) {
                builder.withDetail("byNode", byNode);
            }
            if (unselectable > 0) {
                // 등록돼 있는데 고를 수 없는 노드 수 — 이 값이 등록 수와 같으면 위탁은 전건 거부다.
                builder.withDetail("unselectable", unselectable);
            }
        } catch (Exception ledgerUnavailable) {
            // 원장을 못 읽은 것은 이 외부 시스템의 상태와 무관하다 — 축을 빼고 넘어간다.
            // ★다만 <완전 침묵>은 두지 않는다. 판정에 반영하지 않는 것과 흔적조차 남기지 않는 것은
            //   다르다 — 로그가 없으면 부가 축이 조용히 사라진 것을 아무도 알아채지 못한다.
            //   ⚠ 예외 메시지·스택트레이스는 싣지 않는다(CWE-209). 클래스명만 남긴다.
            log.debug("[Vlm] 헬스 상세의 원장 노드 축을 생략합니다(판정에는 영향 없음). 원인={}",
                    ledgerUnavailable.getClass().getSimpleName());
        }
        return builder;
    }

    @Override
    public Health health() {
        try {
            // 헬스는 주기적으로 불린다 — 성공 호출 로그는 DEBUG 로 낮춘다(실패는 평소 레벨). [@design NFR-038]
            VlmServerStatus status = vlmClient.fetchStatus(null, true)
                    .timeout(PING_TIMEOUT)
                    .block();
            // 서버가 상태를 돌려줬다 = 살아 있다. busy·loading 도 UP 이다(위탁 수용 여부는 별개 축이며
            // 그 판정은 VlmServerStatus.isSubmittable() 이 위탁 경로에서 담당한다 — 헬스가 대신하지 않는다).
            Health.Builder up = Health.up().withDetail("service", SERVICE);
            if (status != null) {
                if (status.status() != null) {
                    up.withDetail("status", status.status());
                }
                if (status.queue() != null) {
                    up.withDetail("queue", status.queue());
                }
                if (status.pending() != null) {
                    up.withDetail("pending", status.pending());
                }
            }
            return withLedgerNodes(up).build();
        } catch (WebClientResponseException e) {
            // 응답이 온 경우 — 서버는 살아 있다. 상태코드만 싣고 본문은 싣지 않는다(CWE-209).
            //   ① 4xx·5xx: retrieve() 의 기본 상태 핸들러가 던진다.
            //   ② 2xx 인데 Content-Type 을 해석할 디코더가 없음(text/html 오류 페이지 등):
            //      본문 추출이 UnsupportedMediaTypeException 을 이 예외로 감싸 던진다(실측 확인).
            Health.Builder up = Health.up()
                    .withDetail("service", SERVICE)
                    .withDetail("httpStatus", e.getStatusCode().value());
            if (e.getCause() instanceof UnsupportedMediaTypeException) {
                up.withDetail(DECODED, false);
            }
            return withLedgerNodes(up).build();
        } catch (DecodingException e) {
            // 응답은 도달했는데(2xx) 본문이 우리가 기대한 JSON 이 아니라 디코딩에 실패한 경우.
            //   리버스 프록시·LB 가 "200 + HTML 오류 페이지" 를 돌려주는 형상이 대표적이다.
            //   응답이 온 이상 서버는 살아 있으므로 UP 이며, 이것을 DOWN 으로 내리면 위 관제 사고와
            //   똑같이 집계 헬스가 상시 DOWN 이 된다. 해석 실패 사실만 축으로 싣는다.
            //   ⚠ 예외 메시지에는 본문 조각이 섞일 수 있으므로 절대 싣지 않는다(CWE-209).
            return withLedgerNodes(Health.up()
                    .withDetail("service", SERVICE)
                    .withDetail(DECODED, false))
                    .build();
        } catch (Exception e) {
            // 연결 자체가 성립하지 않은 경우(타임아웃·커넥션 거부·DNS 실패 등).
            return withLedgerNodes(Health.down()
                    .withDetail("service", SERVICE)
                    .withDetail("error", e.getClass().getSimpleName()))
                    .build();
        }
    }
}
