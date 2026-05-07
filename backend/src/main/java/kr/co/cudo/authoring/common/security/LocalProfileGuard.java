package kr.co.cudo.authoring.common.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * HIGH-4 fix (CWE-798): local 프로파일 운영 환경 부트 차단.
 *
 * <p>application-local.yml 은 docker dev 컨테이너용 fallback 비밀번호를 포함하므로,
 * 운영(ENV=dev/stg/prd) 환경에서 active profile=local 로 잘못 배포되면 안전한 기본값 노출 위험이 있다.
 *
 * <p>판정 규칙:
 * <ul>
 *   <li>ENV 환경변수가 미설정 또는 "local" → 통과 (개발자 머신).</li>
 *   <li>ENV 가 dev/stg/prd 인데 active profile 에 local 포함 → IllegalStateException 으로 부트 거부.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalProfileGuard {

    private final Environment env;

    @PostConstruct
    void verify() {
        if (!env.acceptsProfiles(Profiles.of("local"))) {
            return;
        }
        String envName = System.getenv("ENV");
        if (envName == null || envName.isBlank() || "local".equalsIgnoreCase(envName)) {
            return;
        }
        String lower = envName.toLowerCase();
        if (lower.equals("dev") || lower.equals("stg") || lower.equals("prd")) {
            throw new IllegalStateException(
                    "local profile은 ENV=local 환경에서만 허용됩니다. (현재 ENV=" + envName + ")");
        }
    }
}
