package kr.co.cudo.authoring.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

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
     * <p>enabled=true 일 때 baseUrl 을 {@link VlmUrlPolicy} 로 검증한다(위반 시 IllegalStateException
     * → 빈 생성 실패 → 기동 차단). 정책은 운영 엄격(HTTPS 전용 + 사설망 차단) / 개발 완화(평문 http +
     * 사설 IP 허용)로 갈리며, <b>완화는 전용 프로퍼티 + 프로파일 allowlist + 기동 assert 로 격리</b>되어
     * 설정만으로 운영에 새지 않는다. 상세 근거는 {@link VlmUrlPolicy} 참조.
     *
     * <p>enabled=false (기본값) 면 검증 생략 — 미연동 환경 영향 0.
     *
     * <p><b>local/dev 완화의 배경</b>: 이 두 프로파일의 VLM 위탁 대상은 목업 벤더 서버(mock-server)다 —
     * TLS 미지원 평문 http 이고 호스트도 컨테이너 내부 이름({@code klid-mock-server})이라, 운영용 강제를
     * 그대로 적용하면 <b>빈 생성 실패로 애플리케이션이 기동조차 못 한다</b>(2026-07-25 로컬 배선 시도 시
     * 실측·원복). 설정 누락(빈 값)·placeholder 호스트·링크로컬/메타데이터 대역 차단은 완화 대상이 아니다.
     * stg/prd·<b>프로파일 미지정</b>·<b>{@code ENV=stg|prd} 표식</b>은 기존 강제를 유지한다(fail-closed).
     */
    @Bean(name = "vlmWebClient")
    public WebClient vlmWebClient(
            @Value("${vlm.client.url:http://localhost:9400}") String baseUrl,
            @Value("${vlm.client.token:}") String token,
            @Value("${vlm.client.enabled:false}") boolean enabled,
            VlmUrlPolicy urlPolicy) {
        if (enabled) {
            urlPolicy.validate(baseUrl);
        }
        WebClient.Builder b = WebClient.builder().baseUrl(baseUrl);
        if (token != null && !token.isBlank()) {
            // 평문 http 에 Bearer 토큰이 실리면 네트워크에 그대로 노출된다 (CWE-319) — 경고만, 값 미출력.
            urlPolicy.warnIfTokenOnCleartext(baseUrl, token);
            b.defaultHeader("Authorization", "Bearer " + token);
        }
        return b.build();
    }

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
     */
    @Bean(name = "controlNotifyWebClient")
    public WebClient controlNotifyWebClient(
            @Value("${authoring.control-notify.url:http://localhost:8090}") String baseUrl,
            @Value("${authoring.control-notify.token:}") String token,
            @Value("${authoring.control-notify.enabled:false}") boolean enabled) {
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);
        if (token != null && !token.isBlank()) {
            if (baseUrl != null && baseUrl.trim().toLowerCase(java.util.Locale.ROOT).startsWith("http://")) {
                // 평문 http 에 인증 토큰이 실리면 네트워크에 그대로 노출된다(CWE-319) — 값 미출력.
                log.warn("[ControlNotify] 평문 http 엔드포인트에 인증 토큰이 설정되어 있습니다 — "
                        + "토큰이 네트워크에 평문 노출됩니다(CWE-319). 운영에서는 HTTPS 필수. tokenLength={}",
                        token.trim().length());
            }
            builder.defaultHeader(CONTROL_NOTIFY_TOKEN_HEADER, token.trim());
        } else if (enabled) {
            log.warn("[ControlNotify] 통지가 활성화됐으나 인증 토큰(authoring.control-notify.token)이 "
                    + "비어 있습니다 — 관제 SPI 가 {} 를 요구하면 전 통지가 401 로 거부됩니다.",
                    CONTROL_NOTIFY_TOKEN_HEADER);
        }
        return builder.build();
    }

}
