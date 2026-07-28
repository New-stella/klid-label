package kr.co.cudo.authoring.common.security.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * VLM 콜백 IP allowlist — {@code VlmResultController} 의 TODO 이행 (B-ISSUE-25).
 *
 * <p>VLM describe 콜백은 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1)상 <b>무서명</b> 규격이라
 * HMAC 을 요구할 수 없다(요구하면 실 콜백이 전건 401). 대신 3계층으로 보호한다:
 * <ol>
 *   <li>IP allowlist (본 클래스, 운영 프로파일에서 설정)</li>
 *   <li>rate limit + 본문 size cap ({@code HmacWebhookFilter})</li>
 *   <li>{@code VlmResultService.lookupForProcessing} 의 request_id 발급 게이트(무단 주입 차단)</li>
 * </ol>
 *
 * <p>설정: {@code webhook.vlm.allowed-ip-cidrs} (CSV). <b>비어 있으면 미적용</b>(로컬/dev 기본).
 *
 * <h3>운영(prd)·스테이징(stg) 명시 설정 강제 (DEV_FIX M-3 / REDESIGN R-3)</h3>
 * <p>과거에는 미설정이면 WARN 만 남기고 <b>전면 허용(fail-open)</b> 으로 기동했다. 시크릿은
 * 미설정 시 기동 차단인데 3계층 방어 중 첫 계층만 조용히 꺼지는 <b>정책 강도 비대칭</b>이라, 실서버
 * 프로파일에서는 값을 명시하도록 강제한다. 벤더 대역을 모르면 {@code none} 을 명시해 "미적용" 을
 * 의식적으로 선택한다(이 경우 rate limit + {@code request_id} 발급 게이트 2계층으로 보호된다).
 *
 * <p>대상 프로파일은 {@link WebhookConfigProfiles} 가 단일 진실원이다 — 과거에는 이 클래스와
 * {@link ClientIpResolver} 가 각자 {@code contains("prd")} 를 복제해 <b>stg 가 사각지대</b>였다(R-3).
 */
@Slf4j
@Component
public class WebhookIpAllowlist {

    /** allowlist 미적용을 <b>명시적으로</b> 선언하는 값 — 빈 값(=미설정)과 구분한다. */
    public static final String NOT_APPLIED = "none";

    static final String SETTING_KEY = "webhook.vlm.allowed-ip-cidrs";
    static final String ENV_KEY = "WEBHOOK_VLM_ALLOWED_IP_CIDRS";

    private final List<IpAddressMatcher> allowed;

    public WebhookIpAllowlist(@Value("${webhook.vlm.allowed-ip-cidrs:}") String allowedCidrs,
                              Environment environment) {
        boolean explicitRequired = WebhookConfigProfiles.requiresExplicitConfig(environment);
        if (explicitRequired && (allowedCidrs == null || allowedCidrs.isBlank())) {
            throw new BeanInitializationException(
                    "webhook.vlm.allowed-ip-cidrs (env WEBHOOK_VLM_ALLOWED_IP_CIDRS) 가 설정되지 "
                            + "않았습니다(" + WebhookConfigProfiles.describeProfiles() + " 프로파일 필수). "
                            + "VLM 콜백은 벤더 규격상 무서명이라 IP allowlist 가 3계층 방어의 "
                            + "첫 계층입니다. 벤더 송신 대역을 지정하거나, 미적용을 의도한다면 '"
                            + NOT_APPLIED + "' 을 명시하세요.");
        }
        // 형식 오류는 무시하지 않고 기동을 막는다 — 조용히 버리면 allowlist 가 비어 "전면 허용"
        // 으로 떨어져, 설정을 한 운영자가 보호받고 있다고 오인한다(DEV_FIX N-4).
        this.allowed = isNotApplied(allowedCidrs)
                ? List.of()
                : WebhookCidrParser.parseStrict(allowedCidrs, SETTING_KEY, ENV_KEY);
        if (!allowed.isEmpty()) {
            log.info("[Webhook] VLM 콜백 IP allowlist 활성 count={}", allowed.size());
        } else if (explicitRequired) {
            log.warn("[Webhook] VLM 콜백 IP allowlist 미적용('{}' 명시) — rate limit 과 request_id "
                    + "발급 게이트만으로 보호됩니다.", NOT_APPLIED);
        }
    }

    /** {@code none} = 미적용을 명시적으로 선택 (빈 값=미설정 과 구분). */
    private static boolean isNotApplied(String value) {
        return value != null && NOT_APPLIED.equalsIgnoreCase(value.trim());
    }

    /** allowlist 가 설정돼 있는가(미설정이면 미적용). */
    public boolean isEnabled() {
        return !allowed.isEmpty();
    }

    /** 허용 대상 IP 인가. allowlist 미설정 시 항상 {@code true}. */
    public boolean isAllowed(String ip) {
        if (allowed.isEmpty()) {
            return true;
        }
        if (ip == null || ip.isBlank()) {
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
