package kr.co.cudo.authoring.dev;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * [보안] <b>운영 계열 프로파일(prd·stg)</b>에서 dev 로그인 토글이 켜져 있으면 <b>부팅을 거부</b>한다
 * (A-ISSUE-05).
 *
 * <p>{@code /v1/dev/tokens} 는 permitAll 이며 인증 없이 임의 {@code userNo}/{@code role} 토큰을 발급한다.
 * 즉 이 토글이 운영에서 켜지는 순간 인증 체계 전체가 우회 가능해진다. 기존
 * {@link DevToggleStartupWarner} 는 WARN 로그만 남겨 실제로는 켜진 채 운영될 수 있었다.
 *
 * <h3>왜 stg 도 대상인가 (DEV_FIX H-3)</h3>
 * <p>{@code application-stg.yml} 의 {@code enabled: false} 는 <b>yml 리터럴</b>이라 Spring 의 프로퍼티
 * 우선순위상 환경변수({@code AUTHORING_DEV_LOGIN_ENABLED=true}) 하나로 덮인다. 즉 "stg 는 OFF" 라는
 * 정책이 설정 파일만으로는 강제되지 않았다. stg 는 온프렘 개발서버로 네트워크 도달 가능한 클라이언트가
 * 존재하므로 {@code sub=<시드 REVIEWER>} 토큰 발급만으로 인증 우회 + 관리자 권한 획득이 성립한다.
 * 따라서 prd 와 동일하게 <b>부팅 fail-fast</b> 로 못 박는다 — 프로파일은 공격자 제어 입력이 아니므로
 * 이 판정 축은 신뢰할 수 있다.
 *
 * <p>가용성 문제를 런타임 우회(공격자 제어 입력으로 통제를 끄는 분기)로 풀지 않고 <b>설정 fail-fast</b>
 * 로 강제한다 — 잘못된 설정은 배포 시점에 즉시 드러나야 한다.
 *
 * <p>dev 업로드 토글({@code authoring.dev.upload.enabled})은 대상이 아니다 — 해당 엔드포인트는
 * {@code /v1/dev/**} REVIEWER 인가 게이트 뒤에 있어 인증 우회 경로가 아니다.
 */
@Slf4j
@Component
public class DevToggleProfileGuard {

    /** 운영 계열 프로파일 — 이 중 하나라도 활성이면 dev 로그인 토글을 금지한다. */
    private static final Profiles PRODUCTION_LIKE = Profiles.of("prd", "stg");

    private final Environment environment;
    private final boolean devLoginEnabled;

    public DevToggleProfileGuard(Environment environment,
                                 @Value("${authoring.dev.login.enabled:false}") boolean devLoginEnabled) {
        this.environment = environment;
        this.devLoginEnabled = devLoginEnabled;
    }

    @PostConstruct
    void verify() {
        verify(environment.acceptsProfiles(PRODUCTION_LIKE), devLoginEnabled);
    }

    /**
     * 순수 판정 — 부수효과(빈 생명주기)와 분리해 단위 테스트 가능하게 한다.
     *
     * @param productionLikeActive prd 또는 stg 프로파일 활성 여부
     * @throws IllegalStateException 운영 계열 프로파일에서 dev 로그인이 활성화된 경우
     */
    static void verify(boolean productionLikeActive, boolean devLoginEnabled) {
        if (productionLikeActive && devLoginEnabled) {
            throw new IllegalStateException(
                    "authoring.dev.login.enabled=true 는 prd/stg 프로파일에서 허용되지 않습니다. "
                            + "(/v1/dev/tokens 는 인증 없이 임의 권한 토큰을 발급하는 경로입니다)");
        }
    }
}
