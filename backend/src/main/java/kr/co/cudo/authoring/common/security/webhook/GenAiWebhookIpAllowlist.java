package kr.co.cudo.authoring.common.security.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 생성형 AI(증강) 웹훅 IP allowlist — Phase 7-A2.
 *
 * <p>「생성형 AI API 연동명세서 v1.1」의 웹훅은 <b>서명·인증 헤더가 없는</b> 규격이라 HMAC 을 요구할
 * 수 없다. 대신 3계층으로 보호한다:
 * <ol>
 *   <li>IP allowlist (본 클래스)</li>
 *   <li>rate limit + 본문 size cap ({@code HmacWebhookFilter} 무서명 가드 경로)</li>
 *   <li>{@code request_id} 발급 게이트 ({@code GenAiCallbackService}) — 우리가 발급한 적 없는
 *       {@code request_id} 는 401. 무단 주입을 막는 <b>최종 방어선</b></li>
 * </ol>
 *
 * <h3>{@link WebhookIpAllowlist}(VLM) 와 정책이 다르다 — fail-closed</h3>
 * <p>VLM 용 allowlist 는 "미설정 = 미적용(전면 허용)" 이다(벤더 대역 미상 상태로 운영에 들어간
 * 이력이 있어 그렇게 굳었다). 본 클래스는 <b>반대로 미설정이면 전면 차단</b> 한다 —
 * 신규 연동이므로 조용한 전면 허용 기본값을 물려받지 않는다. 허용하려면 대역을 <b>명시</b>해야
 * 하고, 값 형식 오류는 기동을 막는다({@link WebhookCidrParser}).
 *
 * <p>설정: {@code webhook.genai.allowed-ip-cidrs} (CSV, 기본 {@code none} = 허용 IP 없음).
 * 로컬/개발처럼 발신 IP 가 유동적인 환경은 {@code 0.0.0.0/0,::/0} 를 <b>명시</b>한다 — 전면 허용을
 * 의도했다는 사실이 설정값에 남아야 grep 으로 드러난다.
 */
@Slf4j
@Component
public class GenAiWebhookIpAllowlist {

    /** 허용 IP 없음(전면 차단)을 뜻하는 값 — 미설정 기본값이기도 하다. */
    public static final String NONE = "none";

    static final String SETTING_KEY = "webhook.genai.allowed-ip-cidrs";
    static final String ENV_KEY = "WEBHOOK_GENAI_ALLOWED_IP_CIDRS";

    private final List<IpAddressMatcher> allowed;

    public GenAiWebhookIpAllowlist(
            @Value("${webhook.genai.allowed-ip-cidrs:}") String allowedCidrs) {
        this.allowed = isNone(allowedCidrs)
                ? List.of()
                : WebhookCidrParser.parseStrict(allowedCidrs, SETTING_KEY, ENV_KEY);
        if (allowed.isEmpty()) {
            log.warn("[Webhook] 생성형 AI 콜백 IP allowlist 미설정 — 모든 출처를 403 으로 차단합니다. "
                    + "허용하려면 {} (env {}) 에 대역을 명시하세요.", SETTING_KEY, ENV_KEY);
        } else {
            log.info("[Webhook] 생성형 AI 콜백 IP allowlist 활성 count={}", allowed.size());
        }
    }

    /** 빈 값(미설정) 과 {@code none} 은 모두 "허용 IP 없음" 이다. */
    private static boolean isNone(String value) {
        return value == null || value.isBlank() || NONE.equalsIgnoreCase(value.trim());
    }

    /** 허용 대상 IP 인가. 미설정이면 <b>항상 false</b>(fail-closed). */
    public boolean isAllowed(String ip) {
        if (allowed.isEmpty() || ip == null || ip.isBlank()) {
            return false;
        }
        for (IpAddressMatcher matcher : allowed) {
            try {
                if (matcher.matches(ip)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                return false; // 비정상 IP 리터럴 = 거부 (fail-closed)
            }
        }
        return false;
    }
}
