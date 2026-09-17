package kr.co.cudo.authoring.batch.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 비식별 제외 출처유형 설정 해석 — ADR-066 판정 규칙 고정.
 *
 * @design ADR-066
 */
class DeidentExclusionPolicyTest {

    @Test
    @DisplayName("기본값_GENERATED_는_생성형_영상만_제외한다")
    void 기본값은_생성형만_제외() {
        DeidentExclusionPolicy policy = new DeidentExclusionPolicy("GENERATED");

        assertThat(policy.isExcluded("GENERATED")).isTrue();
        assertThat(policy.isExcluded("ORIGINAL")).isFalse();
        assertThat(policy.isExcluded("RELAY")).isFalse();
        assertThat(policy.isExcluded("IMPORTED")).isFalse();
    }

    @Test
    @DisplayName("쉼표로_분리하고_앞뒤_공백을_떼며_빈_항목은_무시한다")
    void 쉼표_공백_빈항목() {
        DeidentExclusionPolicy policy = new DeidentExclusionPolicy(" GENERATED , ,AUGMENTED,, ");

        assertThat(policy.excludedSrcTypes()).containsExactly("GENERATED", "AUGMENTED");
        assertThat(policy.isExcluded("AUGMENTED")).isTrue();
    }

    @Test
    @DisplayName("설정이_비면_목록이_비어_어떤_영상도_제외하지_않는다_안전측")
    void 빈설정은_전부_위탁() {
        assertThat(new DeidentExclusionPolicy("").excludedSrcTypes()).isEmpty();
        assertThat(new DeidentExclusionPolicy("   ").isExcluded("GENERATED")).isFalse();
        assertThat(new DeidentExclusionPolicy(null).isExcluded("GENERATED")).isFalse();
    }

    @Test
    @DisplayName("대소문자를_바꾸지_않고_정확히_비교한다")
    void 대소문자_정확비교() {
        DeidentExclusionPolicy policy = new DeidentExclusionPolicy("GENERATED");

        assertThat(policy.isExcluded("generated")).isFalse();
        assertThat(new DeidentExclusionPolicy("generated").isExcluded("GENERATED")).isFalse();
    }

    @Test
    @DisplayName("출처유형이_null_이면_어떤_설정에서도_제외_대상이_아니다")
    void null_출처유형은_제외아님() {
        assertThat(new DeidentExclusionPolicy("GENERATED").isExcluded(null)).isFalse();
    }

    @Test
    @DisplayName("기동_시_적용_목록을_INFO_한_줄로_남긴다_빈_목록이면_대괄호만")
    void 기동_로그_한줄() {
        Logger logger = (Logger) LoggerFactory.getLogger(DeidentExclusionPolicy.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new DeidentExclusionPolicy("GENERATED").logApplied();
            new DeidentExclusionPolicy("").logApplied();

            assertThat(appender.list).hasSize(2);
            assertThat(appender.list).allMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO);
            assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo("[Deident] excluded srcTypes=[GENERATED]");
            assertThat(appender.list.get(1).getFormattedMessage()).isEqualTo("[Deident] excluded srcTypes=[]");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
