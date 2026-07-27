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
        verify(System.getenv("ENV"));
    }

    /**
     * 판정 본체 — 환경변수 읽기를 분리해 자동 회귀 테스트가 가능하도록 파라미터로 받는다 (A-ISSUE-21).
     *
     * <p>{@code System.getenv} 모킹 라이브러리를 새로 도입하는 대신 <b>테스트 가능한 형태로 추출</b>했다.
     * 이 가드는 배포 안전의 마지막 방어선이므로 회귀 시 CI 가 즉시 잡아야 한다.
     *
     * @param envName 배포 환경 이름 (ENV 환경변수). null/공백/"local" 이면 통과.
     * @throws IllegalStateException ENV 가 dev/stg/prd 인데 active profile 에 local 이 포함된 경우
     */
    void verify(String envName) {
        if (!env.acceptsProfiles(Profiles.of("local"))) {
            return;
        }
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
