package kr.co.cudo.authoring.common.config;

import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * 외부 증강(생성형 AI) 위탁 base-url({@code authoring.augment.external.base-url}) 검증 —
 * <b>이 연동의 단일 판정 원천</b>.
 *
 * <h3>★ 무엇을 보는가 — 스킴과 형식뿐이다 (2026-09-01 확정, 구속) [@design ADR-046]</h3>
 * <p>연동 주소 정책의 확정 규칙은 <b>「주소 대역 차단을 하지 않는다 — 남는 검증은 스킴 {@code http}/
 * {@code https} + URL 형식뿐」</b>이며, 그 확정이 대상으로 명시한 연동에 <b>이 연동이 포함</b>된다.
 * 처음 결정할 때 목록에서 <b>증강만 빠져 있었고</b>, 그래서 시계열 위탁({@link VlmUrlPolicy})이 그
 * 정책으로 옮겨간 뒤 <b>증강만 옛 정책에 남아</b> 같은 성질의 외부 벤더 위탁인데 한쪽만 운영에서
 * 평문 주소를 거부하는 비대칭이 생겼다. 이 클래스는 원래 그 비대칭을 없애려고 만들어진 창구라,
 * <b>방향만 뒤집힌 채 되살아난 것</b>이었다. 그래서 판정을 시계열·비식별(KPST)과 <b>같은 축</b>
 * ({@link ExternalUrlPolicy#internalNetwork})으로 되돌린다.
 *
 * <h3>그대로 남는 것 / 없어진 것</h3>
 * <ul>
 *   <li><b>남는다</b> — {@code http}/{@code https} 외 스킴 거부({@code file://}·{@code ftp://}·
 *       {@code ws://}), 빈값·파싱 불가 거부, placeholder/예제 호스트 거부, 클라우드 메타데이터·
 *       링크로컬·ULA·CGNAT·멀티캐스트 등 <b>정상 위탁 대상이 될 수 없는 예약 대역</b> 거부
 *       (IMDS 는 어떤 환경에서도 위탁 대상이 아니다).</li>
 *   <li><b>없어졌다</b> — HTTPS <b>강제</b>와 사설망 차단, 그리고 그 둘을 프로파일로 가르던
 *       게이팅({@link ProfileGatedUrlPolicy})과 완화 플래그. 모두 <b>폐기된 조항</b>이다.
 *       되살리지 말 것.</li>
 * </ul>
 *
 * <p><b>https 는 계속 통과한다</b> — 나중에 보안 요구로 TLS 전환을 하면 <b>코드 변경 없이</b> 그대로
 * 동작한다(강제하지 않을 뿐 지원은 유지).
 *
 * <h3>왜 프로파일 게이팅에서 빠졌는가</h3>
 * <p>그 골격은 <b>운영 엄격 / 개발 완화</b>를 프로파일로 가르고 완화를 전용 플래그
 * ({@code authoring.augment.external.allow-insecure-url})로 격리하는 장치다. 확정 정책이 <b>운영에서도
 * 평문·사설을 허용</b>하는 쪽으로 정리되면서 가를 것이 없어졌고, 그 플래그는 <b>켜든 끄든 결과가 같은
 * 죽은 설정</b>이 되어 함께 걷어냈다(설정 파일·환경 템플릿 포함). 남겨 두면 "이걸 끄면 엄격해진다"는
 * 잘못된 기대를 준다.
 *
 * <p>⚠ 이 정합이 없으면 <b>운영에서 위탁을 켜는 순간 평문 벤더 주소에서 기동이 막힌다</b>. 이 주석이
 * 쓰일 당시에는 운영 기본값이 미연동 모드라 <b>설정 클래스 자체가 뜨지 않아 드러나지 않았을 뿐</b>
 * 이었고, 2026-09-03 에 그 모드 축이 폐기되면서 이 정합이 <b>실제로 쓰이기 시작했다</b> —
 * 정상 동작이니 놀라서 되돌리지 말 것.
 *
 * <h3>★ 빈값만은 기동에서 걸리지 않는다 (2026-09-03 확정, 구속)</h3>
 * <p>이 클래스는 종전대로 <b>빈값도 거부</b>하지만, <b>호출부</b>({@code AugmentApiWebClientConfig})가
 * <b>주소가 있을 때만</b> 검증을 태운다. 빈값은 <b>위험한 것이 아니라 아직 안 정해진 것</b>이라
 * 기동이 아니라 <b>연동을 시도하는 시점</b>에 실패해야 하기 때문이다(전송 차단은
 * {@code AugmentTransportGuard}). 비허용 스킴·파싱 불가·placeholder 호스트·예약 대역 <b>네 축은
 * 종전대로 기동에서 막는다</b> — 그건 잘못된 주소라 나가면 안 된다.
 *
 * <p>⚠ <b>인지·수용한 위험</b> — 이 커넥션으로 나가는 본문에는 공유 저장소의 비식별 프레임 절대경로가
 * 다수 실려 유출 표면이 시계열보다 넓다. 그럼에도 대역 차단을 두지 않는 근거는 <b>대역 차단으로 막히는
 * 위험이 아니기 때문</b>이다(공인망 주소로 바꾸면 그만이다) — 확정 정책의 본래 근거와 같다.
 */
@Component
public class AugmentUrlPolicy {

    /** 검증 대상 프로퍼티 — 예외 메시지가 이 이름을 그대로 노출해 설정 지점을 지목한다. */
    static final String PROP_URL = "authoring.augment.external.base-url";

    /** 로그 태그 — 주소·토큰이 아니라 연동 식별자다. */
    private static final String LOG_TAG = "AugmentUrl";

    /** 판정 자체는 공용 원천이 소유한다 — 여기서 규칙을 다시 쓰지 않는다. */
    private final ExternalUrlPolicy policy = ExternalUrlPolicy.internalNetwork(PROP_URL);

    /**
     * base-url 을 검증한다.
     *
     * @return https 면 {@code true}, http 면 {@code false} (호출자의 TLS 구성 분기용)
     * @throws IllegalStateException 정책 위반 시
     */
    public boolean check(String baseUrl) {
        return policy.check(baseUrl);
    }

    /** {@link #check(String)} 의 반환값이 필요 없는 호출부용 별칭. */
    public void validate(String baseUrl) {
        check(baseUrl);
    }

    /** 평문 구간에 토큰이 실렸는지 경고한다(값 미출력 — CWE-532). 판정은 공용 원천 소유. */
    public void warnIfTokenOnCleartext(String baseUrl, String token) {
        ExternalUrlPolicy.warnIfTokenOnCleartext(LOG_TAG, baseUrl, token);
    }

    /**
     * 이미 해석된 주소 집합에 대해 대역 판정만 수행한다 — 해석(DNS)과 판정을 분리해 다중 A/AAAA
     * 응답을 실 DNS 없이 검증하기 위한 경로다({@link ExternalUrlPolicy#verifyResolvedAddresses}).
     */
    void verifyResolvedAddresses(String host, InetAddress... addresses) {
        policy.verifyResolvedAddresses(host, addresses);
    }
}
