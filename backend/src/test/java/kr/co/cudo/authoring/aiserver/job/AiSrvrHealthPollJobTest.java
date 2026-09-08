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
import static org.mockito.Mockito.never;
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
 * <h3>★★ 계통을 모르면 <b>아무것도 하지 않는다</b> (fail-closed · 2026-09-08)</h3>
 * <p>⚠ <b>구 동작 폐기</b> — 계통을 모르면 <b>전 계통</b>으로 되돌아갔다. 계통 전용 잡이 따로 등록된
 * 뒤로 그 폴백은 <b>두 경로가 같은 장비를 함께 훑게</b> 만들고, 두 경로는 이름이 달라 서로를 막지
 * 못해 같은 연속 실패 계수를 각각 읽고 각각 써 <b>한쪽 갱신이 유실</b>된다 — 계수가 임계에 닿지 못해
 * <b>죽은 장비가 가용으로 남는다</b>. 그 폴백이 지키려던 「안 도는 쪽이 더 위험하다」의 전제가
 * 뒤집힌 것이다.
 *
 * <p>그리고 <b>건너뛴 사실은 조용하면 안 된다</b> — 옛 이름으로 등록된 정의가 남아 있는 배포에서는
 * 이 경고가 <b>그 행을 걷어내라</b>는 유일한 신호다. 두 경로(키 부재 · 알 수 없는 값)를
 * <b>대칭으로</b> 물어 한쪽만 조용해지는 어긋남이 다시 생기지 않게 한다.
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
     * ★★ <b>이 라운드의 핵심</b> — <b>키 부재</b>(계통 축 이전의 등록 행)면 <b>아무것도 하지 않는다</b>.
     *
     * <p>이 잡 정의는 <b>이미 돌고 있는 배포의 저장소에 남아 있다</b>. 잡 정의 덮어쓰기가 꺼진
     * 형상이라 새 배포도 그 행을 갱신하지 않기 때문이다. 그 행이 전 계통을 훑으면 새로 등록된 계통
     * 전용 잡과 대상이 겹쳐 <b>같은 장비의 연속 실패 계수를 두 경로가 각각 읽고 각각 써</b>
     * 한쪽 갱신이 유실된다 — 죽은 장비가 가용으로 남는다.
     */
    @Test
    @DisplayName("★★계통_키가_없으면_아무것도_하지_않고_그_사실을_경고로_남긴다")
    void 계통_키가_없으면_아무것도_하지_않는다() {
        job.execute(contextWith(null));

        verify(poller, never()).poll(org.mockito.ArgumentMatchers.any());
        assertThat(warnings())
                .as("조용히 건너뛰면 관측이 멈춘 이유를 아무도 찾지 못한다")
                .hasSize(1)
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("아무것도 하지 않았습니다")
                .contains(AiSrvrHealthPollJob.LEGACY_JOB_NAME);
    }

    /**
     * ★ <b>옛 이름으로 등록된 정의</b>가 그대로 발화하는 상황 — 이미 돌고 있는 배포의 재현.
     *
     * <p>구 회귀는 <b>깨끗한 환경만</b> 검증해 이 자리를 통과시켰다. 옛 정의는 이름도 옛것이고 잡
     * 데이터도 비어 있으므로, 두 성질을 <b>함께</b> 재현해 그 틱이 관측을 한 건도 내지 않는지 본다.
     */
    @Test
    @DisplayName("★★옛_이름으로_등록된_잡_정의가_발화해도_관측이_한_건도_나가지_않는다")
    void 옛_이름으로_등록된_잡_정의는_아무것도_하지_않는다() {
        job.execute(legacyContext());

        verify(poller, never()).poll(org.mockito.ArgumentMatchers.any());
        assertThat(warnings()).hasSize(1);
    }

    /** 빈 문자열도 같은 축이다 — 「값이 있다」로 읽어 열거 변환에서 터지면 틱이 통째로 죽는다. */
    @Test
    @DisplayName("계통_값이_공백이어도_아무것도_하지_않고_경고를_남긴다")
    void 계통_값이_공백이어도_경고를_남긴다() {
        job.execute(contextWith("   "));

        verify(poller, never()).poll(org.mockito.ArgumentMatchers.any());
        assertThat(warnings()).hasSize(1);
    }

    /** ★ 대칭 — 「알 수 없는 값」 경로도 같은 답이다(한쪽만 열어 두지 않는다). */
    @Test
    @DisplayName("★계통_값을_알_수_없어도_아무것도_하지_않고_경고를_남긴다")
    void 계통_값을_알_수_없어도_경고를_남긴다() {
        job.execute(contextWith("GPU_FARM"));

        verify(poller, never()).poll(org.mockito.ArgumentMatchers.any());
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
        return contextWith(srvrType, AiSrvrHealthPollJob.LEGACY_JOB_NAME);
    }

    /** 옛 이름 + 잡 데이터 없음 — 이미 돌고 있는 배포에 남아 있는 정의 그대로. */
    private static JobExecutionContext legacyContext() {
        return contextWith(null, AiSrvrHealthPollJob.LEGACY_JOB_NAME);
    }

    private static JobExecutionContext contextWith(String srvrType, String jobName) {
        JobDataMap data = new JobDataMap();
        if (srvrType != null) {
            data.put(AiSrvrHealthPollJob.SRVR_TYPE_KEY, srvrType);
        }
        JobDetail detail = JobBuilder.newJob(AiSrvrHealthPollJob.class)
                .withIdentity(jobName, AiSrvrHealthPollJob.JOB_GROUP)
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
