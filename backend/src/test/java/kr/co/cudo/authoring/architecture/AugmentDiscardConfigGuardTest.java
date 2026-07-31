package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.augment.config.AugmentDiscardProperties;
import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 폐기 실삭제 설정의 <b>배포 형상 정합</b> 가드 (DEV_FIX FIX-C).
 *
 * <h2>무엇이 문제였나</h2>
 * <p>{@code file-cleanup-max-attempts} 는 {@link AugmentDiscardProperties} 가 선언하고 하한까지
 * fail-closed 로 검사하는데, <b>{@code application.yml} 과 {@code .env.example} 양쪽에 그 키만
 * 없었다</b>. placeholder 가 없으니 운영자가 {@code AUGMENT_DISCARD_FILE_CLEANUP_MAX_ATTEMPTS} 를
 * 넣어도 <b>조용히 무시</b>되고 항상 기본값 5 로 동작했다(하한 검증도 도달 불가). 기본 주기 1시간 ×
 * 5회 = NAS 5시간 장애면 대기 중 비석이 전량 데드레터인데 <b>늘릴 수단이 없었다</b>.
 *
 * <p>이 리포에는 ".env 값이 있는 줄 알았는데 무력화" 사고 클래스가 이미 있다(빈 값이
 * {@code ${KEY:default}} 를 무력화). 기존 가드들({@code ImplicitDefaultConfigGuardTest} ·
 * {@code ConfigPropertyKeyGuardTest})은 <b>소스의 {@code ${key:default}} 참조</b>만 스캔해서,
 * {@code @ConfigurationProperties} 레코드의 {@code @DefaultValue} 컴포넌트는 사각지대였다.
 *
 * <h2>그래서 레코드 컴포넌트에서 키를 <b>파생</b>시킨다</h2>
 * <p>표를 손으로 나열하면 새 설정을 추가할 때 표도 같이 잊는다. 컴포넌트 목록에서 kebab-case 키와
 * 환경변수명을 계산해 ①공통 yml 선언 ②환경변수 placeholder ③{@code .env.example} 노출을 전수
 * 대조하므로, <b>레코드에 필드를 하나 더 넣는 순간</b> 형상이 따라오지 않으면 여기서 깨진다.
 */
class AugmentDiscardConfigGuardTest {

    private static final String COMMON_YML = "application.yml";
    private static final String PREFIX = "authoring.augment.discard.";
    private static final String ENV_PREFIX = "AUGMENT_DISCARD_";
    private static final Path ENV_EXAMPLE = Paths.get(".env.example");

    @Test
    @DisplayName("폐기_설정의_모든_손잡이가_yml과_env예시에_노출된다")
    void everyDiscardPropertyIsExposedInDeploymentShape() {
        // given
        Set<String> ymlKeys = MainResourceYaml.keys(COMMON_YML);
        String envExample = read(ENV_EXAMPLE);

        List<String> missingInYml = new ArrayList<>();
        List<String> notOverridable = new ArrayList<>();
        List<String> missingInEnvExample = new ArrayList<>();

        // when
        for (RecordComponent component : AugmentDiscardProperties.class.getRecordComponents()) {
            String key = PREFIX + kebab(component.getName());
            String envName = ENV_PREFIX + screamingSnake(component.getName());
            if (!ymlKeys.contains(key)) {
                missingInYml.add(key);
                continue;
            }
            String raw = String.valueOf(MainResourceYaml.rawValue(COMMON_YML, key));
            if (!raw.contains("${" + envName)) {
                // 리터럴이면 운영에서 조절할 수단이 없다(환경변수를 넣어도 조용히 무시된다).
                notOverridable.add(key + " → " + raw + " (기대 placeholder: ${" + envName + ":…})");
            }
            if (!envExample.contains(envName + "=")) {
                missingInEnvExample.add(envName);
            }
        }

        // then
        assertThat(missingInYml)
                .as("코드가 선언한 폐기 설정이 공통 yml 에 없다 — 운영자가 존재를 발견할 수 없다: %s", missingInYml)
                .isEmpty();
        assertThat(notOverridable)
                .as("환경변수 placeholder 가 없어 운영 조절이 불가능한 키: %s", notOverridable)
                .isEmpty();
        assertThat(missingInEnvExample)
                .as(".env.example 에 없어 배포 형상에서 누락되는 키: %s", missingInEnvExample)
                .isEmpty();
    }

    /**
     * 재시도 상한의 <b>실값</b>이 코드 기본값과 같아야 한다 — yml 을 채우면서 값이 달라지면 그것 자체가
     * 조용한 동작 변경이다(자동 복구 포기까지의 시간이 바뀐다).
     */
    @Test
    @DisplayName("파일정리_재시도_상한의_yml_실값이_코드_기본값과_같다")
    void fileCleanupMaxAttemptsMatchesCodeDefault() {
        String resolved = MainResourceYaml.environment(COMMON_YML)
                .getProperty(PREFIX + "file-cleanup-max-attempts");

        assertThat(resolved).isEqualTo("5");
        assertThat(resolved)
                .as("하한(%d) 미만이면 기동이 실패한다", AugmentDiscardProperties.MIN_FILE_CLEANUP_MAX_ATTEMPTS)
                .isNotNull();
        assertThat(Integer.parseInt(resolved))
                .isGreaterThanOrEqualTo(AugmentDiscardProperties.MIN_FILE_CLEANUP_MAX_ATTEMPTS);
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String screamingSnake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("배포 형상 파일을 읽을 수 없습니다: " + path.toAbsolutePath(), e);
        }
    }
}
