package kr.co.cudo.authoring.augment.integration;

import io.netty.channel.ChannelOption;
import kr.co.cudo.authoring.common.client.ExternalCallLoggingFilter;
import kr.co.cudo.authoring.common.config.AugmentUrlPolicy;
import kr.co.cudo.authoring.common.config.ExternalEndpointAddress;
import kr.co.cudo.authoring.common.config.GenAiIntegrationWiringGuard;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpoint;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointExchangeFilter;
import kr.co.cudo.authoring.sysconfig.endpoint.IntegrationEndpointResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * <h3>★ 주소가 비어 있어도 기동한다 — 막는 것은 기동이 아니라 전송이다 (2026-09-03 확정, 구속)</h3>
 * <p>구 동작은 {@code base-url} 이 비면 정책의 "빈값 거부" 로 <b>기동을 막았다</b>. 그래서 벤더 주소가
 * 아직 없다는 이유만으로 개발·스테이징·운영이 전부 <b>미연동 모드로 도망갔고</b>, 그 우회가 「증강
 * 위탁이 실제로 나간 적이 한 번도 없는」 상태를 굳혔다. <b>fail-closed 를 잘못된 자리에 건 것</b>이다 —
 * 막아야 할 것은 <b>잘못된 곳으로 나가는 것</b>이지 기동이 아니며, <b>빈 주소는 「아직 안 정해짐」이지
 * 「위험함」이 아니다</b>.
 *
 * <p>그래서 <b>주소가 있을 때만</b> 정책을 태우고, 미주입이면 {@link AugmentTransportGuard} 가
 * <b>전송 자체를 막는다</b>(시계열 위탁 클라이언트와 같은 형태 — 그쪽이 선례다). 네 축이 어디서
 * 걸리는지는 아래 표가 정본이다.
 *
 * <p>★ <b>2026-09-03 재확정 — 네 축도 기동에서 걷어냈다.</b> 빈값 하나만 옮겼던 것을 <b>전 축</b>으로
 * 넓혔다. 하나만 고치면 다음 배포에서 다른 축·다른 연동이 같은 일을 낸다.
 *
 * <table border="1">
 *   <caption>주소 판정 축과 걸리는 지점</caption>
 *   <tr><th>축</th><th>걸리는 지점</th></tr>
 *   <tr><td>{@code http}/{@code https} 외 스킴</td><td>기동은 정상 · <b>위탁 시도 시점에 실패</b></td></tr>
 *   <tr><td>파싱 불가</td><td>기동은 정상 · <b>위탁 시도 시점에 실패</b></td></tr>
 *   <tr><td>placeholder/예제 호스트</td><td>기동은 정상 · <b>위탁 시도 시점에 실패</b></td></tr>
 *   <tr><td>예약 대역(메타데이터·링크로컬·ULA·CGNAT·멀티캐스트)</td><td>기동은 정상 · <b>위탁 시도 시점에 실패</b></td></tr>
 *   <tr><td><b>빈값(미주입)</b></td><td>기동은 정상 · <b>위탁 시도 시점에 실패</b></td></tr>
 * </table>
 *
 * <h3>기본값에 localhost 를 두지 않는다</h3>
 * <p>{@code base-url} 기본값이 {@code http://localhost:9400} 이면 <b>목업 주소가 운영 형상에 상주</b>한다
 * (환경변수 미설정 배포가 조용히 목업을 향해 기동). 공통 기본값은 빈 문자열이며 목업 주소는
 * local/dev 프로파일 yml 과 compose 가 <b>명시 주입</b>한다.
 *
 * <h3>★ 조건부 활성이 아니다 — 연동이 유일한 형상이다</h3>
 * <p>구 동작은 {@code authoring.augment.external.mode=http} 일 때만 이 설정이 활성이었다. 그
 * <b>미연동 모드 토글 축은 폐기</b>됐다(설정 키·판정기·전용 클라이언트 구현까지). 이제 이 빈은 항상
 * 만들어지고, 「연동됐는가」는 {@link AugmentExternalLinkPolicy}(= 주소 주입 여부)가 판정한다.
 *
 * <h3>★ 주소 축 말고 <b>짝 맞춤 축</b>도 여기서 막는다 (2026-09-03 확정, 구속)</h3>
 * <p>위탁 주소가 정상이어도 <b>콜백 IP allowlist 가 비어 있으면</b> 결과를 되받을 수 없어 그 증강이
 * 영구 고착된다. 그 판정({@code GenAiIntegrationWiringGuard})도 <b>기동을 막던 것을 위탁 시점으로</b>
 * 옮겼다 — 주소 축과 <b>다른 축</b>이지만 「보호가 필요한 순간은 기동이 아니다」라는 근거가 같다.
 * ⚠ 이 필터를 떼면 <b>되받지 못할 위탁이 실제로 나간다</b>(= 그 가드가 없어진 것과 같다).
 *
 * <p>★★ <b>그 판정도 요청 시점 재평가다 (2026-09-08)</b> — 주소가 화면에서 바뀌므로 <b>값</b>이 아니라
 * <b>판정</b>({@code wiringGuard::commissionRejectionLabel})을 넘긴다. 기동 시점 값으로 되돌리면
 * 배포 기본값이 빈 배포에서 「미연동 → 요구 없음」으로 계산돼 <b>허용 대역이 비었는데도 위탁이
 * 나간다</b>. 짝 판정 필터는 재작성 필터 <b>뒤</b>여야 저장된 주소를 본다.
 *
 * <h3>★ 주소는 빈 생성 시점에 고정되지 않는다 — 저장하면 다음 호출부터 반영된다 (2026-09-08)</h3>
 * <p>{@link IntegrationEndpointExchangeFilter} 를 달아 <b>매 호출 시점</b>에 설정 override 를 다시
 * 읽는다. 조달 순서는 다른 연동과 <b>같다</b> — 설정에 값이 있으면 그것, 없으면 배포 기본값
 * ({@link IntegrationEndpointResolver}). override 가 없으면 필터는 <b>아무것도 하지 않으므로</b>
 * 기존 형상·시험 동작은 그대로다.
 *
 * <p>⚠ <b>구 서술 폐기(2026-09-08)</b> — <i>"증강은 운영 화면 주소 override 대상이 아니다"</i>.
 * {@link IntegrationEndpoint#AUGMENT} 가 등록되면서 화면에 <b>외부 증강 벤더 칸이 생겼는데</b> 이
 * 배선만 빠져 있어, 저장은 되지만 위탁은 계속 배포 기본값으로 나갔다 — <b>오류도 경고도 없는</b>
 * 죽은 칸이었다(같은 라운드가 바로 그 이유로 화면에서 칸 둘을 걷어냈다). 배선을 떼지 말 것.
 *
 * <p>★ <b>필터 순서가 계약의 일부다</b>: 재작성 필터가 전송 가드들보다 <b>앞</b>이어야 가드가
 * <b>최종 URL</b>을 본다. 뒤로 옮기면 배포 기본값이 비었거나 거부된 배포에서 <b>정상 override 로
 * 가는 위탁까지</b> 「주소 없음」으로 막힌다(= 저장이 다시 무효가 된다).
 *
 * <p>base-url 은 <b>서버 설정값</b>만 사용하며 사용자 입력으로 호스트를 구성하지 않는다. 인증 헤더는
 * 붙이지 않는다 — 명세서·목 서버 모두 인증 미구현이 확정 계약이다.
 *
 * <h3>★ 외부향 빈은 호출 로그 필터를 맨 마지막에 단다</h3>
 * <p>{@link kr.co.cudo.authoring.common.client.ExternalCallLoggingFilter} — 재작성·전송 가드·자격증명
 * 필터를 거친 <b>실제 대상</b>과 결과 코드·소요 시간, 오류 응답 사유를 서버 로그에 남긴다.
 *
 * @design ADR-046
 * @design ADR-062
 * @design API-069
 * @design NFR-038
 */
@Slf4j
@Configuration
public class AugmentApiWebClientConfig {

    /**
     * 응답(JSON) 버퍼 상한. 파일 본문은 주고받지 않으므로(경로만 교환) 작게 잡는다.
     *
     * <p>Phase 7-A2 재검토 — 최대 응답은 202 ACK 가 아니라 §4.3 결과 조회다. 계약 상한인 100건 ×
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
            AugmentUrlPolicy urlPolicy,
            GenAiIntegrationWiringGuard wiringGuard,
            IntegrationEndpointResolver endpointResolver) {
        // ★ 주소가 어떤 상태여도 기동한다 — 판정은 그대로 태우되 결과를 <들고 있다가> 전송 시점에 쓴다.
        //   구 배선은 여기서 예외를 던져(빈 생성 실패) 기동을 막았다. 규칙은 그대로이고 시점만 옮겼다.
        ExternalEndpointAddress address = ExternalEndpointAddress.of(
                AugmentExternalLinkPolicy.KEY_BASE_URL, baseUrl, urlPolicy.inspect(baseUrl));
        // 거부·미주입은 빈 문자열로 정규화된다 — null 을 그대로 넘기면 실패가 NPE 로 나와 판독이 어렵다.
        String base = address.baseUrl();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(RESPONSE_TIMEOUT);
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs((ClientCodecConfigurer c) -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
        return WebClient.builder()
                .baseUrl(base)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                // ★ 저장된 주소를 <매 호출 시점에> 다시 읽어 URL 을 재작성한다 — 다른 연동 4종과 같은
                //   배선·같은 조달 순서(설정 값이 있으면 그것, 없으면 배포 기본값)다. 이 필터가 없으면
                //   화면에서 주소를 저장해도 오류도 경고도 없이 <배포 기본값으로만> 위탁이 나간다
                //   (「저장은 되는데 실동작 0건」 — 이 저장소가 반복해 겪은 형태다).
                //   ★ 가드보다 <앞>이어야 한다 — 뒤에 두면 가드가 재작성 전 URL 을 보고, 배포 기본값이
                //     비었거나 거부된 배포에서 <정상 override 로 가는 위탁까지> 막힌다.
                .filter(IntegrationEndpointExchangeFilter.of(
                        IntegrationEndpoint.AUGMENT, base, endpointResolver))
                // ★ 미연동(주소 미주입)이면 <전송 자체>를 막는다 — 빈 base-url 은 상대 URI 가 되어
                //   loopback:80 으로 실제 연결이 나가고, 그 요청 바디에는 비식별 프레임 절대경로와
                //   콜백 주소가 실린다(온프렘은 같은 호스트에 웹서버가 있어 접근 로그에 남는다).
                .filter(AugmentTransportGuard.requireUsableAddress(address.rejectionLabel()))
                // ★ 주소가 멀쩡해도 <결과를 되받을 수 없으면> 위탁을 걸지 않는다 — 위탁은 202 로
                //   나가는데 콜백이 전건 403 이면 그 증강이 PENDING 으로 영구 고착된다(만료 스윕 없음).
                //   조회·취소는 대상이 아니다(고착된 job 을 정리할 경로까지 막으면 안 된다).
                //   ★ 값이 아니라 <판정>을 넘긴다 — 기동 시점에 계산된 값을 넘기면 운영 화면에서
                //     저장한 주소를 보지 못해, 배포 기본값이 빈 배포에서 「미연동 → 요구 없음」으로
                //     계산돼 허용 대역이 비었는데도 위탁이 나간다(= 이 가드가 없어진 것과 같다).
                .filter(AugmentTransportGuard.requirePairedCallbackIntake(
                        wiringGuard::commissionRejectionLabel))
                // ★ 호출 로그는 맨 마지막 — 가드가 막은 요청은 가드가 이미 기록하므로 여기까지 오지 않는다.
                //   오류 응답의 벤더 사유를 남긴다(NFR-038).
                .filter(ExternalCallLoggingFilter.of(IntegrationEndpoint.AUGMENT.name(), true))
                .build();
    }
}
