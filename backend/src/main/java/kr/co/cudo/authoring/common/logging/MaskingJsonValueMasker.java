package kr.co.cudo.authoring.common.logging;

import com.fasterxml.jackson.core.JsonStreamContext;
import net.logstash.logback.mask.ValueMasker;

/**
 * Logstash JSON 인코더용 값 마스커.
 *
 * <p>마스킹 규칙은 {@link LogMaskingPatterns} 단일 원천에 위임한다 — {@link MaskingPatternLayout}(local)과
 * 동일 규칙이 적용되도록 보장하기 위함이다(A-ISSUE-62: 두 구현이 정규식 상수를 복제 보유해 드리프트).
 *
 * <p>dev/stg/prd profile 의 JSON 로그에서 {@code MaskingJsonGeneratorDecorator} 를 통해 <b>모든 문자열
 * 값</b>(message + stack_trace 포함)에 적용된다 — 과거에는 {@code <message>} provider 하위에만 걸려
 * 있어 예외 스택트레이스에 실린 자격증명·PII 가 무마스킹으로 나갔다(local 과 환경 간 비대칭).
 *
 * <p>OWASP A09:2025 (Security Logging Failures) / CWE-532 / CWE-359 — 민감 정보 로그 출력 차단.
 */
public class MaskingJsonValueMasker implements ValueMasker {

    @Override
    public Object mask(JsonStreamContext context, Object value) {
        if (!(value instanceof CharSequence cs)) {
            return value;
        }
        return maskString(cs.toString());
    }

    /** 패키지 내 가시성 — 단위 테스트용. */
    String maskString(String input) {
        return LogMaskingPatterns.mask(input);
    }
}
