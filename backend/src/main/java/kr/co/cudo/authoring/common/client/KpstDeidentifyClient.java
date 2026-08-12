package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.client.dto.KpstDeleteResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProgressRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.common.client.dto.KpstReportRequest;
import kr.co.cudo.authoring.common.client.dto.KpstReportResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * ㈜KPST 비식별화 솔루션 폴링 경로 클라이언트 — Phase 1 신설.
 *
 * <p>규격 정본: {@code docs/v2-wiki/22-deid-solution-api.md}. 본 클라이언트는 핵심 4종
 * (연결확인·프로젝트생성·진행조회·삭제)을 구현한다. 위탁은 공유 마운트(shared-mount) 모델로 원본
 * 경로를 {@code /project} 에 직접 참조시키므로 업로드 단계가 없고, 결과 또한 KPST 가 우리 비식별
 * 저장소(export_path)에 직접 쓰므로 다운로드 단계도 없다(no-copy). UC018 경로 단일화로 비식별
 * 확정의 유일한 위탁/완료 경로다(레거시 동기 SPI 콜백 모델 제거).
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>TLS 자체 CA (CWE-295): 신뢰 체인은 {@code KpstWebClientConfig} 가 ca.crt 로 구성한
 *       {@code kpstDeidWebClient} 빈에서만 주입된다. 본 클래스는 WebClient 를 직접 만들지 않는다.</li>
 *   <li>SSRF (CWE-918): base-url 은 application.yml 설정값(빈 주입)만 사용. 사용자 입력으로 URL/호스트
 *       를 구성하지 않으며, 경로는 모두 상수다.</li>
 *   <li>경로 순회 (CWE-22): 결과 회수는 no-copy 라 본 클라이언트가 파일을 쓰지 않는다. 회수 경로의
 *       외부 응답 fileName 정화는 호출측 {@code KpstDeidentService.sanitizeFileName} 이 방어한다.</li>
 *   <li>정보 유출 (CWE-209): 외부 API 원문 에러 본문/스택트레이스를 예외/로그에 노출하지 않는다.
 *       상태 코드와 예외 클래스명만 기록한다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class KpstDeidentifyClient {

    /** API 경로 상수 (규격 §22.2). 사용자 입력으로 구성 금지. */
    private static final String PATH_CONNECT = "/";
    private static final String PATH_PROJECT = "/project";
    private static final String PATH_RETRIEVE_PROGRESS = "/retrieve_progress";
    /** 처리 결과 리포트 조회(§22.3.7) — 완료 시점 1회 호출. [req: R14] */
    private static final String PATH_RETRIEVE_REPORT = "/retrieve_report";
    private static final String PATH_DELETE_PROJECT_ID = "/delete_project_id";

    private static final String CONNECT_OK_BODY = "Connect";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);
    private static final String DELETE_RESULT_SUCCESS = "success";

    private final WebClient webClient;
    private final HttpClient progressHttpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    /**
     * 진행조회 주소 판정 — {@code null} 이면 저수준 클라이언트의 기동 시점 base-url 을 그대로 쓴다
     * (구 동작). 운영 컨테이너에서는 항상 주입된다.
     */
    private final kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver endpointResolver;

    /** 진행조회 base-url 의 배포 기본값 — override 가 없을 때 쓰인다. */
    private final String progressBootBaseUrl;

    /**
     * 구 시그니처 — 진행조회가 <b>기동 시점 주소</b>를 그대로 쓴다.
     *
     * <p>남겨 두는 이유는 기존 호출자·테스트가 그대로 컴파일·동작하게 하기 위해서다. 운영 컨테이너는
     * 아래 6-인자 생성자로 주입된다.
     */
    public KpstDeidentifyClient(@Qualifier("kpstDeidWebClient") WebClient webClient,
                                @Qualifier("kpstDeidProgressHttpClient") HttpClient progressHttpClient,
                                @Qualifier("kpstDeidCircuitBreaker") CircuitBreaker circuitBreaker,
                                RetryRegistry retryRegistry) {
        this(webClient, progressHttpClient, circuitBreaker, retryRegistry, null, null);
    }

    /**
     * ★ R11 — 진행조회도 <b>호출 시점</b>에 주소를 다시 읽는다.
     *
     * <h3>왜 진행조회만 따로 손대는가</h3>
     * <p>연결확인·프로젝트생성·삭제는 {@code kpstDeidWebClient}(WebClient)로 나가 필터가 URL 을 고쳐
     * 주지만, <b>진행조회만 저수준 {@code HttpClient}</b> 를 쓴다(서버가 GET 에도 JSON 바디를 요구해
     * WebClient 로는 바디가 전송되지 않기 때문). 저수준 클라이언트에는 필터 훅이 없다.
     *
     * <p>그대로 두면 <b>주소를 바꿨을 때 프로젝트는 새 서버에 생기고 진행조회는 옛 서버로 나간다</b> —
     * 그 작업은 영원히 완료되지 않는다. <b>부분 반영이 미반영보다 위험</b>하므로 여기서 절대 URI 를
     * 만들어 넘긴다(reactor-netty 는 절대 URI 를 받으면 base-url 을 무시한다).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public KpstDeidentifyClient(@Qualifier("kpstDeidWebClient") WebClient webClient,
                                @Qualifier("kpstDeidProgressHttpClient") HttpClient progressHttpClient,
                                @Qualifier("kpstDeidCircuitBreaker") CircuitBreaker circuitBreaker,
                                RetryRegistry retryRegistry,
                                kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver endpointResolver,
                                @org.springframework.beans.factory.annotation.Value("${kpst.deid.base-url:}") String progressBootBaseUrl) {
        this.webClient = webClient;
        this.progressHttpClient = progressHttpClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retryRegistry.retry("kpstDeid");
        this.endpointResolver = endpointResolver;
        this.progressBootBaseUrl = progressBootBaseUrl;
    }

    /**
     * 진행조회 요청 URI — override 가 있으면 <b>절대 URI</b>, 없으면 구 동작(상대 경로)이다.
     *
     * <p>상대 경로를 그대로 돌려주는 경우 저수준 클라이언트의 기동 시점 base-url 이 쓰인다.
     */
    private String progressUri() {
        return lowLevelUri(PATH_RETRIEVE_PROGRESS);
    }

    /**
     * 저수준 {@code HttpClient} 로 나가는 요청의 URI — override 가 있으면 <b>절대 URI</b>, 없으면
     * 구 동작(상대 경로)이다. 진행조회와 리포트조회가 <b>같은 주소 판정</b>을 공유해야 한다 —
     * 갈리면 프로젝트는 새 서버에 있는데 조회만 옛 서버로 나간다(부분 반영이 미반영보다 위험).
     */
    private String lowLevelUri(String path) {
        if (endpointResolver == null || progressBootBaseUrl == null || progressBootBaseUrl.isBlank()) {
            return path;
        }
        String effective = endpointResolver.resolve(
                kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpoint.DEIDENTIFY, progressBootBaseUrl);
        if (effective == null || effective.isBlank()) {
            return path;
        }
        String base = effective.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    /**
     * 서버 연결 확인 — {@code GET /}. 응답 {@code "Connect"} 면 true.
     */
    public boolean connect() {
        String body = webClient.get()
                .uri(PATH_CONNECT)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)
                .bodyToMono(String.class)
                .timeout(DEFAULT_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(e -> Mono.just(""))
                .blockOptional(DEFAULT_TIMEOUT)
                .orElse("");
        return CONNECT_OK_BODY.equalsIgnoreCase(body.trim());
    }

    /**
     * 프로젝트 생성 및 작업 등록 — {@code POST /project} (application/json).
     * 동일 이름 존재 시 외부 409 → {@link ErrorCode#CONFLICT}.
     *
     * <h3>★ 논블로킹 반환 (Phase C-2) — "외부연동은 모두 비동기" 의 스레드 축</h3>
     * <p>구 구현은 {@code blockOptional(45s)} 로 <b>수락(ACK) 왕복 동안 호출 스레드를 점유</b>했다.
     * 그 스레드는 적재 경로의 {@code batch-async-}(core 2) 또는 재비식별 요청의 Tomcat 요청 스레드였다.
     * 이제 {@link Mono} 를 그대로 돌려주고 <b>구독·완료 처리는 호출자가 전용 풀에서</b> 수행한다
     * ({@code KpstDeidentService} → {@code kpstSubmitScheduler}). 프로토콜은 원래부터 비동기였고
     * (결과는 {@code retrieve_progress} 폴링) 이번 변경은 ACK 왕복의 스레드 점유만 제거한다.
     *
     * <p>빈 응답({@code onComplete} only)은 어떤 신호도 남기지 않아 완료 핸들러가 전혀 실행되지 않으므로,
     * 구 코드의 {@code orElseThrow} 가드를 {@code switchIfEmpty} 로 옮겨 <b>실패 경로로 흐르게</b> 유지한다
     * (무흔적 유실 차단). 예외는 지연 생성해 정상 경로에서 스택트레이스를 채우지 않는다.
     *
     * <p>{@code switchIfEmpty} 는 retry/circuitBreaker <b>뒤</b>에 둔다 — 빈 응답을 재시도 대상으로
     * 승격시키지 않기 위함이며, 이는 구 {@code blockOptional().orElseThrow()} 의 동작과 동일하다.
     *
     * @return 수락 응답 Mono. <b>구독 시점에 요청이 전송</b>된다(cold).
     */
    public Mono<KpstProjectResponse> createProject(KpstProjectRequest request) {
        return webClient.post()
                .uri(PATH_PROJECT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)
                .bodyToMono(KpstProjectResponse.class)
                .timeout(DEFAULT_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(this::translate)
                .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "비식별 프로젝트 생성 응답이 비어있습니다.")));
    }

    /**
     * 진행 상황 조회 — {@code GET /retrieve_progress} (JSON 바디 필수).
     *
     * <p>실서버는 GET 요청에도 JSON 바디 필터(reqUserId/prjId)를 강제하며, 쿼리 전용 호출은
     * {@code HTTP 400 (Invalid JSON body)} 로 거부됨을 실서버에서 확인했다. 따라서 reqUserId/prjId 를
     * JSON 바디({@link KpstProgressRequest})로 전송한다(서버가 camelCase 키 수용).
     *
     * <p>전송 경로(중요): Spring {@code WebClient.method(GET).bodyValue(...)} 는 reactor-netty 가 GET
     * 바디 바이트를 실제로 전송하지 않아(서버가 본문을 무기한 대기 → 타임아웃) 동작하지 않는다.
     * 따라서 바디 전송이 가능한 저수준 {@code HttpClient.request(GET).send(...)} 경로로 전송하고,
     * 응답 JSON 을 {@link KpstProgressResponse} 로 역직렬화한다. Resilience4j retry/circuitBreaker·
     * timeout·onErrorMap·blockOptional 파이프라인 구조는 그대로 유지한다.
     *
     * @param reqUserId 요청자 ID (필수)
     * @param prjId     프로젝트 ID 필터 (필수 — 폴링 시 단일 프로젝트 대상)
     */
    public KpstProgressResponse retrieveProgress(String reqUserId, Long prjId) {
        return getWithJsonBody(progressUri(), new KpstProgressRequest(reqUserId, prjId),
                KpstProgressResponse.class, "비식별 진행 조회");
    }

    /**
     * 처리 결과 리포트 조회 — {@code GET /retrieve_report} (JSON 바디 필수, 규격 §22.3.7). [req: R14]
     *
     * <p>처리 완료(procState=2)된 데이터셋의 <b>얼굴/번호판 검출 집계</b>와 처리 시각을 받는다.
     * 전송 경로·재시도·서킷·타임아웃 정책은 진행조회와 <b>완전히 동일</b>하다(같은 저수준 경로를
     * 공유한다) — 규격이 진행조회와 같은 "GET + JSON 바디" 패턴이기 때문이다.
     *
     * <p><b>호출측 계약</b>: 이 메서드는 다른 외부 호출과 똑같이 실패 시 예외를 던진다. 리포트는
     * 부가 정보이므로 <b>실패를 삼켜 비식별 완료 흐름을 계속시키는 책임은 호출측</b>
     * ({@code KpstDeidentService.fetchReportQuietly})에 둔다 — 클라이언트가 조용히 null 을 돌려주면
     * 다른 호출자가 실패를 관측할 방법이 없어진다.
     *
     * @param reqUserId 요청자 ID (필수)
     * @param prjId     프로젝트 ID 필터
     */
    public KpstReportResponse retrieveReport(String reqUserId, Long prjId) {
        return getWithJsonBody(lowLevelUri(PATH_RETRIEVE_REPORT), new KpstReportRequest(reqUserId, prjId),
                KpstReportResponse.class, "비식별 결과 리포트 조회");
    }

    /**
     * "GET + JSON 바디" 공통 호출 경로 — 진행조회·리포트조회가 공유한다.
     *
     * <p>Spring {@code WebClient.method(GET).bodyValue(...)} 는 reactor-netty 가 GET 바디 바이트를
     * 실제로 전송하지 않아(서버가 본문을 무기한 대기 → 타임아웃) 동작하지 않는다. 따라서 바디 전송이
     * 가능한 저수준 {@code HttpClient.request(GET).send(...)} 경로를 쓴다.
     *
     * <p>두 엔드포인트가 이 메서드를 공유하는 이유는 4xx 비재시도 분류(K3)·본문 미노출(CWE-209)·
     * 빈 응답 처리 같은 <b>미묘한 방어가 복제되면 한쪽만 낡기</b> 때문이다.
     *
     * @param label 오류 메시지용 내부 상수 문자열(사용자 입력 아님 — 로그 인젝션 표면 없음)
     */
    private <T> T getWithJsonBody(String uri, Object request, Class<T> responseType, String label) {
        byte[] payload = serializeRequest(request, label);
        return progressHttpClient
                .headers(h -> {
                    h.set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE,
                            MediaType.APPLICATION_JSON_VALUE);
                    // GET 은 기본적으로 바디리스로 인코딩된다 — Content-Length 를 명시해야 Netty 가
                    // 바디 바이트를 프레이밍·전송한다(서버의 JSON 바디 필수 요구 충족).
                    h.set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, payload.length);
                })
                .request(io.netty.handler.codec.http.HttpMethod.GET)
                .uri(uri)
                .send((req, out) -> out.sendByteArray(Mono.just(payload)))
                .responseSingle((resp, content) -> content.asByteArray()
                        .defaultIfEmpty(new byte[0])
                        .map(body -> {
                            int status = resp.status().code();
                            if (status < 200 || status >= 300) {
                                // 본문 원문은 노출하지 않는다(CWE-209) — 상태 코드만 매핑.
                                // K3: 4xx 결정적 실패는 비재시도(ignore) 예외로 분류해 재시도·서킷집계에서
                                // 제외한다. 5xx·기타(3xx)만 재시도·집계 대상(CustomException 그대로 전파).
                                if (status >= 400 && status < 500) {
                                    throw new NonRetryableExternalException(
                                            label + " 4xx 응답", mapStatus(status));
                                }
                                throw mapStatus(status);
                            }
                            return deserialize(body, responseType, label);
                        }))
                .timeout(DEFAULT_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(this::translate)
                .blockOptional(DEFAULT_TIMEOUT)
                .orElseThrow(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        label + " 응답이 비어있습니다."));
    }

    private byte[] serializeRequest(Object request, String label) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, label + " 요청 직렬화에 실패했습니다.");
        }
    }

    private <T> T deserialize(byte[] body, Class<T> responseType, String label) {
        try {
            return objectMapper.readValue(body, responseType);
        } catch (IOException e) {
            // 본문 원문은 노출하지 않는다(CWE-209) — 예외 종류만 변환.
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, label + " 응답 파싱에 실패했습니다.");
        }
    }

    /**
     * 프로젝트 삭제(ID 기준) — {@code POST /delete_project_id} (application/json).
     */
    public void deleteProject(Long prjId, String userId) {
        Map<String, Object> body = Map.of("project_id", prjId, "user_id", userId);
        KpstDeleteResponse resp = webClient.post()
                .uri(PATH_DELETE_PROJECT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toNonRetryable4xx)
                .bodyToMono(KpstDeleteResponse.class)
                .timeout(DEFAULT_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(this::translate)
                .block(DEFAULT_TIMEOUT);
        if (resp == null || !DELETE_RESULT_SUCCESS.equalsIgnoreCase(resp.result())) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 프로젝트 삭제에 실패했습니다.");
        }
    }

    /**
     * 외부 API 예외를 내부 표준 예외로 변환(CWE-209: 원문 노출 금지).
     * 상태 코드만 매핑하고 본문/스택트레이스는 예외 메시지에 포함하지 않는다.
     */
    private Throwable translate(Throwable e) {
        // K3: 4xx 비재시도 예외는 상태코드→ErrorCode 매핑 결과(CustomException)를 cause 로 실어 보냈으므로
        // 최종 사용자-대면 예외로 복원한다(400→INVALID_INPUT/403→FORBIDDEN/404→NOT_FOUND/409→CONFLICT 보존).
        if (e instanceof NonRetryableExternalException && e.getCause() instanceof CustomException mapped) {
            return mapped;
        }
        if (e instanceof CustomException) {
            return e;
        }
        if (e instanceof WebClientResponseException ex) {
            int status = ex.getStatusCode().value();
            return mapStatus(status);
        }
        log.warn("[KpstDeid] external call failed type={}", e.getClass().getSimpleName());
        return new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 솔루션 호출에 실패했습니다.");
    }

    /**
     * 4xx 클라이언트 오류를 비재시도 예외로 변환 — {@code .retrieve().onStatus(...)} 훅용(K3).
     *
     * <p>상태코드→{@link ErrorCode} 매핑 결과({@link #mapStatus})를 {@link NonRetryableExternalException}
     * 의 cause 로 실어, RetryOperator/CircuitBreakerOperator 는 {@code ignore-exceptions} 로 이를 건너뛰고
     * 파이프라인 말미 {@link #translate} 가 최종 CustomException 으로 복원한다(CWE-209: 본문 원문 미노출).
     */
    private Mono<Throwable> toNonRetryable4xx(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        int status = response.statusCode().value();
        // 본문을 소비/해제(리소스 누수 방지)한 뒤 상태 코드만으로 매핑한다(외부 본문 미노출).
        return response.releaseBody()
                .then(Mono.error(new NonRetryableExternalException(
                        "비식별 솔루션 4xx 응답", mapStatus(status))));
    }

    /** HTTP 상태 코드 → 내부 표준 예외(CWE-209: 원문/스택트레이스 미노출, 상태 코드만 매핑). */
    private CustomException mapStatus(int status) {
        log.warn("[KpstDeid] external error status={}", status);
        ErrorCode code = switch (status) {
            case 400 -> ErrorCode.INVALID_INPUT;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            default -> ErrorCode.EXTERNAL_API_ERROR;
        };
        return new CustomException(code, "비식별 솔루션 호출 실패(status=" + status + ")");
    }
}
