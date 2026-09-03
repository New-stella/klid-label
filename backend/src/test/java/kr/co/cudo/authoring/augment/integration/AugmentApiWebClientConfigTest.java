package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.config.AugmentUrlPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

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
    @DisplayName("★prd_프로파일에서도_평문_http_사설대역_base_url_로_빈이_생성된다 — 이 축이 깨지면 운영 기동이 막힌다")
    void prdPlaintextPrivateStartsUp() {
        // 구 기대값은 "평문/사설이면 기동 실패" 였다. HTTPS 강제·사설망 차단은 폐기됐고(2026-09-01),
        //   운영에서 AUGMENT_EXTERNAL_MODE=http 로 위탁을 켜는 순간 이 축이 그대로 드러난다.
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.base-url=http://10.0.0.5:9400")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasBean("augmentApiWebClient"));
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.base-url=http://genai.vendor.io:9400")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasBean("augmentApiWebClient"));
    }

    @Test
    @DisplayName("prd_프로파일에서_증강_base_url_이_메타데이터_대역이면_컨텍스트_기동이_실패한다")
    void prdMetadataRangeFailsContextStartup() {
        // 대역 차단 폐지 이후에도 예약 대역(IMDS)은 계속 막힌다 — 판정이 빈 생성 경로에 실제로
        //   걸려 있는지를 여기서 고정한다(호출을 지우면 이 시험이 죽는다).
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.base-url=http://169.254.169.254")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("CWE-918"));
    }

    @Test
    @DisplayName("★비허용_스킴은_여전히_기동을_막는다 — 빈값만 예외지 잘못된 주소는 아니다")
    void disallowedSchemeStillFailsContextStartup() {
        for (String url : new String[]{"file:///tmp/x", "ftp://vendor.io", "ws://vendor.io:9400"}) {
            runner.withPropertyValues(
                            "spring.profiles.active=prd",
                            "authoring.augment.external.base-url=" + url)
                    .run(ctx -> assertThat(ctx).hasFailed());
        }
    }

    @Test
    @DisplayName("★호스트를_알_수_없는_주소도_기동을_막는다")
    void unparsableUrlStillFailsContextStartup() {
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.base-url=not-a-url")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("prd_프로파일에서_증강_base_url_이_placeholder_면_컨텍스트_기동이_실패한다")
    void prdPlaceholderFailsContextStartup() {
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.base-url=https://your-service.example.com")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().hasStackTraceContaining("placeholder"));
    }

    @Test
    @DisplayName("★base_url_미설정이어도_기동한다 — 빈 주소는 「위험함」이 아니라 「아직 안 정해짐」이다")
    void missingBaseUrlStillBoots() {
        // 구 기대값은 "미설정이면 기동 실패" 였다(2026-09-03 폐기). 그 fail-closed 가 잘못된 자리에
        //   걸려 있어서, 벤더 주소가 아직 없다는 이유만으로 dev/stg/prd 가 전부 미연동 모드로
        //   도망갔고 증강 위탁이 한 번도 나간 적이 없는 상태가 굳었다.
        //   ★ 이 단언을 뒤집으면(=urlPolicy.validate 를 무조건 호출하도록 되돌리면) 빨개진다.
        runner.withPropertyValues("spring.profiles.active=prd")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasBean("augmentApiWebClient"));
        runner.withPropertyValues("spring.profiles.active=prd",
                        "authoring.augment.external.base-url=")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasBean("augmentApiWebClient"));
        runner.withPropertyValues("spring.profiles.active=prd",
                        "authoring.augment.external.base-url=   ")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasBean("augmentApiWebClient"));
    }

    @Test
    @DisplayName("★base_url_미설정이면_전송_자체가_막힌다 — loopback:80 으로 새지 않는다")
    void missingBaseUrlBlocksTheRequest() {
        // 빈 base-url 은 상대 URI 가 되어 <보내지지 않는 것이 아니라> loopback:80 으로 나간다.
        //   위탁 바디에는 비식별 프레임 절대경로가 실리므로 전송 자체를 막아야 한다.
        //   ★ 전송 가드 필터를 떼면 이 단언이 깨진다(연결 시도 오류로 바뀐다).
        runner.withPropertyValues("spring.profiles.active=prd").run(ctx -> {
            WebClient client = (WebClient) ctx.getBean("augmentApiWebClient");
            StepVerifier.create(client.post().uri("/api/genai/jobs").retrieve().bodyToMono(String.class))
                    .expectErrorSatisfies(e -> assertThat(e)
                            .isInstanceOf(NonRetryableExternalException.class)
                            .hasMessageContaining("연동 주소가 설정되지 않아"))
                    .verify();
        });
    }

    @Test
    @DisplayName("base_url_이_주입되면_그_주소로_요청이_조립된다 — 가드가 정상 위탁을 막지 않는다")
    void injectedBaseUrlPassesTheGuard() {
        // 가드가 host 유무만 보는지 확인한다 — 주소가 있으면 통과하고 실제 연결 단계로 넘어간다
        //   (수신자가 없으므로 연결 실패로 끝나지만, 그것은 <가드에 막힌 것이 아니다>).
        runner.withPropertyValues("spring.profiles.active=prd",
                "authoring.augment.external.base-url=http://127.0.0.1:1").run(ctx -> {
            WebClient client = (WebClient) ctx.getBean("augmentApiWebClient");
            StepVerifier.create(client.post().uri("/api/genai/jobs").retrieve().bodyToMono(String.class))
                    .expectErrorSatisfies(e -> assertThat(e)
                            .isNotInstanceOf(NonRetryableExternalException.class))
                    .verify();
        });
    }

    @Test
    @DisplayName("local_에서_목업_base_url_로_빈이_생성된다")
    void localMockUrlStartsUp() {
        runner.withPropertyValues(
                        "spring.profiles.active=local",
                        "authoring.augment.external.base-url=http://localhost:9400")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasBean("augmentApiWebClient"));
    }

    @Test
    @DisplayName("★폐기된_미연동_모드_토글은_이제_빈_생성에_영향을_주지_않는다")
    void retiredModeToggleNoLongerGatesTheBean() {
        // 구 동작은 mode=noop 이면 이 설정 자체가 뜨지 않았다. 그 축이 폐기됐으므로 어떤 값을 넣어도
        //   빈은 만들어진다 — 「나갈지 말지」를 환경설정으로 고르지 않는다(2026-09-03 확정).
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.mode=noop",
                        "authoring.augment.external.base-url=http://genai.vendor.io:9400")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasBean("augmentApiWebClient"));
    }

    @Test
    @DisplayName("증강_base_url_기본값에_localhost_가_없고_구_완화플래그_키도_남아있지_않다")
    void defaultBaseUrlHasNoLocalhost() throws Exception {
        // 목업 기본값이 운영 형상(공통 application.yml)에 상주하면 환경변수 미설정 배포가 조용히 기동한다.
        String yml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("^\\s*base-url:\\s*\\$\\{AUGMENT_API_BASE_URL:([^}]*)}\\s*$",
                Pattern.MULTILINE).matcher(yml);

        assertThat(m.find()).as("AUGMENT_API_BASE_URL 기본값 선언을 찾지 못했습니다").isTrue();
        assertThat(m.group(1)).isEmpty();
        // 구 완화 플래그는 켜든 끄든 결과가 같은 <죽은 설정>이 되어 제거했다. 되살아나면 "이걸 끄면
        //   엄격해진다"는 잘못된 기대가 다시 생기므로 설정 축에서 부재를 고정한다.
        //   ⚠ 문자열 포함이 아니라 <바인딩되는 키>가 없음을 본다 — 제거 사실을 적어 둔 주석은 남아 있고,
        //     그 주석까지 금지하면 "왜 없는지"를 적을 수 없다.
        assertThat(Pattern.compile("^\\s*allow-insecure-url\\s*:", Pattern.MULTILINE).matcher(yml).find())
                .as("구 완화 플래그 키가 되살아났습니다 (allow-insecure-url)").isFalse();
    }
}
