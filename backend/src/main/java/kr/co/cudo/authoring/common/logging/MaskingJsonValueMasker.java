package kr.co.cudo.authoring.common.logging;

import com.fasterxml.jackson.core.JsonStreamContext;
import net.logstash.logback.mask.ValueMasker;

import java.util.regex.Pattern;

/**
 * Logstash JSON 인코더용 값 마스커.
 *
 * <p>{@link MaskingPatternLayout} 와 동일한 정규식으로 password/token/secret/Authorization 값을 마스킹한다.
 * <p>dev/stg/prd profile 의 JSON 로그(LoggingEventCompositeJsonEncoder) 에서 message 필드에 적용된다.
 *
 * <p>OWASP A09:2025 (Security Logging Failures) / CWE-532 / CWE-359 — 민감 정보 로그 출력 차단.
 */
public class MaskingJsonValueMasker implements ValueMasker {

    private static final Pattern HEADER_COLON_PATTERN = Pattern.compile(
            "(?i)(authorization)\\s*:\\s*(?:Bearer\\s+)?\\S+"
    );
    private static final Pattern KV_PATTERN = Pattern.compile(
            "(?i)(password|token|secret|authorization)\\s*=\\s*\\S+"
    );

    @Override
    public Object mask(JsonStreamContext context, Object value) {
        if (!(value instanceof CharSequence cs)) {
            return value;
        }
        return maskString(cs.toString());
    }

    /** 패키지 내 가시성 — 단위 테스트용. */
    String maskString(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = HEADER_COLON_PATTERN.matcher(input).replaceAll("$1: ***");
        result = KV_PATTERN.matcher(result).replaceAll(matchResult -> matchResult.group(1) + "=***");
        return result;
    }
}
