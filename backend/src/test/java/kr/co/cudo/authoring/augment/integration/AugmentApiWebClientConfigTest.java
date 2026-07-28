package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.common.config.AugmentUrlPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 증강 WebClient 빈의 <b>실제 기동 배선</b> 검증 (DEV_FIX HIGH-1).
 *
 * <p>{@code AugmentUrlPolicyTest} 는 판정 로직을 직접 호출해 고정하지만, 그 판정이 빈 생성 경로에
 * 실제로 걸려 있는지는 증명하지 못한다(호출을 지워도 조용히 통과). 컨텍스트 refresh 로 고정한다.
 */
class AugmentApiWebClientConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AugmentApiWebClientConfig.class, AugmentUrlPolicy.class);

    @Test
    @DisplayName("prd_프로파일에서_증강_base_url_이_평문_HTTP_면_컨텍스트_기동이_실패한다")
    void prdPlaintextFailsContextStartup() {
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.base-url=http://genai.vendor.io:9400")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("HTTPS"));
    }

    @Test
    @DisplayName("prd_프로파일에서_증강_base_url_이_사설IP_면_컨텍스트_기동이_실패한다")
    void prdPrivateNetworkFailsContextStartup() {
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.base-url=https://10.0.0.5:9400")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("CWE-918"));
    }

    @Test
    @DisplayName("prd_프로파일에서_증강_완화플래그가_켜져있으면_컨텍스트_기동이_실패한다")
    void prdRelaxationFlagFailsContextStartup() {
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.allow-insecure-url=true",
                        "authoring.augment.external.base-url=https://genai.vendor.io")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("allow-insecure-url"));
    }

    @Test
    @DisplayName("base_url_미설정이면_기동이_실패한다_완화기본값_상주_금지")
    void missingBaseUrlFailsContextStartup() {
        runner.withPropertyValues("spring.profiles.active=prd")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("local_완화플래그에서는_목업_base_url_로_빈이_생성된다")
    void localMockUrlStartsUp() {
        runner.withPropertyValues(
                        "spring.profiles.active=local",
                        "authoring.augment.external.allow-insecure-url=true",
                        "authoring.augment.external.base-url=http://localhost:9400")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasBean("augmentApiWebClient"));
    }

    @Test
    @DisplayName("mode_noop_이면_증강_HTTP_WebClient_빈이_생성되지_않는다")
    void noopModeDoesNotCreateHttpClientBean() {
        // 위탁하지 않는 환경(prd 기본 형상)에서 base-url 설정을 요구하지 않아야 한다.
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.mode=noop")
                .run(ctx -> assertThat(ctx).hasNotFailed()
                        .doesNotHaveBean(WebClient.class));
    }

    @Test
    @DisplayName("증강_base_url_기본값에_localhost_가_없다")
    void defaultBaseUrlHasNoLocalhost() throws Exception {
        // 완화 기본값이 운영 형상(공통 application.yml)에 상주하면 환경변수 미설정 배포가 조용히 기동한다.
        String yml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("^\\s*base-url:\\s*\\$\\{AUGMENT_API_BASE_URL:([^}]*)}\\s*$",
                Pattern.MULTILINE).matcher(yml);

        assertThat(m.find()).as("AUGMENT_API_BASE_URL 기본값 선언을 찾지 못했습니다").isTrue();
        assertThat(m.group(1)).isEmpty();
        assertThat(yml).contains("allow-insecure-url: ${AUGMENT_ALLOW_INSECURE_URL:false}");
    }
}
