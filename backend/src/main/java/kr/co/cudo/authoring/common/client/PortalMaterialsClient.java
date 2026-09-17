package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.PortalMaterialsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 포털 <b>소재 조회</b> 조달 클라이언트 — 데이터셋 숫자 식별자로 소재 경로 목록을 받아 온다.
 *
 * <h3>★ 방향 — 우리가 포털을 부른다</h3>
 * <p>같은 연동점의 <b>정리 삭제 트리거</b>는 포털이 우리를 부르는 인바운드 축이고, 이 클라이언트는
 * 그 반대다. 두 축은 <b>발급 주체가 반대인 별개의 키</b>를 쓴다 — 이쪽은 <b>포털이 발급해 우리에게
 * 준 키</b>이고, 트리거 키는 우리가 발급해 포털에 준 키다. ⚠ <b>섞지 말 것.</b>
 * 인바운드 축의 필터·매처·키를 여기서 재사용하지 않는다.
 *
 * <h3>★ 창구를 번호로 부르지 않는다</h3>
 * <p>포털은 자기 창구에 자기 프로젝트의 번호를 붙여 부르는데, <b>그 번호는 우리 번호 공간에서 전혀
 * 다른 창구를 가리킨다</b>. 그래서 이 클래스는 창구를 <b>경로와 역할로만</b> 식별한다.
 *
 * <h3>★ 설정이 없으면 조달이 닫히고 <b>기동은 산다</b></h3>
 * <p>주소나 키가 비어 있으면 {@link #available()} 이 거짓이고 {@link #fetch(long)} 이
 * {@link PortalMaterialsUnavailableException} 을 던진다. 기동을 막지 않는 것은 연동 주소 정책의
 * 확정 규칙(2026-09-03)과 같은 방향이다 — 한 연동의 설정 실수로 저작 업무 전체가 멈추는 편이
 * 훨씬 비싸다. 그리고 <b>재시도해도 결과가 같으므로</b> 이 실패는 재시도·서킷 집계 대상이 아니다.
 *
 * <h3>★ 대역을 차단하지 않는다 (2026-08-10 구속)</h3>
 * <p>포털 내부 창구는 <b>내부망 주소</b>다. 사설·루프백 어느 대역도 애플리케이션이 막지 않으며,
 * 주소 검증은 <b>스킴과 형식</b>뿐이다({@code WebClientConfig#portalMaterialsWebClient}).
 * 아웃바운드 통제는 인프라 계층이 담당한다. 되살리면 정상 연동이 전부 막힌다.
 *
 * <h3>오류 분류</h3>
 * <ul>
 *   <li><b>4xx</b>(401 키 불일치 · 404 없는 데이터셋 등) → {@link NonRetryableExternalException}.
 *       다시 물어도 같은 답이라 재시도·서킷 집계에서 제외한다({@code resilience4j …portalMaterials
 *       .ignore-exceptions}). 없는 데이터셋을 여러 번 조회했다고 서킷이 열리면 그 구간의 <b>정상
 *       조달이 함께 막힌다</b>.</li>
 *   <li><b>5xx·타임아웃·네트워크</b> → 그대로 전파해 재시도·서킷 집계 대상으로 남긴다.</li>
 * </ul>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>키·응답 본문 원문·{@code localPath} 를 로그에 남기지 않는다(CWE-209/532). 키 헤더는
 *       로그 마스킹 패턴이 이미 덮고 있다.</li>
 *   <li>경로 변수는 {@code long} 이라 주입 여지가 없다(CWE-89/CWE-117 계열 무관).</li>
 * </ul>
 *
 * @design INT-014
 */
@Slf4j
@Component
public class PortalMaterialsClient {

    /**
     * 소재 조회 경로 — 포털 소유 창구.
     *
     * <p>조회 키는 데이터셋의 <b>숫자 식별자</b>다(숫자가 아니면 포털이 없음으로 답한다).
     */
    static final String MATERIALS_PATH = "/api/internal/v1/datasets/{datasetId}/materials";

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Duration timeout;
    private final boolean configured;

    public PortalMaterialsClient(
            @Qualifier("portalMaterialsWebClient") WebClient webClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            @Value("${authoring.portal.materials.base-url:}") String baseUrl,
            @Value("${authoring.portal.materials.api-key:}") String apiKey,
            @Value("${authoring.portal.materials.timeout-seconds:10}") long timeoutSeconds) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("portalMaterials");
        this.retry = retryRegistry.retry("portalMaterials");
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        // ★ 주소와 키가 <둘 다> 있어야 조달이 열린다. 키만 빠져도 전건 401 이라 조달은 성립하지 않고,
        //   주소만 빠지면 빈 base 가 상대 URI 가 되어 loopback:80 으로 실제 TCP 연결이 나간다.
        this.configured = notBlank(baseUrl) && notBlank(apiKey);
        if (!configured) {
            // 미연동이 정상인 배포가 실재한다(관제 채널 배포본) — 사고가 아니므로 INFO 다.
            //   ⚠ 주소·키 값을 남기지 않는다. 설정 키 이름과 「비었다」는 사실만 남긴다.
            log.info("[PortalMaterials] 소재 조달이 구성되지 않았습니다 — 조달 창구가 닫힙니다. "
                    + "baseUrlPresent={} apiKeyPresent={}", notBlank(baseUrl), notBlank(apiKey));
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 조달이 구성됐는가 — 주소와 키가 둘 다 있어야 참이다. */
    public boolean available() {
        return configured;
    }

    /**
     * 소재 경로 목록을 조회한다 — <b>파일 바이트는 오지 않는다</b>.
     *
     * <p>호출은 조달 작업 스레드에서 이뤄지므로 여기서 블로킹한다(reactor 이벤트 루프가 아니다).
     *
     * @param datasetId 데이터셋 숫자 식별자
     * @throws PortalMaterialsUnavailableException 조달이 구성되지 않음(재시도 무의미)
     * @throws NonRetryableExternalException       포털이 4xx 로 거부(키 불일치·없는 데이터셋 등)
     */
    public PortalMaterialsResponse fetch(long datasetId) {
        return fetchMono(datasetId).block();
    }

    /** {@link #fetch(long)} 의 비동기 형태 — 회복성 정책이 붙은 단일 구현이다. */
    public Mono<PortalMaterialsResponse> fetchMono(long datasetId) {
        if (!configured) {
            return Mono.error(new PortalMaterialsUnavailableException(
                    "포털 소재 조달이 구성되지 않았습니다(연동 주소 또는 키 미설정)."));
        }
        log.info("[PortalMaterials] 소재 조회 datasetId={}", datasetId);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path(MATERIALS_PATH).build(datasetId))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, PortalMaterialsClient::toNonRetryable4xx)
                .bodyToMono(PortalMaterialsResponse.class)
                .timeout(timeout)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * 4xx → 비재시도 예외. 본문을 소비/해제한 뒤 <b>상태 코드만</b> 남긴다.
     *
     * <p>⚠ 포털 오류 본문에는 요청 경로가 실려 올 수 있다(404). 그 원문을 예외·로그에 보존하지
     * 않는다(CWE-209) — 상태 코드만으로 사유(401 키 불일치 / 404 없는 데이터셋)가 갈린다.
     */
    private static Mono<Throwable> toNonRetryable4xx(ClientResponse response) {
        int status = response.statusCode().value();
        log.warn("[PortalMaterials] 소재 조회 4xx (비재시도) status={}", status);
        return response.releaseBody()
                .then(Mono.error(new NonRetryableExternalException(
                        "포털 소재 조회 4xx 응답(status=" + status + ")", status)));
    }

    /**
     * 조달 미구성 — 설정이 채워지기 전까지 <b>다시 불러도 같은 결과</b>다.
     *
     * <p>{@link NonRetryableExternalException} 을 상속해 재시도·서킷 집계에서 함께 제외된다
     * ({@code ignore-exceptions} 에 부모 타입이 등록돼 있어 별도 등록이 필요 없다).
     */
    public static class PortalMaterialsUnavailableException extends NonRetryableExternalException {
        public PortalMaterialsUnavailableException(String message) {
            super(message);
        }
    }
}
