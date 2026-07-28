package kr.co.cudo.authoring.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * VLM base-url({@code vlm.client.url}) 검증 정책 — <b>운영 엄격 / 개발 완화</b>의 단일 판정 원천.
 *
 * <h3>배경 (B-ISSUE-21 / ENV-ISSUE-02)</h3>
 * <p>기존 검증은 {@code enabled=true} 면 무조건 HTTPS + 공인 IP 를 요구해, 평문 HTTP·사설 IP 인
 * 로컬 목업(klid-mock-server:9400)으로는 <b>빈 생성이 실패해 애플리케이션이 기동조차 못 했다</b>
 * (크래시 루프). 그 결과 로컬에서 VLM 실배선 검증이 원천적으로 불가능했다.
 *
 * <h3>격리 설계</h3>
 * <p>완화 격리 규칙(전용 플래그 + 프로파일 allowlist + 기동 assert)과 실제 판정은 각각
 * {@link ProfileGatedUrlPolicy} 와 {@link ExternalUrlPolicy} 가 담당한다. 본 클래스는 <b>연동별 값</b>
 * (프로퍼티 이름)만 지정한다 — {@link AugmentUrlPolicy}·{@link KpstWebClientConfig} 도 같은 골격을
 * 공유하므로 연동마다 검증 강도가 갈라지지 않는다(DEV_FIX HIGH-1 의 비대칭 재발 차단).
 */
@Component
public class VlmUrlPolicy extends ProfileGatedUrlPolicy {

    private static final String PROP_URL = "vlm.client.url";
    private static final String PROP_FLAG = "vlm.client.allow-insecure-url";

    public VlmUrlPolicy(Environment environment,
                        @Value("${vlm.client.allow-insecure-url:false}") boolean allowInsecureUrl) {
        super("VlmUrl", PROP_URL, PROP_FLAG, environment, allowInsecureUrl);
    }
}
