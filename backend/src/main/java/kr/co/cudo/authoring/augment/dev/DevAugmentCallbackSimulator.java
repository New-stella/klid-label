package kr.co.cudo.authoring.augment.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.common.security.HmacSigner;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * dev 콜백 시뮬레이터 — 콜백 충실 플로우 Phase 2.
 *
 * <p>외부 0(자족) 로컬 환경에서 외부 SFR-07 증강 시스템을 대신해, 실제 콜백 URL 로
 * HMAC 서명된 결과 콜백을 HTTP POST 한다. {@link HmacWebhookFilter} 의 검증 규칙과 동일한
 * {@link HmacSigner} 를 사용해 서명하므로 시뮬레이션 콜백이 운영 검증 필터를 그대로 통과한다.
 *
 * <h3>빈 활성 조건</h3>
 * <ul>
 *   <li>{@code @Profile("!prd")} — 운영(prd) 에서는 절대 로드되지 않는다.</li>
 *   <li>{@code authoring.augment.external.mode=dev} — mode=dev 일 때만 활성.
 *       기본/noop 에서는 {@code NoopExternalAugmentClient} 가 {@code @Primary} 로 사용된다.</li>
 * </ul>
 *
 * <h3>서명 무결성(HIGH)</h3>
 * <p>서명 대상 문자열과 전송 본문이 <b>바이트 동일</b>해야 검증을 통과한다. 따라서 JSON 직렬화를
 * <b>1회만</b> 수행하고, 그 문자열을 (1) 서명 입력과 (2) 전송 bodyValue 양쪽에 동일하게 사용한다.
 *
 * <h3>SSRF(CWE-918)</h3>
 * <p>호출 base-url 은 서버 설정값({@code authoring.webhook.callback-base-url}) 기반이며, 사용자
 * 입력으로 호스트를 구성하지 않는다. 콜백 path 는 고정 매핑 경로다.
 */
@Slf4j
@Component
@Profile("!prd")
@ConditionalOnProperty(name = "authoring.augment.external.mode", havingValue = "dev")
public class DevAugmentCallbackSimulator implements ExternalAugmentClient {

    /** 콜백 결과 수신 컨트롤러 매핑 경로 — 단일 진실원({@link HmacWebhookFilter#PATH_AUGMENT}) 참조. */
    public static final String CALLBACK_PATH = HmacWebhookFilter.PATH_AUGMENT;

    /** 콜백 결과 상태 — dev 시뮬은 항상 성공을 가정한다. */
    static final String SIMULATED_STATUS = "SUCCESS";

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    private final WebClient callbackWebClient;
    private final ObjectMapper objectMapper;
    private final String secretAugment;

    public DevAugmentCallbackSimulator(
            @Qualifier("augmentCallbackWebClient") WebClient callbackWebClient,
            ObjectMapper objectMapper,
            @Value("${webhook.hmac.secret.augment:}") String secretAugment) {
        this.callbackWebClient = callbackWebClient;
        this.objectMapper = objectMapper;
        this.secretAugment = secretAugment == null ? "" : secretAugment;
    }

    /**
     * 증강 요청을 수신하면 비동기로 HMAC 서명 콜백을 전송한다.
     *
     * <p>{@code @Async} 로 호출 흐름(요청 트랜잭션 AFTER_COMMIT 브리지)을 막지 않는다.
     * HTTP/직렬화 실패는 삼키고 WARN 로깅만 한다 — 요청 측 동작에 영향 0 (best-effort).
     */
    @Override
    @Async("batchAsyncExecutor")
    public boolean requestAugment(Long originAugSn, String augType, String idempotencyKey,
                                  String externalJobId, String callbackUrl) {
        try {
            AugmentResultRequest payload = new AugmentResultRequest(
                    idempotencyKey, externalJobId, SIMULATED_STATUS, originAugSn, augType, null);

            // 직렬화 1회 — 서명 대상 문자열과 전송 본문을 동일 문자열로 유지(바이트 동일 보장).
            String body = objectMapper.writeValueAsString(payload);
            String timestamp = Long.toString(System.currentTimeMillis());
            String signature = HmacWebhookFilter.SIGNATURE_PREFIX
                    + HmacSigner.hex(secretAugment, timestamp + "." + body);

            callbackWebClient.post()
                    .uri(CALLBACK_PATH)
                    .header(HmacWebhookFilter.TIMESTAMP_HEADER, timestamp)
                    .header(HmacWebhookFilter.SIGNATURE_HEADER, signature)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("[Augment][dev-sim] callback sent originAugSn={} augType={} externalJobId={}",
                    originAugSn, safe(augType), safe(externalJobId));
            return true;
        } catch (Exception e) {
            // best-effort: 콜백 실패가 요청 흐름에 영향을 주지 않도록 예외 삼킴 + WARN. 시크릿은 로그 금지.
            log.warn("[Augment][dev-sim] callback failed (ignored) originAugSn={} externalJobId={} err={}",
                    originAugSn, safe(externalJobId), safe(e.getMessage()));
            return false;
        }
    }

    /**
     * 검수 결정 통보 — dev 시뮬에서는 외부 수신처가 없으므로 로그만 남기고 ack 한다.
     */
    @Override
    public boolean syncDecision(Long dataAugSn, String decision, String reasonOrNull) {
        log.info("[Augment][dev-sim] decision sync (log-only) dataAugSn={} decision={} reason={}",
                dataAugSn, safe(decision), reasonOrNull == null ? "" : safe(reasonOrNull));
        return true;
    }

    /** Log Injection(CWE-117) 방어 — CR/LF/TAB 제거. */
    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
