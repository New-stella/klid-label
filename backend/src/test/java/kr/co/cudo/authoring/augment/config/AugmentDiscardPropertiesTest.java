package kr.co.cudo.authoring.augment.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 폐기 유예 설정의 <b>fail-closed 검증</b> (H9).
 *
 * <p>이 설정이 무너지는 방향은 하나뿐이다 — 유예가 사라져 "반려 즉시 영구 삭제" 가 되는 것. 경고 로그는
 * 배포 로그에 묻히므로 <b>기동 자체를 실패</b>시킨다. 순수 판정({@link AugmentDiscardProperties#validate()})과
 * 실제 바인딩 경로 두 축을 모두 고정한다.
 */
class AugmentDiscardPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(AugmentDiscardConfig.class);

    @Test
    @DisplayName("유예기간_설정이_0이거나_음수면_기동에_실패한다")
    void zeroOrNegativeGraceDaysFailsStartup() {
        runner.withPropertyValues("authoring.augment.discard.grace-days=0")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("authoring.augment.discard.grace-days=-1")
                .run(ctx -> assertThat(ctx).hasFailed());

        assertThatThrownBy(() -> props(0).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("grace-days");
    }

    @Test
    @DisplayName("기본값은_유예_7일이며_정상_기동한다")
    void defaultsAreSaneAndStart() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            AugmentDiscardProperties props = ctx.getBean(AugmentDiscardProperties.class);
            assertThat(props.graceDays()).isEqualTo(7);
            assertThat(props.enabled()).isTrue();
        });
    }

    @Test
    @DisplayName("배치크기_주기_클레임만료_파일정리상한_하한을_어기면_기동에_실패한다")
    void otherLowerBoundsAreEnforced() {
        runner.withPropertyValues("authoring.augment.discard.batch-size=0")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("authoring.augment.discard.interval-ms=1000")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("authoring.augment.discard.claim-stale-minutes=1")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("authoring.augment.discard.file-cleanup-max-attempts=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    /**
     * FIX-1 — cutoff 는 <b>유예를 넓히는 방향만</b> 허용한다.
     *
     * <p>유예 조건만 파라미터였기 때문에, 신규 호출처가 {@code now()} 를 넘기면 반려 직후 파생이 영구
     * 삭제될 수 있었다(최종 DELETE 의 다른 두 조건은 SQL 리터럴이라 무력화 불가). 클램프 판정은 설정
     * 소유자 한 곳에 있으므로 여기서 순수 검증한다.
     */
    @Test
    @DisplayName("cutoff_클램프는_유예를_좁히지_못하고_넓히기만_허용한다")
    void clampCutoffOnlyWidensGrace() {
        AugmentDiscardProperties props = props(7);
        LocalDateTime hardCutoff = LocalDateTime.now().minusDays(7);

        // 좁히기 시도(= 더 최근 시각) → 하드 컷오프로 되돌아간다
        assertThat(props.clampCutoff(LocalDateTime.now()))
                .isBeforeOrEqualTo(hardCutoff.plusSeconds(1));
        assertThat(props.clampCutoff(LocalDateTime.now().plusYears(1)))
                .isBeforeOrEqualTo(hardCutoff.plusSeconds(1));
        assertThat(props.clampCutoff(null)).isBeforeOrEqualTo(hardCutoff.plusSeconds(1));

        // 넓히기(= 더 과거)는 그대로 통과한다 — 더 오래된 건만 지우는 방향이라 안전하다
        LocalDateTime wider = LocalDateTime.now().minusDays(30);
        assertThat(props.clampCutoff(wider)).isEqualTo(wider);
    }

    private static AugmentDiscardProperties props(int graceDays) {
        return new AugmentDiscardProperties(true, graceDays, 3_600_000L, 600_000L, 50, 60, 5);
    }
}
