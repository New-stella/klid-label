package kr.co.cudo.authoring.aiserver.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>잡 정의가 이미 등록돼 있는 배포</b>에서도 계통 구분이 성립하는가. [@design ADR-057]
 *
 * <h3>★★ 이 시험이 없어서 결함이 통과했다 — 깨끗한 환경만 검증했다</h3>
 * <p>이 시스템은 잡 정의를 <b>저장소에 두고</b> {@code spring.quartz.overwrite-existing-jobs} 를 켜지
 * 않는다(기본값 꺼짐). 그래서 <b>이미 등록된 정의는 덮어쓰이지 않는다</b> — 기존 잡 이름에 계통
 * 표식만 얹는 방식은 <b>새로 설치하는 환경에서만</b> 맞고, 이미 돌고 있는 배포에서는 표식이 빈 옛
 * 정의가 그대로 발화한다. 새로 설치하는 환경에서만 맞는 구분은 구분이 아니다.
 *
 * <p>여기서 재현하는 것은 그 형상 그대로다 — <b>옛 이름의 정의를 먼저 심어 두고</b>, 등록을
 * 「이미 있으면 건드리지 않는다」로 수행한 뒤 무엇이 남는지 본다.
 *
 * <p>⚠ <b>「덮어쓰기 켜기」는 채택하지 않았다</b> — 그 스위치는 이 잡 하나가 아니라 <b>전 잡</b>에
 * 걸려 다른 잡의 정의·주기까지 함께 갈아엎는다. 그래서 <b>이름을 가르는 쪽</b>을 골랐고, 고아로 남는
 * 옛 정의는 {@link AiSrvrHealthPollJob} 의 fail-closed 가 무해하게 만든다(그 축은
 * {@code AiSrvrHealthPollJobTest} 가 지킨다 — 두 시험이 짝이다).
 */
class AiSrvrHealthPollJobRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(AiSrvrHealthPollTriggerConfig.class)
            .withPropertyValues("authoring.integration.ai-server.poll-enabled=true");

    @Test
    @DisplayName("★★옛_정의가_남아_있어도_계통별_잡이_새로_등록된다_그리고_옛_정의는_표식을_얻지_못한다")
    void 옛_정의가_남아_있어도_계통별_잡이_새로_등록된다() {
        runner.run(context -> {
            Scheduler scheduler = ramScheduler();
            try {
                // given — 이미 돌고 있는 배포: 계통 축 이전의 정의가 옛 이름으로 등록돼 있다.
                scheduler.addJob(legacyJobDetail(), true);

                // when — 새 판이 뜬다. 덮어쓰기가 꺼져 있으므로 <이미 있으면 건드리지 않는다>.
                for (JobDetail detail : context.getBeansOfType(JobDetail.class).values()) {
                    addJobIfAbsent(scheduler, detail);
                }

                // then ① — 옛 정의는 그대로 남고 <계통 표식을 얻지 못한다>. 이것이 결함의 뿌리다.
                JobDetail legacy = scheduler.getJobDetail(legacyKey());
                assertThat(legacy).isNotNull();
                assertThat(legacy.getJobDataMap().getString(AiSrvrHealthPollJob.SRVR_TYPE_KEY))
                        .as("덮어쓰기가 꺼져 있어 옛 정의에는 표식이 얹히지 않는다")
                        .isNull();

                // then ② — 그럼에도 계통별 잡은 <새 이름이라> 실제로 등록됐다. 이름을 옛것으로 두면
                //   ①에 걸려 아무 계통 잡도 생기지 않는다.
                assertThat(typeOf(scheduler, AiSrvrHealthPollJob.INFERENCE_JOB_NAME))
                        .isEqualTo("INFERENCE");
                assertThat(typeOf(scheduler, AiSrvrHealthPollJob.TIMESERIES_JOB_NAME))
                        .isEqualTo("TIMESERIES");
            } finally {
                scheduler.shutdown(true);
            }
        });
    }

    /**
     * ★ 대조 — <b>옛 이름을 그대로 쓰면</b> 표식이 영원히 들어가지 않는다.
     *
     * <p>「새 이름이라 등록된다」는 주장이 <b>실제로 무엇을 막는지</b>를 보여 주는 음성 대조다.
     * 이것이 없으면 위 시험이 「그냥 이름이 둘이다」로만 읽힌다.
     */
    @Test
    @DisplayName("★대조_옛_이름으로_등록하려_하면_표식이_들어가지_않는다")
    void 옛_이름으로_등록하려_하면_표식이_들어가지_않는다() throws SchedulerException {
        Scheduler scheduler = ramScheduler();
        try {
            scheduler.addJob(legacyJobDetail(), true);

            // 새 판이 <옛 이름 그대로> 표식을 얹은 정의를 등록하려 한다.
            addJobIfAbsent(scheduler, JobBuilder.newJob(AiSrvrHealthPollJob.class)
                    .withIdentity(legacyKey())
                    .usingJobData(AiSrvrHealthPollJob.SRVR_TYPE_KEY, "INFERENCE")
                    .storeDurably()
                    .build());

            assertThat(scheduler.getJobDetail(legacyKey()).getJobDataMap()
                    .getString(AiSrvrHealthPollJob.SRVR_TYPE_KEY))
                    .as("이미 등록된 정의는 덮어쓰이지 않으므로 표식이 들어갈 자리가 없다")
                    .isNull();
        } finally {
            scheduler.shutdown(true);
        }
    }

    // --- fixtures ------------------------------------------------------------------------------

    /**
     * 스프링의 잡 등록 규칙을 그대로 옮긴 것 — 덮어쓰기가 꺼져 있으면 <b>이미 있는 키는 건드리지
     * 않는다</b>({@code SchedulerFactoryBean#addJobToScheduler}).
     */
    private static void addJobIfAbsent(Scheduler scheduler, JobDetail detail) throws SchedulerException {
        if (scheduler.getJobDetail(detail.getKey()) == null) {
            scheduler.addJob(detail, true);
        }
    }

    private static JobDetail legacyJobDetail() {
        // 계통 축 이전의 정의 — 잡 데이터가 비어 있다.
        return JobBuilder.newJob(AiSrvrHealthPollJob.class)
                .withIdentity(legacyKey())
                .storeDurably()
                .build();
    }

    private static JobKey legacyKey() {
        return JobKey.jobKey(AiSrvrHealthPollJob.LEGACY_JOB_NAME, AiSrvrHealthPollJob.JOB_GROUP);
    }

    private static String typeOf(Scheduler scheduler, String jobName) throws SchedulerException {
        JobDetail detail = scheduler.getJobDetail(
                JobKey.jobKey(jobName, AiSrvrHealthPollJob.JOB_GROUP));
        assertThat(detail).as("계통 잡이 등록되지 않았다: %s", jobName).isNotNull();
        return detail.getJobDataMap().getString(AiSrvrHealthPollJob.SRVR_TYPE_KEY);
    }

    /** 메모리 저장소 스케줄러 — 이 시험은 저장 매체가 아니라 <b>등록 규칙</b>을 잰다. */
    private static Scheduler ramScheduler() throws SchedulerException {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "aiSrvrRegTest-" + UUID.randomUUID());
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        // 발화시키지 않는다 — 등록 규칙만 본다(시작하면 잡이 실제로 돌아 폴러를 찾는다).
        return new StdSchedulerFactory(props).getScheduler();
    }
}
