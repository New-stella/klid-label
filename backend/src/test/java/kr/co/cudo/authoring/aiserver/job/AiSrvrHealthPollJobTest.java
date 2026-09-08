package kr.co.cudo.authoring.aiserver.job;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.service.AiSrvrHealthPoller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 상태점검 잡이 <b>어느 계통을 훑는가</b> — 잡 축의 계약. [@design AC-1099] [@design ADR-057]
 *
 * <h3>왜 폴러 시험으로 대체할 수 없는가</h3>
 * <p>폴러 쪽에도 「계통을 주지 않으면 전 계통을 훑는다」가 있지만 그것은 <b>폴러 축</b>이다 — 인자로
 * {@code null} 을 받았을 때의 동작을 잰다. 여기서 재는 것은 <b>잡 축</b>이다: 잡 데이터에서 계통을
 * <b>어떻게 읽어 그 인자를 만드는가</b>, 그리고 읽지 못했을 때 <b>그 사실을 드러내는가</b>.
 * 두 축은 서로를 대신하지 못한다(잡이 계통을 잘못 읽어도 폴러 시험은 전부 초록이다).
 *
 * <h3>★ 전 계통 폴백은 <b>조용하면 안 된다</b></h3>
 * <p>그 폴백이 도는 동안 계통별 트리거와 겹쳐 <b>시계열이 두 번 관측</b>될 수 있다. 결과는 같은 값의
 * 덮어쓰기라 손상되지 않지만 <b>외부 벤더 호출이 두 배</b>가 되므로, 드러나지 않으면 호출량이 는
 * 이유를 아무도 찾지 못한다.
 *
 * <p>⚠ <b>이 시험이 닫는 어긋남(2026-09-08)</b> — 구현의 javadoc 은 「WARN 으로 드러낸다」고 선언해
 * 두고, 정작 그 문단이 <b>지목한 시나리오</b>(계통 축 이전의 등록 행 = <b>키 부재</b>)는 조용히
 * 폴백했다. WARN 은 「알 수 없는 값」 경로에만 있었다. 두 경로를 <b>대칭으로</b> 물어 그 어긋남이
 * 다시 생기지 않게 한다.
 */
class AiSrvrHealthPollJobTest {

    private AiSrvrHealthPoller poller;
    private AiSrvrHealthPollJob job;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        poller = mock(AiSrvrHealthPoller.class);
        job = new AiSrvrHealthPollJob();
        // 잡은 Quartz 가 만들고 스프링이 필드로 주입한다 — 생성자가 없어 여기서 직접 꽂는다.
        ReflectionTestUtils.setField(job, "poller", poller);
        logs = new ListAppender<>();
        logs.start();
        jobLogger().addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        jobLogger().detachAppender(logs);
        logs.stop();
    }

    @Test
    @DisplayName("★잡_데이터의_계통이_그대로_폴러로_전달된다")
    void 잡_데이터의_계통이_그대로_폴러로_전달된다() {
        job.execute(contextWith(LsAiSrvr.SrvrType.TIMESERIES.name()));

        verify(poller).poll(LsAiSrvr.SrvrType.TIMESERIES);
        // 정상 경로는 조용하다 — 매 틱 경고를 내면 진짜 이상 신호가 묻힌다.
        assertThat(warnings()).isEmpty();
    }

    @Test
    @DisplayName("추론_계통도_그대로_전달된다_대칭")
    void 추론_계통도_그대로_전달된다() {
        job.execute(contextWith(LsAiSrvr.SrvrType.INFERENCE.name()));

        verify(poller).poll(LsAiSrvr.SrvrType.INFERENCE);
        assertThat(warnings()).isEmpty();
    }

    /**
     * ★ <b>키 부재</b> — 계통 축 이전의 등록 행. 전 계통으로 훑되 <b>그 사실을 드러낸다</b>.
     *
     * <p>이것이 구현 javadoc 이 지목했던 바로 그 시나리오인데 <b>로그가 없었다</b>. 도달성은 낮지만
     * (등록 갱신이 잡 데이터를 대체한다) 남는 경로가 수동·프로그래밍 등록과 되돌림 배포이며,
     * 그 상황은 정확히 사람이 알아야 할 상황이다.
     */
    @Test
    @DisplayName("★계통_키가_없으면_전_계통을_훑고_그_사실을_경고로_남긴다")
    void 계통_키가_없으면_전_계통을_훑고_경고를_남긴다() {
        job.execute(contextWith(null));

        verify(poller).poll(null);
        assertThat(warnings())
                .as("조용히 폴백하면 벤더 호출량이 두 배가 된 이유를 아무도 찾지 못한다")
                .hasSize(1)
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("전 계통")
                .contains(AiSrvrHealthPollJob.JOB_NAME);
    }

    /** 빈 문자열도 같은 축이다 — 「값이 있다」로 읽어 열거 변환에서 터지면 틱이 통째로 죽는다. */
    @Test
    @DisplayName("계통_값이_공백이어도_전_계통을_훑고_경고를_남긴다")
    void 계통_값이_공백이어도_경고를_남긴다() {
        job.execute(contextWith("   "));

        verify(poller).poll(null);
        assertThat(warnings()).hasSize(1);
    }

    /** ★ 대칭 — 「알 수 없는 값」 경로의 경고는 종전 그대로 유지된다(한쪽만 남기지 않는다). */
    @Test
    @DisplayName("★계통_값을_알_수_없어도_전_계통을_훑고_경고를_남긴다")
    void 계통_값을_알_수_없어도_경고를_남긴다() {
        job.execute(contextWith("GPU_FARM"));

        verify(poller).poll(null);
        assertThat(warnings()).hasSize(1);
    }

    /**
     * ⚠ 잡 데이터 <b>값</b>은 로그에 싣지 않는다 — 원장 밖 저장소에서 오고 손으로 고칠 수 있어
     * 개행이 섞이면 기록 위조 통로가 된다(CWE-117). 어느 잡인지는 <b>잡 식별자</b>로 갈린다.
     */
    @Test
    @DisplayName("★계통_값을_로그에_그대로_싣지_않는다")
    void 계통_값을_로그에_그대로_싣지_않는다() {
        job.execute(contextWith("MALICIOUS\nINJECTED-LINE"));

        assertThat(warnings())
                .hasSize(1)
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .doesNotContain("INJECTED-LINE")
                .doesNotContain("MALICIOUS");
    }

    /** 폴러가 터져도 틱이 예외로 끝나지 않는다 — 스케줄러 로그에만 남고 다음 틱까지 기록이 없다. */
    @Test
    @DisplayName("폴러가_실패해도_예외를_밖으로_내보내지_않는다")
    void 폴러가_실패해도_예외를_밖으로_내보내지_않는다() {
        org.mockito.BDDMockito.willThrow(new IllegalStateException("boom"))
                .given(poller).poll(LsAiSrvr.SrvrType.INFERENCE);

        job.execute(contextWith(LsAiSrvr.SrvrType.INFERENCE.name()));

        assertThat(warnings()).hasSize(1);
    }

    /** 잡 데이터에 계통을 담은 실행 문맥 — {@code null} 이면 키 자체를 넣지 않는다. */
    private static JobExecutionContext contextWith(String srvrType) {
        JobDataMap data = new JobDataMap();
        if (srvrType != null) {
            data.put(AiSrvrHealthPollJob.SRVR_TYPE_KEY, srvrType);
        }
        JobDetail detail = JobBuilder.newJob(AiSrvrHealthPollJob.class)
                .withIdentity(AiSrvrHealthPollJob.JOB_NAME, AiSrvrHealthPollJob.JOB_GROUP)
                .storeDurably()
                .build();
        JobExecutionContext context = mock(JobExecutionContext.class);
        given(context.getMergedJobDataMap()).willReturn(data);
        given(context.getJobDetail()).willReturn(detail);
        return context;
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static ch.qos.logback.classic.Logger jobLogger() {
        return (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(AiSrvrHealthPollJob.class);
    }
}
