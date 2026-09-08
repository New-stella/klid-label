package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.client.dto.VlmVerifyRequest;
import kr.co.cudo.authoring.common.client.dto.VlmVerifyResponse;
import kr.co.cudo.authoring.common.client.dto.YoloRequest;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointExchangeFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;


/**
 * ai-server(YOLO/SAM2/VLM) 추론 호출 클라이언트.
 *
 * <p>모든 추론 호출의 <b>호출당 상한</b>은 {@link AiWaitBudgetPolicy#PER_CALL_TIMEOUT} 하나를 쓴다.
 * 여기에 숫자를 다시 적으면 화면이 받는 대기 예산과 실제 서버 상한이 갈려, "정상 동작이 AI 실패로
 * 보이는" 결함이 되살아난다(그 예산이 바로 이 값에서 도출된다).
 *
 * <h3>★ 목적지는 <b>장비 원장</b>에서 온다 (2026-09-08) [@design ADR-057]</h3>
 * <p>⚠ <b>구 형상 폐기</b> — 종전에는 이 클라이언트가 상대 경로만 쓰고 목적지는 빈의 기준 주소
 * (= <b>단일 설정값</b>)가 정했다. 장비를 둘 등록해도 <b>요청은 늘 한 대로</b> 갔다. 이제 호출마다
 * {@link AiSrvrTargetResolver} 가 원장에서 고르고 그 결과를 <b>절대 목적지로 고정</b>한다.
 * 되돌리지 말 것 — 되돌리면 이중화가 배선만 있고 동작하지 않는 상태로 조용히 돌아간다.
 *
 * <p>고정 방식은 시계열 위탁({@link VlmClient})과 <b>같은 술어·같은 표식</b>을 쓴다
 * ({@link PinnedTarget} · {@code EXPLICIT_TARGET_ATTRIBUTE}). 여기서 자기 기준으로 주소를 조립하면
 * 「고를 때」와 「보낼 때」의 판정이 갈려, 원장에는 A 로 보냈다고 남는데 요청은 B 로 가는
 * <b>조용한 어긋남</b>이 열린다.
 *
 * <h3>영상 고정은 <b>호출자가</b> 넘긴다</h3>
 * <p>이 클라이언트는 <b>영상이 무엇인지 모른다</b>. 배치는 영상 단위로 장비를 고정해야 하므로
 * ({@code AiSrvrBatchAssignment}) 스텝이 정한 주소를 {@code srvrAddr} 인자로 넘기고, 그 값이 있으면
 * 원장을 다시 고르지 않는다. 화면 요청은 고정할 대상이 없어 호출마다 고르며 배정을 기록하지 않는다.
 *
 * <p>⚠ <b>기존 시그니처는 그대로 둔다</b> — 화면 경로가 쓰는 얼굴이라 깨면 그 도메인이 함께 무너진다.
 * 배치용 경로는 <b>인자를 더한 형제</b>로 붙인다(용도 축을 더할 때와 같은 방식).
 */
@Component
public class AiServerClient {

    private final WebClient webClient;
    private final CircuitBreaker batchCircuitBreaker;
    private final CircuitBreaker interactiveCircuitBreaker;
    private final CircuitBreaker vlmVerifyCircuitBreaker;
    private final Retry retry;
    private final AiSrvrTargetResolver targetResolver;

    public AiServerClient(@Qualifier("aiServerWebClient") WebClient webClient,
                          @Qualifier("aiBatchCircuitBreaker") CircuitBreaker batchCircuitBreaker,
                          @Qualifier("aiInteractiveCircuitBreaker") CircuitBreaker interactiveCircuitBreaker,
                          @Qualifier("aiCircuitBreaker") CircuitBreaker vlmVerifyCircuitBreaker,
                          RetryRegistry retryRegistry,
                          AiSrvrTargetResolver targetResolver) {
        this.webClient = webClient;
        this.batchCircuitBreaker = batchCircuitBreaker;
        this.interactiveCircuitBreaker = interactiveCircuitBreaker;
        this.vlmVerifyCircuitBreaker = vlmVerifyCircuitBreaker;
        // 재시도는 용도로 가르지 않는다 — 호출마다 독립 판정이라 상태를 공유해도 서로 막지 않는다.
        this.retry = retryRegistry.retry("ai");
        this.targetResolver = targetResolver;
    }

    /**
     * 이 호출의 <b>절대 목적지</b>. 고르지 못했으면 {@code null}(상대 경로 유지 = 배포 기본 주소).
     *
     * <p>주소가 있는데 목적지를 만들 수 없으면 <b>조용히 되돌리지 않고 실패</b>한다 — {@code null} 로
     * 낮추면 요청이 배포 기본 주소로 나가 「고른 장비」와 실제 목적지가 갈린다. 정상 경로에서는
     * 선택기가 같은 술어로 이미 걸렀으므로 도달하지 않는 fail-secure 분기다.
     *
     * <p>⚠ 예외·로그에 <b>주소를 싣지 않는다</b> — 내부 토폴로지이고(CWE-497) 개행이 섞이면 기록
     * 위조 통로가 된다(CWE-117).
     */
    private URI targetFor(String path, AiWorkload workload, String srvrAddr) {
        String addr = (srvrAddr == null || srvrAddr.isBlank())
                ? targetResolver.resolveAddress(workload).orElse(null)
                : srvrAddr;
        if (addr == null || addr.isBlank()) {
            return null;
        }
        return PinnedTarget.resolve(addr, path).orElseThrow(() ->
                new NonRetryableExternalException(
                        "AI 추론 대상 장비의 주소로 목적지를 만들 수 없습니다."));
    }

    private CircuitBreaker circuitFor(AiWorkload workload) {
        return workload == AiWorkload.BATCH ? batchCircuitBreaker : interactiveCircuitBreaker;
    }

    /**
     * 공통 호출 골격 — 용도에 따라 <b>헤더와 서킷</b>이 갈린다.
     *
     * <p>배치일 때만 {@link AiWorkload#HEADER_NAME} 을 싣는다. 화면은 값을 붙이지 않는다 —
     * ai-server 판정이 「정확히 batch」 하나로 유지돼야 오타·대소문자 차이가 조용히 배치로 새지 않는다.
     */
    private <T> Mono<T> call(String uri, Object request, Class<T> responseType, AiWorkload workload) {
        return call(uri, request, responseType, workload, null);
    }

    /**
     * 공통 호출 골격 — 용도에 따라 <b>헤더와 서킷</b>이 갈리고, 목적지는 <b>원장에서 고른 장비</b>로
     * 고정된다.
     *
     * <p>배치일 때만 {@link AiWorkload#HEADER_NAME} 을 싣는다. 화면은 값을 붙이지 않는다 —
     * ai-server 판정이 「정확히 batch」 하나로 유지돼야 오타·대소문자 차이가 조용히 배치로 새지 않는다.
     *
     * <p>★ 목적지 해석을 {@link Mono#defer} 안에 둔다. 밖에서 풀면 <b>구독 전에</b> 예외가 나
     * {@code Mono} 를 돌려받을 것으로 아는 호출자의 오류 처리를 통째로 건너뛴다(후보 0 거부가 바로
     * 그 예외다). 안에 두면 실패가 다른 외부 호출 실패와 <b>같은 통로</b>로 흐른다.
     *
     * @param srvrAddr 호출자가 이미 정한 장비 기준 주소(배치의 영상 고정). {@code null} 이면 이
     *                 호출을 위해 원장에서 고른다
     */
    private <T> Mono<T> call(String uri, Object request, Class<T> responseType, AiWorkload workload,
                             String srvrAddr) {
        return Mono.defer(() -> {
            URI target = targetFor(uri, workload, srvrAddr);
            WebClient.RequestBodySpec spec = webClient.post()
                    .uri(uriBuilder -> target == null ? uriBuilder.path(uri).build() : target);
            if (target != null) {
                spec = spec.attributes(attrs -> attrs.put(
                        IntegrationEndpointExchangeFilter.EXPLICIT_TARGET_ATTRIBUTE, Boolean.TRUE));
            }
            String headerValue = workload.headerValue();
            if (headerValue != null) {
                spec = spec.header(AiWorkload.HEADER_NAME, headerValue);
            }
            return spec.bodyValue(request)
                    .retrieve()
                    .bodyToMono(responseType);
        })
                .timeout(AiWaitBudgetPolicy.PER_CALL_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitFor(workload)));
    }

    /**
     * 용도를 명시하지 않은 호출 — <b>저작도구 화면</b>으로 처리한다.
     * <p>배치 파이프라인만 {@link #predictYolo(YoloRequest, AiWorkload)} 로 용도를 명시한다.
     * 기본값을 화면으로 둔 이유는 표시를 빠뜨려도 사람이 쓰는 쪽이 보호되는 방향으로
     * 틀리기 때문이다({@code ADR-056}).
     */
    public Mono<YoloResponse> predictYolo(YoloRequest request) {
        return predictYolo(request, AiWorkload.defaultWorkload());
    }

    /** 용도를 명시하는 호출 — 실행 슬롯과 서킷이 {@code workload} 로 갈린다. */
    public Mono<YoloResponse> predictYolo(YoloRequest request, AiWorkload workload) {
        return call("/infer/yolo/predict", request, YoloResponse.class, workload);
    }

    /**
     * YOLO Track 추론 — Phase 3.
     * <p>{@code clip_id} 단위로 ai-server 가 트래커 상태를 격리하며, 동일 객체에 동일 track_id 를 부여한다.
     * Phase 4 에서 {@link kr.co.cudo.authoring.batch.step.YoloAutolabelStep} 가 {@link #predictYolo}
     * 대신 본 메서드를 호출하도록 전환된다.
     */
    /**
     * 용도를 명시하지 않은 호출 — <b>저작도구 화면</b>으로 처리한다.
     * <p>배치 파이프라인만 {@link #predictYoloTrack(YoloTrackRequest, AiWorkload)} 로 용도를 명시한다.
     * 기본값을 화면으로 둔 이유는 표시를 빠뜨려도 사람이 쓰는 쪽이 보호되는 방향으로
     * 틀리기 때문이다({@code ADR-056}).
     */
    public Mono<YoloResponse> predictYoloTrack(YoloTrackRequest request) {
        return predictYoloTrack(request, AiWorkload.defaultWorkload());
    }

    /** 용도를 명시하는 호출 — 실행 슬롯과 서킷이 {@code workload} 로 갈린다. */
    public Mono<YoloResponse> predictYoloTrack(YoloTrackRequest request, AiWorkload workload) {
        return predictYoloTrack(request, workload, null);
    }

    /**
     * 용도와 <b>장비</b>를 함께 명시하는 호출 — <b>배치의 영상 고정</b>이 쓰는 얼굴. [@design ADR-057]
     *
     * @param srvrAddr {@code AiSrvrBatchAssignment} 가 그 영상에 대해 정한 장비 기준 주소.
     *                 {@code null} 이면 이 호출을 위해 원장에서 고른다(고정 없음)
     */
    public Mono<YoloResponse> predictYoloTrack(YoloTrackRequest request, AiWorkload workload,
                                               String srvrAddr) {
        return call("/infer/yolo/track", request, YoloResponse.class, workload, srvrAddr);
    }

    /**
     * 용도를 명시하지 않은 호출 — <b>저작도구 화면</b>으로 처리한다.
     * <p>배치 파이프라인만 {@link #segment(Sam2Request, AiWorkload)} 로 용도를 명시한다.
     * 기본값을 화면으로 둔 이유는 표시를 빠뜨려도 사람이 쓰는 쪽이 보호되는 방향으로
     * 틀리기 때문이다({@code ADR-056}).
     */
    public Mono<Sam2Response> segment(Sam2Request request) {
        return segment(request, AiWorkload.defaultWorkload());
    }

    /** 용도를 명시하는 호출 — 실행 슬롯과 서킷이 {@code workload} 로 갈린다. */
    public Mono<Sam2Response> segment(Sam2Request request, AiWorkload workload) {
        return segment(request, workload, null);
    }

    /**
     * 용도와 <b>장비</b>를 함께 명시하는 호출 — <b>배치의 영상 고정</b>이 쓰는 얼굴. [@design ADR-057]
     *
     * @param srvrAddr {@code AiSrvrBatchAssignment} 가 그 영상에 대해 정한 장비 기준 주소.
     *                 {@code null} 이면 이 호출을 위해 원장에서 고른다(고정 없음)
     */
    public Mono<Sam2Response> segment(Sam2Request request, AiWorkload workload, String srvrAddr) {
        return call("/infer/sam2/segment", request, Sam2Response.class, workload, srvrAddr);
    }

    /**
     * 용도를 명시하지 않은 호출 — <b>저작도구 화면</b>으로 처리한다.
     * <p>배치 파이프라인만 {@link #track(Sam2TrackRequest, AiWorkload)} 로 용도를 명시한다.
     * 기본값을 화면으로 둔 이유는 표시를 빠뜨려도 사람이 쓰는 쪽이 보호되는 방향으로
     * 틀리기 때문이다({@code ADR-056}).
     */
    public Mono<Sam2TrackResponse> track(Sam2TrackRequest request) {
        return track(request, AiWorkload.defaultWorkload());
    }

    /** 용도를 명시하는 호출 — 실행 슬롯과 서킷이 {@code workload} 로 갈린다. */
    public Mono<Sam2TrackResponse> track(Sam2TrackRequest request, AiWorkload workload) {
        return call("/infer/sam2/track", request, Sam2TrackResponse.class, workload);
    }

    /**
     * ai-server 의 객체 검증 추론 — 서킷만 전용이고 <b>목적지 고정은 다른 호출과 같다</b>.
     *
     * <p>이 호출도 ai-server 로 가므로 원장에서 고른 장비로 고정한다. 여기만 배포 기본 주소로 두면
     * 같은 서버를 부르는 두 경로가 서로 다른 곳을 보게 된다.
     */
    public Mono<VlmVerifyResponse> verifyObjects(VlmVerifyRequest request) {
        String path = "/infer/vlm/verify-objects";
        return Mono.defer(() -> {
            URI target = targetFor(path, AiWorkload.defaultWorkload(), null);
            return webClient.post()
                    .uri(uriBuilder -> target == null ? uriBuilder.path(path).build() : target)
                    .attributes(attrs -> {
                        if (target != null) {
                            attrs.put(IntegrationEndpointExchangeFilter.EXPLICIT_TARGET_ATTRIBUTE,
                                    Boolean.TRUE);
                        }
                    })
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(VlmVerifyResponse.class);
        })
                .timeout(AiWaitBudgetPolicy.PER_CALL_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(vlmVerifyCircuitBreaker));
    }

    // Phase 1 (2026-05-19): extractVideoMeta(VlmMetaRequest) 메서드 제거.
    //  - 영상 단위 시계열 메타 추출은 외부 VLM 서비스 책임으로 이관됨 (V1.8 / ccarch if-vlm-timeseries-spi).
    //  - 신규 외부 위탁 클라이언트는 {@link kr.co.cudo.authoring.common.client.VlmClient}.
    //  - ai-server 의 /infer/vlm/meta 라우트는 본 Phase 이전부터 부재 (verify-objects 만 유지).
}
