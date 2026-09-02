package kr.co.cudo.authoring.aiserver.job;

import kr.co.cudo.authoring.aiserver.service.AiSrvrHealthPoller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.JobDetail;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태점검 잡 <b>등록 조건</b> 회귀 가드. [@design ADR-057]
 *
 * <p>게이팅이 조용히 풀리면 관측이 저절로 켜진다 — 그리고 그 시기에 켜지면 <b>바쁜 장비를 죽은
 * 장비로 오판</b>해 멀쩡한 장비를 하나 잃는다. 기본값이 바뀌었는지, 조건이 다른 키를 보고 있는지를
 * 컨테이너 없이 확인한다.
 */
class AiSrvrHealthPollTriggerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(AiSrvrHealthPollTriggerConfig.class);

    @Test
    @DisplayName("설정이_없으면_잡이_등록되지_않는다")
    void 설정이_없으면_잡이_등록되지_않는다() {
        // 기본값은 꺼짐이다 — 미완성이라서가 아니라 의도된 배포 형상이다.
        runner.run(context -> assertThat(context).doesNotHaveBean(JobDetail.class));
    }

    @Test
    @DisplayName("설정을_꺼두면_잡이_등록되지_않는다")
    void 설정을_꺼두면_잡이_등록되지_않는다() {
        runner.withPropertyValues("authoring.integration.ai-server.poll-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(JobDetail.class));
    }

    @Test
    @DisplayName("설정을_켜면_잡과_트리거가_등록된다")
    void 설정을_켜면_잡과_트리거가_등록된다() {
        // ★켰을 때 실제로 등록되는지도 확인한다 — 조건이 오타난 키를 보고 있으면 「항상 꺼짐」이
        //   되어 위 두 시험은 통과하는데 기능이 영영 켜지지 않는다.
        runner.withPropertyValues("authoring.integration.ai-server.poll-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(JobDetail.class);
                    assertThat(context).hasSingleBean(Trigger.class);
                });
    }

    @ParameterizedTest(name = "poll-enabled={0}")
    @ValueSource(strings = {"true", "TRUE", "True", "false", "FALSE", "1", "0", "on", "yes", "y", ""})
    @DisplayName("잡_등록_여부와_폴러의_게이팅_해석이_같은_값에서_같다")
    void 잡_등록_여부와_폴러의_게이팅_해석이_같은_값에서_같다(String rawValue) {
        // given — ★두 겹이 같은 키를 <다르게> 읽으면 잡은 등록되지 않는데 폴러·헬스 상세는
        //        「켜짐」이라고 말한다. 운영자가 켰다고 믿는 채 원장이 영원히 정지한다.
        //        @ConditionalOnProperty(havingValue="true") 는 equalsIgnoreCase("true") 하나만
        //        참으로 보는 반면, boolean 주입은 on/yes/1 까지 참으로 읽는 것이 그 원인이었다.
        runner.withPropertyValues("authoring.integration.ai-server.poll-enabled=" + rawValue)
                .run(context -> {
                    boolean jobRegistered = context.getBeansOfType(JobDetail.class).size() == 1;

                    // then — 잡 등록 조건이 진실원이고, 폴러는 그것과 <같은 답>을 내야 한다.
                    assertThat(AiSrvrHealthPoller.isEnabled(rawValue))
                            .as("설정값 '%s' 에서 잡 등록=%s 인데 폴러 판정이 다르다", rawValue, jobRegistered)
                            .isEqualTo(jobRegistered);
                });
    }

    @Test
    @DisplayName("게이팅_판정은_앞뒤_공백을_흡수하고_그_밖의_값은_꺼짐이다")
    void 게이팅_판정은_앞뒤_공백을_흡수하고_그_밖의_값은_꺼짐이다() {
        // 공백은 yml/환경변수에서 흔히 섞이며, 애너테이션 쪽도 바인딩 단계에서 정리된다.
        assertThat(AiSrvrHealthPoller.isEnabled("  true  ")).isTrue();
        // 값 미주입(null)은 꺼짐 — 판정 불가는 거부다(fail-closed).
        assertThat(AiSrvrHealthPoller.isEnabled(null)).isFalse();
        assertThat(AiSrvrHealthPoller.isEnabled("enabled")).isFalse();
    }

    @Test
    @DisplayName("주기가_0으로_설정돼도_트리거가_만들어진다")
    void 주기가_0으로_설정돼도_트리거가_만들어진다() {
        // 0 을 그대로 넘기면 Quartz 가 트리거를 만들지 못해 기동이 통째로 깨진다 — 하한으로 흡수한다.
        runner.withPropertyValues(
                        "authoring.integration.ai-server.poll-enabled=true",
                        "authoring.integration.ai-server.poll-interval-seconds=0")
                .run(context -> {
                    SimpleTrigger trigger = (SimpleTrigger) context.getBean(Trigger.class);
                    assertThat(trigger.getRepeatInterval()).isPositive();
                });
    }
}
