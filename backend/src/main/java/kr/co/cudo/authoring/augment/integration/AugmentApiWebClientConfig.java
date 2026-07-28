package kr.co.cudo.authoring.augment.integration;

import io.netty.channel.ChannelOption;
import kr.co.cudo.authoring.common.config.AugmentUrlPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * 외부 증강(생성형 AI) API 연동용 WebClient — Phase 7-A1 신설 / DEV_FIX HIGH-1 정책 정합.
 *
 * <h3>base-url 검증은 공용 정책에 위임한다</h3>
 * <p>과거에는 이 클래스가 <b>스키마만</b> 보는 자체 검증을 갖고 있었다. 그래서 운영에서
 * {@code http://10.0.0.5:9400}(사설망 평문) · {@code http://169.254.169.254/}(클라우드 메타데이터) ·
 * {@code http://your-service.example.com}(미설정 placeholder) 이 모두 <b>기동에 성공</b>했고, 같은 값을
 * VLM 은 차단하는 <b>정책 비대칭</b>이 생겼다. 이제 {@link AugmentUrlPolicy}(→ {@code ProfileGatedUrlPolicy}
 * → {@code ExternalUrlPolicy}) 라는 <b>VLM 과 동일한 판정 원천</b>을 쓴다: prd/stg 는 HTTPS + 공인망 강제,
 * local/dev 는 전용 완화 플래그가 켜졌을 때만 목업(평문/사설) 허용.
 *
 * <h3>기본값에 localhost 를 두지 않는다</h3>
 * <p>{@code base-url} 기본값이 {@code http://localhost:9400} 이면 <b>완화 기본값이 운영 형상에 상주</b>한다
 * (환경변수 미설정 배포가 조용히 기동). 기본값을 빈 문자열로 두고 정책의 "빈값 거부" 로 부팅을 막는다 —
 * local 은 {@code application-local.yml}/compose 가 명시 주입한다.
 *
 * <h3>{@code mode=noop} 이면 빈을 만들지 않는다</h3>
 * <p>본 WebClient 의 유일한 소비자는 {@link HttpExternalAugmentClient} 이고 그 빈은 {@code mode=http}
 * 에서만 활성이다. 조건 없이 커넥터를 만들면 <b>위탁하지 않는 환경(prd, mode=noop)에서도</b> base-url
 * 설정을 요구하게 되어 무의미한 배포 제약이 된다. 두 빈의 활성 조건을 동일하게 맞춘다.
 *
 * <p>base-url 은 <b>서버 설정값</b>만 사용하며 사용자 입력으로 호스트를 구성하지 않는다. 인증 헤더는
 * 붙이지 않는다 — 명세서·목 서버 모두 인증 미구현이 확정 계약이다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "authoring.augment.external.mode", havingValue = "http",
        matchIfMissing = true)
public class AugmentApiWebClientConfig {

    /** 202 응답(JSON) 대비 버퍼 상한. 파일 본문은 주고받지 않으므로 작게 잡는다. */
    private static final int MAX_IN_MEMORY_BYTES = 1024 * 1024;

    /** 커넥션 수립 타임아웃 — 방화벽 drop(SYN 무응답) 시 무기한 블록 방지. */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** Netty 레벨 응답 타임아웃 안전망(클라이언트 {@code .timeout(...)} 보다 넉넉하게). */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    @Bean(name = "augmentApiWebClient")
    public WebClient augmentApiWebClient(
            @Value("${authoring.augment.external.base-url:}") String baseUrl,
            AugmentUrlPolicy urlPolicy) {
        // 정책 위반이면 IllegalStateException → 빈 생성 실패 → 기동 차단(fail-closed).
        urlPolicy.validate(baseUrl);
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(RESPONSE_TIMEOUT);
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs((ClientCodecConfigurer c) -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
        return WebClient.builder()
                .baseUrl(baseUrl.trim())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
