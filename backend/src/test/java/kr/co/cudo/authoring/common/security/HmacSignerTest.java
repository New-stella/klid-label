package kr.co.cudo.authoring.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HmacSigner 공용 유틸 — 콜백 충실 플로우 Phase 2 추출.
 *
 * <p>HmacWebhookFilter(검증 측)와 동일한 서명 규칙을 보장하기 위한 회귀 테스트.
 */
class HmacSignerTest {

    private static final String SECRET = ("phase2-test-dummy-" + "y".repeat(20));

    @Test
    @DisplayName("HmacSigner_hex가_HmacSHA256_소문자hex_규칙을_따른다")
    void producesLowercaseHmacSha256Hex() throws Exception {
        String message = "1700000000000.{\"k\":1}";

        String actual = HmacSigner.hex(SECRET, message);

        String expected = referenceHmacHex(SECRET, message);
        assertThat(actual).isEqualTo(expected);
        assertThat(actual).matches("[0-9a-f]{64}");
    }

    private static String referenceHmacHex(String secret, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(raw);
    }
}
