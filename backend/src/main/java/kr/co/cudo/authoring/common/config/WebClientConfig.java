package kr.co.cudo.authoring.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

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

}
