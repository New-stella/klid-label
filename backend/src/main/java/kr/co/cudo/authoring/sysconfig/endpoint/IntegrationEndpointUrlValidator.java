package kr.co.cudo.authoring.sysconfig.endpoint;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.SafeUrl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/**
 * 운영자가 입력한 연동 주소의 <b>단일 판정 지점</b> (R11).
 *
 * <h3>검증하는 것 — 스키마와 형식뿐이다</h3>
 * <ul>
 *   <li>{@code http} / {@code https} 스키마만 허용한다.</li>
 *   <li>URI 로 파싱되고 host 가 있어야 한다. 호스트 추출은 {@link SafeUrl#hostOf(String)} 에 위임해
 *       <b>언더스코어 호스트</b>({@code http://my_host:9400} — 도커 컴포즈 서비스명)를 거부하지 않는다.
 *       {@code URI#getHost()} 만 보면 그런 주소가 전부 400 이라 정당한 내부 주소를 넣을 수 없었다.</li>
 *   <li><b>userinfo 금지</b> — {@code http://user:pass@host} 형태를 거부한다(아래).</li>
 *   <li>길이 상한(컬럼·로그 오염 방지).</li>
 * </ul>
 *
 * <h3>★ userinfo 를 거부하는 이유 — 대역 차단과 무관하다</h3>
 * <p>이 값은 <b>감사 로그에 원문으로 기록</b>되는데, 로그 마스킹({@code LogMaskingPatterns})은
 * 키워드({@code password=}) 기반이라 {@code http://admin:s3cr3t@host} 를 <b>잡지 못한다</b>(실측).
 * 즉 자격증명이 평문으로 남는다(CWE-532). 게다가 이 연동들은 어느 것도 URL 삽입 자격증명을 쓰지
 * 않으므로 정당한 사용처가 없다. <b>이것은 주소 대역 판정이 아니라 자격증명 삽입 차단이다</b> —
 * 폐지된 대역 차단 정책과 무관하며, 되돌리지 말 것.
 *
 * <h3>★ IP 대역으로는 막지 않는다 (2026-08-10 사용자 확정, 구속)</h3>
 * <p>사설·링크로컬·루프백 대역 차단은 <b>폐지됐다</b>. 근거: 이 연동들(비식별 · AI 추론 · 외부 시계열
 * 분석 벤더 · 관제 통지 · 외부 증강 벤더)은 <b>실제로 내부망의 별도 서버에 있을 가능성이 높아</b>,
 * 대역으로 막으면 정당한 대상을 막는다.
 * 아웃바운드·인바운드 통제는 <b>인프라 계층이 담당</b>한다.
 *
 * <p>같은 이유로 <b>요청 전송 직전 재검증(DNS rebinding 방어)도 폐지</b>했다 — 막을 대역이 없으면
 * 재검증할 내용이 없다. 스키마·형식은 저장 시점에 확정되고 이후 이름 해석으로 바뀌지 않으므로
 * 매 요청 재확인이 성립하지 않는다.
 *
 * <p>⚠ <b>이 클래스가 SSRF 를 막는다고 적지 말 것.</b> 막지 않는다. 방어를 없앴으면 그 방어를 주장하는
 * 서술도 함께 없어야 한다 — "막는다고 적혀 있는데 안 막는" 상태가 이 저장소의 반복 결함이다.
 *
 * <h3>거부 응답에 입력을 되돌려주지 않는다 (CWE-117/209)</h3>
 * <p>사유별 <b>고정 문구</b>만 내보내고 입력 원문·호스트는 싣지 않는다.
 */
@Slf4j
@Component
public class IntegrationEndpointUrlValidator {

    /** 값 길이 상한 — DTO {@code @Size(500)} 보다 좁게 잡아 컬럼·로그 오염을 막는다(CWE-770). */
    static final int MAX_URL_LENGTH = 300;

    static final String MSG_BLANK = "주소를 입력해 주세요.";
    static final String MSG_MALFORMED = "주소 형식이 올바르지 않습니다.";
    static final String MSG_SCHEME = "http 또는 https 주소만 사용할 수 있습니다.";
    /** 고정 문구 — 입력 원문(아이디·비밀번호)을 되돌려주지 않는다. */
    static final String MSG_USERINFO = "주소에 아이디·비밀번호를 포함할 수 없습니다.";

    /**
     * 저장 요청 검증 — 위반 시 {@link ErrorCode#INVALID_INPUT}(400).
     *
     * @throws CustomException 빈값·길이·형식·스키마 위반
     */
    public void validateForSave(IntegrationEndpoint endpoint, String url) {
        if (url == null || url.isBlank()) {
            throw reject(endpoint, "blank", MSG_BLANK);
        }
        if (url.length() > MAX_URL_LENGTH) {
            // 입력 원문을 메시지에 싣지 않는다(CWE-117).
            throw reject(endpoint, "too_long",
                    "주소는 " + MAX_URL_LENGTH + "자를 초과할 수 없습니다.");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw reject(endpoint, "malformed", MSG_MALFORMED);
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw reject(endpoint, "scheme", MSG_SCHEME);
        }
        if (SafeUrl.hostOf(url) == null) {
            throw reject(endpoint, "no_host", MSG_MALFORMED);
        }
        // userinfo 는 감사 로그에 평문으로 남고(마스킹 규칙이 못 잡는다) 정당한 사용처도 없다.
        if (SafeUrl.hasUserInfo(url)) {
            throw reject(endpoint, "userinfo", MSG_USERINFO);
        }
    }

    /** 거부 로그에는 대상과 사유 코드만 남긴다 — 입력 원문은 남기지 않는다. */
    private CustomException reject(IntegrationEndpoint endpoint, String reason, String message) {
        log.warn("[IntegrationEndpoint] 주소 거부 target={} reason={}", endpoint.name(), reason);
        return new CustomException(ErrorCode.INVALID_INPUT, message);
    }
}
