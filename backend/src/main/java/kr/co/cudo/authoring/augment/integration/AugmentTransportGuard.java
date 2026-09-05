package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.config.GenAiIntegrationWiringGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 증강 위탁 전송 계층 가드 — <b>보낼 수 없는 상태면 아무 데도 보내지 않는다</b>.
 *
 * <p>두 축을 막는다: <b>주소</b>가 정해지지 않았거나 정책을 위반한 경우
 * ({@link #requireUsableAddress(String)}), 그리고 주소가 멀쩡해도 <b>결과를 되받을 수 없는</b>
 * 경우({@link #requirePairedCallbackIntake(String)}). 둘 다 <b>기동을 막던 것을 위탁 시점으로</b>
 * 옮긴 것이다.
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
 *
 * @design ADR-062
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

    /**
     * ★★ <b>결과를 되받을 수 없으면 위탁을 걸지 않는다</b> (2026-09-03 사용자 확정, 구속).
     *
     * <h3>무엇이 짝인가</h3>
     * <p>증강은 <b>위탁</b>과 <b>콜백 수신</b>이 모두 열려야 완결된다. 위탁 주소는 주입돼 있는데 콜백
     * IP allowlist 가 비어 있으면(= 전면 차단) 위탁은 202 로 나가고 결과는 <b>전건 403</b> 이라 그
     * 증강이 <b>PENDING 으로 영구 고착</b>된다(만료 스윕 없음). 판정은
     * {@code GenAiIntegrationWiringGuard} 가 소유하며 <b>여기서 규칙을 다시 쓰지 않는다</b>.
     *
     * <h3>기동이 아니라 여기서 막는다 — 그리고 여기서 막아야 보호가 남는다</h3>
     * <p>구 배선은 이 짝이 안 맞으면 <b>기동 자체를 실패</b>시켰다. 그래서 주소를 제대로 넣은
     * <b>정상 배포가 다른 설정 한 줄이 비었다는 이유로</b> 뜨지 못했다. 그 판정을 이 자리로 옮긴다.
     *
     * <p>⚠⚠ <b>기동만 통과시키고 이 거부를 넣지 않으면 보호가 사라진다</b> — 이 가드가 지키는 것은
     * <b>위탁을 건 뒤 열리는 결과 수신구</b>이므로, 위탁을 그대로 내보내면 「되받지 못할 위탁」이
     * 실제로 나간다. <b>기동 통과와 위탁 거부는 한 쌍</b>이다.
     *
     * <h3>★ 위탁 요청만 거부한다 — 조회·취소는 그대로 나간다</h3>
     * <p>대상은 <b>job 생성</b>({@code POST} {@code /api/genai/jobs}) 하나다. 상태·결과 조회와 취소는
     * <b>새 수신구를 열지 않으며</b>, 오히려 이미 걸려 있는 위탁을 <b>회수·정리하는 경로</b>라 함께
     * 막으면 짝이 어긋난 배포에서 <b>고착된 job 을 취소할 수단까지 잃는다</b>.
     *
     * <p>실패의 성질과 노출 수준은 {@link #requireUsableAddress(String)} 와 같다 —
     * {@link NonRetryableExternalException}(설정을 고쳐야 풀리는 결정적 실패라 재시도·서킷 집계에서
     * 제외된다)이고, 메시지에는 <b>대상 이름과 사유 분류만</b> 싣는다. 설정 키·대역 값은 서버
     * 로그에만 남긴다(CWE-209/532).
     *
     * @param rejectionLabel 짝 맞춤 거부 사유({@code GenAiIntegrationWiringGuard#commissionRejectionLabel()}).
     *                       {@code null}/공백이면 짝이 맞다는 뜻이라 아무것도 하지 않는다.
     * @design ADR-062
     * @design INT-006
     */
    public static ExchangeFilterFunction requirePairedCallbackIntake(String rejectionLabel) {
        return (request, next) -> {
            if (rejectionLabel == null || rejectionLabel.isBlank() || !isCommission(request)) {
                return next.exchange(request);
            }
            log.error("[Augment] 위탁 ↔ 콜백 수신 배선의 짝이 맞지 않아 위탁을 보내지 않았습니다 — "
                            + "위탁은 나가지만 결과 콜백이 전건 차단되어 증강이 영구 고착됩니다. "
                            + "설정키={} 사유={}",
                    GenAiIntegrationWiringGuard.KEY_ALLOWLIST, rejectionLabel);
            return Mono.error(new NonRetryableExternalException(
                    DISPLAY_NAME + " 위탁 ↔ 콜백 수신 배선의 짝이 맞지 않아 요청을 보내지 않았습니다 ("
                            + rejectionLabel + ")."));
        };
    }

    /**
     * 이 요청이 <b>새 위탁을 거는</b> 요청인가 — job 컬렉션에 대한 {@code POST} 하나다.
     *
     * <p>경로는 {@code endsWith} 로 본다 — base-url 에 컨텍스트 경로가 붙어도 성립해야 하고,
     * 하위 경로({@code .../{jobId}} · {@code .../{jobId}/results} · {@code .../{jobId}/cancel})는
     * 여기에 걸리지 않는다.
     */
    private static boolean isCommission(ClientRequest request) {
        if (!HttpMethod.POST.equals(request.method())) {
            return false;
        }
        String path = request.url().getPath();
        return path != null && path.endsWith(HttpExternalAugmentClient.JOBS_PATH);
    }
}
