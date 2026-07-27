package kr.co.cudo.authoring.common.security.webhook;

import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Set;

/**
 * 웹훅 보안 설정의 <b>명시 강제 대상 프로파일</b> 판정 — 단일 진실원 (DEV_FIX M-3 / REDESIGN R-3).
 *
 * <h3>왜 별도 클래스인가</h3>
 * <p>과거에는 {@link ClientIpResolver} 와 {@link WebhookIpAllowlist} 가 각자
 * {@code activeProfiles.contains("prd")} 를 <b>복제</b>해 갖고 있었다. 그 결과
 * <b>{@code stg} 가 통째로 사각지대</b>가 됐다 — 이 프로젝트의 {@code stg} 는 온프렘 개발서버로
 * <b>LB/Nginx 뒤</b>에 배포되는데, {@code application-stg.yml} 에 webhook 키가 없어
 * {@code application.yml} 의 빈 기본값으로 조용히 떨어졌고, 모든 콜백 IP 가 LB IP 하나로 수렴했다.
 * 판정을 한 곳에 모아 프로파일이 늘어날 때 한쪽만 고쳐지는 드리프트를 구조적으로 막는다.
 *
 * <h3>대상</h3>
 * <ul>
 *   <li>{@code prd} — 운영</li>
 *   <li>{@code stg} — 온프렘 개발서버(LB/Nginx 뒤). 프록시 뒤라는 점은 prd 와 동일하다.</li>
 * </ul>
 * <p>{@code local}/{@code dev} 는 프록시 없이 직접 기동하므로 빈 기본값(=XFF 전면 무시)이 안전하다.
 *
 * <p>강제의 의미는 "값을 <b>의식적으로</b> 정하라" 이지 "특정 값을 쓰라" 가 아니다. 프록시가 없으면
 * {@code none} 을 명시하면 된다(빈 값=미설정 과 구분되는 기존 규약 유지).
 */
final class WebhookConfigProfiles {

    /** 웹훅 보안 설정을 반드시 명시해야 하는 프로파일. */
    private static final Set<String> EXPLICIT_CONFIG_PROFILES = Set.of("prd", "stg");

    private WebhookConfigProfiles() {
    }

    /** 이 환경에서 웹훅 보안 설정을 <b>명시</b>해야 하는가(미설정 시 기동 차단). */
    static boolean requiresExplicitConfig(Environment environment) {
        if (environment == null) {
            return false;
        }
        return List.of(environment.getActiveProfiles()).stream()
                .anyMatch(EXPLICIT_CONFIG_PROFILES::contains);
    }

    /** 오류 메시지에 노출할 대상 프로파일 목록 표기. */
    static String describeProfiles() {
        return "prd/stg";
    }
}
