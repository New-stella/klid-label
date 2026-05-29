package kr.co.cudo.authoring.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

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
     * <p>enabled=false (기본값) 면 검증 생략 — local/dev 환경 영향 0.
     */
    @Bean(name = "vlmWebClient")
    public WebClient vlmWebClient(
            @Value("${vlm.client.url:http://localhost:9400}") String baseUrl,
            @Value("${vlm.client.token:}") String token,
            @Value("${vlm.client.enabled:false}") boolean enabled) {
        if (enabled) {
            validateExternalUrl(baseUrl);
        }
        WebClient.Builder b = WebClient.builder().baseUrl(baseUrl);
        if (token != null && !token.isBlank()) {
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
     * 외부 호출 baseUrl 의 SSRF / cleartext / placeholder 위험을 검증한다.
     *
     * @throws IllegalStateException 검증 실패 시 (Spring Bean 생성 실패 → fail-closed)
     */
    private void validateExternalUrl(String baseUrl) {
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
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
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
}
