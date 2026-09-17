package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.VlmServerStatus;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointExchangeFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 외부 시계열 분석 위탁 클라이언트 — 확정 계약(KLID 연동 API v1.1.0) 정합.
 *
 * <p>연동 대상 창구는 <b>둘</b>이다:
 * <ul>
 *   <li>{@link #DESCRIBE_PATH} — <b>묘사</b>. 이벤트 관점에서 영상의 장소·환경·상황을 서술한다.</li>
 *   <li>{@link #CUSTOM_PATH} — <b>추가 질문</b>. 마킹에서 고른 질문 문구를 요청 본문에 직접 싣고
 *       그에 대한 서술을 받는다. ⚠ 구 창구 {@link #DESCRIBE_SUB_PATH} 는 <b>더 이상 호출하지 않는다</b>
 *       (규격 v1.2.0 에서 이 창구로 대체됐다) — 그 상수를 남긴 이유는 그 선언부 주석에 있다.</li>
 * </ul>
 * 두 창구는 각각 <b>별개의 request_id</b> 로 나간다(콜백 본문에 창구 구분자가 없어 그것으로 역조회한다).
 * ⚠ 요청 형식은 <b>같지 않다</b> — 묘사는 {@code event_type} 을, 추가 질문은 {@code prompt} 를 싣는다.
 * 접수는 HTTP 202 + {@code status="accepted"} 이고 실제 결과는 {@code POST /v1/vlm/callback} 콜백으로 온다.
 *
 * <p><b>판정 창구는 연동하지 않는다</b> — 그 창구만 제공하는 발생 여부·일치도 값은 우리 확정 경로
 * 어디에도 쓰이지 않는다. 되살리지 말 것.
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li><b>비동기 위탁</b>: 동기 응답은 "수락" 만 확인. 결과는 콜백.</li>
 *   <li><b>상관관계</b>: 상관키는 {@code request_id}(요청 바디). 접수 응답에 externalJobId 는 없다.
 *       호출자(Step)가 request_id 를 발급/주입하며, 응답의 request_id echo 일치를 본 클라이언트가 검증한다.
 *       (Step 이 request_id 를 ledger(request_id→rawSn)에 먼저 등록해 콜백 역조회를 성립시킨다.)</li>
 *   <li><b>미연동 = 실패</b>: 연동 주소가 주입되지 않았으면 위탁은 <b>조용히 건너뛰지 않고 실패</b>한다.
 *       구 동작(설정 토글이 꺼져 있으면 외부 호출 없이 즉시 SKIPPED 반환)은 폐지됐다 — 기본값이
 *       비활성이라 시계열이 꺼진 채 납품돼도 그 사실이 아무 데도 남지 않았다. 벤더 미연동 구간은
 *       사람이 사유를 남기는 단계 스킵으로 운영한다(그 사실이 처리 이력에 남는다).</li>
 *   <li><b>회복성</b>: Resilience4j Retry(exp backoff) + CircuitBreaker. 타임아웃은 WebClient
 *       {@code .timeout(...)} 으로 Reactor 네이티브 처리(단일 출처).</li>
 *   <li><b>보안</b>: URL/토큰 하드코딩 금지(환경변수). URL SSRF/HTTPS 검증은 {@code WebClientConfig}.
 *       응답 request_id echo + status 화이트리스트 검증. 로그 출력 전 {@link #safeForLog(String)}
 *       로 CRLF/탭 sanitize(CWE-117).</li>
 * </ul>
 *
 * @design ADR-049
 */
@Slf4j
@Component
public class VlmClient {

    /** 묘사 위탁 경로 — 규격 §3.2. 결과가 시계열 서술 전문을 채운다. */
    static final String DESCRIBE_PATH = "/v1/videovlm-klid/describe";

    /**
     * <b>추가 질문 위탁 경로</b> — 규격 §3.4. 결과가 이벤트 어노테이션의 질의응답 축 초안을 채운다.
     *
     * <p>이 창구는 <b>이벤트 유형을 쓰지 않고 질문 문구를 요청 본문({@code prompt})에 직접</b> 싣는다.
     * 벤더 가이드가 구 추가 질문 창구 대신 이 창구를 쓰라고 안내했다.
     */
    static final String CUSTOM_PATH = "/v1/videovlm-klid/custom";

    /**
     * [폐기] 구 추가 질문 위탁 경로 — <b>연동 대상으로 두지 않는다</b>(설계 결정).
     *
     * <p>⚠ 벤더는 이 창구를 <b>계속 제공</b>한다 — 없어진 것이 아니라 우리가 쓰지 않는 것이다.
     * 이미 이 창구로 받아 적재된 결과는 <b>작업 결과라 보존</b>하며, 원장 채널 식별자
     * ({@link kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency#CHANNEL_VLM_SUB})도
     * <b>그대로 둔다</b> — 창구가 바뀐 것이지 축이 바뀐 게 아니고, 그 값은 이미 적재된 행의
     * 역조회 키이자 미결 회수의 대상 목록이다.
     *
     * <p>상수를 남기는 것은 과거 기록을 읽는 사람이 「그때 무엇으로 보냈는가」를 알 수 있게 하기 위함이다.
     */
    static final String DESCRIBE_SUB_PATH = "/v1/videovlm-klid/describe-sub";

    /**
     * 서버 상태 조회 경로 — 규격 §3.5. 위탁 전 처리 가능 여부 확인용이자 <b>장비 상태점검 창구</b>다.
     *
     * <p>★ 이 경로가 시계열 계통의 상태점검 창구다 — 추론 계통의 창구({@code /health})와 다르며,
     * 창구가 계통마다 다르다는 것은 근거 결정이 명시한 사양이다. 그 판정을 하는 자리는
     * {@code HttpAiSrvrHealthProbe} 하나이고 <b>경로 문자열은 여기 하나뿐</b>이다(복제 금지).
     * [@design ADR-057] [@design INTSPEC-003]
     */
    static final String STATUS_PATH = "/v1/videovlm-klid/status";

    /**
     * 동시 처리 한도 초과 응답 — 규격 §2.9. <b>재시도 대상</b>이다.
     *
     * <p>잠시 뒤 다시 보내면 되는 일시 상태이지 요청이 잘못된 것이 아니다. 이 값을 다른 4xx 와
     * 함께 비재시도로 묶으면, 이중 위탁으로 호출이 두 배가 된 상황에서 정상 위탁이 확정 실패로
     * 종결된다.
     */
    private static final int STATUS_TOO_MANY_REQUESTS = 429;

    /** 수락 상태 화이트리스트 — 접수 응답은 "accepted" 만 정상(CWE-20). */
    private static final String STATUS_ACCEPTED = VlmTimeseriesResponse.STATUS_ACCEPTED;

    /** 로그 sanitize 패턴 — CR/LF/TAB 제거(CWE-117 Log Injection 차단). */
    private static final Pattern LOG_UNSAFE_CHARS = Pattern.compile("[\\r\\n\\t]");

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Duration timeout;

    public VlmClient(@Qualifier("vlmWebClient") WebClient webClient,
                     CircuitBreakerRegistry circuitBreakerRegistry,
                     RetryRegistry retryRegistry,
                     @Value("${vlm.client.timeout-seconds:10}") long timeoutSeconds) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("vlmClient");
        this.retry = retryRegistry.retry("vlmClient");
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    /**
     * 묘사(describe)를 비동기 위탁한다 — 규격 §3.2. 결과가 시계열 서술 전문을 채운다.
     *
     * <p>Retry + CircuitBreaker + 응답 무결성 검증이 적용된 외부 호출을 수행한다.
     * <b>미연동(연동 주소 미주입) 이어도 별도 분기를 두지 않는다</b> — 호출이 그대로 실패해 기존 실패
     * 경로(완료 핸들러의 확정 실패 기록)로 흐른다. 조용한 SKIPPED 로 삼키면 시계열 결손이 드러나지
     * 않는다(그 구 동작이 폐지된 이유다).
     *
     * <p>{@code event_type} 은 <b>조달값 그대로</b> 실어 보낸다 — 우리 쪽 허용목록으로 사전 차단하지
     * 않으며 수용 여부는 벤더 응답이 정한다(사본 목록이 두 번째 진실원이 되는 것을 막는다).
     *
     * @param request 요청 바디. {@code request_id} 가 null/blank 이면 방어적으로 UUID 자동 발급.
     * @return 외부 시스템 접수 응답 (request_id echo + status)
     */
    public Mono<VlmTimeseriesResponse> submitDescribe(VlmTimeseriesRequest request) {
        return submitDescribe(request, null);
    }

    /**
     * 묘사 위탁을 <b>지정한 장비로</b> 보낸다 — 노드 분산. [@design ADR-057]
     *
     * @param srvrAddr 보낼 장비의 기준 주소({@code LS_AI_SRVR.SRVR_ADDR}). {@code null}/공백이면 배포
     *                 기본 주소로 나간다(장비를 고르지 못한 구성 — 이 기능이 없던 때와 같은 동작).
     *                 값이 있는데 목적지를 만들 수 없으면 <b>조용히 되돌리지 않고 실패</b>한다
     *                 (자세히는 {@link #absoluteTarget})
     */
    public Mono<VlmTimeseriesResponse> submitDescribe(VlmTimeseriesRequest request, String srvrAddr) {
        return submit(DESCRIBE_PATH, "describe", request, srvrAddr);
    }

    /**
     * 추가 질문(custom)을 비동기 위탁한다 — 규격 v1.2.0 신설 창구.
     *
     * <p>★<b>질문 문구를 우리가 요청 본문에 직접 싣는다</b>({@code prompt}). 마킹에서 고른 질문이
     * 그대로 나가므로 그 값은 기록이 아니라 <b>실제로 보내는 값</b>이다 — 보낸 문구를 원장에 남겨
     * 콜백이 그것을 읽는다(재조달하면 보낸 값과 기록된 값이 갈라진다).
     *
     * <p>⚠ <b>구 서술 폐기</b> — 「질문 문장은 이벤트별로 서버가 관리하며 연동 시스템이 지정하지
     * 않는다」는 구 창구({@link #DESCRIBE_SUB_PATH}) 시절 사실이고 지금은 반대다. 되살리지 말 것.
     *
     * <p>이 창구는 {@code event_type} 을 싣지 않는다 — 묘사 축과 요청 형식이 다르다.
     * 판정 항목은 제공되지 않는다(모델이 "네"로 답을 시작해도 그 문장은 서술에 그대로 담긴다).
     *
     * <p>{@link #submitDescribe} 와 <b>반드시 다른 request_id</b> 로 호출해야 한다(콜백 역조회 축).
     */
    public Mono<VlmTimeseriesResponse> submitCustom(VlmTimeseriesRequest request) {
        return submitCustom(request, null);
    }

    /**
     * 추가 질문 위탁을 <b>지정한 장비로</b> 보낸다 — 노드 분산. [@design ADR-057]
     *
     * <p>같은 영상의 두 창구는 <b>같은 장비</b>로 보낸다(호출자가 한 번 고른 값을 두 번 넘긴다). 창구마다
     * 따로 고르면 위탁 원장에는 장비가 창구별로 갈려 남는데, 그 값은 부하 집계의 입력이라 한 영상의
     * 위탁이 두 장비의 부하를 동시에 올린 것처럼 보인다.
     */
    public Mono<VlmTimeseriesResponse> submitCustom(VlmTimeseriesRequest request, String srvrAddr) {
        return submit(CUSTOM_PATH, "custom", request, srvrAddr);
    }

    /**
     * 서버 상태 조회 — 규격 §3.5. 위탁을 보내기 전에 처리 가능한 상태인지 확인한다.
     *
     * <p><b>조회 실패를 위탁 차단으로 삼지 않는다</b> — 이 조회는 편의이지 게이트가 아니다.
     * 상태를 확인하지 못했다고 정상 위탁을 우리가 먼저 막으면, 상태 창구만 잠시 불안정해도
     * 파이프라인이 통째로 선다. 호출자는 실패 시 그대로 위탁을 진행한다.
     */
    public Mono<VlmServerStatus> fetchStatus() {
        return fetchStatus(null);
    }

    /**
     * 서버 상태를 <b>지정한 장비에게</b> 묻는다 — 노드 분산·상태점검. [@design ADR-057]
     *
     * <p>{@link #submit} 과 <b>같은 방식</b>으로 목적지를 고정한다(같은 술어 {@link PinnedTarget},
     * 같은 표식 {@code EXPLICIT_TARGET_ATTRIBUTE}). 여기서 자기 기준으로 주소를 조립하면 「고를 때」와
     * 「보낼 때」의 판정이 갈려 원장이 거짓말을 한다.
     *
     * <h3>★ 상태점검이 이것을 쓰는 것과 「이 조회는 게이트가 아니다」는 <b>양립한다</b></h3>
     * <p>위 {@link #fetchStatus()} javadoc 이 못 박은 것은 <b>「한 번 조회에 실패했다고 그 위탁을
     * 즉시 막지 않는다」</b>이다(위탁 직전 관측 축 — {@code VlmTimeseriesStep.observeServerStatus}).
     * 반면 상태점검이 장비를 내리는 것은 <b>연속 실패가 임계에 닿았을 때</b>의 누적 판정이고, 그
     * 판정으로 내려간 장비도 연속 성공이 복귀 임계에 닿으면 저절로 돌아온다. 추론 계통이 이미 그
     * 구조이며 여기서 축이 하나 늘어난 것뿐이다.
     *
     * <p>⚠ 그러므로 <b>위탁 직전 관측의 「게이트 아님」 성질은 그대로 유지</b>한다 — 두 축을 하나로
     * 합쳐 「상태 조회가 실패하면 위탁하지 않는다」로 만들지 말 것. 상태 창구가 잠깐 흔들려도
     * 파이프라인이 서지 않게 하는 것이 그 축의 존재 이유다.
     *
     * @param srvrAddr 물어볼 장비의 기준 주소. {@code null}/공백이면 배포 기본 주소로 나간다
     *                 (장비를 고르지 못한 구성 — 이 기능이 없던 때와 같은 동작)
     */
    public Mono<VlmServerStatus> fetchStatus(String srvrAddr) {
        return fetchStatus(srvrAddr, false);
    }

    /**
     * {@link #fetchStatus(String)} 와 같되 <b>주기 점검인지</b>를 호출 로그에 알린다. [@design NFR-038]
     *
     * <p>상태점검·헬스처럼 주기적으로 반복되는 호출은 {@code periodicProbe=true} 로 부른다 — 성공했을 때
     * 호출 로그가 DEBUG 로 낮아져 실제 위탁 기록을 덮지 않는다(실패는 평소 레벨). 위탁 직전 관측은
     * 위탁 흐름의 일부라 표식 없이 부른다({@link #fetchStatus()}).
     */
    public Mono<VlmServerStatus> fetchStatus(String srvrAddr, boolean periodicProbe) {
        URI target = absoluteTarget(srvrAddr, STATUS_PATH);
        return webClient.get()
                .uri(uriBuilder -> target == null ? uriBuilder.path(STATUS_PATH).build() : target)
                .attributes(attrs -> {
                    if (target != null) {
                        attrs.put(IntegrationEndpointExchangeFilter.EXPLICIT_TARGET_ATTRIBUTE, Boolean.TRUE);
                    }
                    if (periodicProbe) {
                        attrs.put(ExternalCallLoggingFilter.PERIODIC_PROBE_ATTRIBUTE, Boolean.TRUE);
                    }
                })
                .retrieve()
                .bodyToMono(VlmServerStatus.class)
                .timeout(timeout);
    }

    /**
     * 위탁 공통 구현 — 창구 경로만 다르고 요청 형식·응답 검증·회복성 정책은 동일하다(규격 §3.2·§3.3).
     *
     * @param path  창구 경로.
     * @param label 로그용 창구 이름(상수라 sanitize 불필요).
     */
    private Mono<VlmTimeseriesResponse> submit(String path, String label, VlmTimeseriesRequest request,
                                               String srvrAddr) {
        Objects.requireNonNull(request, "request must not be null");
        String requestId = resolveRequestId(request.requestId());
        VlmTimeseriesRequest enriched = new VlmTimeseriesRequest(
                requestId, request.eventType(), request.media(), request.callbackUrl(), request.prompt());

        // ★ 장비를 골랐으면 그 장비로 <실제로> 나가야 한다. 상대 경로로 두면 빈 생성 시점의 base 나
        //   설정 override 로 흘러가고, 그러면 위탁 원장에는 「A 로 보냈다」가 남는데 요청은 B 로 간다 —
        //   오류가 아니라 조용한 어긋남이라 로그에도 남지 않는다. 표식은 URL 재작성 필터에게
        //   「이 요청의 대상은 이미 정해졌다」를 알린다(설정 override 가 이 결정을 덮지 못하게 한다).
        URI target = absoluteTarget(srvrAddr, path);

        log.info("[Vlm] {} submit request_id={} pinned={}", label, safeForLog(requestId), target != null);
        return webClient.post()
                .uri(uriBuilder -> target == null ? uriBuilder.path(path).build() : target)
                .attributes(attrs -> {
                    if (target != null) {
                        attrs.put(IntegrationEndpointExchangeFilter.EXPLICIT_TARGET_ATTRIBUTE, Boolean.TRUE);
                    }
                })
                .bodyValue(enriched)
                .retrieve()
                // 4xx 중 429 만 재시도 대상이다(규격 §2.9 — 동시 처리 한도 초과는 잠시 후 재시도).
                // 400(형식·미지원 event_type·허용되지 않은 경로)·415(Content-Type)는 다시 보내도 같은
                // 결과라 비재시도 예외로 분류해 재시도·서킷집계에서 제외한다. 503 은 5xx 라 기본 재시도에 걸린다.
                //
                // ★ 429 는 <b>전용 예외</b>로 감싼다 — 재시도는 태우되 서킷은 열지 않기 위해서다.
                //   기본 예외를 그대로 두면 재시도는 되지만 서킷 failure 로도 집계돼, 벤더가 잠시 바쁜
                //   구간에 서킷이 열리고 그 구간의 정상 위탁이 확정 실패로 종결된다.
                .onStatus(VlmClient::isRateLimited, this::toRateLimited)
                .onStatus(VlmClient::isNonRetryableClientError, this::toNonRetryable4xx)
                .bodyToMono(VlmTimeseriesResponse.class)
                .timeout(timeout)
                .map(resp -> validateResponse(resp, requestId))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * 장비 주소 + 창구 경로 -> <b>절대 URI</b>. 장비를 고르지 못했으면 {@code null}(상대 경로 유지).
     *
     * <p>빈을 늘리지 않고 <b>요청 시점</b>에 푸는 이유: 장비마다 {@link WebClient} 를 두면 커넥션 풀이
     * 장비 수만큼 늘고, 용도 축(어느 연동인가)과 장비 축(그중 어느 대인가)이 빈 정의에서 얽힌다.
     *
     * <h3>두 경우를 구분한다 — 이 구분이 원장의 정직함을 지킨다</h3>
     * <ul>
     *   <li><b>주소가 없다</b>(null/공백) = 장비를 고르지 못한 구성. 상대 경로로 두어 배포 기본 주소로
     *       나간다. 호출자도 원장에 <b>장비 미상</b>으로 적으므로 기록과 목적지가 일치한다.</li>
     *   <li><b>주소는 있는데 목적지를 만들 수 없다</b> = <b>시끄럽게 실패</b>한다. 여기서 조용히
     *       {@code null} 로 되돌리면 호출자는 원장에 「A 로 보냈다」를 적어 둔 채 요청은 배포 기본
     *       주소로 나가 <b>기록이 거짓말</b>을 한다(오류가 아니라 조용한 어긋남).</li>
     * </ul>
     *
     * <p>다만 <b>정상 경로에서는 이 실패가 일어나지 않는다</b> — 호출자가 장비를 고를 때 이미 같은
     * 술어({@link PinnedTarget#canPin})로 걸렀기 때문이다. 이 분기는 그 계약이 깨졌을 때를 위한
     * fail-secure 이며, 그래서 <b>재시도 대상이 아니다</b>(같은 주소로 다시 보내도 결과가 같다).
     *
     * <p>★ 예외 메시지에 <b>주소를 싣지 않는다</b> — 그 메시지는 처리 이력 컬럼에 그대로 영속될 수
     * 있고, 장비 주소는 내부 토폴로지다(CWE-497). 주소에 개행이 섞이면 로그 위조 통로도 된다(CWE-117).
     *
     * <p>⚠ <b>구 서술 정정</b>: 「주소 꼴이 아니면 {@code URI.create} 가 예외를 던진다」는 부정확했다 —
     * 그 팩토리는 스킴 없는 문자열({@code "ts02:9500"} 등)에 예외를 던지지 않고 <b>상대 URI</b> 를
     * 만들어 낸다. 그 값을 실제로 막는 주체는 전송 계층의
     * {@code IntegrationEndpointTransportGuards#requireResolvedHost}(호스트 없는 최종 URL 차단)였고,
     * 지금은 그보다 앞의 {@link PinnedTarget} 판정이 선택 단계에서 걸러 낸다.
     */
    private static URI absoluteTarget(String srvrAddr, String path) {
        if (srvrAddr == null || srvrAddr.isBlank()) {
            return null;
        }
        return PinnedTarget.resolve(srvrAddr, path).orElseThrow(() ->
                new NonRetryableExternalException(
                        "시계열 분석 위탁 대상 장비의 주소로 목적지를 만들 수 없습니다."));
    }

    /** 동시 처리 한도 초과인가 — 규격 §2.9. 일시 상태이므로 재시도 대상이다. */
    private static boolean isRateLimited(HttpStatusCode status) {
        return status.value() == STATUS_TOO_MANY_REQUESTS;
    }

    /**
     * 비재시도로 분류할 클라이언트 오류인가 — <b>429 는 제외</b>한다.
     *
     * <p>429(동시 처리 한도 초과)는 규격이 "잠시 후 재시도한다"고 명시한 일시 상태다. 다른 4xx 와
     * 함께 묶으면 재시도에서 빠져 확정 실패로 종결된다.
     */
    private static boolean isNonRetryableClientError(HttpStatusCode status) {
        return status.is4xxClientError() && !isRateLimited(status);
    }

    /**
     * 429 를 재시도 가능 예외로 변환 — 본문을 소비/해제한 뒤 상태 코드만 기록한다(CWE-209).
     */
    private Mono<Throwable> toRateLimited(ClientResponse response) {
        log.warn("[Vlm] rate limited (retryable) status={}", response.statusCode().value());
        return response.releaseBody()
                .then(Mono.error(new RateLimitedExternalException(
                        "시계열 분석 위탁 동시 처리 한도 초과(429) — 재시도 대상")));
    }

    /**
     * 4xx 클라이언트 오류를 비재시도 예외로 변환 — {@code .retrieve().onStatus(...)} 훅용(V1).
     *
     * <p>본문을 소비/해제(리소스 누수 방지)한 뒤 상태 코드만 기록해 {@link NonRetryableExternalException}
     * 으로 전파한다. Resilience4j retry/circuitbreaker 는 {@code ignore-exceptions} 로 이를 건너뛴다.
     * CWE-209: 외부 응답 본문 원문은 예외/로그에 노출하지 않는다(상태 코드만).
     */
    private Mono<Throwable> toNonRetryable4xx(ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .onErrorReturn("")
                .map(body -> {
                    Integer vendorCode = extractVendorCode(body);
                    if (vendorCode != null) {
                        log.warn("[Vlm] non-retryable 4xx status={} vendorCode={}", status, vendorCode);
                    } else {
                        log.warn("[Vlm] non-retryable 4xx status={}", status);
                    }
                    return (Throwable) new NonRetryableExternalException(
                            "시계열 분석 위탁 4xx 응답(status=" + status + ")", status, vendorCode);
                })
                .flatMap(Mono::error);
    }

    /**
     * 4xx 본문에서 <b>벤더 오류 코드(정수)만</b> 뽑는다 — 규격 §2.10.
     *
     * <p>규격은 <b>정의되지 않은 이벤트 유형일 때만</b> 본문에 {@code code} 를 싣고 그 밖의 오류는
     * {@code detail} 만 준다고 못 박는다. 그래서 이 값이 있으면 <b>「미지원 이벤트 유형」의 단일 근거</b>다.
     * 저작도구는 이벤트 유형을 우리 허용목록으로 사전 차단하지 않고 <b>벤더 응답이 판정하게</b> 하므로,
     * 그것이 없으면 실패 기록이 "4xx 응답"으로만 남아 <b>왜 거부됐는지 알 수 없다</b>.
     *
     * <p>★ <b>적용 축은 묘사 하나다</b> — 추가 질문 축은 이벤트 유형을 보내지 않아 이 코드가 나올 수 없다.
     *
     * <p>⚠ <b>본문 원문을 남기지 않는다</b>(CWE-209). 정수 코드만 꺼내고 문자열은 버린다.
     * <p>⚠ 본문이 JSON 이 아니거나 {@code code} 가 없거나 정수가 아니어도 <b>예외를 던지지 않는다</b> —
     * 그것이 정상 응답이며, 되받을 수 없는 입구에서의 엄격함은 곧 손실이다. {@code null} 을 돌려
     * 기존 동작(일반 비재시도 4xx)으로 떨어뜨린다.
     */
    private static Integer extractVendorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher m = VENDOR_CODE_PATTERN.matcher(body);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 본문 최상위 {@code "code": <정수>} 만 집는 패턴.
     *
     * <p>본문 전체를 객체로 역직렬화하지 않는 이유는 벤더가 필드를 더하거나 모양을 바꿔도
     * <b>이 판독이 깨지면 안 되기 때문</b>이다. 이 값 하나 말고는 아무것도 쓰지 않는다.
     *
     * <p>★ 뒤의 {@code (?!\d)} 는 <b>자릿수 경계</b>다. 없으면 상한을 넘는 긴 수에서 <b>앞 9자리만
     * 잘라</b> {@code 400011111} 같은 <b>존재하지 않는 코드를 지어낸다</b>. 못 읽는 것보다 나쁘다 —
     * 없는 사유가 기록에 남는다.
     */
    private static final Pattern VENDOR_CODE_PATTERN =
            Pattern.compile("\"code\"\\s*:\\s*(-?\\d{1,9})(?!\\d)");

    /**
     * 호출자가 request_id 를 제공하지 않으면 UUIDv4 로 방어적 발급.
     *
     * <p>정상 흐름에서는 Step 이 request_id 를 발급/등록하므로 여기서는 주입값을 그대로 사용한다.
     * Resilience4j Retry 는 동일 Mono 를 재구독하므로 재시도 시 같은 request_id 가 바디에 유지된다.
     */
    private String resolveRequestId(String provided) {
        if (provided == null || provided.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return provided;
    }

    /**
     * 접수 응답 무결성 검증 — 두 창구가 같은 형식이라 검증도 공통이다(규격 §2.6).
     *
     * <p>외부 시스템은 신뢰 영역 밖이므로 응답을 검증한다:
     * <ol>
     *   <li>request_id echo 일치 — 상관관계 위조/혼선 차단.</li>
     *   <li>status == "accepted" — 수락 화이트리스트(CWE-20).</li>
     * </ol>
     * 검증 실패 시 {@link CustomException}{@code (EXTERNAL_API_ERROR)} 로 변환되어 Retry/FAILED 경로로 흐른다.
     *
     * @return 검증을 통과한 응답 그대로 반환
     */
    private VlmTimeseriesResponse validateResponse(VlmTimeseriesResponse resp, String expectedRequestId) {
        if (resp == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "시계열 분석 위탁 응답이 null 입니다.");
        }
        String echoed = resp.requestId();
        if (echoed == null || !echoed.equals(expectedRequestId)) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "시계열 분석 위탁 응답 request_id echo 가 일치하지 않습니다: " + safeForLog(echoed));
        }
        String status = resp.status();
        if (!STATUS_ACCEPTED.equals(status)) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "시계열 분석 위탁 응답 status 가 accepted 가 아닙니다: " + safeForLog(status));
        }
        return resp;
    }

    /**
     * 로그 출력용 외부 입력 sanitize — CWE-117 Log Injection 차단.
     *
     * <p>외부 응답 값(request_id echo, status) 을 로그에 출력하기 전 호출. CR/LF/TAB 을 {@code _} 로 치환한다.
     */
    public static String safeForLog(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE_CHARS.matcher(s).replaceAll("_");
    }

}
