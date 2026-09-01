package kr.co.cudo.authoring.aiserver.job;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Date;

/**
 * 상태점검 잡 등록 — <b>설정이 켜져 있을 때만</b>. [@design ADR-057]
 *
 * <h3>★ 기본값이 꺼짐인 이유 (관측을 켜면 멀쩡한 장비를 잃는다)</h3>
 * <p>상태 점검과 부하 조회는 둘 다 <b>추론 서버가 요청을 받아 응답할 여유가 있어야</b> 성립한다.
 * 실행을 용도별로 나누기 전에는 추론이 서버의 처리 흐름을 통째로 붙잡아 <b>상태 점검조차 늦어지므로</b>,
 * 그 시기에 관측을 켜면 <b>바쁜 장비를 죽은 장비로 오판</b>한다. 장비가 하나뿐이면 마지막 하나를 지키는
 * 장치가 막아 주지만, <b>둘 이상이면 먼저 판정된 하나는 그 장치에 걸리지 않아</b> 멀쩡한 장비를 하나
 * 잃는다. 그래서 <b>부하 축만이 아니라 상태 점검 축까지 함께</b> 꺼둔 상태로 들어간다.
 *
 * <p>「등록하지 않는다」를 고른 이유: 등록해 두고 잡 안에서 되돌아가면 <b>발화 이력이 쌓여</b> 켜져
 * 있는 것처럼 보이고, 클러스터 트리거 표에도 남아 운영자가 상태를 오독한다. 켜는 스위치가 곧
 * 등록 여부여야 상태가 한눈에 드러난다.
 *
 * <p>⚠ 폴러 자신도 같은 설정을 한 번 더 본다. 두 겹인 이유는 프로그래밍 호출·수동 트리거로 들어오는
 * 경로가 이 등록 조건을 우회하기 때문이다.
 */
@Configuration
@ConditionalOnProperty(prefix = "authoring.integration.ai-server", name = "poll-enabled",
        havingValue = "true")
public class AiSrvrHealthPollTriggerConfig {

    /** 첫 발화 지연 — 기동 직후의 워밍업 구간을 죽은 노드로 오판하지 않도록 한 박자 늦춘다. */
    private static final long START_DELAY_MILLIS = 30_000L;

    @Value("${authoring.integration.ai-server.poll-interval-seconds:5}")
    private int pollIntervalSeconds;

    @Bean
    public JobDetail aiSrvrHealthPollJobDetail() {
        return JobBuilder.newJob(AiSrvrHealthPollJob.class)
                .withIdentity(AiSrvrHealthPollJob.JOB_NAME, AiSrvrHealthPollJob.JOB_GROUP)
                .storeDurably()
                .withDescription("AI 추론 노드 상태점검·부하 관측")
                .build();
    }

    @Bean
    public Trigger aiSrvrHealthPollTrigger(JobDetail aiSrvrHealthPollJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(aiSrvrHealthPollJobDetail)
                .withIdentity(AiSrvrHealthPollJob.TRIGGER_NAME, AiSrvrHealthPollJob.JOB_GROUP)
                .startAt(new Date(System.currentTimeMillis() + START_DELAY_MILLIS))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        // 0 이하면 Quartz 가 트리거를 만들지 못해 기동이 깨진다 — 하한으로 흡수한다.
                        .withIntervalInSeconds(Math.max(1, pollIntervalSeconds))
                        .repeatForever()
                        // 밀린 틱을 몰아서 발화시키지 않는다. 관측은 <지금 값>이 필요할 뿐이라
                        // 과거 시각의 틱을 되돌려 실행해 봐야 외부 호출만 늘어난다.
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }
}
