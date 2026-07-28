package kr.co.cudo.authoring.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Configuration
public class WebClientConfig {

    /** ai-server는 이미지 base64가 포함된 요청을 처리하므로 32MB 버퍼 적용. */
    private static final int AI_SERVER_BUFFER_SIZE = 32 * 1024 * 1024;

    /**
     * 명백한 placeholder/예제 호스트 — enabled=true 일 때 차단.
     * URL 이 코드에 하드코딩되어 운영에 잘못 배포되는 사고를 막기 위한 fail-closed 가드.
     */
    private static final Set<String> PLACEHOLDER_HOST_FRAGMENTS = Set.of(
            "example.com", "example.org", "example.net",
            "your-vlm-service", "your-service", "todo", "placeholder",
            "changeme", "change-me"
    );

    /** 배포(운영급) 프로파일 — 하나라도 활성이면 local/dev 완화를 적용하지 않는다. */
    private static final String[] DEPLOYED_PROFILES = {"stg", "prd"};

    /** 배포 환경 표식({@code ENV}) — {@code DevProfileGuard.DEPLOYED_ENVS} 와 동일 기준. */
    private static final Set<String> DEPLOYED_ENV_MARKERS = Set.of("stg", "prd");

    private static ExchangeStrategies largeBufferStrategies() {
        return ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(AI_SERVER_BUFFER_SIZE))
                .build();
    }

    @Bean(name = "deidentifyWebClient")
    public WebClient deidentifyWebClient(
            @Value("${authoring.integration.deidentify.base-url}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean(name = "aiServerWebClient")
    public WebClient aiServerWebClient(
            @Value("${authoring.integration.ai-server.base-url}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .exchangeStrategies(largeBufferStrategies())
                .build();
    }

    /**
     * 외부 VLM 시계열 분석 위탁 클라이언트용 WebClient — Phase 1 신설.
     *
     * <p>DEV_FIX-1 보강: enabled=true 일 때 baseUrl 검증 적용.
     *  - HTTPS 스키마만 허용 (CWE-319 Cleartext Transmission)
     *  - private/loopback/link-local IP 차단 (CWE-918 SSRF)
     *  - 명백한 placeholder 호스트 차단 (운영 사고 방지 fail-closed)
     *  - 위반 시 IllegalStateException → 빈 생성 실패 → 애플리케이션 기동 차단
     *
     * <p>enabled=false (기본값) 면 검증 생략.
     *
     * <p><b>local/dev 완화</b>: 이 두 프로파일의 VLM 위탁 대상은 목업 벤더 서버(mock-server)다 —
     * TLS 미지원 평문 http 이고 호스트도 컨테이너 내부 이름({@code klid-mock-server})이라, 운영용
     * 강제를 그대로 적용하면 <b>빈 생성 실패로 애플리케이션이 기동조차 못 한다</b>(2026-07-25 로컬
     * 배선 시도 시 실측·원복). 따라서 local/dev 에 한해 평문 http 와 내부 호스트를 허용하고
     * 기동 시 WARN 을 남긴다. 설정 누락(빈 값)·placeholder 호스트 차단은 완화 대상이 아니다.
     * stg/prd 및 <b>프로파일 미지정</b>은 기존 강제를 유지한다(기본이 강제 = fail-closed).
     * 배포 판정은 stg/prd 프로파일 또는 {@code ENV=stg|prd} 표식 둘 다를 보며, dev/local 이
     * 함께 활성이어도 <b>배포 쪽이 이긴다</b>({@code DevProfileGuard.DEPLOYED_ENVS} 선례).
     */
    @Bean(name = "vlmWebClient")
    public WebClient vlmWebClient(
            @Value("${vlm.client.url:http://localhost:9400}") String baseUrl,
            @Value("${vlm.client.token:}") String token,
            @Value("${vlm.client.enabled:false}") boolean enabled,
            Environment environment) {
        if (enabled) {
            validateExternalUrl(baseUrl, allowsInternalEndpoint(environment));
        }
        WebClient.Builder b = WebClient.builder().baseUrl(baseUrl);
        if (token != null && !token.isBlank()) {
            warnIfTokenOnCleartext(baseUrl, token);
            b.defaultHeader("Authorization", "Bearer " + token);
        }
        return b.build();
    }

    /**
     * Phase 2 — 관제서버 outbound 통지 클라이언트용 WebClient.
     *
     * <p>CWE-918 SSRF: base-url 은 application.yml 설정값만 사용. 사용자 입력 X.
     */
    @Bean(name = "controlNotifyWebClient")
    public WebClient controlNotifyWebClient(
            @Value("${authoring.control-notify.url:http://localhost:8090}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 콜백 충실 플로우 Phase 2 — dev 콜백 시뮬레이터용 WebClient.
     *
     * <p>외부 0(자족) 로컬에서 {@code DevAugmentCallbackSimulator} 가 자기 자신(저작도구)의 콜백
     * 엔드포인트로 HMAC 서명 결과를 POST 한다. base-url 은 서버 설정값만 사용(사용자 입력 X, CWE-918).
     *
     * <p>응답 지연이 @Async 스레드를 점유하지 않도록 10s response timeout 을 적용한다.
     * 추가로 connect timeout(5s)을 두어, 로컬 콜백 서버 미기동 시 TCP 연결 단계에서 무한 대기로
     * batchAsyncExecutor 풀이 점유되는 것을 방지한다(reactor-netty CONNECT_TIMEOUT_MILLIS).
     */
    @Bean(name = "augmentCallbackWebClient")
    public WebClient augmentCallbackWebClient(
            @Value(WebhookCallbackDefaults.VALUE_EXPRESSION) String baseUrl) {
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(java.time.Duration.ofSeconds(10));
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * 목 서버(평문 http · 내부 호스트) 위탁을 허용해도 되는 환경인지 판정한다.
     *
     * <p>판정식은 <b>"local/dev 가 활성이고 <u>동시에</u> stg/prd 가 활성이 아닐 때"</b> 다.
     * {@code acceptsProfiles} 는 OR 판정이므로 {@code SPRING_PROFILES_ACTIVE=dev,stg} 처럼 배포
     * 프로파일이 섞이면 완화가 그대로 따라붙는다 — 그래서 배포 프로파일을 <b>거부 목록</b>으로
     * 먼저 걸러낸다. 배포 환경 표식({@code ENV})도 동일하게 {@code stg}/{@code prd} 를 모두
     * 거부한다({@code DevProfileGuard.DEPLOYED_ENVS} 선례 — stg 는 이미 "배포 환경"으로 취급된다).
     */
    private boolean allowsInternalEndpoint(Environment environment) {
        if (environment == null) {
            return false; // 판정 불가 → 강제 유지(fail-closed)
        }
        if (environment.acceptsProfiles(Profiles.of(DEPLOYED_PROFILES))) {
            return false;
        }
        String envName = environment.getProperty("ENV");
        if (envName != null && DEPLOYED_ENV_MARKERS.contains(envName.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return environment.acceptsProfiles(Profiles.of("local", "dev"));
    }

    /**
     * 외부 호출 baseUrl 의 SSRF / cleartext / placeholder 위험을 검증한다.
     *
     * @param internalEndpointAllowed local/dev 목 서버 위탁 허용 여부. true 면 평문 http 스키마와
     *                                내부/사설 호스트를 허용한다(빈 값·placeholder 차단은 유지).
     * @throws IllegalStateException 검증 실패 시 (Spring Bean 생성 실패 → fail-closed)
     */
    private void validateExternalUrl(String baseUrl, boolean internalEndpointAllowed) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "vlm.client.url 가 비어있습니다. enabled=true 시 실제 외부 URL 필수.");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("vlm.client.url 형식이 올바르지 않습니다: " + baseUrl, e);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean http = "http".equalsIgnoreCase(scheme);
        if (!https && !(internalEndpointAllowed && http)) {
            throw new IllegalStateException(
                    "vlm.client.url 은 HTTPS 스키마만 허용됩니다 (현재: " + scheme + "). CWE-319 cleartext 차단.");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("vlm.client.url 의 host 가 비어있습니다: " + baseUrl);
        }
        String hostLower = host.toLowerCase();
        for (String fragment : PLACEHOLDER_HOST_FRAGMENTS) {
            if (hostLower.contains(fragment)) {
                throw new IllegalStateException(
                        "vlm.client.url 호스트가 placeholder/예제입니다: " + host + ". 운영 환경 변수 미설정 의심.");
            }
        }
        if (internalEndpointAllowed) {
            // local/dev — 목 서버는 컨테이너 내부 이름이라 도커 밖에서는 해석조차 되지 않는다.
            //   해석 실패를 실패로 취급하면 네이티브 기동이 통째로 막히므로 "해석되면 검사, 안 되면 통과"로 둔다.
            //   단, 해석에 성공한 경우 링크로컬/클라우드 메타데이터 대역(169.254.0.0/16, fe80::/10)은
            //   개발 환경에서도 정상적인 위탁 대상이 될 수 없으므로 거부한다(설정 사고 표면 차단).
            InetAddress resolved = resolveQuietly(host);
            if (resolved != null && (resolved.isLinkLocalAddress()
                    || resolved.getHostAddress().startsWith("169.254."))) {
                throw new IllegalStateException(
                        "vlm.client.url 이 링크로컬/클라우드 메타데이터 대역을 가리킵니다: " + host + " → "
                                + resolved.getHostAddress() + ". 개발 프로파일에서도 차단됩니다.");
            }
            // (외부에 노출되지 않는 개발 네트워크 전제 — 평문 사용 사실만 로그로 남긴다)
            log.warn("[VLM] 개발 프로파일 완화 적용 — 평문/내부 엔드포인트 위탁 허용: scheme={} host={}", scheme, host);
            return;
        }
        // IP 검증 — DNS rebinding 대응을 위해 호스트가 도메인이면 해석 후 검증.
        InetAddress addr;
        try {
            addr = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("vlm.client.url 호스트를 해석할 수 없습니다: " + host, e);
        }
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
            throw new IllegalStateException(
                    "vlm.client.url 이 내부/사설 네트워크를 가리킵니다: " + host + " → " + addr.getHostAddress()
                            + ". CWE-918 SSRF 차단.");
        }
        // 169.254.169.254 등 메타데이터 IP 추가 차단 (link-local 검사로 일반적으로 잡히지만 명시).
        String ip = addr.getHostAddress();
        if (ip.startsWith("169.254.")) {
            throw new IllegalStateException(
                    "vlm.client.url 이 클라우드 메타데이터 대역을 가리킵니다: " + ip + ". CWE-918 차단.");
        }
    }

    /**
     * 호스트를 해석하되 실패하면 {@code null} 을 돌려준다(예외 없음).
     *
     * <p>완화 경로 전용 — 컨테이너 내부 이름(도커 밖에서 해석 불가)을 기동 실패로 만들지 않기 위함이다.
     */
    private InetAddress resolveQuietly(String host) {
        String normalized = host.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            return InetAddress.getByName(normalized);
        } catch (UnknownHostException | SecurityException e) {
            return null; // 해석 불가 = 개발 네트워크 밖 → 완화 유지(기동 보장)
        }
    }

    /**
     * 평문(http) 엔드포인트에 인증 토큰이 설정된 경우 경고를 남긴다 (CWE-319).
     *
     * <p>local/dev 완화 경로는 TLS 가 없으므로 {@code Authorization: Bearer ...} 헤더가 네트워크에
     * 평문으로 흐른다. 개발 목 서버 연동에서 토큰이 필요할 수 있어 거부하지 않고 경고만 남기며,
     * 토큰 값은 절대 출력하지 않는다(존재/길이만).
     */
    private void warnIfTokenOnCleartext(String baseUrl, String token) {
        if (token == null || token.isBlank() || baseUrl == null) {
            return;
        }
        if (!baseUrl.trim().toLowerCase(Locale.ROOT).startsWith("http://")) {
            return;
        }
        log.warn("[VLM] 평문 http 엔드포인트에 인증 토큰이 설정되어 있습니다 — 토큰이 네트워크에 평문 노출됩니다"
                + " (CWE-319). 운영에서는 HTTPS 필수. tokenLength={}", token.trim().length());
    }
}
