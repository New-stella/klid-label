package kr.co.cudo.authoring.common.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

import java.util.Iterator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-ISSUE-24 — local 프로파일 로그 마스킹이 <b>appender 출력 종단</b>까지 실제로 적용되는지 검증한다.
 *
 * <p>기존 {@code MaskingPatternLayoutTest} 는 {@code layout.mask(...)} 를 직접 호출해 로직만 확인했다.
 * 그래서 "로직은 통과하는데 실제 파이프라인에는 미연결(죽은 conversionRule + %msg 패턴)" 이라는 상태를
 * 전혀 잡아내지 못했다. 본 테스트는 실제 스프링 컨텍스트(=logback-spring.xml 적용) 위에서 로거를 호출하고
 * 콘솔 출력을 캡처해 검증하므로 배선이 끊기면 즉시 실패한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@ExtendWith(OutputCaptureExtension.class)
class LocalLogMaskingIT {

    private static final Logger log = LoggerFactory.getLogger(LocalLogMaskingIT.class);

    @Test
    @DisplayName("로그_출력에_토큰_패스워드가_마스킹되어_기록됨")
    void sensitiveValuesAreMaskedInConsoleOutput(CapturedOutput output) {
        // given: 코드에 평문 시크릿을 박지 않도록 런타임 생성값을 사용한다.
        String secretValue = "v-" + UUID.randomUUID();

        // when: 민감 키(password/token/Authorization)를 포함한 로그를 남긴다.
        log.info("[MaskingIT] login attempt password={} token={} ok", secretValue, secretValue);
        log.info("[MaskingIT] upstream call Authorization: Bearer {}", secretValue);

        // then: 콘솔(appender 종단)에 평문이 남지 않고 마스킹된 형태로 기록된다.
        assertThat(output.getAll()).doesNotContain(secretValue);
        assertThat(output.getAll()).contains("password=***");
        assertThat(output.getAll()).contains("token=***");
        assertThat(output.getAll()).contains("Authorization: ***");
    }

    @Test
    @DisplayName("로그_출력에_PII와_JSON형태_자격증명_bare_JWT가_마스킹되어_기록됨")
    void piiAndJsonCredentialsAreMaskedInConsoleOutput(CapturedOutput output) {
        // A-ISSUE-62 — key=value / Authorization 두 형태만 커버하던 규칙의 사각지대.
        // given/when: 이슈에서 평문 누출이 실측된 형태들을 그대로 출력한다.
        log.info("[MaskingIT] body={}", "{\"password\":\"hunter2-plain\"}");
        log.info("[MaskingIT] phone={} email={}", "010-1234-5678", "leaked@example.com");
        log.info("[MaskingIT] ssn={}", "900101-1234567");
        log.info("[MaskingIT] header={}", "X-Access-Token: TOKENVAL-plain");
        log.info("[MaskingIT] jwt={}", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.SIGPART-plain");

        // then: 어떤 형태로도 평문이 콘솔(appender 종단)에 남지 않는다.
        String all = output.getAll();
        assertThat(all).doesNotContain("hunter2-plain");
        assertThat(all).doesNotContain("010-1234-5678");
        assertThat(all).doesNotContain("leaked@example.com");
        assertThat(all).doesNotContain("900101-1234567");
        assertThat(all).doesNotContain("TOKENVAL-plain");
        assertThat(all).doesNotContain("SIGPART-plain");
    }

    @Test
    @DisplayName("local_프로파일_스프링_컨텍스트_로드_성공_및_마스킹_레이아웃_배선")
    void localAppenderIsWiredWithMaskingLayout() {
        // 컨텍스트 로드 자체가 logback 설정 로드 성공의 안전망(설정 오류 시 기동 실패).
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        boolean maskingWired = false;
        for (Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it = root.iteratorForAppenders();
             it.hasNext(); ) {
            Appender<ch.qos.logback.classic.spi.ILoggingEvent> appender = it.next();
            if (appender instanceof OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent> osa) {
                Encoder<ch.qos.logback.classic.spi.ILoggingEvent> encoder = osa.getEncoder();
                if (encoder instanceof LayoutWrappingEncoder<ch.qos.logback.classic.spi.ILoggingEvent> lwe
                        && lwe.getLayout() instanceof MaskingPatternLayout) {
                    maskingWired = true;
                }
            }
        }
        assertThat(maskingWired)
                .as("local 프로파일 CONSOLE appender 가 MaskingPatternLayout 을 통해 출력해야 한다")
                .isTrue();
    }
}
