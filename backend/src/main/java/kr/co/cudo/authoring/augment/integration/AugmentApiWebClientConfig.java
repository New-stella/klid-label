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
 * <p>과거에는 이 클래스가 <b>스키마만</b> 보는 자체 검증을 갖고 있었다. 그래서 미설정 placeholder
 * ({@code http://your-service.example.com})나 클라우드 메타데이터 주소({@code http://169.254.169.254/})가
 * 그대로 기동에 성공했고, 같은 값을 VLM 은 차단하는 <b>정책 비대칭</b>이 생겼다. 이제
 * {@link AugmentUrlPolicy}(→ {@code ExternalUrlPolicy}) 라는 <b>VLM·KPST 와 동일한 판정 원천</b>을 쓴다.
 *
 * <p>★ 그 판정이 보는 것은 <b>스킴({@code http}/{@code https})과 URL 형식뿐</b>이다
 * (2026-09-01 확정 — [@design ADR-046]).
 * <b>HTTPS 강제·사설망 차단·프로파일 게이팅·완화 플래그는 폐기</b>됐으므로 평문 http + 내부망 주소는
 * 전 프로파일에서 그대로 통과한다. 계속 차단되는 것은 비허용 스킴·빈값·placeholder 호스트와
 * 예약 대역(메타데이터·링크로컬·ULA·CGNAT·멀티캐스트)이다. 상세와 근거는 {@link AugmentUrlPolicy} 참조.
 *
 * <h3>기본값에 localhost 를 두지 않는다</h3>
 * <p>{@code base-url} 기본값이 {@code http://localhost:9400} 이면 <b>목업 주소가 운영 형상에 상주</b>한다
 * (환경변수 미설정 배포가 조용히 목업을 향해 기동). 기본값을 빈 문자열로 두고 정책의 "빈값 거부" 로
 * 부팅을 막는다 — local 은 {@code application-local.yml}/compose 가 명시 주입한다.
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

    /**
     * 응답(JSON) 버퍼 상한. 파일 본문은 주고받지 않으므로(경로만 교환) 작게 잡는다.
     *
     * <p>Phase 7-A2 재검토 — 최대 응답은 202 ACK 가 아니라 §4.5 결과 조회다. 계약 상한인 100건 ×
     * (generated_data_id 128 + output_file_path 500 + checksum 128 + media_type) ≈ 80KB 이므로 1MB 는
     * 항목당 ~10KB 의 {@code media_metadata} 여유를 남긴다(목은 mime_type/size_bytes 2개뿐). 넉넉하다.
     * 초과 시에는 조용히 절단되지 않고 {@code DataBufferLimitException} 으로 <b>실패</b>하므로,
     * 부분 결과를 전량으로 오인할 위험도 없다(fail-closed).
     */
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
