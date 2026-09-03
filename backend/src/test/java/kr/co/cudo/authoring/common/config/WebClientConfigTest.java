package kr.co.cudo.authoring.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WebClientConfig 의 VLM URL 검증(SSRF / HTTPS / placeholder fail-closed) 단위 테스트.
 *
 * <p>대상: {@code WebClientConfig#vlmWebClient}
 *  - baseUrl 이 주입돼 있을 때 검증 적용 (정책 판정은 {@link VlmUrlPolicy} 단일 원천)
 *  - baseUrl 이 비어 있을 때 검증 생략 (미연동 환경 기동 보장 — ADR-049)
 *
 * <p>구 기준(설정 토글 {@code enabled} 의 참/거짓)은 그 토글이 폐지되면서 사라졌다. 판정 축이
 * <b>토글 → 주소 주입 여부</b>로 옮겨간 것이며, 아래 기대값들은 그 축으로 다시 쓰였다.
 * 미연동 기동 보장 자체의 전용 가드는 {@link VlmBlankUrlBootTest} 다.
 *
 * <p>프로파일별 완화/엄격 분리 자체의 검증은 {@link VlmUrlPolicyTest} 가 담당한다. 여기서는
 * <b>빈 생성 경로가 그 정책을 실제로 경유하는지</b>를 고정한다.
 */
class WebClientConfigTest {

    private final WebClientConfig cfg = new WebClientConfig();

    /**
     * 검증 정책 — <b>프로파일로 갈리지 않는다</b>. 구 도우미 두 개(엄격/완화)는 그 갈림이 폐기되면서
     * 하나로 합쳐졌다(2026-08-10 확정 정합). 두 이름을 남겨 두면 "지금도 두 정책이 있다"로 읽힌다.
     */
    private static VlmUrlPolicy policy() {
        return new VlmUrlPolicy();
    }

    /**
     * ★ 뒤집힌 단언이다 — 구 기대값은 "설정 토글이 꺼져 있으면 어떤 URL 이든 통과" 였다.
     *
     * <p>그 토글은 폐지됐고(ADR-049), 검증을 생략하는 조건은 이제 <b>주소가 비어 있는 것</b> 하나다.
     * 따라서 {@code http://localhost:9400} 같은 <b>채워진</b> 주소는 더 이상 면제 대상이 아니다 —
     * 아래 {@code localhostRejected} 가 그 주소를 거부 대상으로 고정한다.
     */
    @Test
    @DisplayName("WebClientConfig_vlm_url_이_비어있으면_검증_생략_미연동_기동_보장")
    void blankUrlSkipsValidation() {
        // given / when / then — 주소 미주입이면 어떤 정책에서도 빈 생성 성공(기동 차단 금지)
        assertThat(cfg.vlmWebClient("", "", policy(), null)).isNotNull();
        assertThat(cfg.vlmWebClient("  ", "", policy(), null)).isNotNull();
    }

    /**
     * ★ <b>뒤집힌 단언</b> — 구 기대값은 "평문 http 면 빈 생성 실패(HTTPS 강제)" 였다.
     * HTTPS 강제·사설망 차단은 2026-08-10 확정으로 폐기됐고, 실 연동은 평문 http 다.
     */
    @Test
    @DisplayName("WebClientConfig_vlm_url_평문http_사설IP_여도_빈이_생성된다 — 확정 정책(2026-08-10)")
    void plaintextAndPrivateAddressesAccepted() {
        assertThat(cfg.vlmWebClient("http://vlm.vendor.io", "", policy(), null)).isNotNull();
        assertThat(cfg.vlmWebClient("http://localhost:9400", "", policy(), null)).isNotNull();
        assertThat(cfg.vlmWebClient("http://10.0.0.5:9400", "", policy(), null)).isNotNull();
        assertThat(cfg.vlmWebClient("http://192.168.1.10:9400", "", policy(), null)).isNotNull();
        assertThat(cfg.vlmWebClient("http://172.16.0.1:9400", "", policy(), null)).isNotNull();
        assertThat(cfg.vlmWebClient("http://127.0.0.1:9400", "", policy(), null)).isNotNull();
    }

    /**
     * ★ 축이 옮겨졌다 — 구 기대값은 "빈 생성 실패" 였다(2026-09-03 폐기).
     * 같은 규칙으로 <b>전송</b>을 막는다. 무르게 한 것이 아니라 자리를 옮긴 것이다.
     */
    private void assertBootsButNeverSends(String url, String expectedLabel) {
        WebClient client = cfg.vlmWebClient(url, "", policy(), null);
        assertThat(client).as("연동 주소가 어떤 상태여도 기동은 막히지 않는다 — " + url).isNotNull();
        assertThatThrownBy(() -> client.post().uri("/v1/videovlm-klid/describe").bodyValue("{}")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)))
                .as("연결 거부(ConnectException)가 나면 이미 전송을 시도했다는 뜻이다 — " + url)
                .isInstanceOf(kr.co.cudo.authoring.common.client.NonRetryableExternalException.class)
                .hasMessageContaining(expectedLabel);
    }

    @Test
    @DisplayName("★WebClientConfig_vlm_url_비허용_스킴_메타데이터대역은_기동을_막지_않고_전송을_막는다")
    void nonHttpSchemeAndMetadataRangeStillRejected() {
        assertBootsButNeverSends("ftp://vlm.vendor.io", "허용되지 않는 스킴");
        assertBootsButNeverSends("file:///etc/passwd", "허용되지 않는 스킴");
        assertBootsButNeverSends("http://169.254.169.254", "예약 대역");
        assertBootsButNeverSends("http://vlm vendor:9400", "주소 형식 오류");
    }

    /**
     * ★ 빈 문자열 케이스가 <b>여기서 빠졌다</b> — 폐기가 아니라 {@link #blankUrlSkipsValidation} 으로
     * <b>기대값이 뒤집혀 이동</b>했다. 빈 값은 이제 "설정 안 함(미연동)" 이지 placeholder 가 아니다.
     * 여기 남은 것은 <b>사람이 예시 값을 그대로 배포한</b> 형태(fail-closed 대상)뿐이다.
     */
    @Test
    @DisplayName("★WebClientConfig_vlm_url_placeholder_는_기동을_막지_않고_전송을_막는다_fail_closed")
    void placeholderRejectedWhenEnabled() {
        assertBootsButNeverSends("https://example.com", "예시·미설정 호스트");
        assertBootsButNeverSends("https://your-vlm-service", "예시·미설정 호스트");
    }

    @Test
    @DisplayName("★거부_사유에는_주소도_설정키도_실리지_않는다_CWE209")
    void rejectionMessageCarriesNoInput() {
        WebClient client = cfg.vlmWebClient("https://your-vlm-service", "", policy(), null);
        assertThatThrownBy(() -> client.post().uri("/v1/videovlm-klid/describe")
                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)))
                .hasMessageNotContaining("your-vlm-service")
                .hasMessageNotContaining("vlm.client.url");
    }

    @Test
    @DisplayName("WebClientConfig_vlm_url_정상_HTTPS_공인_IP_시_빈_생성_성공")
    void validHttpsPublicIpAccepted() {
        // 공인 IP(8.8.8.8 — Google DNS) 직접 사용해 DNS 의존성 없이 검증 성공 케이스만 확인.
        assertThat(cfg.vlmWebClient("https://8.8.8.8/", "", policy(), null)).isNotNull();
    }

    @Test
    @DisplayName("WebClientConfig_vlm_목업_평문_HTTP_주소로_빈_생성_성공")
    void mockPlaintextUrlAccepted() {
        assertThat(cfg.vlmWebClient("http://klid-mock-server:9400", "", policy(), null)).isNotNull();
        assertThat(cfg.vlmWebClient("http://127.0.0.1:9400", "", policy(), null)).isNotNull();
    }

    @Test
    @DisplayName("WebClientConfig_vlm_평문구간에_토큰이_설정되면_경고하고_토큰값은_출력하지_않는다")
    void cleartextTokenLogsWarning() {
        // given: 평문 구간에는 TLS 가 없어 Bearer 토큰이 그대로 흐른다(CWE-319).
        //   경고 판정은 공용 원천(ExternalUrlPolicy)이 소유하므로 로거도 그 클래스다.
        Logger logger = (Logger) LoggerFactory.getLogger(ExternalUrlPolicy.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // when
            assertThat(cfg.vlmWebClient("http://klid-mock-server:9400", "secret-token-value",
                    policy(), null)).isNotNull();

            // then: 경고가 남되 토큰 값은 절대 출력되지 않는다(길이만 — CWE-532)
            List<String> warns = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warns).anyMatch(m -> m.contains("평문") && m.contains("토큰"));
            assertThat(warns).anyMatch(m -> m.contains("tokenLength=18"));
            assertThat(warns).noneMatch(m -> m.contains("secret-token-value"));

            // and: HTTPS 구간에서는 경고하지 않는다
            appender.list.clear();
            assertThat(cfg.vlmWebClient("https://8.8.8.8/", "secret-token-value", policy(), null))
                    .isNotNull();
            assertThat(appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("tokenLength")))
                    .isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 설정 override 를 돌려주는 리졸버 — 실제 조회 경로({@code SystemConfigService} → 리졸버) 그대로다.
     * 판정을 통과시키기 위한 스텁이 아니라 "설정에 값이 들어 있는 상태"의 재현이다.
     */
    private static IntegrationEndpointResolver resolverReturning(String overrideUrl) {
        SystemConfigService configService = mock(SystemConfigService.class);
        when(configService.findString(anyString())).thenReturn(java.util.Optional.ofNullable(overrideUrl));
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(configService);
        return new IntegrationEndpointResolver(provider);
    }

    /**
     * ★ <b>확정된 동작을 고정하는 회귀 가드 — 결함이 아니다. 되돌리지 말 것</b> (2026-08-10 판단).
     *
     * <h3>무엇이 확정됐나</h3>
     * <p>{@link VlmUrlPolicy} 의 검증은 {@code vlmWebClient} 의 <b>{@code @Value} 배포 기본값에만</b>
     * 걸린다. 운영 화면에서 넣는 <b>설정 override 는 그 검증을 거치지 않는다</b> — override 에 남는
     * 검증은 {@code IntegrationEndpointUrlValidator} 의 <b>스킴(http/https) + 형식</b>뿐이다.
     *
     * <p>⚠ <b>두 검증의 차이가 좁아졌다(2026-09-01)</b> — 기동 시점 정책도 이제 스킴·형식만 보므로
     * (HTTPS 강제·대역 차단은 폐기), 남은 차이는 <b>placeholder 호스트</b>와 <b>예약 대역</b> 거부뿐이다.
     * 그래서 아래 대조군이 "평문 http 를 막는다" 에서 <b>"placeholder 를 막는다"</b> 로 바뀌었다 —
     * 폐기된 조항으로 대조군을 세우면 그 시험이 폐기 정책을 되살리는 압력이 된다.
     *
     * <h3>왜 그것이 의도인가</h3>
     * <p>온프렘 내부망 배포이고 연동 4종(비식별·AI 추론·시계열 분석·관제 통지)이 <b>내부 서버일 가능성이
     * 높아</b>, override 에 HTTPS·대역 제한을 강제하면 <b>정당한 대상을 막는다</b>. 같은 이유로 대역 차단이
     * 이미 폐지됐다({@code IntegrationEndpointUrlValidator} 참조).
     *
     * <h3>왜 문서가 아니라 테스트로 고정하나</h3>
     * <p>문서에만 있으면 다음 사람이 <b>기동 시점 정책을 불변식으로 읽는다</b> — "선언은 막는다는데 실제로는
     * 안 막는" 형태가 이 저장소의 반복 결함이다. 여기서는 반대로 <b>안 막는다는 사실 자체</b>를 실소켓으로
     * 고정한다. 반대로 override 에 정책을 걸기로 <b>결정을 뒤집는다면</b> 이 테스트가 실패하므로, 그때는
     * 지우는 게 아니라 <b>기대값을 뒤집고 사유를 남긴다</b>.
     *
     * <p><b>mutation 확인</b>: {@code WebClientConfig#vlmWebClient} 에서 필터 배선
     * ({@code .filter(IntegrationEndpointExchangeFilter.of(...))})을 지우면 요청이 override 주소로 나가지
     * 않아 이 테스트가 실패한다.
     *
     * <p><b>판정 방식</b>: 소켓으로는 "override 주소가 실제로 요청을 받았다"(= 재작성 필터 배선)를 보고,
     * 정책 우회 자체는 <b>정책이 거부하는 주소(placeholder)를 override 로 넣어도 빈이 만들어진다</b>로
     * 본다. placeholder 호스트는 해석되지 않아 소켓으로 관측할 수 없으므로 두 축을 나눈다.
     */
    @Test
    @DisplayName("★설정_override_는_VlmUrlPolicy_검증을_거치지_않는다 — 정책이 거부하는 주소도 그대로 나간다(의도된 동작)")
    void configOverrideBypassesVlmUrlPolicy() throws Exception {
        MockWebServer overrideTarget = new MockWebServer();
        overrideTarget.start();
        try {
            // given — 배포 기본값은 실제로는 아무도 응답하지 않는 주소(203.0.113.0/24 = TEST-NET-3).
            //   여기로 나가면 안 된다는 뜻이다.
            VlmUrlPolicy policy = policy();
            String bootDefault = "https://203.0.113.10:9443";
            String override = overrideTarget.url("/").toString();   // 평문 http + loopback

            // and — 대조군: 기동 시점 정책이 <거부>하는 주소를 override 에 넣어도 빈이 만들어진다.
            //   (평문 http·사설 대역은 이제 정책도 통과시키므로 대조군이 될 수 없다 — placeholder 를 쓴다.)
            String rejectedByBootPolicy = "http://your-vlm-service:9400";
            assertThatThrownBy(() -> policy.validate(rejectedByBootPolicy))
                    .as("기동 시점 정책은 placeholder 호스트를 막는다 — override 가 그 정책 밖임을 보이는 대조군")
                    .isInstanceOf(IllegalStateException.class);
            assertThat(cfg.vlmWebClient(bootDefault, "", policy, resolverReturning(rejectedByBootPolicy)))
                    .as("정책이 거부하는 주소를 override 로 넣어도 빈 생성은 막히지 않는다(의도된 동작)")
                    .isNotNull();

            WebClient client = cfg.vlmWebClient(bootDefault, "", policy, resolverReturning(override));
            overrideTarget.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

            // when — 빈은 그대로 두고 호출한다.
            try {
                client.post().uri("/v1/videovlm-klid/describe").retrieve().bodyToMono(String.class)
                        .block(Duration.ofSeconds(5));
            } catch (RuntimeException ignored) {
                // 전송 여부는 아래 소켓 관측으로만 판정한다 — 응답 처리 실패로 판정이 흐려지지 않게 한다.
            }

            // then — 정책이 거부하는 주소가 실제로 요청을 받았다. 막히지 않는 것이 확정된 동작이다.
            RecordedRequest received = overrideTarget.takeRequest(5, TimeUnit.SECONDS);
            assertThat(received)
                    .as("override 주소가 요청을 받아야 한다 — 필터 배선이 빠지면 여기서 실패한다")
                    .isNotNull();
            assertThat(received.getPath()).isEqualTo("/v1/videovlm-klid/describe");
        } finally {
            overrideTarget.shutdown();
        }
    }
}
