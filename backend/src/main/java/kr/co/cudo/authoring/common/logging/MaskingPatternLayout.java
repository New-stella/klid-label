package kr.co.cudo.authoring.common.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

public class MaskingPatternLayout extends PatternLayout {

    private static final Pattern HEADER_COLON_PATTERN = Pattern.compile(
            "(?i)(authorization)\\s*:\\s*(?:Bearer\\s+)?\\S+"
    );
    private static final Pattern KV_PATTERN = Pattern.compile(
            "(?i)(password|token|secret|authorization)\\s*=\\s*\\S+"
    );

    @Override
    public String doLayout(ILoggingEvent event) {
        String original = super.doLayout(event);
        return mask(original);
    }

    public String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = HEADER_COLON_PATTERN.matcher(input).replaceAll("$1: ***");
        result = KV_PATTERN.matcher(result).replaceAll(matchResult -> matchResult.group(1) + "=***");
        return result;
    }
}
