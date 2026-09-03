package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 증강 위탁 전송 계층 가드 — <b>주소가 정해지지 않았으면 아무 데도 보내지 않는다</b>.
 *
 * <h3>왜 필요한가 (실측)</h3>
 * <p>위탁 주소가 비어 있으면 {@code WebClient.baseUrl("")} + 상대 URI 가 되는데, 그러면 요청이
 * <b>보내지지 않는 것이 아니라 loopback 의 80 포트로 나간다</b>({@code Connection refused:
 * /[0:0:0:0:0:0:0:1]:80} 로 관측). 온프렘은 같은 호스트에 프론트 웹서버를 두므로 80 이 열려 있으면
 * <b>연결이 실제로 수신되고 접근 로그에 요청 경로가 남는다</b>. 증강 위탁 바디에는 <b>공유 저장소의
 * 비식별 프레임 절대경로가 다수</b> 실리므로 그 유출 표면은 시계열 위탁보다 넓다.
 *
 * <h3>기동이 아니라 여기서 막는다 (2026-09-03 사용자 확정, 구속)</h3>
 * <p>빈 주소는 <b>위험한 것이 아니라 아직 안 정해진 것</b>이다. 그래서 기동은 정상이고
 * <b>연동을 시도하는 시점</b>에 실패한다. 잘못된 주소(비허용 스킴·파싱 불가·placeholder 호스트·
 * 예약 대역)는 종전대로 {@code AugmentUrlPolicy} 가 <b>기동에서</b> 막는다 — 두 축을 섞지 말 것.
 *
 * <h3>실패의 성질</h3>
 * <p>{@link NonRetryableExternalException} 이다 — 주소가 없다는 것은 재전송해도 결과가 같은
 * <b>결정적</b> 실패라 재시도·서킷 집계에서 제외돼야 한다(두 축의 {@code ignore-exceptions} 에 이미
 * 등록돼 있다). 새 실패 경로를 만들지 않으므로 기존 확정 실패 기록으로 그대로 흐른다.
 *
 * <p>메시지·로그에 <b>주소도 경로도 싣지 않는다</b>(CWE-209/532) — 대상 이름만 남긴다.
 *
 * <p>⚠ 이 필터는 시계열 위탁이 쓰는
 * {@code IntegrationEndpointTransportGuards#requireResolvedHost} 와 <b>같은 판정</b>이다. 그 공용
 * 구현을 그대로 부르지 못하는 이유는 인자가 「운영 화면에서 주소를 바꿀 수 있는 연동」 열거값인데
 * <b>증강이 아직 그 열거에 없기 때문</b>이다(등록은 시스템 설정 도메인 소관 — 별건). 증강이 등록되면
 * 이 클래스를 지우고 공용 구현으로 갈아끼운다.
 */
@Slf4j
public final class AugmentTransportGuard {

    /** 로그·메시지에 쓰는 대상 이름 — 주소가 아니다. */
    static final String DISPLAY_NAME = "외부 증강 벤더";

    private AugmentTransportGuard() {
    }

    /** 최종 URL 에 호스트가 없으면 전송하지 않는다 — "미연동 = 아무 데도 안 보낸다" 를 성립시킨다. */
    public static ExchangeFilterFunction requireResolvedHost() {
        return requireUsableAddress(null);
    }

    /**
     * ★ <b>배포 설정값이 정책을 위반했으면 그 벤더로 나가지 않는다</b> (2026-09-03 확정, 구속).
     *
     * <p>구 배선은 비허용 스킴·파싱 불가·예시 호스트·예약 대역 <b>네 축</b>을 {@code AugmentUrlPolicy}
     * 가 <b>기동에서</b> 막았다. 그 축들도 여기로 옮겼다 — 규칙은 그대로이고 <b>언제 막는지</b>만
     * 바뀐다. 거부된 주소는 빈 base 로 낮춰지므로 최종 URL 에 호스트가 없고, 그래서 「주소 없음」과
     * 「주소 부적합」이 <b>한 자리</b>에서 처리된다(결과는 같다 — 아무 데도 보내지 않는다).
     *
     * <p>메시지에는 <b>대상 이름과 사유 분류만</b> 싣는다 — 주소·경로·자격증명은 싣지 않는다.
     *
     * @param rejectionLabel 배포 설정값의 거부 사유. {@code null} 이면 "설정되지 않음" 으로 다룬다.
     */
    public static ExchangeFilterFunction requireUsableAddress(String rejectionLabel) {
        return (request, next) -> {
            URI url = request.url();
            String host = url == null ? null : url.getHost();
            if (host != null && !host.isBlank()) {
                return next.exchange(request);
            }
            if (rejectionLabel == null || rejectionLabel.isBlank()) {
                log.error("[Augment] 위탁 주소가 설정되지 않아 요청을 보내지 않았습니다 — "
                        + "주소가 비면 상대 URI 가 되어 loopback:80 으로 나가므로 전송 자체를 막는다.");
                return Mono.error(new NonRetryableExternalException(
                        DISPLAY_NAME + " 연동 주소가 설정되지 않아 요청을 보내지 않았습니다."));
            }
            log.error("[Augment] 위탁 주소 설정값이 유효하지 않아 요청을 보내지 않았습니다 — "
                    + "설정을 고쳐야 풀리는 실패입니다(재시도 대상 아님). 설정키={} 사유={}",
                    "authoring.augment.external.base-url", rejectionLabel);
            return Mono.error(new NonRetryableExternalException(
                    DISPLAY_NAME + " 연동 주소 설정값이 유효하지 않아 요청을 보내지 않았습니다 ("
                            + rejectionLabel + ")."));
        };
    }
}
