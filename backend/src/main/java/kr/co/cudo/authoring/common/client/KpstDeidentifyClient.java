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
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private static final String PATH_DELETE_PROJECT_ID = "/delete_project_id";

    private static final String CONNECT_OK_BODY = "Connect";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);
    private static final String DELETE_RESULT_SUCCESS = "success";

    private final WebClient webClient;
    private final HttpClient progressHttpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public KpstDeidentifyClient(@Qualifier("kpstDeidWebClient") WebClient webClient,
                                @Qualifier("kpstDeidProgressHttpClient") HttpClient progressHttpClient,
                                @Qualifier("kpstDeidCircuitBreaker") CircuitBreaker circuitBreaker,
                                RetryRegistry retryRegistry) {
        this.webClient = webClient;
        this.progressHttpClient = progressHttpClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retryRegistry.retry("kpstDeid");
    }

    /**
     * 서버 연결 확인 — {@code GET /}. 응답 {@code "Connect"} 면 true.
     */
    public boolean connect() {
        String body = webClient.get()
                .uri(PATH_CONNECT)
                .retrieve()
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
     */
    public KpstProjectResponse createProject(KpstProjectRequest request) {
        return webClient.post()
                .uri(PATH_PROJECT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(KpstProjectResponse.class)
                .timeout(DEFAULT_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(this::translate)
                .blockOptional(DEFAULT_TIMEOUT)
                .orElseThrow(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "비식별 프로젝트 생성 응답이 비어있습니다."));
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
        byte[] payload = serializeProgressRequest(new KpstProgressRequest(reqUserId, prjId));
        return progressHttpClient
                .headers(h -> {
                    h.set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE,
                            MediaType.APPLICATION_JSON_VALUE);
                    // GET 은 기본적으로 바디리스로 인코딩된다 — Content-Length 를 명시해야 Netty 가
                    // 바디 바이트를 프레이밍·전송한다(서버의 JSON 바디 필수 요구 충족).
                    h.set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, payload.length);
                })
                .request(io.netty.handler.codec.http.HttpMethod.GET)
                .uri(PATH_RETRIEVE_PROGRESS)
                .send((req, out) -> out.sendByteArray(Mono.just(payload)))
                .responseSingle((resp, content) -> content.asByteArray()
                        .defaultIfEmpty(new byte[0])
                        .map(body -> {
                            int status = resp.status().code();
                            if (status < 200 || status >= 300) {
                                // 본문 원문은 노출하지 않는다(CWE-209) — 상태 코드만 매핑.
                                throw mapStatus(status);
                            }
                            return deserializeProgress(body);
                        }))
                .timeout(DEFAULT_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(this::translate)
                .blockOptional(DEFAULT_TIMEOUT)
                .orElseThrow(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "비식별 진행 조회 응답이 비어있습니다."));
    }

    private byte[] serializeProgressRequest(KpstProgressRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "비식별 진행 조회 요청 직렬화에 실패했습니다.");
        }
    }

    private KpstProgressResponse deserializeProgress(byte[] body) {
        try {
            return objectMapper.readValue(body, KpstProgressResponse.class);
        } catch (IOException e) {
            // 본문 원문은 노출하지 않는다(CWE-209) — 예외 종류만 변환.
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 진행 조회 응답 파싱에 실패했습니다.");
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
