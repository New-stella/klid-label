package kr.co.cudo.authoring.common.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * local 프로파일 콘솔 출력용 마스킹 레이아웃.
 *
 * <p>{@code doLayout} 이 throwable 을 포함한 <b>전체 출력 문자열</b>을 대상으로 하므로 예외 메시지·
 * 스택트레이스에 실린 값도 함께 마스킹된다.
 *
 * <p>마스킹 규칙은 {@link LogMaskingPatterns} 단일 원천에 위임한다 — 과거 이 클래스와
 * {@link MaskingJsonValueMasker} 가 정규식 상수를 복제 보유해 규칙이 갈라졌다(A-ISSUE-62).
 */
public class MaskingPatternLayout extends PatternLayout {

    @Override
    public String doLayout(ILoggingEvent event) {
        String original = super.doLayout(event);
        return mask(original);
    }

    public String mask(String input) {
        return LogMaskingPatterns.mask(input);
    }
}
