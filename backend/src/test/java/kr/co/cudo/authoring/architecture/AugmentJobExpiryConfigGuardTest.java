package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 증강 job 만료 스윕 설정의 <b>환경별 실값</b> 고정 가드 (Phase 8-A).
 *
 * <p>과거 이 리포에서 기능이 <b>무관한 토글/스케줄러 활성원</b>에 종속돼 운영(dev/stg/prd)에서만
 * 무증상 중단된 사고가 2연속 있었다. 만료 스윕은 종결 보장 장치라 특정 환경에서만 꺼지면
 * 그 환경에서만 증강이 PENDING 에 영구 고착된다 — 그래서 <b>전 프로파일 실값</b>을 못박는다.
 *
 * <p>추가로 이 스윕은 자기 토글({@code authoring.augment.job-expiry.enabled})만 본다.
 * 외부 위탁 모드({@code authoring.augment.external.mode})·관제 통지 토글
 * ({@code authoring.control-notify.enabled}) 같은 <b>남의 스위치</b>에 얹히지 않는다.
 */
class AugmentJobExpiryConfigGuardTest {

    private static final String COMMON_YML = "application.yml";
    private static final String PREFIX = "authoring.augment.job-expiry.";

    /** 전 프로파일에서 동일해야 하는 실값(환경변수 미주입 시). */
    private static final Map<String, String> EXPECTED = Map.of(
            PREFIX + "enabled", "true",
            PREFIX + "interval-ms", "900000",
            PREFIX + "initial-delay-ms", "300000",
            PREFIX + "idle-timeout-minutes", "360",
            PREFIX + "batch-size", "50");

    @Test
    @DisplayName("만료_스윕_설정이_공통_yml에_선언되고_전_프로파일에서_동일한_실값을_갖는다")
    void expirySettingsAreDeclaredAndIdenticalAcrossProfiles() {
        // given: 공통 yml 선언 (운영자가 발견 가능한 손잡이여야 한다)
        assertThat(MainResourceYaml.keys(COMMON_YML))
                .as("만료 스윕 설정은 공통 yml 에 명시돼야 한다(암묵 기본값 금지)")
                .containsAll(EXPECTED.keySet());

        // when / then: local/dev/stg/prd 어디서도 값이 갈라지지 않는다
        for (String profileYml : List.of("application-local.yml", "application-dev.yml",
                "application-stg.yml", "application-prd.yml")) {
            Environment env = MainResourceYaml.environment(COMMON_YML, profileYml);
            EXPECTED.forEach((key, expected) -> assertThat(env.getProperty(key))
                    .as("%s 의 %s 실값", profileYml, key)
                    .isEqualTo(expected));
        }
    }

    @Test
    @DisplayName("만료_스윕_토글은_환경변수로_override_가능하다")
    void expiryToggleIsOverridableByEnvVariable() {
        // given / then: 리터럴이면 운영에서 끌 수단이 없다 → placeholder 형태 강제
        assertThat(String.valueOf(MainResourceYaml.rawValue(COMMON_YML, PREFIX + "enabled")))
                .contains("${AUGMENT_JOB_EXPIRY_ENABLED");
        assertThat(String.valueOf(MainResourceYaml.rawValue(COMMON_YML, PREFIX + "idle-timeout-minutes")))
                .contains("${AUGMENT_JOB_EXPIRY_IDLE_TIMEOUT_MINUTES");
    }
}
