package kr.co.cudo.authoring.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.status.Status;
import net.logstash.logback.composite.JsonProviders;
import net.logstash.logback.composite.loggingevent.MessageJsonProvider;
import net.logstash.logback.composite.loggingevent.StackTraceJsonProvider;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;
import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-ISSUE-62 (HIGH, CWE-359/532) — dev/stg/prd JSON 로그의 <b>스택트레이스</b> 마스킹 회귀 가드.
 *
 * <p>기존 배선은 마스커를 {@code <message>} provider 하위에만 걸어, {@code <stackTrace/>} 로 나가는
 * 예외 메시지·스택에 실린 자격증명/PII 는 전혀 마스킹되지 않았다(local 의 {@code MaskingPatternLayout}
 * 은 throwable 포함 전체 문자열을 처리하므로 <b>환경 간 비대칭</b>). 마스커를 인코더 레벨
 * {@code MaskingJsonGeneratorDecorator} 로 올려 모든 문자열 값에 적용되게 했고, 본 테스트는
 * ① 실제 인코더 파이프라인 동작과 ② {@code logback-spring.xml} 배선을 함께 검증한다.
 */
class JsonLogMaskingTest {

    @Test
    @DisplayName("JSON_인코더_스택트레이스에_실린_토큰과_PII가_마스킹됨")
    void stackTraceValuesAreMasked() throws Exception {
        // given: 예외 메시지에 자격증명/PII 가 실린 로그 이벤트
        LoggerContext context = new LoggerContext();
        LoggingEventCompositeJsonEncoder encoder = new LoggingEventCompositeJsonEncoder();
        encoder.setContext(context);

        MaskingJsonGeneratorDecorator decorator = new MaskingJsonGeneratorDecorator();
        decorator.addValueMasker(new MaskingJsonValueMasker());
        decorator.start();
        encoder.setJsonGeneratorDecorator(decorator);

        JsonProviders<ch.qos.logback.classic.spi.ILoggingEvent> providers = new JsonProviders<>();
        providers.addProvider(new MessageJsonProvider());
        providers.addProvider(new StackTraceJsonProvider());
        encoder.setProviders(providers);
        encoder.start();

        RuntimeException cause = new IllegalStateException(
                "upstream rejected password=hunter2 phone 010-1234-5678 user@example.com");
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("kr.co.cudo.authoring.test");
        event.setLevel(Level.ERROR);
        event.setMessage("[Test] call failed token=abcdefgh");
        event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(cause));

        // when
        String json = new String(encoder.encode(event), StandardCharsets.UTF_8);
        encoder.stop();

        // then: message 와 stack_trace 양쪽에서 평문이 사라진다
        assertThat(json)
                .doesNotContain("hunter2")
                .doesNotContain("abcdefgh")
                .doesNotContain("010-1234-5678")
                .doesNotContain("user@example.com");
        assertThat(json).contains("stack_trace");
    }

    @Test
    @DisplayName("logback_설정의_devstgprd_블록에_인코더_레벨_마스킹_데코레이터가_배선됨")
    void logbackConfigWiresEncoderLevelMasker() throws Exception {
        // 배선이 끊기면(=provider 하위로 되돌아가면) stackTrace 마스킹이 조용히 사라지므로 설정을 고정한다.
        String xml = readLogbackConfig();

        assertThat(xml).contains("net.logstash.logback.mask.MaskingJsonGeneratorDecorator");
        assertThat(xml).contains("kr.co.cudo.authoring.common.logging.MaskingJsonValueMasker");
        assertThat(xml).contains("<stackTrace/>");
    }

    @Test
    @DisplayName("devstgprd_appender_선언이_실제로_Joran_파싱되고_마스커가_주입됨")
    void devProfileAppenderActuallyParses() throws Exception {
        // local 테스트만으로는 dev/stg/prd 블록의 XML↔API 불일치(잘못된 요소명/누락 setter)를 잡지 못한다.
        // 실제 선언을 Joran 으로 구성해 기동 시점 설정 오류를 미리 드러낸다.
        String inner = extractProfileBlock(readLogbackConfig(), "dev,stg,prd");
        String standalone = "<configuration>" + inner + "</configuration>";

        LoggerContext context = new LoggerContext();
        context.setName("dev-profile-parse-check");
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(new ByteArrayInputStream(standalone.getBytes(StandardCharsets.UTF_8)));

        // 설정 오류가 하나라도 있으면 dev/stg/prd 기동 시 로깅 배선이 조용히 무너진다.
        List<Status> errors = context.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getLevel() == Status.ERROR)
                .toList();
        assertThat(errors).as("logback dev/stg/prd 블록 설정 오류: %s", errors).isEmpty();

        // 그리고 실제로 마스킹 데코레이터가 인코더에 주입됐는지 확인한다.
        Appender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("JSON");
        assertThat(appender).isInstanceOf(OutputStreamAppender.class);
        Encoder<ch.qos.logback.classic.spi.ILoggingEvent> encoder =
                ((OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>) appender).getEncoder();
        assertThat(encoder).isInstanceOf(LoggingEventCompositeJsonEncoder.class);
        assertThat(((LoggingEventCompositeJsonEncoder) encoder).getJsonGeneratorDecorator())
                .isInstanceOf(MaskingJsonGeneratorDecorator.class);

        context.stop();
    }

    private static String readLogbackConfig() throws Exception {
        return Files.readString(Path.of("src/main/resources/logback-spring.xml"), StandardCharsets.UTF_8);
    }

    /** {@code <springProfile name="…">} 블록의 내용만 떼어낸다 (Joran 은 springProfile 을 모른다). */
    private static String extractProfileBlock(String xml, String profiles) {
        String open = "<springProfile name=\"" + profiles + "\">";
        int start = xml.indexOf(open);
        assertThat(start).as("springProfile name=\"%s\" 블록이 있어야 한다", profiles).isNotNegative();
        int end = xml.indexOf("</springProfile>", start);
        assertThat(end).isGreaterThan(start);
        return xml.substring(start + open.length(), end);
    }
}
