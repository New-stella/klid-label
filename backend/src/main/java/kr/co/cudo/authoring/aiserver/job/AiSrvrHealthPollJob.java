package kr.co.cudo.authoring.aiserver.job;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.service.AiSrvrHealthPoller;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 노드 상태점검·부하 관측 Quartz 잡. [@design ADR-057]
 *
 * <h3>방어</h3>
 * <ul>
 *   <li>{@link DisallowConcurrentExecution} — <b>같은 노드</b>에서 틱이 겹치지 않게 한다. 관측이
 *       주기보다 오래 걸리면(노드가 전부 상한까지 기다린 경우) 다음 틱이 뒤에 쌓인다.</li>
 *   <li>2노드 Active-Active 에서의 중복 발화는 Quartz 클러스터링이 막는다. 겹쳐 발화해도 결과가
 *       <b>같은 값의 덮어쓰기</b>라 손상되지 않지만, 외부 호출이 두 배가 된다.</li>
 *   <li>예외를 밖으로 내보내지 않는다 — 잡이 예외로 끝나면 스케줄러 로그에만 남고 다음 틱까지
 *       아무 기록이 없다. 노드 단위 격리는 폴러가 갖는다.</li>
 * </ul>
 *
 * <p>⚠ 이 잡은 <b>등록 자체가 설정으로 막혀 있다</b>(기본 꺼짐). 이유는
 * {@link AiSrvrHealthPollTriggerConfig} 와 {@link AiSrvrHealthPoller} 의 javadoc 참조.
 */
@Slf4j
@DisallowConcurrentExecution
public class AiSrvrHealthPollJob implements Job {

    /**
     * <b>추론 계통</b> 잡 이름 — 이름을 <b>바꾸지 않는다</b>.
     *
     * <p>계통을 가르면서 이 이름을 「…Inference」 로 개명하고 싶어지지만, 개명하면 <b>기존 등록 행이
     * 고아로 남아 계속 발화</b>한다(덮어쓰기는 <b>같은 식별자</b>만 대체하고 사라진 식별자의 행을
     * 지우지는 않는다). 그러면 추론이 두 번 관측되고 외부 호출이 두 배가 된다. 그래서 <b>기존 축이
     * 이름을 그대로 갖고 새 축이 새 이름을 갖는다</b> — 비대칭은 의도다.
     */
    public static final String JOB_NAME = "aiSrvrHealthPollJob";
    public static final String JOB_GROUP = "aiserver";
    public static final String TRIGGER_NAME = "aiSrvrHealthPollTrigger";

    /** <b>시계열 계통</b> 잡·트리거 이름 — 2026-09-08 신설(위 {@link #JOB_NAME} javadoc 참조). */
    public static final String TIMESERIES_JOB_NAME = "aiSrvrHealthPollJobTimeseries";
    public static final String TIMESERIES_TRIGGER_NAME = "aiSrvrHealthPollTriggerTimeseries";

    /**
     * 이 틱이 훑을 <b>계통</b>을 담는 잡 데이터 키. [@design AC-1099]
     *
     * <p>값은 {@link LsAiSrvr.SrvrType} 의 이름 <b>문자열</b>이다 — Quartz JobStore 가
     * {@code useProperties=true} 로 동작해 잡 데이터에 문자열만 담을 수 있다(다른 타입을 담으면
     * 직렬화 단계에서 기동이 깨진다).
     */
    public static final String SRVR_TYPE_KEY = "srvrTypeCd";

    @Autowired
    private AiSrvrHealthPoller poller;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            poller.poll(srvrTypeOf(context));
        } catch (RuntimeException failure) {
            log.warn("[AiSrvr] 상태점검 틱이 실패했습니다. 원인={}", failure.getClass().getSimpleName());
        }
    }

    /**
     * 이 틱의 계통 — <b>모르면 전 계통</b>이다.
     *
     * <p>계통을 알 수 없는 경우는 둘이며 <b>둘 다 원인이 같다</b> — 이 잡이 계통을 갖지 않던 시절의
     * 등록 행이거나(키 부재), 사람이 손으로 고친 행이다(알 수 없는 값). 그때 아무것도 하지 않으면
     * 상태점검이 통째로 멈춰 죽은 장비를 걸러 낼 수단이 사라지는데, 근거 기준이 <b>「안 도는 쪽이 더
     * 위험하다」</b>고 못 박고 있으므로 전 계통으로 되돌아간다.
     *
     * <p>⚠ 그 폴백이 도는 동안 <b>시계열이 두 번 관측될 수 있다</b>(구 행 + 새 시계열 트리거).
     * 결과는 같은 값의 덮어쓰기라 손상되지 않지만 외부 호출이 두 배가 되므로, <b>두 경우 모두</b>
     * WARN 으로 드러낸다 — 조용히 두면 벤더 호출량이 는 이유를 아무도 못 찾는다.
     *
     * <p>⚠ <b>구 동작 정정(2026-09-08)</b> — WARN 이 <b>「알 수 없는 값」 경로에만</b> 있었고, 정작 위
     * 문단이 지목한 시나리오(계통이 없던 시절의 등록 행 = <b>키 부재</b>)는 <b>조용히</b> 폴백했다.
     * 선언과 코드가 어긋난 것이며, 더 흔한 쪽이 보이지 않는 방향으로 어긋나 있었다. 두 분기를 한
     * 자리로 합쳐 그 어긋남이 다시 생길 자리를 없앴다.
     *
     * <p>⚠ <b>도달성은 낮다</b> — 잡 등록 갱신이 같은 식별자 행의 잡 데이터를 새 값으로 대체하므로
     * 정상 배포에서는 키가 늘 채워진다. 남는 경로는 수동·프로그래밍 등록과 되돌림 배포뿐이다.
     */
    private LsAiSrvr.SrvrType srvrTypeOf(JobExecutionContext context) {
        String raw = context.getMergedJobDataMap().getString(SRVR_TYPE_KEY);
        if (raw == null || raw.isBlank()) {
            warnAllTypeFallback(context, "계통이 지정되지 않았습니다(계통 축 이전의 등록 행)");
            return null;
        }
        try {
            return LsAiSrvr.SrvrType.valueOf(raw.trim());
        } catch (IllegalArgumentException unknown) {
            warnAllTypeFallback(context, "계통 값을 알 수 없습니다");
            return null;
        }
    }

    /**
     * 전 계통 폴백을 <b>드러내는 유일한 자리</b> — 두 분기가 이 하나를 함께 쓴다.
     *
     * <p>⚠ <b>잡 데이터 값을 로그에 싣지 않는다</b>. 그 값은 원장 밖 저장소에서 오고 손으로 고칠 수
     * 있어 개행이 섞이면 기록 위조 통로가 된다(CWE-117). 어느 잡인지는 <b>잡 식별자</b>로 갈리며,
     * 사유는 이 클래스가 소유한 <b>고정 문구</b>라 외부 입력이 아니다.
     */
    private void warnAllTypeFallback(JobExecutionContext context, String reason) {
        log.warn("[AiSrvr] 상태점검 잡이 전 계통을 훑습니다({}) — 계통별 트리거와 겹치면 외부 호출이"
                        + " 두 배가 될 수 있습니다. jobKey={}",
                reason, context.getJobDetail().getKey().getName());
    }
}
