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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;


/**
 * ai-server(YOLO/SAM2/VLM) 추론 호출 클라이언트.
 *
 * <p>모든 추론 호출의 <b>호출당 상한</b>은 {@link AiWaitBudgetPolicy#PER_CALL_TIMEOUT} 하나를 쓴다.
 * 여기에 숫자를 다시 적으면 화면이 받는 대기 예산과 실제 서버 상한이 갈려, "정상 동작이 AI 실패로
 * 보이는" 결함이 되살아난다(그 예산이 바로 이 값에서 도출된다).
 */
@Component
public class AiServerClient {

    private final WebClient webClient;
    private final CircuitBreaker batchCircuitBreaker;
    private final CircuitBreaker interactiveCircuitBreaker;
    private final CircuitBreaker vlmVerifyCircuitBreaker;
    private final Retry retry;

    public AiServerClient(@Qualifier("aiServerWebClient") WebClient webClient,
                          @Qualifier("aiBatchCircuitBreaker") CircuitBreaker batchCircuitBreaker,
                          @Qualifier("aiInteractiveCircuitBreaker") CircuitBreaker interactiveCircuitBreaker,
                          @Qualifier("aiCircuitBreaker") CircuitBreaker vlmVerifyCircuitBreaker,
                          RetryRegistry retryRegistry) {
        this.webClient = webClient;
        this.batchCircuitBreaker = batchCircuitBreaker;
        this.interactiveCircuitBreaker = interactiveCircuitBreaker;
        this.vlmVerifyCircuitBreaker = vlmVerifyCircuitBreaker;
        // 재시도는 용도로 가르지 않는다 — 호출마다 독립 판정이라 상태를 공유해도 서로 막지 않는다.
        this.retry = retryRegistry.retry("ai");
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
        WebClient.RequestBodySpec spec = webClient.post().uri(uri);
        String headerValue = workload.headerValue();
        if (headerValue != null) {
            spec = spec.header(AiWorkload.HEADER_NAME, headerValue);
        }
        return spec.bodyValue(request)
                .retrieve()
                .bodyToMono(responseType)
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
        return call("/infer/yolo/track", request, YoloResponse.class, workload);
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
        return call("/infer/sam2/segment", request, Sam2Response.class, workload);
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

    public Mono<VlmVerifyResponse> verifyObjects(VlmVerifyRequest request) {
        return webClient.post()
                .uri("/infer/vlm/verify-objects")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(VlmVerifyResponse.class)
                .timeout(AiWaitBudgetPolicy.PER_CALL_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(vlmVerifyCircuitBreaker));
    }

    // Phase 1 (2026-05-19): extractVideoMeta(VlmMetaRequest) 메서드 제거.
    //  - 영상 단위 시계열 메타 추출은 외부 VLM 서비스 책임으로 이관됨 (V1.8 / ccarch if-vlm-timeseries-spi).
    //  - 신규 외부 위탁 클라이언트는 {@link kr.co.cudo.authoring.common.client.VlmClient}.
    //  - ai-server 의 /infer/vlm/meta 라우트는 본 Phase 이전부터 부재 (verify-objects 만 유지).
}
