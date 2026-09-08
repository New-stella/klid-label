package kr.co.cudo.authoring.aiserver.job;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
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
 *
 * <h3>★★ 점검 <b>주기</b>는 계통마다 따로다 — 그래서 트리거도 계통마다 있다 (2026-09-08 확정)</h3>
 * <p>[@design AC-1099] [@design ADR-057]
 *
 * <table>
 *   <caption>계통별 주기 설정 자리</caption>
 *   <tr><th>계통</th><th>설정 키</th><th>기본값</th></tr>
 *   <tr><td>추론(INFERENCE)</td>
 *       <td>{@code authoring.integration.ai-server.poll-interval-seconds}</td><td>5초</td></tr>
 *   <tr><td>시계열(TIMESERIES)</td>
 *       <td>{@code vlm.client.poll-interval-seconds}</td><td>10초</td></tr>
 * </table>
 *
 * <p>임계를 가른 것과 <b>같은 근거</b>다 — 추론은 우리가 배포한 서버이고 시계열은 외부 벤더의
 * 서비스라 하나로 공유하면 한쪽을 조이려다 다른 쪽까지 움직인다. 시계열 키가 <b>벤더 축의 설정
 * 이름공간</b>({@code vlm.client})에 있는 것도 임계와 같다 — 추론 키 아래에 두면 이름만으로
 * 「ai-server 의 값」으로 읽혀 두 계통이 다시 얽힌다. <b>공통값을 두고 물려받게 하지 않는다.</b>
 *
 * <p>★ 주기에는 임계에 없는 축이 하나 더 있다 — <b>주기는 상대를 실제로 두드리는 횟수</b>라 그 값이
 * 외부 벤더의 장비에 <b>그대로 얹히는 부하</b>가 된다. 우리 서버는 우리가 감당하지만 벤더는 그렇지 않다.
 * ⚠ <b>근거는 부하이지 과금이 아니다</b>(2026-09-08 정정) — 연동 대상은 조직과 책임의 경계에서
 * 외부일 뿐 <b>망으로는 내부</b>에 있어, 보낸 만큼이 곧 요금이 되는 제3자 서비스가 아니다.
 *
 * <h3>★ 왜 「트리거 하나 + 최근 점검 시각으로 거르기」를 고르지 않았나 (되살리지 말 것)</h3>
 * <p>원장에 {@code CHCK_DT} 가 이미 있어 배선은 그쪽이 작다. 그런데 그 방식은 <b>시계열의 실효
 * 주기가 추론 주기보다 짧아질 수 없다</b> — 누가 시계열을 3초로 낮춰도 트리거가 5초마다 깨어나므로
 * 추론 주기가 바닥이 된다. 즉 <b>한쪽이 다른 쪽의 하한을 정한다</b>. 그것이 정확히 이 결정이
 * 없애려던 결합(「추론 주기를 바꿨더니 시계열이 따라 움직였다」)이라, 확정한 원칙에 구멍을 내지
 * 않으려고 트리거를 갈랐다. <b>대가</b>는 잡·트리거 식별자가 하나 늘고 잡이 「어느 계통을 훑는가」를
 * 알아야 한다는 것이며({@link AiSrvrHealthPollJob#SRVR_TYPE_KEY}), 그 대가를 알고 골랐다.
 *
 * <h3>⚠ 유지해야 할 성질 셋</h3>
 * <ul>
 *   <li><b>한 번의 점검 상한 × 그 계통의 장비 수</b>가 그 계통의 주기를 넘으면 틱이 뒤로 밀린다.
 *       계통을 가르면서 이 산수도 <b>계통별</b>이 됐다(전보다 각 축의 여유가 늘어난다).</li>
 *   <li><b>0 이하 흡수</b> — 0이면 Quartz 가 트리거를 만들지 못해 <b>기동이 통째로 깨진다</b>.
 *       두 자리가 <b>같은 함수</b>({@link #intervalSecondsOf})를 쓰게 해 한쪽만 방어되는 일을 막는다.</li>
 *   <li><b>기동 첫 발화 지연</b> — 두 트리거 <b>모두</b> 지연을 갖는다. 한쪽만 빼면 그 계통이
 *       워밍업 구간을 죽은 장비로 오판한다.</li>
 * </ul>
 *
 * <p>⚠ <b>인지·수용한 대가</b>: 주기가 길어지면 <b>죽은 장비가 목록에 남는 시간이 길어진다</b>.
 * 배제까지는 「주기 × 연속 실패 임계」라 시계열 기본값에서는 추론의 대략 <b>두 배</b>가 되고,
 * 그 구간의 위탁은 죽은 주소로 나갔다 실패한다. 근거 결정에 등재돼 있다.
 */
@Configuration
@ConditionalOnProperty(prefix = "authoring.integration.ai-server", name = "poll-enabled",
        havingValue = "true")
public class AiSrvrHealthPollTriggerConfig {

    /** 첫 발화 지연 — 기동 직후의 워밍업 구간을 죽은 노드로 오판하지 않도록 한 박자 늦춘다. */
    private static final long START_DELAY_MILLIS = 30_000L;

    /** 추론 계통 주기 — <b>기존 키·기존 기본값 그대로</b>다(이번 변경으로 움직이지 않는다). */
    @Value("${authoring.integration.ai-server.poll-interval-seconds:5}")
    private int inferencePollIntervalSeconds;

    /**
     * 시계열 계통 주기 — <b>벤더 축 이름공간에 자기 자리</b>를 갖는다(임계와 같은 자리).
     *
     * <p>기본값이 추론보다 긴 것은 이 값이 <b>외부 벤더를 두드리는 횟수</b>이기 때문이다.
     * ⚠ 추론 값을 물려받지 않는다 — 물려받게 하면 추론 주기를 바꾸는 순간 시계열이 함께 움직인다.
     */
    @Value("${vlm.client.poll-interval-seconds:10}")
    private int timeseriesPollIntervalSeconds;

    /**
     * 추론 계통 잡 — <b>계통별 새 이름</b>을 갖는다(2026-09-08 개명).
     *
     * <p>옛 이름을 그대로 쓰면 이미 등록된 정의가 갱신되지 않아 <b>계통 표식이 영원히 비어</b>
     * 있다(잡 정의 덮어쓰기를 켜지 않는 형상이다). 새 이름이라야 기존 배포에도 새로 등록된다.
     * 옛 정의는 계통을 알 수 없어 아무 일도 하지 않으며, 그 행을 걷어내는 것은 운영 절차의 몫이다.
     * [@design ADR-057]
     */
    @Bean
    public JobDetail aiSrvrInferenceHealthPollJobDetail() {
        return typeScopedJob(AiSrvrHealthPollJob.INFERENCE_JOB_NAME, LsAiSrvr.SrvrType.INFERENCE,
                "AI 추론 노드 상태점검·부하 관측");
    }

    @Bean
    public Trigger aiSrvrInferenceHealthPollTrigger(JobDetail aiSrvrInferenceHealthPollJobDetail) {
        return typeScopedTrigger(aiSrvrInferenceHealthPollJobDetail,
                AiSrvrHealthPollJob.INFERENCE_TRIGGER_NAME, inferencePollIntervalSeconds);
    }

    /**
     * 시계열 계통 잡 — <b>추론과 완전히 독립된 트리거</b>를 갖는다.
     *
     * <p>부하는 재지 않는다(그 계통에 「처리 대기」 개념이 없다) — 상태만 관측하며 그 분기는
     * 폴러가 소유한다.
     */
    @Bean
    public JobDetail aiSrvrTimeseriesHealthPollJobDetail() {
        return typeScopedJob(AiSrvrHealthPollJob.TIMESERIES_JOB_NAME, LsAiSrvr.SrvrType.TIMESERIES,
                "외부 시계열 분석 장비 상태점검");
    }

    @Bean
    public Trigger aiSrvrTimeseriesHealthPollTrigger(JobDetail aiSrvrTimeseriesHealthPollJobDetail) {
        return typeScopedTrigger(aiSrvrTimeseriesHealthPollJobDetail,
                AiSrvrHealthPollJob.TIMESERIES_TRIGGER_NAME, timeseriesPollIntervalSeconds);
    }

    /** 계통을 잡 데이터로 못 박는다 — 잡이 「어느 계통을 훑는가」를 아는 유일한 통로. */
    private static JobDetail typeScopedJob(String name, LsAiSrvr.SrvrType srvrType, String description) {
        return JobBuilder.newJob(AiSrvrHealthPollJob.class)
                .withIdentity(name, AiSrvrHealthPollJob.JOB_GROUP)
                .usingJobData(AiSrvrHealthPollJob.SRVR_TYPE_KEY, srvrType.name())
                .storeDurably()
                .withDescription(description)
                .build();
    }

    /**
     * 계통별 트리거 — <b>주기만 다르고 나머지 성질은 같다</b>.
     *
     * <p>지연·미스파이어 규칙을 여기 한 곳에 두는 이유는, 계통이 늘 때 한쪽만 성질을 잃는 일을
     * 막기 위해서다(첫 발화 지연을 한쪽에서 빠뜨리면 그 계통이 워밍업을 죽음으로 오판한다).
     */
    private static Trigger typeScopedTrigger(JobDetail jobDetail, String triggerName,
                                             int intervalSeconds) {
        return TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withIdentity(triggerName, AiSrvrHealthPollJob.JOB_GROUP)
                .startAt(new Date(System.currentTimeMillis() + START_DELAY_MILLIS))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInSeconds(intervalSecondsOf(intervalSeconds))
                        .repeatForever()
                        // 밀린 틱을 몰아서 발화시키지 않는다. 관측은 <지금 값>이 필요할 뿐이라
                        // 과거 시각의 틱을 되돌려 실행해 봐야 외부 호출만 늘어난다.
                        .withMisfireHandlingInstructionNextWithRemainingCount())
                .build();
    }

    /**
     * 오설정 흡수 — <b>두 자리 모두</b>에 건다.
     *
     * <p>0 이하면 Quartz 가 트리거를 만들지 못해 <b>기동이 통째로 깨진다</b>. 계통을 가르면서
     * <b>한쪽만 방어하는 일이 없도록</b> 두 자리가 이 함수를 함께 쓴다(임계 쪽 {@code clamp} 와
     * 같은 이유·같은 모양이다).
     */
    static int intervalSecondsOf(int configured) {
        return Math.max(1, configured);
    }
}
