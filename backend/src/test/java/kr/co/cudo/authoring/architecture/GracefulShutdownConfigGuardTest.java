package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.common.config.MvcAsyncExecutorConfig;
import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종료 유예(graceful shutdown) 설정 고정 — <b>앱 예산과 systemd 예산의 정합</b>까지 함께 본다.
 *
 * <h3>무엇이 반쪽이었나</h3>
 * <p>{@link MvcAsyncExecutorConfig} 의 풀은 {@code waitForTasksToCompleteOnShutdown} 으로 진행 중
 * 작업을 기다리게 돼 있었는데, <b>커넥터에는 유예가 없어 즉시 닫혔다</b>. 커넥터가 먼저 닫히면 실행기가
 * 아무리 기다려도 쓸 소켓이 없으므로, 배포할 때마다 진행 중인 대용량 내려받기가 그대로 끊겼다.
 *
 * <h3>왜 systemd 까지 보는가 (앱만 고치면 무의미하다)</h3>
 * <p>온프렘은 systemd 로 뜬다. {@code TimeoutStopSec} 이 앱의 종료 예산보다 <b>작으면</b> systemd 가
 * SIGKILL 로 먼저 끊어 유예 설정이 통째로 무의미해진다 — 구 형상이 정확히 그랬다(앱 예산 90s vs
 * {@code TimeoutStopSec=30}). 그래서 두 값의 <b>관계</b>를 기계로 고정한다.
 */
class GracefulShutdownConfigGuardTest {

    private static final String COMMON_YML = "application.yml";
    private static final String SHUTDOWN_KEY = "server.shutdown";
    private static final String GRACE_KEY = "spring.lifecycle.timeout-per-shutdown-phase";

    private static final List<String> PROFILE_YMLS = List.of(
            "application-local.yml", "application-dev.yml", "application-stg.yml", "application-prd.yml");

    /** 온프렘 systemd 유닛(모듈 밖 — Gradle test 작업 디렉토리는 backend 모듈 루트). */
    private static final Path SERVICE_UNIT =
            Paths.get("../deploy/onprem/config/systemd/klid-backend.service");

    private static final Pattern TIMEOUT_STOP =
            Pattern.compile("(?m)^TimeoutStopSec=(\\d+)\\s*$");

    /**
     * 유예 상한 — 이보다 길면 배포가 그만큼 멈춘다. 대용량 내려받기(최대 30분)는 어떤 현실적 유예로도
     * 완주하지 못하므로, 유예를 늘려도 얻는 것 없이 배포 지연만 커진다.
     */
    private static final Duration MAX_REASONABLE_GRACE = Duration.ofMinutes(2);

    @Test
    @DisplayName("종료_유예가_공통_yml에_켜져_있고_어떤_프로파일도_되돌리지_않는다")
    void gracefulShutdownIsEnabledEverywhere() {
        assertThat(MainResourceYaml.environment(COMMON_YML).getProperty(SHUTDOWN_KEY))
                .as("%s 가 graceful 이 아니면 커넥터가 즉시 닫혀 진행 중 응답이 배포마다 끊긴다", SHUTDOWN_KEY)
                .isEqualTo("graceful");

        for (String profileYml : PROFILE_YMLS) {
            assertThat(MainResourceYaml.keys(profileYml))
                    .as("%s 가 %s 를 덮으면 그 환경만 조용히 즉시 종료로 돌아간다", profileYml, SHUTDOWN_KEY)
                    .doesNotContain(SHUTDOWN_KEY);
        }
    }

    @Test
    @DisplayName("유예_시간이_명시돼_있고_배포를_멈추지_않을_만큼_짧다")
    void graceIsDeclaredAndBounded() {
        Duration grace = declaredGrace();

        assertThat(grace)
                .as("유예가 0 이면 graceful 을 켠 의미가 없다")
                .isPositive()
                // 상한을 두는 이유: 유예는 "완주 보장"이 아니라 "무의미한 중도 절단 최소화"다.
                // 길게 잡으면 완주는 여전히 못 시키면서 배포만 그만큼 멈춘다.
                .isLessThanOrEqualTo(MAX_REASONABLE_GRACE);
    }

    @Test
    @DisplayName("systemd_강제종료_시간이_앱의_종료_예산보다_크다")
    void systemdStopTimeoutCoversApplicationBudget() {
        // given: 앱이 쓸 수 있는 최대 종료 시간 = 커넥터 유예 + 비동기 실행기 대기 상한
        Duration appBudget = declaredGrace()
                .plusSeconds(MvcAsyncExecutorConfig.AWAIT_TERMINATION_SECONDS);

        // when
        String unit = read(SERVICE_UNIT);
        Matcher matcher = TIMEOUT_STOP.matcher(unit);
        assertThat(matcher.find())
                .as("%s 에 TimeoutStopSec 이 없다 — systemd 기본값(90s)에 조용히 의존하게 된다", SERVICE_UNIT)
                .isTrue();
        Duration stopTimeout = Duration.ofSeconds(Long.parseLong(matcher.group(1)));

        // then: 작으면 SIGKILL 이 먼저 와서 위 두 설정이 통째로 무의미해진다
        assertThat(stopTimeout)
                .as("TimeoutStopSec(%s) 이 앱 종료 예산(%s) 보다 작으면 유예 설정이 무의미하다", stopTimeout, appBudget)
                .isGreaterThan(appBudget);
    }

    private static Duration declaredGrace() {
        Object raw = MainResourceYaml.rawValue(COMMON_YML, GRACE_KEY);
        assertThat(raw).as("%s 미선언 — 프레임워크 기본값에 조용히 의존하게 된다", GRACE_KEY).isNotNull();
        return DurationStyle.detectAndParse(String.valueOf(raw), ChronoUnit.MILLIS);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }
}
