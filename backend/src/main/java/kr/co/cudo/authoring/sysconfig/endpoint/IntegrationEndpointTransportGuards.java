package kr.co.cudo.authoring.sysconfig.endpoint;

import kr.co.cudo.authoring.common.util.SafeUrl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 연동 주소를 <b>운영 화면에서 바꿨을 때</b> 전송 계층이 따라가지 못하는 두 가지를 막는 필터 모음 (R11).
 *
 * <p>{@link IntegrationEndpointExchangeFilter} 가 URL <b>만</b> 바꾸기 때문에, 빈 생성 시점에 고정된
 * 나머지 전송 구성(자격증명 헤더 · TLS)은 그대로 남아 새 대상에 따라간다. 두 필터 모두
 * <b>URL 재작성 필터 뒤에</b> 등록해야 재작성된 최종 URL 을 본다.
 */
@Slf4j
public final class IntegrationEndpointTransportGuards {

    private IntegrationEndpointTransportGuards() {
    }

    /**
     * ★ <b>호스트가 배포 기본값과 다르면 자격증명 헤더를 떼고 경고한다</b>.
     *
     * <h3>왜 필요한가</h3>
     * <p>{@code WebClient.defaultHeader(...)} 는 빈 생성 시점에 고정되고 URL 재작성 필터는 헤더를 건드리지
     * 않는다. 즉 <b>주소를 바꾸면 원 수신처에 발급된 토큰이 그대로 새 호스트로 전송</b>된다(CWE-522) —
     * 화면에서 주소만 바꾸면 임의의 수신처로 자격증명이 흘러간다.
     *
     * <h3>왜 "떼는" 쪽을 고르나 (PM 결정)</h3>
     * <p>그 토큰은 원 수신처에 발급된 값이라 <b>다른 호스트에서는 어차피 무효</b>다. 떼면 상대가 401 로
     * <b>시끄럽게 실패</b>해 운영자가 즉시 알아차리고, 안 떼면 <b>조용히 유출</b>된다. 전송 자체를 막지
     * 않는 이유는, 인증을 요구하지 않는 수신처(목 서버 등)로의 정당한 전환이 실재하기 때문이다.
     *
     * <h3>판정 축은 <b>호스트</b>다</h3>
     * <p>포트·경로 차이만으로 떼지 않는다 — 같은 서버의 경로/포트 변경(리버스 프록시 앞단 이동 등)까지
     * 자격증명이 끊기면 정당한 구성 변경이 깨진다. 호스트를 확인할 수 없으면 "다르다"로 낮춘다
     * (fail-secure). ⚠ 이것은 <b>주소 대역 판정이 아니다</b> — 사설·루프백 주소로 바꾸는 것 자체는
     * 허용되며, 같은 호스트면 토큰도 그대로 간다.
     *
     * <p>경고에는 <b>토큰 값도 주소 원문도 싣지 않는다</b>(CWE-532) — 대상 이름과 "자격증명을 붙이지
     * 않았다"는 사실만 남긴다. 로그 폭주를 막기 위해 1회만 출력한다.
     *
     * @param endpoint    대상 연동(로그 표기용)
     * @param bootDefault 배포 기본값 — 자격증명이 발급된 원 수신처
     * @param headerName  떼어낼 자격증명 헤더명
     */
    public static ExchangeFilterFunction stripCredentialOnHostChange(IntegrationEndpoint endpoint,
                                                                    String bootDefault,
                                                                    String headerName) {
        AtomicBoolean warned = new AtomicBoolean(false);
        return (request, next) -> {
            if (SafeUrl.sameHost(request.url().toString(), bootDefault)) {
                return next.exchange(request);
            }
            if (warned.compareAndSet(false, true)) {
                log.warn("[IntegrationEndpoint] 설정된 {} 주소의 호스트가 배포 기본값과 달라 "
                                + "인증 헤더({})를 붙이지 않습니다 — 그 자격증명은 원 수신처에 발급된 값이라 "
                                + "다른 호스트에서는 유효하지 않습니다. 새 수신처가 인증을 요구하면 "
                                + "해당 환경변수를 새 값으로 다시 배포해야 합니다.",
                        endpoint.displayName(), headerName);
            }
            return next.exchange(ClientRequest.from(request)
                    .headers(h -> h.remove(headerName))
                    .build());
        };
    }

    /**
     * 설정한 주소의 <b>스킴</b>이 배포 기본값과 다르면 1회 경고한다({@code KpstWebClientConfig} 와 대칭).
     *
     * <p>특히 {@code https → http} 다운그레이드는 <b>자격증명·본문이 평문으로 나가는</b> 상태(CWE-319)가
     * 되는데, 이 전환은 화면에서 주소 한 줄로 일어나므로 흔적이 남아야 한다. 반대 방향
     * ({@code http → https})도 커넥터의 TLS 구성이 기동 시점 값으로 고정돼 따라가지 않으므로 함께 알린다.
     *
     * <p><b>값을 출력하지 않는다</b> — 스킴 두 개만 남기고 호스트·토큰은 싣지 않는다.
     */
    public static ExchangeFilterFunction warnOnSchemeChange(IntegrationEndpoint endpoint,
                                                            String bootDefault) {
        AtomicBoolean warned = new AtomicBoolean(false);
        String bootScheme = schemeOf(bootDefault);
        return (request, next) -> {
            String requestScheme = request.url().getScheme();
            if (requestScheme != null && !requestScheme.equalsIgnoreCase(bootScheme)
                    && warned.compareAndSet(false, true)) {
                log.warn("[IntegrationEndpoint] 설정된 {} 주소의 스킴이 배포 기본값과 다릅니다 — "
                                + "평문(http)이면 전송 내용이 그대로 노출되고, TLS 구성은 기동 시점 값으로 "
                                + "고정되어 따라가지 않습니다. 요청 스킴={} / 기동 시점 스킴={}",
                        endpoint.displayName(), requestScheme, bootScheme);
            }
            return next.exchange(request);
        };
    }

    private static String schemeOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String scheme = java.net.URI.create(url.trim()).getScheme();
            return scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
