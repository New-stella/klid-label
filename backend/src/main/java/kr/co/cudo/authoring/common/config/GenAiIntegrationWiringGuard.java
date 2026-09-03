package kr.co.cudo.authoring.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 생성형 AI(증강) <b>위탁 ↔ 콜백 수신</b> 배선 짝 강제 — DEV_FIX 2차 LOW-4.
 *
 * <h3>왜 기동 차단인가</h3>
 * <p>증강은 <b>위탁(outbound)</b> 과 <b>콜백 수신(inbound)</b> 이 모두 열려야 완결된다. 그런데 두
 * 스위치가 서로 다른 설정 축에 있어 <b>한쪽만 켜는 오설정</b>이 가능했다:
 * <ul>
 *   <li>{@code authoring.augment.external.base-url} 주입 — 위탁은 실제로 나간다.</li>
 *   <li>{@code webhook.genai.allowed-ip-cidrs} 미설정/{@code none} — 콜백은 <b>전건 403</b>
 *       ({@code GenAiWebhookIpAllowlist} 는 VLM 과 달리 미설정=전면 차단이다).</li>
 * </ul>
 *
 * <h3>★ 위탁 활성 판정이 「모드 토글」에서 「위탁 주소 주입 여부」로 바뀌었다 (2026-09-03 확정)</h3>
 * <p>미연동 모드 토글({@code authoring.augment.external.mode})은 <b>축 자체가 폐기</b>됐다. 그 키를
 * 계속 읽으면 <b>기본값이 http 라 미설정 전 환경이 「위탁 활성」으로 판정</b>되어, 주소도 없는
 * 배포가 콜백 allowlist 미설정만으로 기동을 거부당한다 — 즉 <b>이번에 걷어낸 「기동 의존」이 다른
 * 이름으로 되살아난다</b>. 이제 판정 축은 {@code AugmentExternalLinkPolicy} 와 같은
 * <b>위탁 주소 주입 여부</b>다: 주소가 없으면 위탁이 나가지 않으므로 콜백도 오지 않고, 따라서
 * allowlist 를 요구할 이유가 없다.
 * 이 조합은 위탁은 성공(202)하고 결과는 영영 들어오지 않으므로, 벤더 재시도가 소진되면
 * {@code LS_DATA_AUG} 가 <b>PENDING 으로 영구 고착</b>된다(만료 스윕 없음). 기동·헬스체크·로그가 모두
 * 정상으로 보이는 실패라 사람이 job 테이블을 뒤지기 전엔 드러나지 않는다.
 *
 * <p>그래서 <b>기동 자체를 실패</b>시킨다({@link ProfileGatedUrlPolicy} 의 완화 플래그 assert 와 동일한
 * 강도 — ⚠ VLM 은 2026-08-10 확정 정합으로 그 골격에서 빠졌으므로 지금 그 assert 를 쓰는 연동은
 * 증강 하나다). 경고만 남기면 배포 로그에 묻히고, 이 결함의
 * 실패 모드가 "조용한 무증상 중단" 이라 경고로는 막을 수 없다. 차단 해제 수단은 두 가지 모두 명시적이다
 * — 미연동이면 위탁 주소를 비워 두고, 연동이면 대역(또는 전면 허용 의도를 남기는
 * {@code 0.0.0.0/0})을 명시한다.
 *
 * <p>판정은 순수 함수({@link #verify})로 분리해 컨테이너 없이도 단위 검증한다
 * ({@code ForwardedHeadersConfigGuard} 동형).
 */
@Slf4j
@Component
public class GenAiIntegrationWiringGuard {

    static final String KEY_BASE_URL = "authoring.augment.external.base-url";
    static final String KEY_ALLOWLIST = "webhook.genai.allowed-ip-cidrs";

    private final String baseUrl;
    private final String allowedCidrs;

    public GenAiIntegrationWiringGuard(
            // 미주입(빈 값)이 곧 "아직 연동 안 됨" 이다 — AugmentExternalLinkPolicy 와 같은 축·같은 기본값.
            @Value("${authoring.augment.external.base-url:}") String baseUrl,
            @Value("${webhook.genai.allowed-ip-cidrs:}") String allowedCidrs) {
        this.baseUrl = baseUrl;
        this.allowedCidrs = allowedCidrs;
    }

    @PostConstruct
    void check() {
        verify(baseUrl, allowedCidrs);
        if (isLinked(baseUrl)) {
            log.info("[GenAi] 위탁(주소 주입) ↔ 콜백 IP allowlist 짝 확인 완료");
        }
    }

    /**
     * 순수 판정 — 위탁이 켜져 있는데 콜백 수신이 닫혀 있으면 거부한다.
     *
     * @throws IllegalStateException 위탁 주소 주입 + allowlist 미설정/{@code none}
     */
    static void verify(String baseUrl, String allowedCidrs) {
        if (!isLinked(baseUrl) || !isNone(allowedCidrs)) {
            return;
        }
        throw new IllegalStateException(
                KEY_BASE_URL + " 가 주입되어 증강 외부 위탁이 활성인데 " + KEY_ALLOWLIST
                        + " 가 비어 있습니다(=전면 차단). 위탁은 나가지만 결과 콜백이 전건 403 이 되어 "
                        + "증강이 PENDING 으로 영구 고착됩니다(만료 스윕 없음). "
                        + "연동한다면 벤더 송신 대역을 명시하고(WEBHOOK_GENAI_ALLOWED_IP_CIDRS, "
                        + "로컬/개발처럼 발신 IP 가 유동적이면 0.0.0.0/0 을 <명시>), "
                        + "아직 연동하지 않는다면 위탁 주소(AUGMENT_API_BASE_URL)를 비워 두세요.");
    }

    /** 위탁 주소가 주입돼 있으면 연동 — 공백만 있는 값은 미주입으로 본다. */
    private static boolean isLinked(String value) {
        return value != null && !value.isBlank();
    }

    /** 빈 값(미설정)과 {@code none} 은 모두 "허용 IP 없음" 이다({@code GenAiWebhookIpAllowlist} 동일 규칙). */
    private static boolean isNone(String value) {
        return value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim());
    }
}
