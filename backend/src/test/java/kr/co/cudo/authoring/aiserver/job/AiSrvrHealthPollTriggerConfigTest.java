package kr.co.cudo.authoring.aiserver.job;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
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
                    // ★계통마다 하나씩 <둘>이다 — 주기를 계통마다 두려면 트리거가 갈려 있어야 한다.
                    assertThat(context.getBeansOfType(JobDetail.class)).hasSize(2);
                    assertThat(context.getBeansOfType(Trigger.class)).hasSize(2);
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
                    // ★「몇 개인가」가 아니라 「등록됐는가」를 본다 — 계통마다 잡이 있어 개수는 둘이며,
                    //   계통이 늘면 또 는다. 개수를 박으면 이 시험이 게이팅과 무관한 이유로 깨진다.
                    boolean jobRegistered = !context.getBeansOfType(JobDetail.class).isEmpty();

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
    @DisplayName("주기가_0으로_설정돼도_트리거가_만들어진다_두_자리_모두")
    void 주기가_0으로_설정돼도_트리거가_만들어진다() {
        // 0 을 그대로 넘기면 Quartz 가 트리거를 만들지 못해 기동이 통째로 깨진다 — 하한으로 흡수한다.
        // ★계통을 가르면서 <한쪽만 방어되는> 일이 없어야 하므로 두 자리를 함께 0·음수로 준다.
        runner.withPropertyValues(
                        "authoring.integration.ai-server.poll-enabled=true",
                        "authoring.integration.ai-server.poll-interval-seconds=0",
                        "vlm.client.poll-interval-seconds=-3")
                .run(context -> {
                    assertThat(intervalSeconds(context, AiSrvrHealthPollJob.TRIGGER_NAME)).isPositive();
                    assertThat(intervalSeconds(context, AiSrvrHealthPollJob.TIMESERIES_TRIGGER_NAME))
                            .isPositive();
                });
    }

    // --- 계통별 점검 주기 [@design AC-1099] -------------------------------------------------------

    /**
     * ★ <b>계통마다 자기 주기 자리가 있다</b> — 서로 다른 값을 줄 수 있어야 한다.
     *
     * <p>여기서 재는 것은 주기가 얼마나 긴가가 아니라 <b>자리가 갈려 있는가</b>다.
     */
    @Test
    @DisplayName("★계통마다_서로_다른_점검_주기를_준다")
    void 계통마다_서로_다른_점검_주기를_준다() {
        runner.withPropertyValues(
                        "authoring.integration.ai-server.poll-enabled=true",
                        "authoring.integration.ai-server.poll-interval-seconds=7",
                        "vlm.client.poll-interval-seconds=23")
                .run(context -> {
                    assertThat(intervalSeconds(context, AiSrvrHealthPollJob.TRIGGER_NAME)).isEqualTo(7);
                    assertThat(intervalSeconds(context, AiSrvrHealthPollJob.TIMESERIES_TRIGGER_NAME))
                            .isEqualTo(23);
                });
    }

    /**
     * ★★ <b>한쪽을 바꿔도 다른 쪽이 움직이지 않는다</b> — 이 결정의 핵심이다.
     *
     * <p>트리거 하나를 두고 최근 점검 시각으로 거르는 방식을 골랐다면 <b>시계열의 실효 주기가 추론
     * 주기보다 짧아질 수 없어</b> 이 단언이 성립하지 않는다(한쪽이 다른 쪽의 하한을 정한다).
     * 그래서 트리거를 계통마다 갈랐고, 그 사실을 여기서 못 박는다.
     */
    @Test
    @DisplayName("★추론_주기만_바꿔도_시계열_점검_간격은_그대로다")
    void 추론_주기만_바꿔도_시계열_점검_간격은_그대로다() {
        runner.withPropertyValues(
                        "authoring.integration.ai-server.poll-enabled=true",
                        // 시계열 값은 <주지 않는다> — 자기 기본값으로 서야 한다.
                        "authoring.integration.ai-server.poll-interval-seconds=41")
                .run(context -> {
                    assertThat(intervalSeconds(context, AiSrvrHealthPollJob.TRIGGER_NAME)).isEqualTo(41);
                    assertThat(intervalSeconds(context, AiSrvrHealthPollJob.TIMESERIES_TRIGGER_NAME))
                            .as("추론 주기를 바꿨더니 시계열이 따라 움직였다 — 계통을 가른 조항이 "
                                    + "이름만 남는다")
                            .isEqualTo(TIMESERIES_DEFAULT_SECONDS);
                });
    }

    /** ★★ 대칭 — 반대 방향도 함께 문다. 한 방향만 두면 반대쪽이 조용히 깨진다. */
    @Test
    @DisplayName("★시계열_주기만_바꿔도_추론_점검_간격은_그대로다")
    void 시계열_주기만_바꿔도_추론_점검_간격은_그대로다() {
        runner.withPropertyValues(
                        "authoring.integration.ai-server.poll-enabled=true",
                        // 추론 값은 <주지 않는다> — 자기 기본값으로 서야 한다.
                        "vlm.client.poll-interval-seconds=41")
                .run(context -> {
                    assertThat(intervalSeconds(context, AiSrvrHealthPollJob.TIMESERIES_TRIGGER_NAME))
                            .isEqualTo(41);
                    assertThat(intervalSeconds(context, AiSrvrHealthPollJob.TRIGGER_NAME))
                            .as("시계열 주기를 바꿨더니 추론이 따라 움직였다")
                            .isEqualTo(INFERENCE_DEFAULT_SECONDS);
                });
    }

    /**
     * 아무 값도 주지 않아도 두 계통이 <b>각자 자기 기본값</b>으로 선다 — 공통값을 물려받지 않는다.
     *
     * <p>기본값이 서로 다른 것은 주기가 <b>상대를 두드리는 횟수</b>라서다. 임계는 우리 쪽 판정 기준만
     * 바꾸지만 주기는 외부 벤더에게 비용으로 그대로 간다.
     */
    @Test
    @DisplayName("★값을_주지_않아도_계통마다_자기_기본값으로_선다")
    void 값을_주지_않아도_계통마다_자기_기본값으로_선다() {
        runner.withPropertyValues("authoring.integration.ai-server.poll-enabled=true")
                .run(context -> {
                    assertThat(intervalSeconds(context, AiSrvrHealthPollJob.TRIGGER_NAME))
                            .isEqualTo(INFERENCE_DEFAULT_SECONDS);
                    assertThat(intervalSeconds(context, AiSrvrHealthPollJob.TIMESERIES_TRIGGER_NAME))
                            .isEqualTo(TIMESERIES_DEFAULT_SECONDS);
                });
    }

    /** 두 트리거 <b>모두</b> 첫 발화를 늦춘다 — 한쪽만 빼면 그 계통이 워밍업을 죽음으로 오판한다. */
    @Test
    @DisplayName("두_계통_모두_기동_첫_발화를_늦춘다")
    void 두_계통_모두_기동_첫_발화를_늦춘다() {
        runner.withPropertyValues("authoring.integration.ai-server.poll-enabled=true")
                .run(context -> {
                    for (Trigger trigger : context.getBeansOfType(Trigger.class).values()) {
                        assertThat(trigger.getStartTime())
                                .as("%s 가 기동 직후 발화한다", trigger.getKey())
                                .isAfter(new java.util.Date());
                    }
                });
    }

    /** 계통은 <b>잡 데이터</b>로 전달된다 — 잡이 「어느 계통을 훑는가」를 아는 유일한 통로. */
    @Test
    @DisplayName("★잡마다_훑을_계통이_잡_데이터로_못박혀_있다")
    void 잡마다_훑을_계통이_잡_데이터로_못박혀_있다() {
        runner.withPropertyValues("authoring.integration.ai-server.poll-enabled=true")
                .run(context -> {
                    assertThat(srvrTypeOf(context, AiSrvrHealthPollJob.JOB_NAME))
                            .isEqualTo(LsAiSrvr.SrvrType.INFERENCE.name());
                    assertThat(srvrTypeOf(context, AiSrvrHealthPollJob.TIMESERIES_JOB_NAME))
                            .isEqualTo(LsAiSrvr.SrvrType.TIMESERIES.name());
                });
    }

    /** 추론 계통의 잡 이름은 <b>바뀌지 않는다</b> — 바꾸면 기존 등록 행이 고아로 남아 계속 발화한다. */
    @Test
    @DisplayName("추론_계통의_잡_트리거_이름은_기존_그대로다")
    void 추론_계통의_잡_트리거_이름은_기존_그대로다() {
        assertThat(AiSrvrHealthPollJob.JOB_NAME).isEqualTo("aiSrvrHealthPollJob");
        assertThat(AiSrvrHealthPollJob.TRIGGER_NAME).isEqualTo("aiSrvrHealthPollTrigger");
    }

    /** 각 계통의 기본값 — 시험이 스스로 값을 지어내지 않도록 여기 한 곳에만 적는다. */
    private static final int INFERENCE_DEFAULT_SECONDS = 5;
    private static final int TIMESERIES_DEFAULT_SECONDS = 10;

    private static long intervalSeconds(org.springframework.context.ApplicationContext context,
                                        String triggerName) {
        SimpleTrigger trigger = (SimpleTrigger) context.getBeansOfType(Trigger.class).values().stream()
                .filter(t -> t.getKey().getName().equals(triggerName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("트리거가 없다: " + triggerName));
        return trigger.getRepeatInterval() / 1000L;
    }

    private static String srvrTypeOf(org.springframework.context.ApplicationContext context,
                                     String jobName) {
        return context.getBeansOfType(JobDetail.class).values().stream()
                .filter(j -> j.getKey().getName().equals(jobName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("잡이 없다: " + jobName))
                .getJobDataMap()
                .getString(AiSrvrHealthPollJob.SRVR_TYPE_KEY);
    }
}
