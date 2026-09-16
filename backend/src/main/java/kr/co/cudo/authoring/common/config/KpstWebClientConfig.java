package kr.co.cudo.authoring.common.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import kr.co.cudo.authoring.common.client.ExternalCallLoggingFilter;
import kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpoint;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointExchangeFilter;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointTransportGuards;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * ㈜KPST 비식별 솔루션 연동용 WebClient 설정 — Phase 1 신설.
 *
 * <p>KPST 전송은 배포 환경에 따라 두 가지 방식을 모두 지원하며 base-url 의 <b>스키마로 자동 분기</b>한다:
 * <ul>
 *   <li><b>{@code http://IP:port}</b> — 내부망 격리 전제의 평문 전송. SSL 미적용, ca-cert 불요.</li>
 *   <li><b>{@code https://host:port}</b> — KPST <b>자체 CA 발급</b> 인증서 사용(규격 §22.1). 시스템 기본
 *       신뢰 체인이 아닌, 제공받은 {@code ca.crt} 로 서버를 검증하도록 reactor-netty {@link HttpClient} 에
 *       커스텀 {@link SslContext} 를 주입한다.</li>
 * </ul>
 *
 * <h3>★ 주소로 기동을 막지 않는다 — 위탁을 막는다 (2026-09-03 사용자 확정, 구속)</h3>
 * <p>빈값·형식 오류·예시 호스트·예약 대역·비허용 스킴, 그리고 <b>https 인데 자체 CA 가 없는 경우</b>까지
 * 전부 <b>기동에 성공</b>한다. 거부 사유는 {@code kpstDeidEndpointAddress} 빈이 들고 있다가
 * <b>비식별로 나가려는 순간</b> 요청을 막는다. 규칙은 그대로이고 적용 시점만 옮겼다.
 *
 * <h3>보안 (CWE-295 Improper Certificate Validation) — https 경로</h3>
 * <ul>
 *   <li>https 인데 {@code kpst.deid.ca-cert-path} 미설정/파일 없음 → <b>위탁 거부</b>(fail-closed).
 *       검증할 수 없으면 보내지 않는다 — 신뢰 우회 없음. (구 동작은 빈 생성 실패였다.)</li>
 *   <li>인증서 검증을 끄지 않는다(InsecureTrustManager/TrustAll 미사용). ca.crt 기반 검증만 수행.</li>
 *   <li>hostname verification(endpoint identification)은 reactor-netty 기본값으로 활성이며 끄지
 *       않는다 — 인증서 CN/SAN 이 대상 호스트와 불일치하면 handshake 가 실패한다.</li>
 * </ul>
 *
 * <h3>http 경로</h3>
 * 평문 전송은 내부망 격리를 전제로 허용하며, 기동 시 1회 WARN 로그로 평문 사용을 알린다. ca-cert 는 불요다.
 *
 * <h3>SSRF (CWE-918)</h3>
 * base-url 은 application.yml 설정값만 사용한다. http/https 스키마만 허용하고 그 외는 거부한다.
 *
 * <p>{@code kpst.deid.enabled=true} 일 때만 빈을 생성한다 — <b>yml 기본값은 true</b>
 * ({@code application.yml: ${KPST_DEID_ENABLED:true}} — 비식별 단일 경로가 KPST 폴링이라 기본 활성이며
 * 필드는 킬스위치로 유지). ca.crt 미보유 환경은 {@code KPST_DEID_ENABLED=false} 로 끄거나 내부망 평문
 * http base-url 을 사용한다. (구 주석 "기본 false" 는 yml 실값과 어긋난 드리프트라 정정)
 *
 * <h3>★ 외부향 빈은 호출 로그 필터를 맨 마지막에 단다</h3>
 * <p>{@link kr.co.cudo.authoring.common.client.ExternalCallLoggingFilter} — 재작성·전송 가드·자격증명
 * 필터를 거친 <b>실제 대상</b>과 결과 코드·소요 시간, 오류 응답 사유를 서버 로그에 남긴다.
 *
 * @design ADR-062
 * @design NFR-038
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class KpstWebClientConfig {

    /** 응답 버퍼 상한 — 진행 조회 등 JSON 응답 대비(다운로드는 스트리밍이라 비대상). */
    private static final int MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;

    /** 커넥션 수립 타임아웃 — 방화벽 drop(SYN 무응답) 시 무기한 블록 방지. */
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    /** 일반(폴링/연결확인) 응답 타임아웃 — 클라이언트 timeout() 의 안전망(Netty 레벨). */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    /**
     * base-url 검증 정책 — 내부망 전제(http/https 허용, 사설 IP 허용, placeholder·비허용 스키마 차단).
     * VLM 과 동일한 {@link ExternalUrlPolicy} 를 사용해 판정 근거를 일원화한다. 평문 WARN 은 정책이 1회만 출력.
     */
    private static final ExternalUrlPolicy URL_POLICY =
            ExternalUrlPolicy.internalNetwork("kpst.deid.base-url");

    /**
     * ★ <b>운영 화면의 「비식별 서버」 주소가 반영되는 지점</b> (R11).
     *
     * <p>{@code baseUrl} 은 빈 생성 시점에 고정되므로, 필터가 <b>매 호출 시점</b>에 설정 override 를
     * 다시 읽어 요청 URL 을 고쳐 쓴다. override 가 없으면 필터는 아무것도 하지 않는다.
     *
     * <p><b>TLS·조건부 생성 배선은 그대로다</b> — 필터는 URL 만 건드린다. 자체 CA {@code SslContext}
     * 구성({@link #buildSslContext})과 {@code @ConditionalOnProperty} 는 변경되지 않았다.
     *
     * <p>⚠ <b>주소를 바꿔도 TLS 구성은 따라가지 않는다</b>({@link #warnIfSchemeDiffers} 참조).
     */
    /**
     * ★ <b>비식별 주소의 기동 시점 판정 결과</b> — 기동을 막지 않고 <b>위탁을 막기</b> 위해 들고 있다.
     * (2026-09-03 사용자 확정, 구속)
     *
     * <h3>구 동작과 무엇이 달라졌나</h3>
     * <p>구 배선은 ①{@code @Value} 에 기본값이 없어 <b>키가 없으면 기동 실패</b> ②{@code URL_POLICY.check}
     * 가 예외를 던져 <b>빈값·형식·예시 호스트·예약 대역에서 기동 실패</b> ③https 인데 자체 CA 가 없으면
     * <b>기동 실패</b>였다. 세 가지 모두 <b>주소 설정 한 줄이 앱 전체를 못 뜨게</b> 만드는 형태다.
     * 이제 셋 다 여기서 <b>거부 판정으로 기록</b>되고, 기동은 정상이며 <b>비식별로 나가려는 순간</b>
     * 실패한다. 판정 규칙 자체는 하나도 바뀌지 않았다.
     *
     * <h3>왜 별도 빈인가</h3>
     * <p>비식별은 <b>WebClient 로 나가는 경로</b>(연결확인·프로젝트 생성·삭제)와 <b>저수준
     * {@code HttpClient} 로 나가는 경로</b>(진행조회·리포트조회)가 갈린다. 저수준 경로에는 필터 훅이
     * 없어 같은 판정을 {@code KpstDeidentifyClient} 가 직접 봐야 한다. <b>한쪽만 막으면 위탁은 새
     * 서버로 가는데 조회만 옛 서버로 나가</b> 그 작업이 영원히 완료되지 않는다 — <b>부분 반영은
     * 미반영보다 위험</b>하므로 두 경로가 <b>같은 빈</b>을 본다.
     */
    @Bean(name = "kpstDeidEndpointAddress")
    public ExternalEndpointAddress kpstDeidEndpointAddress(
            @Value("${kpst.deid.base-url:}") String baseUrl,
            @Value("${kpst.deid.ca-cert-path:}") String caCertPath,
            DeidentifyEndpointTrustGuard trustGuard) {
        // trustGuard 가 null 인 것은 단위 시험 구성이다(IntegrationEndpointResolver 와 같은 관례) —
        //   운영 컨테이너에서는 항상 주입된다.
        ExternalEndpointAddress address = ExternalEndpointAddress.of(
                IntegrationEndpoint.DEIDENTIFY.configKey(), baseUrl, URL_POLICY.inspect(baseUrl));
        if (address.usable() && address.https()) {
            // CWE-295: 자체 CA 로 검증할 수 없으면 <검증 없이 보내느니 안 보낸다>. 기동은 막지 않는다.
            try {
                buildSslContext(caCertPath);
            } catch (IllegalStateException tlsMaterialMissing) {
                address = address.rejectedBecause("TLS 인증서 미비", tlsMaterialMissing.getMessage());
            }
        }
        // 운영에서 목/시뮬레이터 주소면 위탁을 막는다 — 위조 비식별본이 산출물·통지로 나가는 것을 차단.
        //   판정은 신뢰 가드가 단독 소유한다(복제 금지).
        String untrusted = trustGuard == null ? null : trustGuard.commissionBlockReason(baseUrl);
        if (untrusted != null) {
            address = address.rejectedBecause("운영 비신뢰 위탁 대상", untrusted);
        }
        return address;
    }

    /**
     * ★ <b>운영 화면의 「비식별 서버」 주소가 반영되는 지점</b> (R11).
     *
     * <p>{@code baseUrl} 은 빈 생성 시점에 고정되므로, 필터가 <b>매 호출 시점</b>에 설정 override 를
     * 다시 읽어 요청 URL 을 고쳐 쓴다. override 가 없으면 필터는 아무것도 하지 않는다.
     *
     * <p>배포 설정값이 거부됐어도 <b>운영 화면에 정상 주소가 저장돼 있으면 그리로 나간다</b> —
     * 잘못 배포된 주소를 재기동 없이 되돌릴 수 있다. 저장된 값도 없으면 전송 가드가 막는다.
     */
    @Bean(name = "kpstDeidWebClient")
    public WebClient kpstDeidWebClient(
            @Qualifier("kpstDeidEndpointAddress") ExternalEndpointAddress address,
            @Value("${kpst.deid.ca-cert-path:}") String caCertPath,
            IntegrationEndpointResolver endpointResolver) {
        String baseUrl = address.baseUrl();
        return buildClient(address, caCertPath, RESPONSE_TIMEOUT)
                .mutate()
                .filter(IntegrationEndpointExchangeFilter.of(
                        IntegrationEndpoint.DEIDENTIFY, baseUrl, endpointResolver))
                .filter(IntegrationEndpointTransportGuards.requireUsableAddress(
                        IntegrationEndpoint.DEIDENTIFY, address.rejectionLabel()))
                .filter(warnIfSchemeDiffers(baseUrl))
                // ★ 호출 로그는 맨 마지막 — 재작성·가드를 거친 실제 대상과 오류 응답 사유를 남긴다(NFR-038).
                .filter(ExternalCallLoggingFilter.of(IntegrationEndpoint.DEIDENTIFY.name(), true))
                .build();
    }

    /** 스킴 불일치 경고를 1회만 출력하기 위한 가드(로그 폭주 방지). */
    private final java.util.concurrent.atomic.AtomicBoolean schemeMismatchWarned =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 설정한 주소의 스킴이 배포 기본값과 다르면 <b>1회 경고</b>한다.
     *
     * <h3>왜 필요한가 — 코드로 해결할 수 없는 한계</h3>
     * <p>TLS 구성({@code SslContext} 주입 여부·자체 CA)은 <b>빈 생성 시점의 baseUrl 스킴</b>으로 정해지고
     * 필터는 URL 만 바꾸므로 <b>그 구성이 따라가지 않는다</b>:
     * <ul>
     *   <li>배포 기본값이 {@code http} → 설정을 {@code https} 로 바꾸면 자체 CA 신뢰가 구성돼 있지
     *       않아 KPST 사설 인증서 검증에 실패한다.</li>
     *   <li>배포 기본값이 {@code https} → 설정을 {@code http} 로 바꾸면 커넥터가 이미 TLS 모드다.</li>
     *   <li>같은 {@code https} 라도 <b>새 호스트의 인증서가 그 자체 CA 로 서명돼 있어야</b> 하고,
     *       hostname verification 도 새 호스트 이름과 맞아야 한다.</li>
     * </ul>
     * <b>인증서 검증을 낮추지 않는다</b>(CWE-295) — 조용한 실패를 <b>진단 가능한 실패</b>로 바꿀 뿐이다.
     * TLS 구성까지 바꾸려면 재기동(또는 인증서 재배포)이 필요하다.
     */
    private ExchangeFilterFunction warnIfSchemeDiffers(String bootDefault) {
        return (request, next) -> {
            String requestScheme = request.url().getScheme();
            if (requestScheme != null && !requestScheme.equalsIgnoreCase(schemeOf(bootDefault))
                    && schemeMismatchWarned.compareAndSet(false, true)) {
                log.warn("[Kpst] 설정된 비식별 서버 주소의 스킴이 배포 기본값과 다릅니다 — "
                        + "TLS 구성(자체 CA 신뢰 여부)은 기동 시점 값으로 고정되어 따라가지 않습니다. "
                        + "요청 스킴={} / 기동 시점 스킴={}. 전환하려면 재기동이 필요합니다.",
                        requestScheme, schemeOf(bootDefault));
            }
            return next.exchange(request);
        };
    }

    private static String schemeOf(String url) {
        try {
            String scheme = java.net.URI.create(url.trim()).getScheme();
            return scheme == null ? "" : scheme.toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 진행조회({@code GET /retrieve_progress}) 전용 reactor-netty {@link HttpClient} 빈.
     *
     * <p>KPST 실서버는 GET 요청에도 JSON 바디 필터를 강제하나, Spring {@code WebClient} 의
     * {@code method(GET).bodyValue(...)} 는 reactor-netty 가 GET 바디 바이트를 전송하지 않아(서버가
     * 본문을 무기한 대기 → 타임아웃) 동작하지 않는다. 따라서 본 빈은 바디 전송이 가능한 저수준
     * {@code HttpClient.request(GET).send(...)} 경로를 위해 base-url 을 고정 적용해 제공한다.
     * TLS 자체 CA 신뢰·연결/응답 타임아웃은 {@link #kpstDeidWebClient} 와 동일 구성이다.
     */
    @Bean(name = "kpstDeidProgressHttpClient")
    public HttpClient kpstDeidProgressHttpClient(
            @Qualifier("kpstDeidEndpointAddress") ExternalEndpointAddress address,
            @Value("${kpst.deid.ca-cert-path:}") String caCertPath) {
        // ★ 호출 로그 — WebClient 가 아니라 reactor-netty HttpClient 라(본문 실은 GET 때문에 직접 쓴다)
        //   ExchangeFilterFunction 대신 이 클라이언트의 요청·응답·오류·연결 종료 훅으로 같은 로그를 단다.
        //   오류 본문은 호출부가 소비하므로 기록하지 않는다(NFR-038).
        HttpClient client = ExternalCallLoggingFilter.withNettyCallLogging(
                HttpClient.create()
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                        .responseTimeout(RESPONSE_TIMEOUT),
                IntegrationEndpoint.DEIDENTIFY.name(),
                address.usable() ? address.baseUrl() : "");
        if (!address.usable()) {
            // ★ 거부된 주소로는 base 를 걸지 않는다 — 걸면 그 나쁜 주소로 실제 연결이 나간다.
            //   상대 경로 요청은 호출측(KpstDeidentifyClient)이 같은 판정으로 미리 막는다.
            return client;
        }
        client = client.baseUrl(address.baseUrl());
        if (address.https()) {
            SslContext sslContext = buildSslContext(caCertPath);
            client = client.secure(spec -> spec.sslContext(sslContext));
        }
        return client;
    }

    private WebClient buildClient(ExternalEndpointAddress address, String caCertPath,
                                  Duration responseTimeout) {
        boolean https = address.usable() && address.https();
        // 아래 타임아웃은 방화벽 drop 시 무기한 블록을 막는 Netty 레벨 안전망이다(http/https 공통).
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(responseTimeout);
        if (https) {
            // reactor-netty 는 기본적으로 TLS endpoint identification(hostname verification, CWE-295)을
            // 활성화한다 — sslContext 에 TrustAll/InsecureTrustManager 를 주입하지 않으므로 잘못된 CN
            // 인증서 서버와는 handshake 가 실패한다(fail-closed).
            SslContext sslContext = buildSslContext(caCertPath);
            httpClient = httpClient.secure(spec -> spec.sslContext(sslContext));
        }
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs((ClientCodecConfigurer c) -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
        return WebClient.builder()
                .baseUrl(address.baseUrl())
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
            // CWE-209: 사용자/로그 노출 메시지에 절대경로 미포함. 경로는 진단용 DEBUG 로그로만.
            log.debug("[Kpst] ca-cert path not readable path={}", path);
            throw new IllegalStateException(
                    "kpst.deid.ca-cert-path 파일을 읽을 수 없습니다 (경로 설정을 확인하세요). (CWE-295 fail-closed)");
        }
        try (InputStream ca = Files.newInputStream(path)) {
            return SslContextBuilder.forClient()
                    .trustManager(ca)
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException("KPST ca.crt 로 SslContext 구성 실패 (인증서 형식 확인).", e);
        } catch (IOException e) {
            // CWE-209: 절대경로 미노출. 진단용 경로는 DEBUG 로그로만 남긴다.
            log.debug("[Kpst] ca-cert read failed path={}", path, e);
            throw new IllegalStateException(
                    "KPST ca.crt 읽기에 실패했습니다 (경로 설정을 확인하세요).", e);
        }
    }

}
