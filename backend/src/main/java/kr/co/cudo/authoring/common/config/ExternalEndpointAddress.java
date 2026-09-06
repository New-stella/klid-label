package kr.co.cudo.authoring.common.config;

import kr.co.cudo.authoring.common.util.SafeUrl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 외부 연동 base-url 의 <b>기동 시점 판정 결과를 들고 있는 값</b> — 기동을 막지 않고 전송을 막는다.
 *
 * <h3>★ 왜 있는가 (2026-09-03 사용자 확정, 구속)</h3>
 * <p><b>외부 연동 주소가 어떤 상태여도 애플리케이션은 뜬다.</b> 주소가 비었든·형식이 틀렸든·예시
 * 값이든·나가면 안 되는 대역이든 기동을 막지 않는다. 대신 <b>그 주소로 나가려는 순간 실패</b>한다.
 * 한 연동의 설정 실수로 <b>저작 업무 전체가 멈추는</b> 것이 온프렘 배포에서는 실수보다 큰 대가이기
 * 때문이며, 막아야 할 것은 <b>잘못된 곳으로 나가는 것</b>이지 기동이 아니다.
 *
 * <p><b>검증 규칙은 하나도 바뀌지 않았다</b> — 판정은 {@link ExternalUrlPolicy} 가 그대로 소유하고
 * 이 클래스는 <b>그 결과를 나중에 쓰기 위해 들고 있을</b> 뿐이다. 바뀐 것은 <b>언제 막는가</b>다.
 *
 * <h3>두 가지 일을 한다</h3>
 * <ol>
 *   <li><b>{@link #baseUrl()} 정규화</b> — 거부된 주소는 <b>빈 문자열</b>로 낮춘다. 그래야 그 값이
 *       {@code WebClient.baseUrl(...)} 에 들어가도 (ⅰ)파싱 불가 값으로 <b>빈 생성이 터지지 않고</b>
 *       (ⅱ)요청이 <b>그 나쁜 주소로 실제로 나가지 않는다</b>. 빈 base 는 상대 URI 가 되어
 *       {@code loopback:80} 으로 나가므로, 전송 가드가 <b>반드시 함께</b> 배선돼야 한다
 *       ({@code IntegrationEndpointTransportGuards#requireUsableAddress}).</li>
 *   <li><b>{@link #rejectionLabel()} 보존</b> — 전송 실패 사유를 <b>주소 없이</b> 알린다.</li>
 * </ol>
 *
 * <h3>운영 화면 override 는 그대로 살린다</h3>
 * <p>배포 기본값이 거부돼도 {@code baseUrl()} 이 빈 문자열일 뿐이라, 운영 화면에서 정상 주소를
 * 저장하면 URL 재작성 필터가 <b>재기동 없이</b> 그 주소로 요청을 보낸다. 즉 이 설계는
 * <b>잘못 배포된 주소를 되돌릴 길</b>을 함께 연다.
 *
 * <h3>로그에 주소를 싣지 않는다</h3>
 * <p>기동 로그에는 <b>설정 키와 사유 분류</b>만 남긴다(CWE-209/532). 호스트가 담긴 상세는 DEBUG 로만
 * 남긴다 — 거부 사유가 주소별로 갈려 보이면 그 자체가 내부망을 훑는 신호가 된다.
 *
 * <p>⚠ <b>빈값은 사고가 아니다</b> — "아직 안 정해짐" 이므로 ERROR 가 아니라 INFO 로 남긴다.
 * 미연동이 정상인 배포가 실재한다(벤더 주소 확정 전 · 그 연동을 쓰지 않는 채널).
 *
 * @design ADR-062
 */
@Slf4j
public final class ExternalEndpointAddress {

    /** 거부된 주소가 낮춰지는 값 — {@code null} 이 아니라 빈 문자열이다(NPE 대신 판독 가능한 실패). */
    private static final String UNUSABLE = "";

    private final String propertyName;
    private final String baseUrl;
    private final ExternalUrlPolicy.Violation violation;
    private final String extraRejectionLabel;
    private final boolean https;

    private ExternalEndpointAddress(String propertyName, String baseUrl,
                                    ExternalUrlPolicy.Violation violation,
                                    String extraRejectionLabel, boolean https) {
        this.propertyName = propertyName;
        this.baseUrl = baseUrl;
        this.violation = violation;
        this.extraRejectionLabel = extraRejectionLabel;
        this.https = https;
    }

    /**
     * 연동 주소 정책({@link ExternalUrlPolicy})으로 판정한다 — <b>예외를 던지지 않는다</b>.
     *
     * @param propertyName 설정 키(로그 전용 — 응답에는 싣지 않는다)
     * @param raw          배포 설정값(널 허용)
     * @param verdict      {@link ExternalUrlPolicy#inspect(String)} 결과
     */
    public static ExternalEndpointAddress of(String propertyName, String raw,
                                             ExternalUrlPolicy.Verdict verdict) {
        if (!verdict.rejected()) {
            return new ExternalEndpointAddress(propertyName, raw.trim(), null, null, verdict.https());
        }
        logRejection(propertyName, verdict.violation().label(), verdict.detail());
        return new ExternalEndpointAddress(propertyName, UNUSABLE, verdict.violation(), null, false);
    }

    /**
     * <b>형식만</b> 본다 — 이 연동에 대응하는 주소 정책이 아직 없을 때 쓴다.
     *
     * <h3>왜 정책을 걸지 않는가</h3>
     * <p>여기서 {@link ExternalUrlPolicy} 를 새로 걸면 <b>지금까지 막지 않던 값</b>(예시 호스트·예약
     * 대역)을 새로 막게 된다 — 「무엇을 막는지는 그대로, 언제 막는지만 바꾼다」를 어긴다. 그래서
     * <b>빈 생성이 터지지 않게 하는 최소한</b>(파싱 가능 · 호스트 존재)만 본다.
     *
     * <p>호스트 추출은 {@link SafeUrl#hostOf(String)} 에 위임한다 — {@code URI#getHost()} 만 보면
     * 언더스코어 호스트({@code http://ai_server:9300}, 컴포즈 서비스명에서 흔하다)가 <b>전부</b>
     * 형식 오류가 된다.
     */
    public static ExternalEndpointAddress formatOnly(String propertyName, String raw) {
        if (raw == null || raw.isBlank()) {
            logRejection(propertyName, ExternalUrlPolicy.Violation.BLANK.label(), "값 미설정");
            return new ExternalEndpointAddress(propertyName, UNUSABLE,
                    ExternalUrlPolicy.Violation.BLANK, null, false);
        }
        String trimmed = raw.trim();
        if (!parsable(trimmed)) {
            logRejection(propertyName, ExternalUrlPolicy.Violation.MALFORMED.label(), "URI 파싱 불가");
            return new ExternalEndpointAddress(propertyName, UNUSABLE,
                    ExternalUrlPolicy.Violation.MALFORMED, null, false);
        }
        if (SafeUrl.hostOf(trimmed) == null) {
            logRejection(propertyName, ExternalUrlPolicy.Violation.NO_HOST.label(), "host 없음");
            return new ExternalEndpointAddress(propertyName, UNUSABLE,
                    ExternalUrlPolicy.Violation.NO_HOST, null, false);
        }
        return new ExternalEndpointAddress(propertyName, trimmed, null, null,
                trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("https://"));
    }

    /**
     * 주소 자체는 통과했으나 <b>다른 사유</b>로 이 주소에 위탁하면 안 되는 경우를 덧입힌다.
     *
     * <p>실제 쓰임은 두 가지다 — ①https 인데 <b>자체 CA 인증서가 없어</b> 검증할 수 없는 경우
     * (CWE-295: 검증 없이 보내느니 안 보낸다) ②운영에서 <b>목/시뮬레이터 호스트</b>로 판정된 경우
     * (위조 비식별본 유출 차단). 둘 다 <b>기동이 아니라 위탁을 막아야</b> 하는 사유다.
     *
     * <p>이미 거부된 주소에는 덧입히지 않는다 — 첫 사유가 원인이고 뒤의 것은 그 결과일 수 있다.
     */
    public ExternalEndpointAddress rejectedBecause(String label, String detail) {
        if (!usable()) {
            return this;
        }
        logRejection(propertyName, label, detail);
        return new ExternalEndpointAddress(propertyName, UNUSABLE, null, label, false);
    }

    /** 이 주소로 실제로 나가도 되는가. */
    public boolean usable() {
        return violation == null && extraRejectionLabel == null;
    }

    /**
     * {@code WebClient}/{@code HttpClient} 에 넣어도 되는 base-url — 거부됐으면 <b>빈 문자열</b>이다.
     *
     * <p>빈 문자열은 "아무 데도 안 보낸다" 를 <b>스스로 성립시키지 않는다</b>(상대 URI 가 되어
     * loopback:80 으로 나간다). 반드시 전송 가드와 짝으로 쓸 것.
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 전송 실패 메시지에 실을 사유 — <b>주소를 담지 않는다</b>.
     *
     * <p>★ <b>빈값(미설정)은 사유가 아니라 {@code null} 이다</b> — "아직 안 정해짐" 은 <b>위반이
     * 아니라 상태</b>이고, 전송 가드는 {@code null} 을 「주소가 설정되지 않았다」로 읽어 그에 맞는
     * 문구를 낸다. 두 경우의 문구가 갈려야 운영자가 <b>"넣어야 한다"</b>와 <b>"고쳐야 한다"</b>를
     * 구분한다.
     *
     * @return 거부 사유. 통과했거나 <b>미설정</b>이면 {@code null}
     */
    public String rejectionLabel() {
        if (extraRejectionLabel != null) {
            return extraRejectionLabel;
        }
        if (violation == null || violation == ExternalUrlPolicy.Violation.BLANK) {
            return null;
        }
        return violation.label();
    }

    /** 판정된 스킴이 https 인가 — 거부된 주소에서는 항상 {@code false}. */
    public boolean https() {
        return https;
    }

    private static boolean parsable(String url) {
        try {
            URI.create(url);
            // WebClient 의 baseUrl 은 이 빌더로 해석된다 — 여기서 터지면 <빈 생성>이 실패한다.
            UriComponentsBuilder.fromUriString(url).build();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 기동 로그 — <b>설정 키와 사유만</b> 남긴다. 호스트가 담긴 상세는 DEBUG 로만.
     *
     * <p>빈값(미설정)은 사고가 아니므로 INFO 다 — ERROR 로 남기면 미연동이 정상인 배포가 매 기동
     * 오류를 뿜어 진짜 오류가 묻힌다.
     */
    private static void logRejection(String propertyName, String label, String detail) {
        if (ExternalUrlPolicy.Violation.BLANK.label().equals(label)) {
            log.info("[ExternalUrl] 연동 주소가 설정되지 않았습니다 — 기동은 정상이며 그 연동을 "
                    + "시도하는 시점에 실패합니다. 설정키={}", propertyName);
        } else {
            log.error("[ExternalUrl] 연동 주소 설정값이 유효하지 않습니다 — 기동은 계속되고 그 주소로는 "
                    + "요청을 보내지 않습니다. 설정키={} 사유={}", propertyName, label);
        }
        log.debug("[ExternalUrl] 거부 상세 property={} detail={}", propertyName, detail);
    }
}
