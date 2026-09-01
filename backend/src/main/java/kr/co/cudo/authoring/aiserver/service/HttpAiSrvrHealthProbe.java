package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;

/**
 * 노드 상태 점검을 HTTP 로 수행하는 구현 — {@code GET {노드 주소}/health}. [@design ADR-057]
 *
 * <h3>「응답이 오면 살아 있다」를 여기서는 쓰지 않는다</h3>
 * <p>시계열 인디케이터는 4xx·5xx 도 UP 으로 본다 — 그쪽은 규격에 없는 경로를 핑해 403 을 받고
 * 집계 헬스가 상시 DOWN 이 된 사고의 교훈이다. 여기는 <b>판정의 무게가 다르다</b>: 이 결과가 노드를
 * 목록에서 내리고 올린다. 그리고 {@code /health} 는 계약이 보장하는 경로이므로, 그 경로가 2xx 가
 * 아니라는 것은 그 노드가 추론도 받지 못한다는 뜻이다. 따라서 <b>2xx 만 정상</b>으로 본다.
 *
 * <p>⚠ 두 규칙을 "일관성"을 이유로 통일하지 말 것 — 한쪽은 화면 표시이고 다른 한쪽은 배제 판정이다.
 *
 * <p><b>예외를 밖으로 내보내지 않는다</b> — 응답 없음은 {@code false} 다.
 */
@Slf4j
@Component
public class HttpAiSrvrHealthProbe implements AiSrvrHealthProbe {

    static final String HEALTH_PATH = "/health";

    /**
     * 핑 상한 — 형제 인디케이터와 같은 2초.
     *
     * <p>설정 키를 새로 만들지 않았다. 이 값이 늘어나면 <b>노드 수 x 상한</b>이 폴링 주기(기본 5초)를
     * 넘어 틱이 밀리므로, 운영자가 임의로 늘려도 되는 값이 아니다.
     */
    static final Duration PING_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;

    public HttpAiSrvrHealthProbe(WebClient.Builder webClientBuilder) {
        // 노드마다 주소가 다르므로 baseUrl 을 고정하지 않는다(부하 조회와 같은 이유).
        this.webClient = webClientBuilder.build();
    }

    @Override
    public boolean ping(LsAiSrvr server) {
        URI uri;
        try {
            uri = URI.create(stripTrailingSlash(server.getSrvrAddr()) + HEALTH_PATH);
        } catch (RuntimeException malformed) {
            // ★주소 값을 로그에 싣지 않는다(내부 토폴로지 — CWE-497).
            log.warn("[AiSrvr] 상태점검 주소를 만들 수 없습니다. srvrId={}", server.getSrvrId());
            return false;
        }
        try {
            return Boolean.TRUE.equals(webClient.get()
                    .uri(uri)
                    .exchangeToMono(response -> response.releaseBody()
                            .thenReturn(response.statusCode().is2xxSuccessful()))
                    .timeout(PING_TIMEOUT)
                    .onErrorResume(error -> {
                        log.debug("[AiSrvr] 상태점검 실패. srvrId={} · 원인={}",
                                server.getSrvrId(), error.getClass().getSimpleName());
                        return Mono.just(false);
                    })
                    .blockOptional()
                    .orElse(false));
        } catch (RuntimeException unexpected) {
            log.warn("[AiSrvr] 상태점검이 예상치 못하게 중단됐습니다. srvrId={} · 원인={}",
                    server.getSrvrId(), unexpected.getClass().getSimpleName());
            return false;
        }
    }

    private static String stripTrailingSlash(String addr) {
        String value = addr == null ? "" : addr.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
