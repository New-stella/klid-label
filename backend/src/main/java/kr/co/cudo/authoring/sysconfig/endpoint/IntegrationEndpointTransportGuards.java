package kr.co.cudo.authoring.sysconfig.endpoint;

import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.util.SafeUrl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.net.URI;
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
     * <h3>★ 예외 — 원장에서 고른 장비로 <b>핀된</b> 요청은 자격증명을 유지한다 (HIGH · PM 결정)</h3>
     * <p>위 판정은 <b>수신처가 하나</b>라는 전제 위에 있다. 그런데 한 벤더가 <b>여러 장비로 이중화</b>되면
     * 배포 기본값과 같은 호스트는 <b>최대 하나</b>라, 나머지 장비로 가는 <b>정상 요청이 전부</b>
     * 자격증명을 잃는다 — 벤더는 401 로 거부하고 그 실패는 <b>비재시도 확정 실패</b>라 그 영상은 결과를
     * 영영 얻지 못한다. 「운영자가 <b>다른 시스템</b>으로 주소를 바꿨다」에는 맞는 전제가
     * 「<b>같은 벤더의 두 번째 장비</b>」에는 맞지 않는다.
     *
     * <p>그래서 {@link IntegrationEndpointExchangeFilter#EXPLICIT_TARGET_ATTRIBUTE} 표식이 붙은 요청은
     * <b>의도된 수신처</b>로 보고 헤더를 그대로 둔다. 표식은 <b>노드 원장에서 고른 절대 목적지</b>에만
     * 붙는다(그 불변식을 무는 시험이 있다 — {@code ExplicitTargetMarkerCallSiteGuardTest}). 표식이 없는
     * <b>임의의</b> 호스트 변경에는 종전대로 헤더를 뗀다.
     *
     * <p>⚠⚠ <b>이 필터를 다른 연동으로 복사할 때 이 예외까지 함께 가져가라</b> — 지금 이 사슬을 갖지
     * 않은 연동이 실재하고(예: 추론 축 클라이언트는 주소 재작성만 있다), 그쪽에 나중에 인증이 붙으면
     * 이 코드를 참조할 가능성이 높다. <b>예외 없는 형태(배포 기본값 하나와만 비교)를 복사하면 같은
     * 결함이 그대로 옮겨붙는다</b> — 장비가 둘 이상인 순간 절반의 요청이 무인증으로 나간다.
     *
     * <p>⚠ <b>미해결(별건)</b>: 벤더가 <b>장비마다 다른 토큰</b>을 발급한다면 이 예외로는 부족하다 —
     * A 장비의 토큰이 B 장비로 간다. 장비별 자격증명은 원장 스키마·설계 변경이 필요한 별개 축이다.
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
            // 원장에서 고른 장비로 핀된 요청 — 의도된 수신처이므로 자격증명을 유지한다(위 §예외).
            if (request.attribute(IntegrationEndpointExchangeFilter.EXPLICIT_TARGET_ATTRIBUTE).isPresent()) {
                return next.exchange(request);
            }
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

    /**
     * ★ <b>최종 URL 에 호스트가 없으면 전송하지 않는다</b> — "미연동 = 아무 데도 안 보낸다" 를 실제로 성립시킨다.
     *
     * <h3>무엇이 어긋나 있었나 (실측)</h3>
     * <p>연동 주소가 비어 있으면 {@code WebClient.baseUrl("")} + 상대 URI 가 되는데, 그러면 요청이
     * <b>보내지지 않는 것이 아니라 loopback 의 80 포트로 나간다</b>({@code Connection refused:
     * /[0:0:0:0:0:0:0:1]:80} 로 관측). 온프렘은 같은 호스트에 프론트 웹서버를 두므로 80 이 열려 있으면
     * <b>연결이 실제로 수신되고 접근 로그에 요청 경로가 남는다</b>. 위탁 바디에는 비식별 영상의 절대
     * 경로와 콜백 주소가 실리므로, "미연동이면 아무 데도 안 보낸다" 는 전제가 거짓이었다.
     *
     * <h3>왜 빈 생성 시점이 아니라 호출 시점인가</h3>
     * <p>주소는 {@link IntegrationEndpointExchangeFilter} 가 <b>호출 시점에</b> 다시 읽어 재작성한다.
     * 즉 설정이 비어 있어도 운영 화면 override 가 있으면 정상 대상이 된다. 판정은 반드시
     * <b>재작성된 최종 URL</b> 을 봐야 하므로 이 필터는 재작성 필터 <b>뒤에</b> 등록한다.
     *
     * <h3>실패의 성질</h3>
     * <p>{@link NonRetryableExternalException} 이다 — 주소가 없다는 것은 재전송해도 결과가 같은
     * <b>결정적</b> 실패라 재시도·서킷 집계에서 제외돼야 한다(그 두 축의 {@code ignore-exceptions} 에
     * 이미 등록돼 있다). 새 실패 경로를 만들지 않으므로 기존 확정 실패 기록으로 그대로 흐른다.
     *
     * <p>메시지·로그에 <b>주소도 경로도 토큰도 싣지 않는다</b>(CWE-209/532) — 대상 이름만 남긴다.
     */
    public static ExchangeFilterFunction requireResolvedHost(IntegrationEndpoint endpoint) {
        return requireUsableAddress(endpoint, null);
    }

    /**
     * ★ <b>배포 설정값이 정책을 위반했으면 그 연동으로 나가지 않는다</b> (2026-09-03 확정, 구속).
     *
     * <h3>기동이 아니라 여기서 막는다</h3>
     * <p>연동 주소 검증은 지금까지 <b>빈 생성 시점</b>에 걸려 있어, 한 연동의 설정 실수가
     * <b>저작 업무 전체를 세웠다</b>. 온프렘 배포에서 그 대가는 실수보다 크다. 그래서 판정
     * ({@code ExternalUrlPolicy})은 그대로 두고 <b>적용 시점만</b> 여기로 옮겼다 — 무엇을 막는지는
     * 그대로이고 <b>언제 막는지</b>만 바뀐다.
     *
     * <p>거부된 주소는 {@code ExternalEndpointAddress} 가 <b>빈 base-url</b> 로 낮춰 두므로, 여기
     * 도달한 요청의 URL 에는 호스트가 없다. 즉 이 가드는 <b>「주소 없음」과 「주소 부적합」을 한
     * 자리에서</b> 처리한다 — 두 사유 모두 결과는 같다(아무 데도 보내지 않는다).
     *
     * <h3>운영 화면 override 는 살린다</h3>
     * <p>이 필터는 URL 재작성 필터 <b>뒤</b>에 온다. 배포 기본값이 거부됐더라도 운영 화면에 정상
     * 주소가 저장돼 있으면 재작성 결과에 호스트가 있으므로 <b>그대로 통과</b>한다 — 잘못 배포된
     * 주소를 재기동 없이 되돌릴 수 있다.
     *
     * <h3>실패의 성질·노출</h3>
     * <p>{@link NonRetryableExternalException} 이다 — 설정을 고쳐야 풀리는 <b>결정적</b> 실패라
     * 재시도·서킷 집계에서 제외된다(두 축의 {@code ignore-exceptions} 에 이미 등록돼 있다).
     * 메시지에는 <b>대상 이름과 사유 분류만</b> 싣고 주소·호스트·자격증명은 싣지 않는다
     * (CWE-209/532). 설정 키는 <b>서버 로그에만</b> 남긴다 — 내부 속성명은 응답에 실을 정보가 아니다.
     *
     * @param endpoint       대상 연동
     * @param rejectionLabel 배포 설정값의 거부 사유({@code ExternalEndpointAddress#rejectionLabel()}).
     *                       {@code null} 이면 "설정되지 않음" 으로 다룬다.
     */
    public static ExchangeFilterFunction requireUsableAddress(IntegrationEndpoint endpoint,
                                                              String rejectionLabel) {
        return (request, next) -> {
            URI url = request.url();
            String host = url == null ? null : url.getHost();
            if (host != null && !host.isBlank()) {
                return next.exchange(request);
            }
            return Mono.error(unusableAddress(endpoint, rejectionLabel));
        };
    }

    /**
     * 전송 거부 예외를 만든다 — <b>필터를 걸 수 없는 저수준 경로</b>(reactor-netty {@code HttpClient})도
     * 같은 실패를 내도록 여기서 소유한다. 문구가 갈리면 같은 사유가 경로마다 다르게 기록된다.
     */
    public static NonRetryableExternalException unusableAddress(IntegrationEndpoint endpoint,
                                                                String rejectionLabel) {
        if (rejectionLabel == null || rejectionLabel.isBlank()) {
            log.error("[IntegrationEndpoint] {} 연동 주소가 설정되지 않아 요청을 보내지 않았습니다 — "
                            + "주소가 비면 상대 URI 가 되어 loopback:80 으로 나가므로 전송 자체를 막는다. 설정키={}",
                    endpoint.displayName(), endpoint.configKey());
            return new NonRetryableExternalException(
                    endpoint.displayName() + " 연동 주소가 설정되지 않아 요청을 보내지 않았습니다.");
        }
        log.error("[IntegrationEndpoint] {} 연동 주소 설정값이 유효하지 않아 요청을 보내지 않았습니다 — "
                        + "설정을 고쳐야 풀리는 실패입니다(재시도 대상 아님). 설정키={} 사유={}",
                endpoint.displayName(), endpoint.configKey(), rejectionLabel);
        return new NonRetryableExternalException(
                endpoint.displayName() + " 연동 주소 설정값이 유효하지 않아 요청을 보내지 않았습니다 ("
                        + rejectionLabel + ").");
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
