package kr.co.cudo.authoring.observability.health;

import kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 비식별 서버 헬스 체크 (Phase 12).
 *
 * <h3>판정 축 = 실제 위탁 경로(kpst.deid.*) — 2026-07-28 정정</h3>
 * <p>과거 이 인디케이터는 {@code deidentifyWebClient}({@code authoring.integration.deidentify.base-url}
 * = {@code ${DEIDENTIFY_API_URL:http://localhost:9200}})를 핑했다. 그러나 <b>실제 비식별 위탁은
 * {@code kpst.deid.base-url} 로 나간다</b>({@link kr.co.cudo.authoring.batch.step.DeidentifyStep} →
 * {@code KpstDeidentService} → {@code KpstDeidentifyClient}). 즉 헬스가 <b>위탁 대상이 아닌 주소</b>를
 * 핑하고 있었고, 컨테이너 배포에서 {@code DEIDENTIFY_API_URL} 이 주입되지 않아 {@code localhost:9200}
 * 을 쳐서 DOWN 오탐이 났다(cudo_246 실측). 진실원을 KPST 축으로 옮긴다.
 *
 * <h3>핑 경로 = 루트(/)</h3>
 * <p>비식별 벤더(KPST) 서버는 {@code /health} 엔드포인트를 <b>제공하지 않는다</b>. 하위 경로로 핑하면
 * 실 벤더 전환 시 404/연결오류로 DOWN 오탐한다. 목 서버가 우연히 {@code /health} 를 갖고 있어 가려져
 * 있었을 뿐이므로, 벤더가 보장하는 루트({@code /})만 친다.
 *
 * <h3>분기 = {@code DeidentifyStep} 외부 위탁 경로와 동일 판정</h3>
 * <p>헬스가 실행 경로와 다른 기준으로 판정하면 이번과 같은 오탐이 반복된다. 따라서 외부 위탁 경로
 * ({@code run()}(트랜잭션 경계 없음, 출처유형 제외 분기가 먼저) → 프록시 → {@code submitToExternal()}
 * ({@code REQUIRES_NEW}))의 우선순위를 그대로 따른다. 출처유형 제외 분기({@code ADR-066})는 외부 연동을
 * 쓰지 않으므로 <b>헬스 판정에 영향을 주지 않는다</b>.
 * <h3>★ 운영에서 mock-mode 는 UP 이 아니다 (2026-09-03 · {@code ADR-062})</h3>
 * <p>{@code ADR-062} 가 자체 복사 축의 <b>기동 차단을 산출 시점 거부로 옮기면서</b>, "앱은 떴는데
 * 비식별만 실패" 라는 상태가 새로 생겼다. 그 상태에서 헬스가 {@code UP(mock)} 을 돌려주면
 * <b>조용한 실패</b>가 된다 — 이 반전이 인지·수용한 주된 위험이 바로 그것이라, 상태 창구도 같은
 * 사실을 말한다. 운영 여부 판정은 {@link kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard}
 * 가 <b>단독 소유</b>하고 여기서 복제하지 않는다. local/dev/stg 동작은 <b>그대로</b>다.
 *
 * <h3>★ 그 형상은 {@code DOWN} 이 아니라 {@code UNKNOWN} 이다 — 근거 셋</h3>
 * <ol>
 *   <li><b>거짓 「비정상」이 될 수 있다</b> — 운영 화면에 정상 벤더 주소가 저장돼 있으면 URL 재작성
 *       필터가 그 주소로 요청을 보내므로 <b>비식별이 실제로 동작한다</b>. 그 형상에서 {@code DOWN}
 *       은 사실이 아니다.</li>
 *   <li><b>인스턴스를 내려도 설정은 고쳐지지 않는다</b> — {@code DOWN} 은 집계 상태를 503 으로
 *       만들어 <b>멀쩡한 노드를 부하분산에서 빼는데</b>, 이 문제는 회전으로 풀리지 않는다.</li>
 *   <li><b>형제 인디케이터와 대칭</b> — {@link AiServerHealthIndicator} 도 원장 식별자 형식 위반처럼
 *       "우리 WAS 의 이상이 아니고 인스턴스를 내려도 안 풀리는" 형상에 {@code Health#unknown()} +
 *       사유를 쓴다. 같은 성질에 다른 상태를 주면 운영자가 조치를 갈라 배워야 한다.</li>
 * </ol>
 * <p>「알린다」는 목적은 {@code UNKNOWN} + {@code error} 사유로 충분히 달성된다 — 어느 쪽이든
 * <b>{@code UP} 은 아니다</b>.
 *
 * <ol>
 *   <li>{@code authoring.integration.deidentify.mock-mode=true} → 자체 복사(self-fill) 형상.
 *       비운영이면 외부 무접촉이 정상이므로 핑 없이 UP(mode=mock).
 *       <b>운영이면 UNKNOWN</b>(비식별 산출이 거부되는 상태 — 위 근거 셋).</li>
 *   <li>{@code kpst.deid.enabled=true} + KPST WebClient 존재 → 실위탁 형상. {@code kpst.deid.base-url}
 *       의 루트를 핑해 성공=UP / 예외=DOWN.</li>
 *   <li>둘 다 아님 → 실행 경로가 "비식별 경로가 구성되지 않았습니다"로 거부하는 설정 오류 상태.
 *       핑할 대상 자체가 없으므로 DOWN(fail-closed).</li>
 * </ol>
 */
@Component("deidentifyHealth")
public class DeidentifyHealthIndicator implements HealthIndicator {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

    /**
     * KPST 위탁용 WebClient({@code KpstWebClientConfig}, base-url = {@code kpst.deid.base-url}).
     * <b>{@code kpst.deid.enabled=true} 일 때만 존재</b>하므로 optional 주입이며 없으면 null 이다
     * ({@code DeidentifyStep} 의 {@code KpstDeidentService} 주입과 동일 선례). 별도 헬스 전용 WebClient
     * 를 새로 만들지 않고 실제 위탁에 쓰는 빈을 그대로 재사용해, 주소·TLS(자체 CA)·타임아웃 구성이
     * 실행 경로와 갈라지지 않게 한다.
     */
    private final WebClient kpstWebClient;

    /**
     * 비식별 신뢰 판정의 단일 진입점. 여기서는 <b>자체 복사 형상이 운영에서 산출을 막고 있는지</b>만
     * 물어본다(판정 복제 금지 — 이 클래스가 프로파일을 직접 읽으면 두 번째 진실원이 된다).
     *
     * <p>단위 시험은 이 인디케이터를 수동 생성하므로 <b>optional</b> 이다. 없으면(=null) 종전 동작과
     * 동일하게 판정하며, 운영 컨테이너에서는 항상 주입된다.
     */
    private final DeidentifyEndpointTrustGuard trustGuard;

    /**
     * 자체 복사(self-fill) 모드. true 면 실 비식별 서버가 부재/미접촉인 것이 정상 형상이므로
     * 외부 핑 없이 UP(mock) 으로 리포트한다 — 집계 {@code /health} 가 부재 서버 핑 실패로 DOWN 되는
     * 것을 방지. ({@code DeidentifyStep} 과 동일 프로퍼티 — 운영은 false)
     */
    @Value("${authoring.integration.deidentify.mock-mode:false}")
    private boolean mockMode;

    /**
     * UC018 — KPST 위탁 경로 토글(킬스위치). {@code DeidentifyStep.kpstEnabled} 와 동일 프로퍼티·동일
     * 기본값을 읽어 헬스 판정이 실행 경로와 어긋나지 않게 한다.
     */
    @Value("${kpst.deid.enabled:true}")
    private boolean kpstEnabled;

    public DeidentifyHealthIndicator(
            @Autowired(required = false) @Qualifier("kpstDeidWebClient") WebClient kpstWebClient,
            @Autowired(required = false) DeidentifyEndpointTrustGuard trustGuard) {
        this.kpstWebClient = kpstWebClient;
        this.trustGuard = trustGuard;
    }

    /**
     * @design ADR-062 운영에서 자체 복사 형상이면 UP 으로 가리지 않는다(조용한 실패 금지).
     */
    @Override
    public Health health() {
        // ① mock self-fill 형상 — 외부를 전혀 호출하지 않는 것이 정상이므로 핑하지 않는다.
        if (mockMode) {
            // ADR-062: 운영에서는 그 형상 자체가 비식별 산출을 거부시킨다 — UP 이면 거짓말이다.
            //   ★그렇다고 DOWN 도 아니다 — UNKNOWN + 사유로 알린다(근거 셋은 클래스 javadoc).
            if (trustGuard != null && trustGuard.selfCopyBlocked()) {
                return Health.unknown()
                        .withDetail("service", "deidentify")
                        .withDetail("mode", "mock")
                        .withDetail("error", "SelfCopyBlockedInProduction")
                        .build();
            }
            return Health.up()
                    .withDetail("service", "deidentify")
                    .withDetail("mode", "mock")
                    .build();
        }
        // ③ 실행 경로가 거부하는 설정 오류 상태(mock 도 아니고 KPST 위탁도 없음) — 핑 대상 부재.
        if (!kpstEnabled || kpstWebClient == null) {
            return Health.down()
                    .withDetail("service", "deidentify")
                    .withDetail("mode", "unconfigured")
                    .withDetail("error", "NoDeidentifyPathConfigured")
                    .build();
        }
        try {
            // ② KPST 실위탁 형상 — 위탁 대상(kpst.deid.base-url)의 루트를 핑한다.
            //   base-url 은 스킴+호스트+포트 형태(경로 세그먼트 없음)라 "/" 로 루트가 된다.
            kpstWebClient.get()
                    .uri("/")
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(PING_TIMEOUT)
                    .block();
            return Health.up()
                    .withDetail("service", "deidentify")
                    .withDetail("mode", "kpst")
                    .build();
        } catch (Exception e) {
            // CWE-209: 예외 클래스명만 노출(스택트레이스/주소/내부 경로 미노출).
            return Health.down()
                    .withDetail("service", "deidentify")
                    .withDetail("mode", "kpst")
                    .withDetail("error", e.getClass().getSimpleName())
                    .build();
        }
    }
}
