package kr.co.cudo.authoring.common.config;

import kr.co.cudo.authoring.common.client.ControlNotifyTokenProvider;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpoint;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointExchangeFilter;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointTransportGuards;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 외부 연동 {@code WebClient} 구성.
 *
 * <h3>★ 주소는 빈 생성 시점에 고정되지 않는다 (R11)</h3>
 * <p>아래 빈 중 <b>AI 추론·시계열 분석·관제 통지</b> 3종은 {@code baseUrl} 로 <b>배포 기본값</b>을 갖되,
 * {@link IntegrationEndpointExchangeFilter} 를 달아 <b>매 호출 시점</b>에 설정 override 를 다시 읽는다.
 * 설정 화면에서 주소를 바꾸면 재기동 없이 다음 호출부터 새 주소로 나간다.
 *
 * <p>override 가 없으면 필터는 <b>아무것도 하지 않는다</b> — 기존 형상·테스트 동작은 그대로다.
 *
 * <h3>★ 주소가 바뀌면 자격증명은 따라가지 않는다</h3>
 * <p>{@code defaultHeader} 는 빈 생성 시점 고정이라 URL 만 바꾸면 <b>원 수신처에 발급된 토큰이 새
 * 호스트로 그대로 전송</b>된다(CWE-522). {@link IntegrationEndpointTransportGuards} 가 호스트가
 * 달라진 요청에서 그 헤더를 떼고 WARN 을 남긴다 — 상대가 401 로 시끄럽게 실패하는 편이 조용한
 * 유출보다 낫다. 스킴이 바뀌는 경우도 같은 자리에서 경고한다({@code KpstWebClientConfig} 와 대칭).
 *
 * <p>비식별(4번째)은 이 클래스가 아니라 {@link KpstWebClientConfig} 에 배선돼 있다 — 실제 위탁이
 * 그쪽 빈으로 나가기 때문이다.
 *
 * <h3>★ 연동 주소로 기동을 막지 않는다 (2026-09-03 사용자 확정, 구속)</h3>
 * <p>아래 <b>모든</b> 빈은 주소가 비었든·형식이 틀렸든·예시 값이든·예약 대역이든 <b>기동에
 * 성공</b>한다. 거부된 값은 {@link ExternalEndpointAddress} 가 빈 base 로 낮추고, 그 연동으로
 * 나가려는 순간 {@code IntegrationEndpointTransportGuards#requireUsableAddress} 가 막는다.
 * <b>검증 규칙은 그대로이고 적용 시점만 옮겼다</b> — 한 연동의 설정 실수로 저작 업무 전체가 멈추는
 * 편이, 배포 시점에 빨리 아는 것보다 훨씬 비싸기 때문이다.
 */
@Configuration
public class WebClientConfig {

    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    /** ai-server는 이미지 base64가 포함된 요청을 처리하므로 32MB 버퍼 적용. */
    private static final int AI_SERVER_BUFFER_SIZE = 32 * 1024 * 1024;

    private static ExchangeStrategies largeBufferStrategies() {
        return ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(AI_SERVER_BUFFER_SIZE))
                .build();
    }

    /**
     * ⚠ <b>이 빈은 주입 대상이 0건이다</b> — 실제 비식별 위탁은 {@code kpst.deid.base-url}
     * ({@code kpstDeidWebClient})로 나간다({@code DeidentifyStep} → {@code KpstDeidentService} →
     * {@code KpstDeidentifyClient}).
     *
     * <p>그래서 <b>운영 화면의 「비식별 서버」 주소는 이 빈이 아니라 {@code kpstDeidWebClient} 에
     * 배선</b>돼 있다({@link KpstWebClientConfig}). 여기에 달면 화면에서 주소를 바꿔도 아무 일도
     * 일어나지 않는다. 빈 자체의 제거는 이 작업 범위 밖이라 그대로 둔다.
     */
    @Bean(name = "deidentifyWebClient")
    public WebClient deidentifyWebClient(
            @Value("${authoring.integration.deidentify.base-url:}") String baseUrl) {
        // ★ 이 값 때문에 기동이 막히지 않는다 — 미설정·파싱 불가면 빈 base 로 낮춘다(2026-09-03 확정).
        //   정책을 새로 걸지 않는 이유는 「무엇을 막는지는 그대로」이기 때문이다(이 축엔 정책이 없었다).
        return WebClient.builder()
                .baseUrl(ExternalEndpointAddress
                        .formatOnly("authoring.integration.deidentify.base-url", baseUrl)
                        .baseUrl())
                .build();
    }

    /**
     * AI 추론 서버용 WebClient.
     *
     * <h3>★ 주소가 어떤 상태여도 기동한다 (2026-09-03 확정, 구속)</h3>
     * <p>구 배선은 {@code @Value} 에 기본값이 없어 <b>키가 없으면 기동이 죽었고</b>, 값이 파싱되지
     * 않으면 {@code WebClient.baseUrl(...)} 이 던져 역시 기동이 죽었다. 이제 그 두 경우 모두
     * <b>빈 base 로 낮추고</b> 전송 시점에 실패한다.
     *
     * <p>⚠ 이 축에는 <b>주소 정책이 없다</b>({@code AiSrvrSelector} 주석 참조) — 그래서 여기서도
     * 정책을 새로 걸지 않고 <b>형식만</b> 본다. 정책을 새로 걸면 지금까지 통과하던 값을 막게 되어
     * 「무엇을 막는지는 그대로」를 어긴다.
     *
     * <p>노드 원장에서 고른 장비로 <b>핀된</b> 요청은 절대 URI 라 호스트가 있으므로 가드를 그대로
     * 통과한다 — 이중화 배포의 정상 위탁에 영향이 없다.
     */
    @Bean(name = "aiServerWebClient")
    public WebClient aiServerWebClient(
            @Value("${authoring.integration.ai-server.base-url:}") String baseUrl,
            IntegrationEndpointResolver endpointResolver) {
        ExternalEndpointAddress address = ExternalEndpointAddress
                .formatOnly(IntegrationEndpoint.AI_SERVER.configKey(), baseUrl);
        String base = address.baseUrl();
        return WebClient.builder()
                .baseUrl(base)
                .filter(IntegrationEndpointExchangeFilter.of(
                        IntegrationEndpoint.AI_SERVER, base, endpointResolver))
                // 빈 base 는 상대 URI 가 되어 loopback:80 으로 나간다 — 전송 자체를 막는다.
                .filter(IntegrationEndpointTransportGuards.requireUsableAddress(
                        IntegrationEndpoint.AI_SERVER, address.rejectionLabel()))
                .exchangeStrategies(largeBufferStrategies())
                .build();
    }

    /**
     * 외부 시계열 분석 위탁 클라이언트용 WebClient.
     *
     * <p>baseUrl 은 {@link VlmUrlPolicy} 로 판정하되 <b>기동을 막지 않는다</b> — 위반이면 빈 base 로
     * 낮추고 <b>그 주소로 나가려는 순간</b> 거부한다(2026-09-03 확정). 그 정책이 보는 것은
     * <b>스킴({@code http}/{@code https})과 형식</b>
     * 이며 전송·대역을 강제하지 않는다 — 연동 주소 정책의 확정 규칙(2026-08-10)이다. 상세·폐기된
     * 조항은 {@link VlmUrlPolicy} 참조.
     *
     * <p>⚠ <b>구 서술 폐기</b>: <i>"정책은 운영 엄격(HTTPS 전용 + 사설망 차단) / 개발 완화로 갈리며 완화는
     * 전용 프로퍼티 + 프로파일 allowlist + 기동 assert 로 격리된다"</i>. 그 갈림과 완화 플래그
     * ({@code vlm.client.allow-insecure-url})는 <b>함께 폐기</b>됐다. 되살리면 운영 프로파일에서
     * <b>평문 http 주소를 채우는 순간 위탁이 전건 거부된다</b>(실 연동은 양쪽 모두 평문 http 다).
     *
     * <p><b>주소가 비어 있으면 검증을 생략하고 빈을 만든다 — 미연동 환경의 기동을 보장한다.</b>
     * 구 동작은 별도 설정 토글이 꺼져 있을 때 검증을 생략하는 것이었으나,
     * 그 토글은 폐지됐다: 기본값이 비활성이라 <b>납품본이 시계열이 꺼진 채로 나가고 그 사실이 산출물에도
     * 이력에도 드러나지 않았다</b>. 이제 미연동 판정은 <b>연동 주소 주입 여부</b>가 하고, 미연동 구간의
     * 운영은 사람이 사유를 남기는 단계 스킵이 담당한다(그 사실이 처리 이력에 남는다).
     *
     * <p>⚠ <b>주소가 비어 있다고 기동을 거부하지 않는다</b> — 「미연동 시 기동 거부」는 검토 후 기각된
     * 안이다(벤더 연동 확정 전 배포 불가 + 시계열 외 전 기능 동반 차단). 회귀 가드는
     * {@code VlmBlankUrlBootTest} 다.
     *
     * <p><b>local/dev 목업 배선</b>: 두 프로파일의 위탁 대상은 목업 벤더 서버(mock-server)로 TLS 미지원
     * 평문 http 이고 호스트도 컨테이너 내부 이름({@code klid-mock-server})이다. 이제는 <b>모든 프로파일이
     * 같은 정책</b>이라 별도 완화 배선 없이 그대로 기동한다. 설정 누락(빈 값)·placeholder 호스트·
     * 링크로컬/메타데이터 대역 차단은 어느 프로파일에서도 그대로다(fail-closed).
     *
     * @design ADR-049
     */
    @Bean(name = "vlmWebClient")
    public WebClient vlmWebClient(
            @Value("${vlm.client.url:}") String baseUrl,
            @Value("${vlm.client.token:}") String token,
            VlmUrlPolicy urlPolicy,
            IntegrationEndpointResolver endpointResolver) {
        // ★ 주소가 어떤 상태여도 기동한다 — 판정은 그대로 태우되 결과를 <들고 있다가> 전송 시점에 쓴다.
        //   구 배선은 여기서 예외를 던져(빈 생성 실패) 기동을 막았다. 규칙은 그대로이고 시점만 옮겼다.
        ExternalEndpointAddress address =
                ExternalEndpointAddress.of(IntegrationEndpoint.VLM.configKey(), baseUrl,
                        urlPolicy.inspect(baseUrl));
        // 토큰 배선은 그대로 두고 주소만 호출 시점 해석으로 바꾼다 — URL 재작성 필터는 URL 만 건드린다.
        // ★그래서 자격증명·스킴 가드를 그 뒤에 이어 붙인다(재작성된 최종 URL 을 봐야 한다).
        // 거부·미주입은 빈 문자열로 정규화된다 — null 을 그대로 넘기면 실패가 NPE 로 나와 판독이 어렵다.
        String base = address.baseUrl();
        WebClient.Builder b = WebClient.builder()
                .baseUrl(base)
                .filter(IntegrationEndpointExchangeFilter.of(
                        IntegrationEndpoint.VLM, base, endpointResolver))
                // ★ 미연동(주소 미주입)이거나 <설정값이 정책 위반>이면 전송 자체를 막는다 — 재작성 필터
                //   뒤라 최종 URL 을 본다. 주소가 비면 상대 URI 가 되어 loopback:80 으로 실제 TCP 연결이
                //   나가고, 그 요청 바디에는 비식별 영상 절대경로와 콜백 주소가 실린다(온프렘은 같은
                //   호스트에 웹서버가 있어 접근 로그에 경로가 남는다).
                .filter(IntegrationEndpointTransportGuards.requireUsableAddress(
                        IntegrationEndpoint.VLM, address.rejectionLabel()))
                .filter(IntegrationEndpointTransportGuards.warnOnSchemeChange(
                        IntegrationEndpoint.VLM, base));
        if (token != null && !token.isBlank()) {
            // 평문 http 에 Bearer 토큰이 실리면 네트워크에 그대로 노출된다 (CWE-319) — 경고만, 값 미출력.
            urlPolicy.warnIfTokenOnCleartext(base, token);
            b.defaultHeader(AUTHORIZATION_HEADER, "Bearer " + token);
            // defaultHeader 는 빈 생성 시점 고정이라, 주소를 바꾸면 이 토큰이 새 호스트로 따라간다.
            // 호스트가 달라지면 떼어낸다(CWE-522) — 원 수신처에 발급된 값이라 어차피 무효다.
            b.filter(IntegrationEndpointTransportGuards.stripCredentialOnHostChange(
                    IntegrationEndpoint.VLM, base, AUTHORIZATION_HEADER));
        }
        return b.build();
    }

    /** 외부 시계열 분석 벤더 인증 헤더명. */
    static final String AUTHORIZATION_HEADER = "Authorization";

    /** 관제 inbound SPI 인증 헤더명(API-251 / API-285 계약). */
    static final String CONTROL_NOTIFY_TOKEN_HEADER = "x-access-token";

    /**
     * Phase 2 — 관제서버 outbound 통지 클라이언트용 WebClient.
     *
     * <p>CWE-918 SSRF: base-url 은 application.yml 설정값만 사용. 사용자 입력 X.
     *
     * <h3>D-ISSUE-62 — 인증 헤더({@code x-access-token}) 부착 (HIGH)</h3>
     * 관제 inbound SPI 계약(API-251/API-285)이 {@code x-access-token} 을 요구하는데 이 빈은 base-url 만
     * 설정해 <b>전 통지가 인증 없이</b> 나갔다. 로컬 목 서버가 인증을 검사하지 않아 202 로 통과했을 뿐,
     * 실환경에서는 전 통지가 401 로 거부되고 폴백 큐가 재시도 상한을 소진한 뒤 dead-letter 로 고착된다
     * (관제 동기화 전면 중단). {@code vlmWebClient} 는 같은 배선을 이미 갖고 있어 관제 쪽만 누락이었다.
     *
     * <p><b>토큰 취급 (CWE-798/532)</b>: 값은 설정({@code authoring.control-notify.token} ←
     * 환경변수 {@code CONTROL_NOTIFY_TOKEN})에서만 로드하며 <b>코드·yml 에 평문 상수를 두지 않는다</b>.
     * 기본값은 빈 문자열이라 미설정 환경(local 목 서버 등)에서는 헤더를 붙이지 않아 기존 동작이 유지된다.
     * 로그에는 <b>토큰 값을 절대 출력하지 않는다</b> — 존재 여부/길이만 남긴다.
     *
     * <p><b>미설정 경고</b>: 통지가 활성({@code enabled=true})인데 토큰이 비어 있으면 실환경 401 이 확실하므로
     * 기동 WARN 으로 드러낸다(기동 차단은 하지 않는다 — 목 서버 연동/헤더 미요구 환경이 실재하고, 통지
     * 실패는 폴백 큐로 회수되므로 fail-closed 로 앱 전체를 세울 사안이 아니다).
     *
     * <h3>★ x-access-token 은 발송 시점 동적 발급이다 (ADR-063 ⑥)</h3>
     * <p>임시로 쓰던 <b>20년 고정 정적 토큰</b>(CWE-798)을 대체한다. 정적 {@code authoring.control-notify.token}
     * 설정값이 <b>있으면 그 값을 우선</b>(관제팀이 별도 토큰을 주는 경우)하고, 없으면
     * {@link ControlNotifyTokenProvider} 로 <b>매 통지마다 HS256 서비스 토큰을 새로 발급</b>한다
     * (iss=klid-auth · exp=now+ttl · 공유 시크릿 HMAC). 정적·시크릿 둘 다 없으면 헤더 미부착 fail-safe 다
     * (통지는 죽지 않고 폴백 큐가 회수). 발급 값은 로그에 절대 출력하지 않는다(CWE-532).
     *
     * @design ADR-063
     */
    @Bean(name = "controlNotifyWebClient")
    public WebClient controlNotifyWebClient(
            @Value("${authoring.control-notify.url:http://localhost:8090}") String baseUrl,
            @Value("${authoring.control-notify.token:}") String token,
            @Value("${authoring.control-notify.enabled:false}") boolean enabled,
            IntegrationEndpointResolver endpointResolver,
            ControlNotifyTokenProvider tokenProvider) {
        // ★ 주소가 어떤 상태여도 기동한다 — 미설정·파싱 불가면 빈 base 로 낮추고 전송 시점에 실패한다.
        //   이 축에도 주소 정책은 없으므로 형식만 본다(무엇을 막는지는 그대로).
        ExternalEndpointAddress address = ExternalEndpointAddress
                .formatOnly(IntegrationEndpoint.CONTROL_NOTIFY.configKey(), baseUrl);
        String base = address.baseUrl();
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(base)
                .filter(IntegrationEndpointExchangeFilter.of(
                        IntegrationEndpoint.CONTROL_NOTIFY, base, endpointResolver))
                .filter(IntegrationEndpointTransportGuards.requireUsableAddress(
                        IntegrationEndpoint.CONTROL_NOTIFY, address.rejectionLabel()))
                .filter(IntegrationEndpointTransportGuards.warnOnSchemeChange(
                        IntegrationEndpoint.CONTROL_NOTIFY, base));
        boolean cleartext = base.toLowerCase(Locale.ROOT).startsWith("http://");
        if (token != null && !token.isBlank()) {
            // ── 하위호환 override — 관제팀이 별도 토큰을 준 경우 그 값을 그대로 우선한다(발급기 미호출).
            if (cleartext) {
                // 평문 http 에 인증 토큰이 실리면 네트워크에 그대로 노출된다(CWE-319) — 값 미출력.
                log.warn("[ControlNotify] 평문 http 엔드포인트에 인증 토큰이 설정되어 있습니다 — "
                        + "토큰이 네트워크에 평문 노출됩니다(CWE-319). 운영에서는 HTTPS 필수. tokenLength={}",
                        token.trim().length());
            }
            builder.defaultHeader(CONTROL_NOTIFY_TOKEN_HEADER, token.trim());
            // 주소를 바꾸면 이 토큰이 새 호스트로 따라간다 — 호스트가 달라지면 떼어낸다(CWE-522).
            builder.filter(IntegrationEndpointTransportGuards.stripCredentialOnHostChange(
                    IntegrationEndpoint.CONTROL_NOTIFY, base, CONTROL_NOTIFY_TOKEN_HEADER));
        } else if (tokenProvider != null && tokenProvider.canIssue()) {
            // ── 발송 시점 동적 발급 — 매 통지마다 새 HS256 토큰을 x-access-token 에 싣는다(ADR-063 ⑥).
            if (cleartext) {
                log.warn("[ControlNotify] 평문 http 엔드포인트로 동적 x-access-token 이 나갑니다 — "
                        + "네트워크에 평문 노출됩니다(CWE-319). 운영에서는 HTTPS 필수.");
            }
            builder.filter(dynamicAccessTokenFilter(tokenProvider, enabled));
        } else if (enabled) {
            // 정적 토큰도 없고 시크릿(발급기)도 없다 — 실환경 401 이 확실하므로 드러낸다(기동 차단은 안 함).
            log.warn("[ControlNotify] 통지가 활성화됐으나 인증 토큰이 없고 서비스 토큰 발급도 불가합니다 "
                    + "(authoring.control-notify.token 미설정 + JWT_SECRET 미설정) — 관제 SPI 가 {} 를 "
                    + "요구하면 전 통지가 401 로 거부됩니다.", CONTROL_NOTIFY_TOKEN_HEADER);
        }
        return builder.build();
    }

    /**
     * 발송 시점 x-access-token 을 붙이는 필터.
     *
     * <p>매 요청마다 {@link ControlNotifyTokenProvider#issue()} 로 새 토큰을 발급해 헤더에 싣는다.
     * 발급이 {@code null}(발급 실패)이면 헤더 없이 그대로 보낸다(fail-safe — 통지를 죽이지 않고 폴백
     * 큐가 회수). WARN 은 로그 폭주를 막기 위해 1회만 남기고, 토큰 값은 절대 출력하지 않는다(CWE-532).
     */
    private static ExchangeFilterFunction dynamicAccessTokenFilter(
            ControlNotifyTokenProvider provider, boolean enabled) {
        AtomicBoolean warned = new AtomicBoolean(false);
        return (request, next) -> {
            String issued = provider.issue();
            if (issued == null || issued.isBlank()) {
                if (enabled && warned.compareAndSet(false, true)) {
                    log.warn("[ControlNotify] 동적 x-access-token 발급 실패 — 헤더 없이 전송합니다. "
                            + "관제 SPI 가 {} 를 요구하면 401 이 예상됩니다(폴백 큐가 재시도).",
                            CONTROL_NOTIFY_TOKEN_HEADER);
                }
                return next.exchange(request);
            }
            ClientRequest mutated = ClientRequest.from(request)
                    .headers(h -> h.set(CONTROL_NOTIFY_TOKEN_HEADER, issued))
                    .build();
            return next.exchange(mutated);
        };
    }

}
