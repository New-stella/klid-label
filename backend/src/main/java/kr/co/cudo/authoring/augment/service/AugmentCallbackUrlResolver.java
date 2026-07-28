package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.common.security.webhook.WebhookProtectedPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 외부 증강 위탁에 실어 보내는 {@code callback_url} 조립 — <b>단일 원천</b>.
 *
 * <p>요청 경로({@link AugmentRequestService})와 신고 해소 후 <b>재개 경로</b>
 * ({@code AugmentRequestBridge}) 가 같은 값을 만들어야 한다. 두 곳에서 각각 문자열을 조립하면 base URL
 * 정규화 규칙(후행 슬래시 제거 등)이 갈라져 재개 위탁의 콜백이 다른 경로로 오는 드리프트가 생긴다.
 */
@Component
public class AugmentCallbackUrlResolver {

    /** 콜백 경로 — {@link WebhookProtectedPaths#PATH_GENAI_CALLBACK} 단일 진실원. */
    public static final String CALLBACK_PATH = WebhookProtectedPaths.PATH_GENAI_CALLBACK;

    private static final String DEFAULT_BASE = "http://localhost:8080/api";

    /** 콜백 base URL — 외부 시스템이 결과를 push 할 엔드포인트의 prefix. */
    private final String callbackBaseUrl;

    public AugmentCallbackUrlResolver(
            @Value("${authoring.webhook.callback-base-url:http://localhost:8080/api}") String callbackBaseUrl) {
        this.callbackBaseUrl = callbackBaseUrl;
    }

    public String resolve() {
        String base = (callbackBaseUrl == null || callbackBaseUrl.isBlank())
                ? DEFAULT_BASE
                : callbackBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + CALLBACK_PATH;
    }
}
