package kr.co.cudo.authoring.common.config;

import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * 외부 시계열 분석 위탁 base-url({@code vlm.client.url}) 검증 — <b>이 연동의 단일 판정 원천</b>.
 *
 * <h3>★ 무엇을 보는가 — 스킴과 형식뿐이다 (2026-08-10 확정, 구속)</h3>
 * <p>연동 주소 정책의 확정 규칙은 <b>「주소 대역 차단을 하지 않는다 — 남는 검증은 스킴 {@code http}/
 * {@code https} + URL 형식뿐」</b>이며, 그 확정이 대상으로 명시한 연동 4종에 <b>이 연동이 포함</b>된다.
 * 근거는 ①연동 대상이 <b>내부망의 별도 장비</b>일 가능성이 높아 대역 차단이 정당한 대상을 막고
 * ②시스템 전체가 온프렘 내부망 배포이며 ③아웃바운드 통제는 인프라 계층의 몫이라는 것이다.
 * 그래서 판정을 비식별(KPST)과 <b>같은 축</b>({@link ExternalUrlPolicy#internalNetwork})에 둔다.
 *
 * <h3>그대로 남는 것 / 없어진 것</h3>
 * <ul>
 *   <li><b>남는다</b> — {@code http}/{@code https} 외 스킴 거부({@code file://}·{@code ftp://}·
 *       {@code ws://}), 빈값·파싱 불가 거부, placeholder/예제 호스트 거부, 클라우드 메타데이터·
 *       링크로컬·ULA·CGNAT 등 <b>정상 위탁 대상이 될 수 없는 예약 대역</b> 거부(IMDS 는 어떤 환경에서도
 *       위탁 대상이 아니다), 평문 + 토큰 조합 경고.</li>
 *   <li><b>없어졌다</b> — HTTPS <b>강제</b>와 사설망 차단. 둘 다 2026-08-10 에 <b>폐기된 조항</b>이다.
 *       되살리지 말 것.</li>
 * </ul>
 *
 * <p><b>https 는 계속 통과한다</b> — 나중에 보안 요구로 TLS 전환을 하면 <b>코드 변경 없이</b> 그대로
 * 동작한다(강제하지 않을 뿐 지원은 유지). 실제 연동은 배포 기본값·노드 양쪽 모두 평문 http 다.
 *
 * <h3>왜 프로파일 게이팅({@link ProfileGatedUrlPolicy})에서 빠졌는가</h3>
 * <p>그 골격은 <b>운영 엄격 / 개발 완화</b>를 프로파일로 가르고 완화를 전용 플래그
 * ({@code vlm.client.allow-insecure-url})로 격리하는 장치다. 확정 정책이 <b>운영에서도 평문·사설을
 * 허용</b>하는 쪽으로 정리되면서 가를 것이 없어졌고, 그 플래그는 <b>켜든 끄든 결과가 같은 죽은 설정</b>이
 * 되어 함께 걷어냈다(설정 파일·환경 템플릿 포함). 남겨 두면 "이걸 끄면 엄격해진다"는 잘못된 기대를 준다.
 *
 * <p>⚠ 실제 형상에서 이 정합이 없으면 <b>운영 프로파일에 평문 http 주소를 채우는 순간 기동이 막힌다</b>
 * (지금은 그 주소가 비어 있어 드러나지 않을 뿐이다). 그 벽을 없애는 것이 이 클래스의 목적이다.
 *
 * <p>⚠ 증강({@code AugmentUrlPolicy})은 같은 확정 정책의 대상이지만 <b>아직 프로파일 게이팅에 남아
 * 있다</b>(이번 정합 범위 밖). 이 클래스를 근거로 그쪽을 임의로 바꾸지 말 것 — 별도 결정이 필요하다.
 */
@Component
public class VlmUrlPolicy {

    /** 검증 대상 프로퍼티 — 예외 메시지가 이 이름을 그대로 노출해 설정 지점을 지목한다. */
    static final String PROP_URL = "vlm.client.url";

    /** 로그 태그 — 주소·토큰이 아니라 연동 식별자다. */
    private static final String LOG_TAG = "VlmUrl";

    /** 판정 자체는 공용 원천이 소유한다 — 여기서 규칙을 다시 쓰지 않는다. */
    private final ExternalUrlPolicy policy = ExternalUrlPolicy.internalNetwork(PROP_URL);

    /**
     * base-url 을 검증한다.
     *
     * <p>⚠ <b>이 메서드는 더 이상 빈 생성 경로에서 쓰이지 않는다</b> — 연동 주소로 기동을 막지
     * 않기 때문이다(2026-09-03 확정). 빈 생성은 {@link #inspect(String)} 를 쓰고, 예외를 던지는
     * 이 형태는 <b>후보 장비 걸러내기</b>처럼 기동과 무관한 호출부가 쓴다. 다시 빈 생성 경로에
     * 걸지 말 것.
     *
     * @return https 면 {@code true}, http 면 {@code false} (호출자의 TLS 구성 분기용)
     * @throws IllegalStateException 정책 위반 시
     */
    public boolean check(String baseUrl) {
        return policy.check(baseUrl);
    }

    /**
     * ★ <b>예외 없는 판정</b> — 기동을 막지 않고 전송 시점에 쓰기 위한 형태(2026-09-03 확정).
     * 규칙은 {@link #check(String)} 과 <b>같은 객체가 소유</b>하므로 두 경로가 갈릴 여지가 없다.
     */
    public ExternalUrlPolicy.Verdict inspect(String baseUrl) {
        return policy.inspect(baseUrl);
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
