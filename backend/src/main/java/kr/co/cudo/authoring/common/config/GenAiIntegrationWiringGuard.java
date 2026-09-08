package kr.co.cudo.authoring.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 생성형 AI(증강) <b>위탁 ↔ 콜백 수신</b> 배선 짝 판정 — DEV_FIX 2차 LOW-4.
 *
 * <h3>무엇을 지키는가</h3>
 * <p>증강은 <b>위탁(outbound)</b> 과 <b>콜백 수신(inbound)</b> 이 모두 열려야 완결된다. 그런데 두
 * 스위치가 서로 다른 설정 축에 있어 <b>한쪽만 켜는 오설정</b>이 가능하다:
 * <ul>
 *   <li>{@code authoring.augment.external.base-url} 주입 — 위탁은 실제로 나간다.</li>
 *   <li>{@code webhook.genai.allowed-ip-cidrs} 미설정/{@code none} — 콜백은 <b>전건 403</b>
 *       ({@code GenAiWebhookIpAllowlist} 는 VLM 과 달리 미설정=전면 차단이다).</li>
 * </ul>
 * 이 조합은 위탁은 성공(202)하고 결과는 영영 들어오지 않으므로, 벤더 재시도가 소진되면
 * {@code LS_DATA_AUG} 가 <b>PENDING 으로 영구 고착</b>된다(만료 스윕 없음). 기동·헬스체크·로그가 모두
 * 정상으로 보이는 실패라 사람이 job 테이블을 뒤지기 전엔 드러나지 않는다.
 *
 * <h3>★★ 기동이 아니라 <b>위탁 시점</b>에 막는다 (2026-09-03 사용자 확정, 구속)</h3>
 * <p>구 동작은 이 조합에서 <b>기동 자체를 실패</b>시켰다. 그 자리가 틀렸다 — 주소를 제대로 넣은
 * <b>정상 배포가 다른 설정 한 줄이 비었다는 이유로</b> 뜨지 못했고, 그러면 증강과 무관한 저작 업무
 * 전체가 함께 멈춘다. 그래서 <b>판정 규칙은 한 줄도 바꾸지 않고 걸리는 자리만</b>
 * {@link #commissionRejectionLabel(String)} 로 옮겼다({@code AugmentTransportGuard} 가 위탁 요청을 거부한다).
 *
 * <h3>★★ 2026-09-08 — 「언제 보는가」가 한 번 더 옮겨졌다(예고가 실현됐다)</h3>
 * <p>증강 위탁 주소가 <b>운영 화면 교체 대상</b>이 되면서 이 판정도 <b>기동 시점 한 번</b>에서
 * <b>요청 시점 재평가</b>로 바뀌었다. 아래 {@code commissionVerdict} 필드가 <i>"등록되는 날 이 판정은
 * 요청 시점 재평가로 바뀌어야 한다"</i> 고 그날을 예고해 두었고, <b>그날이 와서 그렇게 바꾼 것</b>이다.
 *
 * <p><b>규칙은 여전히 한 줄도 바뀌지 않았다</b> — 바뀐 것은 첫 번째 입력(위탁 주소)을 어디서
 * 가져오는가뿐이다. 고정 값으로 두면 배포 기본값이 빈 배포에서 「미연동 → 요구 없음」으로 계산되어
 * <b>허용 대역이 비었는데도 위탁이 나간다</b>(= 이 가드가 없어진 것과 같다).
 *
 * <p><b>보호가 유지되는 근거</b> — 이 가드가 지키는 것은 <b>위탁을 건 뒤 열리는 결과 수신구</b>다.
 * 위탁을 걸지 않으면 그 수신구가 열리지 않으므로 <b>위험 자체가 성립하지 않는다</b>. 따라서
 * 「기동을 통과시키되 위탁을 거부한다」로 보호가 그대로 남는다.
 *
 * <p>⚠⚠ <b>기동만 통과시키고 위탁을 그냥 보내면 이 가드는 없어진 것이다</b> — 그러면 아무나 콜백을
 * 보낼 수 있는 수신구가 위탁과 함께 열린다. 위탁 시점 거부는 <b>이 변경의 짝</b>이지 부가물이 아니다.
 *
 * <p>⚠ <b>이 가드는 「주소 축」이 아니라 「짝 맞춤 축」이다.</b> 같은 날 오전에 옮긴 것은 연동
 * <b>주소</b> 판정이었고 이 가드는 그때 <b>범위 밖</b>이었다(축이 다르므로). 오후에 사용자 확정으로
 * 「주소가 아니라서 남은 자리」까지 함께 옮긴 것이다 — <b>「주소 축이 아닌데 왜 옮겼나」로
 * 되돌리지 말 것.</b>
 *
 * <p>차단 해제 수단은 두 가지 모두 명시적이다 — 미연동이면 위탁 주소를 비워 두고, 연동이면
 * 대역(또는 전면 허용 의도를 남기는 {@code 0.0.0.0/0})을 명시한다.
 *
 * <p>판정은 순수 함수({@link #inspect})로 분리해 컨테이너 없이도 단위 검증한다
 * ({@code ForwardedHeadersConfigGuard} 동형).
 *
 * @design ADR-062
 * @design INT-006
 */
@Slf4j
@Component
public class GenAiIntegrationWiringGuard {

    static final String KEY_BASE_URL = "authoring.augment.external.base-url";
    /** 콜백 수신 대역 설정 키 — 로그에만 싣는다(응답·예외 문구에는 넣지 않는다). */
    public static final String KEY_ALLOWLIST = "webhook.genai.allowed-ip-cidrs";

    /**
     * 위탁 거부 사유 — <b>주소도 설정 키도 대역 값도 담지 않는다</b>(CWE-209).
     *
     * <p>거부 응답이 사유별로 갈리는 것까지는 운영자를 위해 필요하지만, 그 문구에 설정값이 실리면
     * 응답 자체가 내부 형상을 훑는 수단이 된다. 상세는 서버 로그에만 남긴다.
     */
    public static final String REJECTION_LABEL = "콜백 수신 대역 미설정";

    private final String baseUrl;

    /**
     * 콜백 수신 대역 설정값 — <b>배포 설정</b>이라 재기동 없이 바뀌지 않는다(운영 화면 교체 대상이 아니다).
     * 요청 시점 재평가에서 「짝의 반대쪽」 입력으로 그대로 쓰인다.
     */
    private final String allowedCidrs;

    /** 주소 판정 원천 — 요청 시점 재평가도 <b>같은 판정기</b>를 쓴다(규칙 사본을 만들지 않는다). */
    private final AugmentUrlPolicy urlPolicy;

    /**
     * <b>기동 시점</b> 판정 결과 — 위반이면 사유({@link #REJECTION_LABEL})가 붙은 거부다.
     *
     * <p>★ <b>구 서술 폐기(2026-09-08)</b> — <i>"두 입력(base-url · allowlist)이 모두 배포 설정값이라
     * 재기동 없이 바뀌지 않으므로 여기서 한 번 판정해 들고 있는다. ⚠ 증강은 아직
     * {@code IntegrationEndpoint} 에 등록돼 있지 않아 운영 화면 주소 override 대상이 아니다 —
     * 등록되는 날 이 판정은 <b>요청 시점 재평가</b>로 바뀌어야 한다"</i>.
     *
     * <p>★★ <b>그날이 왔고 그렇게 바꿨다.</b> 증강이 {@code IntegrationEndpoint.AUGMENT} 로 등록되어
     * 운영 화면에서 위탁 주소를 저장할 수 있게 됐고, 위탁 클라이언트도 <b>호출 시점 해석</b>이 됐다.
     * 그래서 입력 둘 중 <b>base-url 은 더 이상 배포 설정값이 아니며</b>, 이 필드에 고정된 값으로
     * 판정하면 <b>배포 기본값이 비어 있는 배포</b>에서 「미연동 → 요구 없음」으로 계산되어
     * <b>허용 대역이 비었는데도 위탁이 나간다</b>(= 이 가드가 존재하는 이유가 무력화된다).
     * 위탁 시점 판정은 {@link #commissionRejectionLabel(String)} 이 소유한다.
     *
     * <p>이 필드는 이제 <b>기동 로그({@link #check()})와 「배포 설정만 놓고 본 상태」 조회 전용</b>이다.
     * 그 로그를 없애지 말 것 — 잘못 배선된 배포가 조용히 뜨는 것을 알리는 유일한 신호다.
     */
    private final Verdict commissionVerdict;

    public GenAiIntegrationWiringGuard(
            // 미주입(빈 값)이 곧 "아직 연동 안 됨" 이다 — AugmentExternalLinkPolicy 와 같은 축·같은 기본값.
            @Value("${authoring.augment.external.base-url:}") String baseUrl,
            @Value("${webhook.genai.allowed-ip-cidrs:}") String allowedCidrs,
            AugmentUrlPolicy urlPolicy) {
        this.baseUrl = baseUrl;
        this.allowedCidrs = allowedCidrs;
        this.urlPolicy = urlPolicy;
        this.commissionVerdict = inspect(commissionableBaseUrl(baseUrl, urlPolicy), allowedCidrs);
    }

    /**
     * ★ <b>「연동됐다」는 「위탁이 실제로 나갈 수 있다」다</b> (2026-09-03 확정).
     *
     * <h3>왜 주소가 있다는 것만으로는 부족한가</h3>
     * <p>연동 주소 검증이 <b>기동에서 전송 시점으로 옮겨지면서</b>, 정책을 위반한 주소도 이제
     * <b>설정값으로는 남는다</b>. 그 값을 「연동됨」으로 읽으면 <b>위탁이 한 건도 나갈 수 없는
     * 배포</b>가 콜백 allowlist 미설정만으로 위탁 경로를 잃는다 — 걷어낸 <b>기동 의존이 다른
     * 이름으로 되살아나는</b> 형태다.
     *
     * <p>위탁이 나갈 수 없으면 콜백도 오지 않는다. 따라서 allowlist 를 요구할 이유도 없다.
     */
    private static String commissionableBaseUrl(String baseUrl, AugmentUrlPolicy urlPolicy) {
        if (!isLinked(baseUrl) || urlPolicy.inspect(baseUrl).rejected()) {
            return "";
        }
        return baseUrl;
    }

    /**
     * 기동 시점에는 <b>막는 대신 알린다</b> — 「막지 않는다」가 「알리지 않는다」가 되면 안 된다.
     *
     * <p>이 ERROR 기록이 없으면 잘못 배선된 배포가 조용히 떠서 증강만 전건 실패하는 상태를 아무도
     * 알아채지 못한다. 이 로그를 지우지 말 것.
     */
    @PostConstruct
    void check() {
        if (commissionVerdict.rejected()) {
            log.error("[GenAi] 위탁 ↔ 콜백 수신 배선의 짝이 맞지 않습니다 — 기동은 계속되고 "
                    + "<증강 위탁만> 거부됩니다. {}", commissionVerdict.detail());
            return;
        }
        if (isLinked(baseUrl)) {
            log.info("[GenAi] 위탁(주소 주입) ↔ 콜백 IP allowlist 짝 확인 완료");
        }
    }

    /**
     * <b>배포 설정값만</b> 놓고 본 판정 — 위반이면 사유, 통과면 {@code null}.
     *
     * <p>⚠ <b>구 서술 폐기(2026-09-08)</b> — <i>"★ 위탁 시점에 쓰는 판정. {@code AugmentApiWebClientConfig}
     * 가 이 값을 전송 가드에 넘겨 위탁 요청만 거부한다"</i>. 위탁 시점 판정은
     * {@link #commissionRejectionLabel(String)} 로 옮겼다 — 이 무인자 판정은 <b>운영 화면에서 저장한
     * 주소를 보지 못해</b> 배포 기본값이 빈 배포에서 「요구 없음」으로 통과시킨다.
     * <b>전송 가드에 이 값을 다시 넘기지 말 것.</b>
     *
     * <p>남는 쓰임은 기동 로그와 <b>배포 설정 회귀 검증</b>이다.
     */
    public String commissionRejectionLabel() {
        return commissionVerdict.rejectionLabel();
    }

    /**
     * ★★ <b>위탁 시점에 쓰는 판정</b> — 그 요청이 <b>실제로 나가는 주소</b>로 다시 본다 (2026-09-08).
     *
     * <p>{@code AugmentApiWebClientConfig} 가 이 메서드를 전송 가드에 넘겨 <b>위탁 요청만</b> 거부한다.
     * 조회·취소는 대상이 아니다 — 그 둘은 새 수신구를 열지 않으며, 오히려 <b>이미 걸려 있는 위탁을
     * 회수·정리하는 경로</b>라 함께 막으면 복구 수단을 잃는다.
     *
     * <h3>왜 입력만 바뀌고 규칙은 그대로인가</h3>
     * <p>판정은 {@link #inspect(String, String)}·{@link #commissionableBaseUrl(String, AugmentUrlPolicy)}
     * <b>그대로</b>이고, 바뀐 것은 첫 번째 입력이 「기동 시점 배포 기본값」에서 「이 요청의 유효 주소」로
     * 옮겨간 것뿐이다. 허용 대역 쪽 규칙을 호출자(증강 축)가 다시 읽지 않게 하는 것이 이 메서드의
     * 존재 이유다 — 다시 읽으면 그 사본이 <b>두 번째 진실원</b>이 되어 한쪽만 갱신되는 순간 갈린다.
     *
     * @param effectiveBaseUrl 이 요청이 실제로 향하는 주소(재작성된 최종 주소의 {@code scheme://authority}).
     *                         비었으면 「아직 연동 안 됨」이라 요구하지 않는다 — 위탁이 나갈 수 없으면
     *                         콜백도 오지 않으므로 allowlist 를 요구할 이유가 없다(무인자 판정과 같은 근거).
     */
    public String commissionRejectionLabel(String effectiveBaseUrl) {
        return inspect(commissionableBaseUrl(effectiveBaseUrl, urlPolicy), allowedCidrs).rejectionLabel();
    }

    /**
     * 순수 판정 — 위탁이 켜져 있는데 콜백 수신이 닫혀 있으면 거부한다.
     *
     * <p><b>규칙은 구 {@code verify} 와 완전히 같다</b>. {@link #verify} 가 이 메서드를 그대로 부르므로
     * 두 경로가 갈릴 여지가 없다({@code ExternalUrlPolicy#inspect} ↔ {@code check} 와 같은 형태).
     */
    static Verdict inspect(String baseUrl, String allowedCidrs) {
        if (!isLinked(baseUrl) || !isNone(allowedCidrs)) {
            return Verdict.ACCEPT;
        }
        return new Verdict(REJECTION_LABEL,
                KEY_BASE_URL + " 가 주입되어 증강 외부 위탁이 활성인데 " + KEY_ALLOWLIST
                        + " 가 비어 있습니다(=전면 차단). 위탁은 나가지만 결과 콜백이 전건 403 이 되어 "
                        + "증강이 PENDING 으로 영구 고착됩니다(만료 스윕 없음). "
                        + "연동한다면 벤더 송신 대역을 명시하고(WEBHOOK_GENAI_ALLOWED_IP_CIDRS, "
                        + "로컬/개발처럼 발신 IP 가 유동적이면 0.0.0.0/0 을 <명시>), "
                        + "아직 연동하지 않는다면 위탁 주소(AUGMENT_API_BASE_URL)를 비워 두세요.");
    }

    /**
     * 예외를 던지는 형태 — <b>배포 형상 회귀 검증</b>이 쓴다(문서대로 띄운 compose/env 조합이 이 짝을
     * 만족하는지 단언).
     *
     * <p>⚠⚠ <b>이 메서드를 다시 기동 경로({@code @PostConstruct}·빈 생성)에 걸지 말 것</b> —
     * 그것이 이번에 걷어낸 바로 그 배선이다({@code ExternalUrlPolicy#check} 에 붙은 같은 경고와
     * 동일한 이유).
     *
     * @throws IllegalStateException 위탁 주소 주입 + allowlist 미설정/{@code none}
     */
    static void verify(String baseUrl, String allowedCidrs) {
        Verdict verdict = inspect(baseUrl, allowedCidrs);
        if (verdict.rejected()) {
            throw new IllegalStateException(verdict.detail());
        }
    }

    /** 위탁 주소가 주입돼 있으면 연동 — 공백만 있는 값은 미주입으로 본다. */
    private static boolean isLinked(String value) {
        return value != null && !value.isBlank();
    }

    /** 빈 값(미설정)과 {@code none} 은 모두 "허용 IP 없음" 이다({@code GenAiWebhookIpAllowlist} 동일 규칙). */
    private static boolean isNone(String value) {
        return value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim());
    }

    /**
     * 짝 맞춤 판정 결과 — 통과이거나, 사유가 붙은 거부다({@code ExternalUrlPolicy.Verdict} 동형).
     *
     * @param rejectionLabel 전송 실패 메시지에 실을 사유(<b>설정값을 담지 않는다</b>). 통과면 {@code null}
     * @param detail         <b>서버 로그 전용</b> 상세(설정 키·환경변수명 포함). 통과면 {@code null}
     */
    record Verdict(String rejectionLabel, String detail) {

        static final Verdict ACCEPT = new Verdict(null, null);

        boolean rejected() {
            return rejectionLabel != null;
        }
    }
}
