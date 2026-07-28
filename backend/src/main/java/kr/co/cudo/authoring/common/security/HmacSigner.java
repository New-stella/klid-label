package kr.co.cudo.authoring.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Webhook HMAC 서명 공용 유틸 — 콜백 충실 플로우 Phase 2 에서 추출.
 *
 * <p>{@link HmacWebhookFilter}(검증 측)와 서명 측이 동일한 서명 규칙을 공유하도록 서명 계산
 * 로직을 한 곳에 모은다. 양측이 같은 코드를 쓰므로 "서명 불일치 401" 회귀를 구조적으로 차단한다.
 *
 * <p>Phase 7-A2 기준 등록된 서명 필수 웹훅 경로는 없다(구 증강 콜백 제거). 다만 경로 판정 불가
 * 요청은 여전히 서명 요구 분기로 들어가므로(fail-closed) 검증 측에서 계속 사용된다.
 *
 * <h3>서명 규칙</h3>
 * <ul>
 *   <li>알고리즘: {@value #HMAC_ALGORITHM}</li>
 *   <li>서명 대상 메시지: {@code "{timestamp}.{body}"} (timestamp 는 epoch-ms 문자열, body 는 전송 본문 그대로)</li>
 *   <li>출력: 소문자 hex (HexFormat 기본)</li>
 * </ul>
 *
 * <p>보안: 시크릿은 인자로만 받고 로그/예외 메시지에 포함하지 않는다 (CWE-532 민감정보 노출 방지).
 */
public final class HmacSigner {

    /** HMAC 알고리즘 — HmacWebhookFilter 와 동일. */
    public static final String HMAC_ALGORITHM = "HmacSHA256";

    private HmacSigner() {
    }

    /**
     * HMAC-SHA256 을 계산하여 소문자 hex 로 반환한다.
     *
     * @param secret  공유 시크릿 (256bit 이상 권장)
     * @param message 서명 대상 메시지 (보통 {@code "{timestamp}.{body}"})
     * @return 소문자 hex 시그니처
     * @throws IllegalStateException HMAC 계산 실패 시 (JCE 미지원 등 — 정상 환경에서는 발생하지 않음)
     */
    public static String hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (java.security.GeneralSecurityException e) {
            // 시크릿 평문은 노출하지 않는다 — 메시지 길이만 단서로 남김.
            throw new IllegalStateException(
                    "HMAC 서명 계산 실패 (algorithm=" + HMAC_ALGORITHM + ")", e);
        }
    }
}
