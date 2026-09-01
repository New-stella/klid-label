package kr.co.cudo.authoring.aiserver.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrSlotLoad;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * 부하 조회 경로를 HTTP 로 호출하는 구현. [@design ADR-057]
 *
 * <h3>계약</h3>
 * <pre>
 *   GET {노드 주소}/internal/load        (추론과 같은 포트 · 인증 없음)
 *   {"slots":{"batch":{"running":1,"queued":12,"oldest_wait_ms":2380},
 *             "interactive":{"running":0,"queued":0,"oldest_wait_ms":0}},
 *    "observed_at":"..."}
 * </pre>
 *
 * <h3>일부러 쓰지 않는 값 둘</h3>
 * <ul>
 *   <li>{@code observed_at} — <b>상대 장비의 시계</b>다. 우리 시계에서 빼면 시계 오차가 그대로
 *       지연으로 잡힌다. 신선도는 우리 폴링 주기로 판단한다.</li>
 *   <li>{@code oldest_wait_ms} — 지금 쓰는 판정(실효 부하)에 들어가지 않는다. 저장하지 않는다 —
 *       쓰지 않는 값을 담아 두면 다음 사람이 그것을 근거로 판정을 만든다.</li>
 * </ul>
 *
 * <h3>관대하게 읽되 지어내지 않는다</h3>
 * <p>모르는 슬롯 키·모르는 필드는 <b>무시하고 아는 것만</b> 반영한다. 상대가 슬롯을 늘렸다고 우리가
 * 깨지면 안 되기 때문이다. 반대로 값이 없거나 해석되지 않으면 <b>비워 둔다</b> — 0 으로 채우면 그
 * 노드가 "가장 한가한 노드"가 되어 요청을 빨아들인다.
 *
 * <p><b>예외를 밖으로 내보내지 않는다.</b> 한 노드의 조회 실패가 순회를 끊으면 뒤 노드가 통째로
 * 관측에서 빠진다.
 */
@Slf4j
@Component
public class HttpAiSrvrLoadProbe implements AiSrvrLoadProbe {

    /** 부하 조회 경로 — 추론과 같은 포트이며 인증이 없다(내부망 전용 경로). */
    static final String LOAD_PATH = "/internal/load";

    private final WebClient webClient;
    private final Duration timeout;

    public HttpAiSrvrLoadProbe(
            WebClient.Builder webClientBuilder,
            @Value("${authoring.integration.ai-server.load-timeout-ms:500}") long timeoutMillis) {
        // ★노드마다 주소가 다르므로 baseUrl 을 고정하지 않고 매 호출 절대 URI 로 부른다.
        //   추론 클라이언트(aiServerWebClient)를 재사용하지 않는 이유: 그쪽은 설정 화면의 주소
        //   override 필터가 달려 있어 <원장이 정한 노드 주소를 다른 값으로 덮어쓴다>.
        this.webClient = webClientBuilder.build();
        // 0 이하는 즉시 만료라 전 노드가 항상 "알 수 없음"이 된다 — 오설정을 하한으로 눌러 흡수한다.
        this.timeout = Duration.ofMillis(Math.max(1L, timeoutMillis));
    }

    @Override
    public AiSrvrLoadReport probe(LsAiSrvr server) {
        URI uri;
        try {
            uri = URI.create(stripTrailingSlash(server.getSrvrAddr()) + LOAD_PATH);
        } catch (RuntimeException malformed) {
            // 주소는 관리 화면에서 들어온 값이라 형식이 깨져 있을 수 있다. ★주소 값을 로그에 싣지
            // 않는다(내부 토폴로지 — CWE-497). 어느 노드인지만 남긴다.
            log.warn("[AiSrvr] 부하 조회 주소를 만들 수 없습니다. srvrId={}", server.getSrvrId());
            return AiSrvrLoadReport.unknown();
        }

        try {
            return webClient.get()
                    .uri(uri)
                    .exchangeToMono(this::toReport)
                    .timeout(timeout)
                    .onErrorResume(error -> {
                        // ★느린 것을 「포화」로 읽지 않는다. 이 경로는 슬롯을 거치지 않으므로 느린 것은
                        //   부하 신호가 아니라 배선·프로세스 이상 신호다. 부하는 미상으로 두고 직전
                        //   값을 유지한다 — 연속 실패 판정은 상태점검 축이 따로 한다.
                        log.warn("[AiSrvr] 부하 조회에 실패해 직전 값을 유지합니다. srvrId={} · 원인={}",
                                server.getSrvrId(), error.getClass().getSimpleName());
                        return Mono.just(AiSrvrLoadReport.unknown());
                    })
                    .blockOptional()
                    .orElseGet(AiSrvrLoadReport::unknown);
        } catch (RuntimeException unexpected) {
            log.warn("[AiSrvr] 부하 조회가 예상치 못하게 중단됐습니다. srvrId={} · 원인={}",
                    server.getSrvrId(), unexpected.getClass().getSimpleName());
            return AiSrvrLoadReport.unknown();
        }
    }

    private Mono<AiSrvrLoadReport> toReport(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        int status = response.statusCode().value();
        if (status == 404) {
            // ★「없는 경로」는 장비 이상이 아니다 — 아직 부하를 알리지 않는다는 뜻이다.
            //   부하 경로는 실행을 용도별로 나누는 변경과 함께 생기므로 그 전에는 존재하지 않고,
            //   되돌리기·배포 순서 어긋남으로 구 버전 노드가 섞이는 구간도 실재한다.
            return response.releaseBody().thenReturn(AiSrvrLoadReport.notReporting());
        }
        if (response.statusCode().isError()) {
            return response.releaseBody().thenReturn(AiSrvrLoadReport.unknown());
        }
        return response.bodyToMono(LoadBody.class)
                .map(HttpAiSrvrLoadProbe::toSlots)
                .defaultIfEmpty(AiSrvrLoadReport.unknown());
    }

    private static AiSrvrLoadReport toSlots(LoadBody body) {
        Map<AiSrvrUsageType, AiSrvrSlotLoad> slots = new EnumMap<>(AiSrvrUsageType.class);
        if (body.slots() != null) {
            body.slots().forEach((key, slot) -> AiSrvrUsageType.fromSlotKey(key)
                    .ifPresent(usage -> toLoad(slot)
                            .ifPresent(load -> slots.put(usage, load))));
        }
        return AiSrvrLoadReport.reported(slots);
    }

    /**
     * 슬롯 하나를 읽는다 — <b>두 건수가 모두 있을 때만</b>.
     *
     * <p>★한쪽이라도 없으면 <b>그 슬롯을 통째로 「값 없음」으로 둔다</b>(슬롯 객체 자체가
     * 빠졌을 때와 같은 취급). 빠진 건수를 0 으로 채우면 「처리 중 1 · 대기 3」이어야 할 노드가
     * 「처리 중 0 · 대기 3」으로 적혀 <b>설계가 막으려던 과소평가</b>가 그대로 되살아난다 — 실효 부하는
     * 둘의 합이라 한 축만 빠져도 그 노드가 더 한가해 보이고, 그것이 원장에 확정값으로 남는다.
     * 부분 구현된 노드(부하 경로 롤아웃 중)가 실제로 이 모양을 보낸다.
     *
     * <p>비워 둔 슬롯은 저장되지 않고 <b>직전 값이 유지</b>된다 — 모르는 것을 지어내지 않는다.
     * 음수 정규화는 {@link AiSrvrSlotLoad} 가 소유한다(한 곳에서만).
     */
    private static Optional<AiSrvrSlotLoad> toLoad(SlotBody slot) {
        if (slot == null || slot.running() == null || slot.queued() == null) {
            return Optional.empty();
        }
        return Optional.of(new AiSrvrSlotLoad(slot.running(), slot.queued()));
    }

    private static String stripTrailingSlash(String addr) {
        String value = addr == null ? "" : addr.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 부하 응답 본문.
     *
     * <p>슬롯을 {@code Map} 으로 받는 것이 <b>관대함의 자리</b>다 — 필드로 고정하면 상대가 슬롯을
     * 늘렸을 때 그 키를 어디에도 담지 못하거나 역직렬화가 깨진다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoadBody(Map<String, SlotBody> slots) {
    }

    /** 슬롯 하나. {@code oldest_wait_ms} 등 우리가 쓰지 않는 필드는 선언하지 않고 무시한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SlotBody(Integer running, Integer queued) {
    }
}
