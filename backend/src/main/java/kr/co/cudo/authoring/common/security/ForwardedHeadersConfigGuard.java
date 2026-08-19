package kr.co.cudo.authoring.common.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * [보안] <b>요청 헤더로 {@code getRemoteAddr()} 을 치환하는 설정</b>이 존재하면 <b>부팅을 거부</b>한다
 * (DEV_FIX 2차 — 회귀 재도입 차단).
 *
 * <h3>왜 기동 차단인가</h3>
 * <p>{@code server.forward-headers-strategy=framework|native} 는 {@code ForwardedHeaderFilter}
 * (또는 Tomcat {@code RemoteIpValve})를 <b>모든 보안 필터보다 앞</b>에 세워 {@code getRemoteAddr()} 을
 * {@code X-Forwarded-For} 의 <b>첫 토큰으로 무검증 치환</b>한다. 그러면
 * {@link kr.co.cudo.authoring.common.security.webhook.ClientIpResolver} 의 신뢰 CIDR 대조가
 * <b>공격자가 고른 값</b>을 대조하게 되어 무력화되고, 웹훅 rate limit(CWE-307)이 헤더 한 줄 회전으로
 * 완전히 우회된다(실측: 위조 XFF 회전 12연타 → 401×12, 429 없음. 되돌린 뒤 401×5 → 429).
 *
 * <h3>왜 통합테스트만으로는 부족한가</h3>
 * <p>회귀를 고정하는 {@code WebhookPathBypassSecurityIT} 는 {@code @ActiveProfiles("local")} 이라
 * <b>{@code application.yml} 축만</b> 덮는다. 아래 두 축은 전 테스트 GREEN 인 채로 뚫린다.
 * <ul>
 *   <li>{@code application-{dev,stg,prd}.yml} 에 설정 추가</li>
 *   <li>환경변수 {@code SERVER_FORWARD_HEADERS_STRATEGY=framework}
 *       (relaxed binding — 온프렘 {@code deploy/onprem/config/backend/env.template} 이 실제 주입 경로)</li>
 * </ul>
 * 그래서 판정을 <b>{@link Environment}</b> 에서 수행한다 — yml(공통/프로파일별)·환경변수·시스템 프로퍼티·
 * 커맨드라인 인자 등 <b>모든 프로퍼티 소스</b>가 한 번에 덮인다.
 *
 * <h3>왜 {@code local} 도 차단인가</h3>
 * <p>로컬만 허용하면 "로컬에서 되니까 운영에도 넣자" 는 재도입 경로가 그대로 열린다. 이 설정은 어떤
 * 프로파일에서도 이 프로젝트의 IP 해석 설계와 양립하지 않으므로 <b>전 프로파일 동일</b>하게 막는다.
 * 프로파일·환경 기반 판정은 공격자 제어 입력이 아니므로 신뢰 축으로 쓸 수 있다.
 *
 * <h3>정책 대칭성</h3>
 * <p>{@code ClientIpResolver}·{@code WebhookIpAllowlist} 는 이미 "조용한 취약 기본값 금지 → 기동 차단"
 * 정책을 쓴다. 더 치명적인(= 신뢰 축 자체를 오염시키는) 이 축에만 방어가 없는 비대칭을 없앤다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForwardedHeadersConfigGuard {

    /** {@code ForwardedHeaderFilter}/{@code RemoteIpValve} 를 세우는 스위치. */
    static final String FORWARD_HEADERS_STRATEGY = "server.forward-headers-strategy";

    /**
     * Tomcat {@code RemoteIpValve} 활성화 키 — 값이 있으면 {@code TomcatWebServerFactoryCustomizer}
     * 가 밸브를 등록하고, 밸브는 필터보다 <b>더 앞(컨테이너 레벨)</b>에서 {@code remoteAddr} 을
     * XFF 로 치환한다. 위험은 {@code forward-headers-strategy} 와 동일하다.
     *
     * <p>{@code internal-proxies}/{@code trusted-proxies}/{@code host-header}/{@code port-header} 는
     * 밸브가 이미 설치된 뒤의 <b>세부 설정</b>일 뿐 단독으로는 밸브를 만들지 않으므로(무효 설정) 대상에서
     * 제외한다 — 아래 두 키를 막으면 밸브 경로 전체가 닫힌다.
     */
    static final String TOMCAT_REMOTE_IP_HEADER = "server.tomcat.remoteip.remote-ip-header";

    /** 하위호환상 이 값만 있어도 {@code RemoteIpValve} 가 등록된다. */
    static final String TOMCAT_PROTOCOL_HEADER = "server.tomcat.remoteip.protocol-header";

    static final List<String> FORBIDDEN_KEYS =
            List.of(FORWARD_HEADERS_STRATEGY, TOMCAT_REMOTE_IP_HEADER, TOMCAT_PROTOCOL_HEADER);

    private final Environment environment;

    @PostConstruct
    void verify() {
        verify(configuredForbiddenKeys(environment));
        warnIfExternalWasHidesValve();
    }

    /**
     * [사각 고지] 외부 WAS 배포에서는 이 가드가 <b>절반만 본다</b>. [@design DEPLOY-001]
     *
     * <p>이 가드는 스프링 {@link Environment} 만 검사한다. 실행 가능 JAR 로 띄우면 내장 톰캣의
     * 설정이 곧 스프링 프로퍼티라 그것으로 충분했다. 그러나 WAR 를 외부 WAS 에 올리면
     * 같은 역할을 하는 {@code RemoteIpValve} 가 <b>WAS 자체 설정 파일</b>에 놓이고, 그것은
     * 스프링 프로퍼티가 아니라 이 가드의 시야 밖이다.
     *
     * <p>즉 이 형상에서는 <b>가드가 통과해도 안전이 보장되지 않는다.</b> 막아 둔 위험(요청 헤더로
     * {@code getRemoteAddr()} 이 치환되어 신뢰 프록시 대조가 무력화되는 것)이 WAS 쪽에서 그대로
     * 열릴 수 있다. 기동을 막지 않는 이유는 <b>확인할 방법이 없어서</b>다 — 없는 근거로 기동을
     * 거부하면 정상 배포를 막는다. 대신 사실을 로그로 남겨 배포 점검 항목으로 삼는다.
     */
    private void warnIfExternalWasHidesValve() {
        if (!kr.co.cudo.authoring.ServletInitializer.isDeployedAsWar()) {
            return;
        }
        log.warn("[ForwardedHeadersGuard] 외부 WAS 배포 감지 — 이 가드는 WAS 자체 설정을 볼 수 없습니다."
                + " RemoteIpValve(및 동등한 프록시 IP 치환 밸브)가 WAS 설정에 <설정되어 있지 않은지>"
                + " 배포 점검에서 직접 확인하세요. 설정돼 있으면 신뢰 프록시 대조가 무력화되어"
                + " 웹훅 rate limit 이 헤더 조작으로 우회됩니다(CWE-348/CWE-307)."
                + " 클라이언트 IP 해석은 webhook.trusted-proxy-cidrs 기반 ClientIpResolver 가 담당합니다.");
    }

    /**
     * 금지 키 중 <b>어떤 프로퍼티 소스로든</b> 설정된 것을 찾는다.
     *
     * <p>{@link Environment#getProperty(String)} 는 {@code SystemEnvironmentPropertySource} 의
     * relaxed binding 을 거치므로 {@code SERVER_FORWARD_HEADERS_STRATEGY} 같은 환경변수 표기도
     * 동일하게 잡힌다. 빈 값은 Spring 에서 아무 효과가 없으므로 미설정으로 본다.
     */
    static List<String> configuredForbiddenKeys(Environment environment) {
        if (environment == null) {
            return List.of();
        }
        return FORBIDDEN_KEYS.stream()
                .filter(key -> {
                    String value = environment.getProperty(key);
                    return value != null && !value.isBlank();
                })
                .toList();
    }

    /**
     * 순수 판정 — 빈 생명주기와 분리해 단위 테스트 가능하게 한다.
     *
     * @param configuredKeys 설정이 감지된 금지 키 목록
     * @throws IllegalStateException 금지 키가 하나라도 설정된 경우
     */
    static void verify(List<String> configuredKeys) {
        if (configuredKeys.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                String.join(", ", configuredKeys) + " 설정은 허용되지 않습니다(전 프로파일 공통). "
                        + "이 설정들은 ForwardedHeaderFilter/RemoteIpValve 를 보안 필터보다 앞에 세워 "
                        + "getRemoteAddr() 을 X-Forwarded-For 의 첫 토큰으로 무검증 치환합니다. "
                        + "그러면 ClientIpResolver 의 신뢰 프록시 CIDR 대조가 공격자가 고른 값을 대조하게 되어 "
                        + "무력화되고, 웹훅 rate limit 이 헤더 한 줄 회전으로 완전히 우회됩니다(CWE-348/CWE-307). "
                        + "프록시 뒤 클라이언트 IP 해석은 webhook.trusted-proxy-cidrs 기반 ClientIpResolver 가 "
                        + "담당하므로 이 키는 값(none 포함) 없이 제거하세요. "
                        + "yml·환경변수(예: SERVER_FORWARD_HEADERS_STRATEGY)·시스템 프로퍼티 모두 대상입니다.");
    }
}
