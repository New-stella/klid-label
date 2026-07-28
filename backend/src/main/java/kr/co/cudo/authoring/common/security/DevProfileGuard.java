package kr.co.cudo.authoring.common.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * HIGH-2 fix (CWE-306/862/1188): dev 프로파일 운영 환경 부트 차단.
 *
 * <p>base {@code docker-compose.yml} 의 기본 프로파일이 local → dev 로 바뀌면서, 프로파일을
 * 지정하지 않고 아무 서버에 올려도 dev 로 정상 부팅한다. dev 프로파일은
 * {@code authoring.dev.login.enabled=${DEV_LOGIN_ENABLED:true}} + {@code /v1/dev/tokens} permitAll
 * 조합이라 <b>인증 없이 REVIEWER JWT 를 발급</b>할 수 있는 진입점이 열린다.
 *
 * <p>{@link LocalProfileGuard}(local 프로파일 오배포 차단)의 <b>dev 등가 가드</b>이며 판정 신호도
 * 동일하게 {@code ENV} 환경변수를 재사용한다(새 환경변수 발명 금지).
 *
 * <p>판정 규칙:
 * <ul>
 *   <li>active profile 에 dev 가 없으면 통과(stg/prd 정상 배포 무영향).</li>
 *   <li>ENV 미설정/blank/local/dev → 통과 (개발자 머신·cudo_246 정상 dev 환경).</li>
 *   <li>ENV 가 stg/prd 인데 dev 프로파일 → {@link IllegalStateException} 으로 부트 거부.</li>
 *   <li>그 외 미지 라벨(qa 등)은 통과 — {@link LocalProfileGuard} 와 동일한 강도로, 명시 운영
 *       표식만 거부해 사내 임시 환경의 정상 기동을 막지 않는다.</li>
 * </ul>
 *
 * <p>{@code ENV} 는 {@link Environment#getProperty(String)} 로 읽는다(환경변수 소스 경유 —
 * {@code DeidentifyStep} 선례). 메시지에는 프로파일/ENV 값만 노출한다(CWE-209).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DevProfileGuard {

    /** 배포 환경 표식 — 이 값이면 dev 프로파일 기동을 거부한다. */
    static final Set<String> DEPLOYED_ENVS = Set.of("stg", "prd");

    private final Environment env;

    @PostConstruct
    void verify() {
        if (!env.acceptsProfiles(Profiles.of("dev"))) {
            return;
        }
        String envName = env.getProperty("ENV");
        if (envName == null || envName.isBlank()) {
            return;
        }
        String normalized = envName.trim().toLowerCase(Locale.ROOT);
        if (DEPLOYED_ENVS.contains(normalized)) {
            throw new IllegalStateException(
                    "dev profile은 배포 환경(stg/prd)에서 허용되지 않습니다 — 인증 없는 dev 토큰 발급 경로가 열립니다. (현재 ENV="
                            + envName + ")");
        }
    }
}
