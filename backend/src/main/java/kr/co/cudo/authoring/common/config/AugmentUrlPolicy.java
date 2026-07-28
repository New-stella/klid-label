package kr.co.cudo.authoring.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 외부 증강(생성형 AI) base-url({@code authoring.augment.external.base-url}) 검증 정책 — DEV_FIX HIGH-1.
 *
 * <h3>왜 신설했나 (정책 비대칭 제거)</h3>
 * <p>증강 클라이언트만 <b>스키마만 보는</b> 자체 검증을 갖고 있었다. 그래서 같은 값
 * ({@code http://10.0.0.5:9400} · {@code http://169.254.169.254/} · {@code http://your-service.example.com})
 * 에서 VLM 은 기동이 막히는데 증강은 <b>운영에서도 기동에 성공</b>했다 — 사설망/메타데이터 대역 SSRF
 * (CWE-918)·평문 전송(CWE-319)·미설정 placeholder 배포가 모두 통과하는 상태였다. 이 커넥션으로 나가는
 * 본문에는 NAS 비식별 프레임 <b>절대경로 100건</b>과 콜백 URL 이 실리므로 약한 정책은 그대로 유출 표면이다.
 *
 * <p>이제 판정은 {@link ExternalUrlPolicy}, 완화 격리는 {@link ProfileGatedUrlPolicy} 로 <b>VLM 과 동일한
 * 공용 골격</b>을 재사용한다(코드 복제 없음). 연동별 차이는 프로퍼티 이름 두 개뿐이다.
 *
 * <ul>
 *   <li>prd/stg/미지정 — {@code strict}: HTTPS 전용 + 사설·link-local·메타데이터 대역 차단 + placeholder 차단.</li>
 *   <li>local/dev — 전용 플래그 {@code authoring.augment.external.allow-insecure-url}(기본 false)가
 *       <b>켜져 있을 때만</b> 목업(평문 http · 사설 IP) 허용. allowlist 밖에서 플래그가 켜지면 기동 실패.</li>
 * </ul>
 */
@Component
public class AugmentUrlPolicy extends ProfileGatedUrlPolicy {

    private static final String PROP_URL = "authoring.augment.external.base-url";
    private static final String PROP_FLAG = "authoring.augment.external.allow-insecure-url";

    public AugmentUrlPolicy(
            Environment environment,
            @Value("${authoring.augment.external.allow-insecure-url:false}") boolean allowInsecureUrl) {
        super("AugmentUrl", PROP_URL, PROP_FLAG, environment, allowInsecureUrl);
    }
}
