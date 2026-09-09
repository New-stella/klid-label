package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.config.AugmentUrlPolicy;
import kr.co.cudo.authoring.common.config.GenAiIntegrationWiringGuard;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
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

    /**
     * ⚠ <b>콜백 allowlist 기본값을 명시</b>한다 — 미지정이면 짝 맞춤 가드가 <b>모든</b> 위탁을 막아
     * 주소 축 단언이 통째로 무의미해진다(사유가 「짝 불일치」로 바뀐다). 짝 축은 아래 전용 시험이
     * <b>비운 값</b>으로 따로 고정한다.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AugmentApiWebClientConfig.class, AugmentUrlPolicy.class,
                    GenAiIntegrationWiringGuard.class, IntegrationEndpointResolver.class)
            .withPropertyValues("webhook.genai.allowed-ip-cidrs=0.0.0.0/0");

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

    /**
     * ★ 거부 축의 <b>두 벌</b>을 한 번에 고정한다 — ①그 상태로 기동한다 ②그 주소로 나가려 하면 실패한다.
     *
     * <p>구 기대값은 <b>"컨텍스트 기동 실패"</b> 하나였다(2026-09-03 폐기). 규칙은 그대로이고
     * <b>언제 막는지</b>만 옮겼으므로, 무르게 하지 않고 <b>단언을 옮긴다</b>.
     */
    private void assertBootsButNeverSends(String url, String expectedLabel) {
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.base-url=" + url)
                .run(ctx -> {
                    assertThat(ctx).as(url).hasNotFailed().hasBean("augmentApiWebClient");
                    WebClient client = (WebClient) ctx.getBean("augmentApiWebClient");
                    StepVerifier.create(client.post().uri("/api/genai/jobs")
                                    .retrieve().bodyToMono(String.class))
                            .expectErrorSatisfies(e -> assertThat(e)
                                    .isInstanceOf(NonRetryableExternalException.class)
                                    .hasMessageContaining("설정값이 유효하지 않아")
                                    .hasMessageContaining(expectedLabel))
                            .verify();
                });
    }

    @Test
    @DisplayName("★메타데이터_대역이어도_기동은_되고_위탁만_거부된다")
    void prdMetadataRangeBlocksTransportNotBoot() {
        // 대역 차단 폐지 이후에도 예약 대역(IMDS)은 계속 막힌다 — 다만 그 차단이 기동이 아니라
        //   전송에서 일어난다. 판정이 배선돼 있지 않으면 이 단언이 깨진다.
        assertBootsButNeverSends("http://169.254.169.254", "예약 대역");
    }

    @Test
    @DisplayName("★비허용_스킴도_기동을_막지_않고_전송을_막는다")
    void disallowedSchemeBlocksTransportNotBoot() {
        for (String url : new String[]{"file:///tmp/x", "ftp://vendor.io", "ws://vendor.io:9400"}) {
            assertBootsButNeverSends(url, "허용되지 않는 스킴");
        }
    }

    @Test
    @DisplayName("★호스트를_알_수_없는_주소도_기동을_막지_않고_전송을_막는다")
    void unparsableUrlBlocksTransportNotBoot() {
        assertBootsButNeverSends("not-a-url", "허용되지 않는 스킴");
        assertBootsButNeverSends("http://vendor io:9400", "주소 형식 오류");
    }

    @Test
    @DisplayName("★placeholder_주소도_기동을_막지_않고_전송을_막는다")
    void prdPlaceholderBlocksTransportNotBoot() {
        assertBootsButNeverSends("https://your-service.example.com", "예시·미설정 호스트");
    }

    @Test
    @DisplayName("★거부_사유에는_주소도_설정키도_실리지_않는다_CWE209")
    void rejectionMessageCarriesNoInput() {
        runner.withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.augment.external.base-url=https://your-service.example.com")
                .run(ctx -> {
                    WebClient client = (WebClient) ctx.getBean("augmentApiWebClient");
                    StepVerifier.create(client.post().uri("/api/genai/jobs")
                                    .retrieve().bodyToMono(String.class))
                            .expectErrorSatisfies(e -> assertThat(e.getMessage())
                                    .doesNotContain("your-service")
                                    .doesNotContain("authoring.augment.external.base-url"))
                            .verify();
                });
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

    // ── 짝 맞춤 축(위탁 ↔ 콜백 수신) — 주소 축과 다른 축이며 같은 날 함께 옮겨졌다 ────────────

    /**
     * 짝 축 전용 러너 — <b>기본 러너를 덮어쓰지 않고 새로 만든다</b>. 같은 키를 두 번 주고 나중 값이
     * 이긴다는 <b>주입 순서 가정</b>에 시험이 기대면, 그 가정이 깨지는 날 <b>조용히 반대 축을
     * 검증</b>하게 된다.
     *
     * @param allowlist 콜백 수신 대역. 빈 값/{@code none} 이 「허용 IP 없음」이다.
     */
    private ApplicationContextRunner pairingRunner(String allowlist) {
        return new ApplicationContextRunner()
                .withUserConfiguration(AugmentApiWebClientConfig.class, AugmentUrlPolicy.class,
                        GenAiIntegrationWiringGuard.class, IntegrationEndpointResolver.class)
                .withPropertyValues(
                        "spring.profiles.active=prd",
                        // 주소는 <해석이 필요 없는 루프백> — 짝 축만 남기려고 주소 축을 통과시킨다.
                        "authoring.augment.external.base-url=http://127.0.0.1:1",
                        "webhook.genai.allowed-ip-cidrs=" + allowlist);
    }

    @Test
    @DisplayName("★★주소가_정상이어도_콜백_allowlist_가_비면_기동은_되고_위탁만_거부된다")
    void unpairedCallbackIntakeBlocksCommissionNotBoot() {
        // 구 동작은 <기동 실패> 였다(2026-09-03 폐기) — 주소를 제대로 넣은 정상 배포가 다른 설정
        //   한 줄이 비었다는 이유로 뜨지 못했다. 규칙은 그대로이고 걸리는 자리만 옮겼다.
        //   ★ 짝 맞춤 필터를 떼면 이 단언이 깨진다(연결 시도 오류로 바뀐다) — 그때가 곧
        //     「되받지 못할 위탁이 실제로 나가는」 상태다.
        for (String allowlist : new String[]{"", "none", "NONE"}) {
            pairingRunner(allowlist).run(ctx -> {
                assertThat(ctx).as("allowlist=[%s]", allowlist)
                        .hasNotFailed().hasBean("augmentApiWebClient");
                WebClient client = (WebClient) ctx.getBean("augmentApiWebClient");
                StepVerifier.create(client.post().uri(HttpExternalAugmentClient.JOBS_PATH)
                                .retrieve().bodyToMono(String.class))
                        .expectErrorSatisfies(e -> assertThat(e)
                                .isInstanceOf(NonRetryableExternalException.class)
                                .hasMessageContaining("짝이 맞지 않아")
                                .hasMessageContaining(GenAiIntegrationWiringGuard.REJECTION_LABEL))
                        .verify();
            });
        }
    }

    @Test
    @DisplayName("★★짝이_안_맞아도_조회·취소는_막지_않는다 — 고착된 job 을 정리할 경로다")
    void unpairedCallbackIntakeStillAllowsQueryAndCancel() {
        // 조회·취소는 <새 수신구를 열지 않는다>. 함께 막으면 짝이 어긋난 배포에서 이미 걸린 위탁을
        //   회수·정리할 수단까지 잃는다. 여기서 나는 오류는 가드가 아니라 <실제 연결 실패> 여야 한다.
        pairingRunner("").run(ctx -> {
            WebClient client = (WebClient) ctx.getBean("augmentApiWebClient");
            StepVerifier.create(client.get().uri(HttpExternalAugmentClient.JOBS_PATH + "/j-1")
                            .retrieve().bodyToMono(String.class))
                    .expectErrorSatisfies(e -> assertThat(e)
                            .isNotInstanceOf(NonRetryableExternalException.class))
                    .verify();
            StepVerifier.create(client.post().uri(HttpExternalAugmentClient.JOBS_PATH + "/j-1/cancel")
                            .retrieve().bodyToMono(String.class))
                    .expectErrorSatisfies(e -> assertThat(e)
                            .isNotInstanceOf(NonRetryableExternalException.class))
                    .verify();
        });
    }

    @Test
    @DisplayName("★짝_거부_사유에는_대역도_주소도_설정키도_실리지_않는다_CWE209")
    void pairingRejectionMessageCarriesNoConfigValue() {
        pairingRunner("").run(ctx -> {
            WebClient client = (WebClient) ctx.getBean("augmentApiWebClient");
            StepVerifier.create(client.post().uri(HttpExternalAugmentClient.JOBS_PATH)
                            .retrieve().bodyToMono(String.class))
                    .expectErrorSatisfies(e -> assertThat(e.getMessage())
                            .doesNotContain("127.0.0.1")
                            .doesNotContain(GenAiIntegrationWiringGuard.KEY_ALLOWLIST)
                            .doesNotContain("authoring.augment.external.base-url"))
                    .verify();
        });
    }

    @Test
    @DisplayName("★짝이_맞으면_위탁이_그대로_나간다 — 가드가 정상 연동을 막지 않는다")
    void pairedCallbackIntakeLetsCommissionThrough() {
        pairingRunner("203.0.113.0/24").run(ctx -> {
            WebClient client = (WebClient) ctx.getBean("augmentApiWebClient");
            StepVerifier.create(client.post().uri(HttpExternalAugmentClient.JOBS_PATH)
                            .retrieve().bodyToMono(String.class))
                    .expectErrorSatisfies(e -> assertThat(e)
                            .isNotInstanceOf(NonRetryableExternalException.class))
                    .verify();
        });
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
