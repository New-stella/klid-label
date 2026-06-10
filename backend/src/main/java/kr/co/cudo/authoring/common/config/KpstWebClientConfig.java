package kr.co.cudo.authoring.common.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * ㈜KPST 비식별 솔루션 연동용 TLS WebClient 설정 — Phase 1 신설.
 *
 * <p>KPST 서버는 <b>자체 CA 발급</b> 인증서를 사용한다(규격 §22.1). 시스템 기본 신뢰 체인이 아닌,
 * 제공받은 {@code ca.crt} 로 서버를 검증하도록 reactor-netty {@link HttpClient} 에 커스텀
 * {@link SslContext} 를 주입한다.
 *
 * <h3>보안 (CWE-295 Improper Certificate Validation)</h3>
 * <ul>
 *   <li>{@code kpst.deid.ca-cert-path} 미설정/파일 없음 → 빈 생성 실패(fail-closed). 신뢰 우회 없음.</li>
 *   <li>인증서 검증을 끄지 않는다(InsecureTrustManager/TrustAll 미사용). ca.crt 기반 검증만 수행.</li>
 *   <li>hostname verification(endpoint identification)은 reactor-netty 기본값으로 활성이며 끄지
 *       않는다 — 인증서 CN/SAN 이 대상 호스트와 불일치하면 handshake 가 실패한다.</li>
 * </ul>
 *
 * <h3>SSRF (CWE-918)</h3>
 * base-url 은 application.yml 설정값만 사용한다. HTTPS 스키마를 강제한다.
 *
 * <p>{@code kpst.deid.enabled=true} 일 때만 빈을 생성한다(기본 false). local/dev 에서 ca.crt 미보유
 * 환경의 기동/테스트 컨텍스트 영향을 막기 위함이며, 실 연동(dev/stg/prd)은 환경변수로 활성화한다.
 */
@Configuration
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class KpstWebClientConfig {

    /** 응답 버퍼 상한 — 진행 조회 등 JSON 응답 대비(다운로드는 스트리밍이라 비대상). */
    private static final int MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;

    /** 커넥션 수립 타임아웃 — 방화벽 drop(SYN 무응답) 시 무기한 블록 방지. */
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    /** 일반(폴링/연결확인) 응답 타임아웃 — 클라이언트 timeout() 의 안전망(Netty 레벨). */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);
    /** 업로드 응답 타임아웃 — 대용량 멀티파트 전송 대비 여유. */
    private static final Duration UPLOAD_RESPONSE_TIMEOUT = Duration.ofMinutes(10);

    @Bean(name = "kpstDeidWebClient")
    public WebClient kpstDeidWebClient(
            @Value("${kpst.deid.base-url}") String baseUrl,
            @Value("${kpst.deid.ca-cert-path:}") String caCertPath) {
        return buildClient(baseUrl, caCertPath, RESPONSE_TIMEOUT);
    }

    /**
     * 업로드 전용 WebClient — 동일 TLS 신뢰 구성. 대용량 멀티파트 전송 시 별도 튜닝 여지를 위해 분리.
     */
    @Bean(name = "kpstDeidUploadWebClient")
    public WebClient kpstDeidUploadWebClient(
            @Value("${kpst.deid.base-url}") String baseUrl,
            @Value("${kpst.deid.ca-cert-path:}") String caCertPath) {
        return buildClient(baseUrl, caCertPath, UPLOAD_RESPONSE_TIMEOUT);
    }

    private WebClient buildClient(String baseUrl, String caCertPath, Duration responseTimeout) {
        validateHttps(baseUrl);
        SslContext sslContext = buildSslContext(caCertPath);
        // reactor-netty 는 기본적으로 TLS endpoint identification(hostname verification, CWE-295)을
        // 활성화한다 — sslContext 에 TrustAll/InsecureTrustManager 를 주입하지 않으므로 잘못된 CN
        // 인증서 서버와는 handshake 가 실패한다(fail-closed). 아래 타임아웃은 방화벽 drop 시 무기한
        // 블록을 막는 Netty 레벨 안전망이다.
        HttpClient httpClient = HttpClient.create()
                .secure(spec -> spec.sslContext(sslContext))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(responseTimeout);
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs((ClientCodecConfigurer c) -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    /** 자체 CA(ca.crt) 로 서버 인증서를 검증하는 SslContext. 미설정/오류 시 fail-closed. */
    private SslContext buildSslContext(String caCertPath) {
        if (caCertPath == null || caCertPath.isBlank()) {
            throw new IllegalStateException(
                    "kpst.deid.ca-cert-path 가 비어있습니다. KPST 자체 CA(ca.crt) 경로 설정 필수 (CWE-295 fail-closed).");
        }
        Path path = Path.of(caCertPath.trim());
        if (!Files.isReadable(path)) {
            throw new IllegalStateException(
                    "kpst.deid.ca-cert-path 파일을 읽을 수 없습니다: " + path + " (CWE-295 fail-closed).");
        }
        try (InputStream ca = Files.newInputStream(path)) {
            return SslContextBuilder.forClient()
                    .trustManager(ca)
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException("KPST ca.crt 로 SslContext 구성 실패 (인증서 형식 확인).", e);
        } catch (IOException e) {
            throw new IllegalStateException("KPST ca.crt 읽기 실패: " + path, e);
        }
    }

    /** SSRF/cleartext 방어 — base-url 은 HTTPS 스키마만 허용(자체 CA TLS 전제). */
    private void validateHttps(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("kpst.deid.base-url 가 비어있습니다.");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("kpst.deid.base-url 형식이 올바르지 않습니다: " + baseUrl, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException(
                    "kpst.deid.base-url 은 HTTPS 스키마만 허용됩니다 (현재: " + scheme + "). 자체 CA TLS 전제.");
        }
    }
}
