package kr.co.cudo.authoring.aiserver.job;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.service.AiSrvrHealthPoller;
import kr.co.cudo.authoring.common.client.VlmClient;
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
 *   <li>여러 노드 Active-Active 에서의 중복 발화는 Quartz 클러스터링이 막는다.</li>
 *   <li>예외를 밖으로 내보내지 않는다 — 잡이 예외로 끝나면 스케줄러 로그에만 남고 다음 틱까지
 *       아무 기록이 없다. 노드 단위 격리는 폴러가 갖는다.</li>
 * </ul>
 *
 * <p>⚠ 이 잡은 <b>등록 자체가 설정으로 막혀 있다</b>(기본 꺼짐). 이유는
 * {@link AiSrvrHealthPollTriggerConfig} 와 {@link AiSrvrHealthPoller} 의 javadoc 참조.
 *
 * <h3>★★ 계통 구분은 <b>이미 돌고 있는 배포에서도</b> 성립해야 한다 (2026-09-08) [@design ADR-057]</h3>
 * <p>잡 정의는 <b>저장소에 영속</b>되고 이 시스템은 <b>이미 등록된 정의를 덮어쓰지 않는 설정</b>으로
 * 돈다. 그래서 「기존 잡 이름에 계통 표식만 얹는다」는 방식은 <b>새로 설치하는 환경에서만</b> 맞다 —
 * 이미 그 잡이 등록된 환경에서는 표식이 빈 옛 정의가 그대로 발화한다. 그 상태에서 계통을 모를 때
 * 전 계통을 훑으면 새로 등록된 계통 전용 잡과 대상이 겹쳐
 * <b>같은 창구를 두 경로가 함께 두드리고</b>, 두 경로는 이름이 달라 서로를 막지 못하므로 같은 장비의
 * 연속 실패 계수를 각각 읽고 각각 써 <b>한쪽 갱신이 유실</b>된다 — 죽은 장비가 가용으로 남는,
 * 이 축이 없애려던 증상 그 자체다.
 *
 * <p>그래서 <b>셋을 함께</b> 한다(둘만으로는 구멍이 남는다).
 * <ol>
 *   <li><b>계통을 모르면 아무것도 하지 않는다</b>(fail-closed) — {@link #srvrTypeOf}. 옛 정의가
 *       그대로 발화해도 관측을 한 건도 내지 않는다.</li>
 *   <li><b>추론 잡·트리거도 계통별 새 이름을 갖는다</b>({@link #INFERENCE_JOB_NAME}) — 새 이름이라
 *       기존 배포에도 <b>새로 등록</b>된다. 1만 하면 추론 점검이 통째로 멈추므로 반드시 함께 한다.</li>
 *   <li>옛 정의·발화 등록 행을 걷어내는 <b>운영 절차</b>가 뒤를 받는다(되돌림 절차서 소관 —
 *       코드가 지우지 않는다).</li>
 * </ol>
 *
 * <p>★ <b>「덮어쓰기 켜기」는 채택하지 않았다</b> — 그 스위치({@code spring.quartz.overwrite-existing-jobs})
 * 는 이 잡 하나가 아니라 <b>전 잡</b>에 걸려 다른 잡의 정의·주기까지 함께 갈아엎는다. 되살리지 말 것.
 *
 * <p>⚠ <b>구 서술 폐기(2026-09-08)</b> — 여기 <i>「추론 계통 잡 이름을 바꾸지 않는다. 개명하면 기존
 * 등록 행이 고아로 남아 계속 발화해 추론이 두 번 관측된다」</i>고 적혀 있었다. <b>그 전제가 1로
 * 닫혔다</b> — 고아로 남은 옛 정의는 계통을 알 수 없어 <b>아무것도 하지 않는다</b>. 지우지 않고
 * 남기는 이유는, 개명의 위험이 무엇이었고 무엇이 그것을 닫았는지가 사라지면 다음 사람이 fail-closed
 * 를 「너무 엄격하다」며 되돌리기 때문이다.
 */
@Slf4j
@DisallowConcurrentExecution
public class AiSrvrHealthPollJob implements Job {

    /**
     * <b>[폐기된 옛 식별자]</b> 계통 축 이전에 쓰던 잡 이름 — <b>지우지 않는다</b>.
     *
     * <p>이 이름으로 등록된 정의가 <b>이미 돌고 있는 배포의 저장소에 남아 있다</b>. 그 행을 걷어내는
     * 운영 절차와 이 잡의 fail-closed 회귀 가드가 이 값을 가리켜야 하므로 상수로 남긴다.
     * <b>새 등록에 쓰지 말 것</b> — 계통 축 잡은 아래 둘이다.
     */
    public static final String LEGACY_JOB_NAME = "aiSrvrHealthPollJob";

    /** <b>[폐기된 옛 식별자]</b> 위 {@link #LEGACY_JOB_NAME} 의 트리거 — 같은 이유로 남긴다. */
    public static final String LEGACY_TRIGGER_NAME = "aiSrvrHealthPollTrigger";

    public static final String JOB_GROUP = "aiserver";

    /**
     * <b>추론 계통</b> 잡·트리거 이름 — 2026-09-08 개명(위 클래스 javadoc §계통 구분은 참조).
     *
     * <p>옛 이름을 그대로 쓰면 <b>이미 등록된 정의가 갱신되지 않아</b> 계통 표식이 영원히 비어 있다.
     * 새 이름이라야 기존 배포에도 새로 등록된다.
     */
    public static final String INFERENCE_JOB_NAME = "aiSrvrHealthPollJobInference";
    public static final String INFERENCE_TRIGGER_NAME = "aiSrvrHealthPollTriggerInference";

    /** <b>시계열 계통</b> 잡·트리거 이름 — 2026-09-08 신설. */
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
        LsAiSrvr.SrvrType srvrType = srvrTypeOf(context);
        if (srvrType == null) {
            // ★fail-closed — 계통을 모르는 틱은 <아무것도 하지 않는다>. 위 클래스 javadoc 참조.
            return;
        }
        try {
            poller.poll(srvrType);
        } catch (RuntimeException failure) {
            log.warn("[AiSrvr] 상태점검 틱이 실패했습니다. 원인={}", failure.getClass().getSimpleName());
        }
    }

    /**
     * 이 틱의 계통 — <b>모르면 {@code null} 이고, 그때 이 틱은 아무 일도 하지 않는다</b>.
     *
     * <p>계통을 알 수 없는 경우는 둘이며 <b>둘 다 원인이 같다</b> — 이 잡이 계통을 갖지 않던 시절의
     * 등록 행이거나(키 부재), 사람이 손으로 고친 행이다(알 수 없는 값).
     *
     * <p>⚠ <b>구 동작 폐기(2026-09-08)</b> — 여기서 <i>전 계통으로 되돌아갔다</i>. 근거는 「안 도는
     * 쪽이 더 위험하다」였는데, 그 근거는 <b>계통 전용 잡이 없던 시절</b>의 것이다. 지금은 계통마다
     * 전용 잡이 따로 등록되므로 이 폴백이 도는 동안 <b>두 경로가 같은 장비를 함께 훑는다</b>. 두
     * 경로는 이름이 달라 서로를 막지 못하고, 같은 연속 실패 계수를 각각 읽고 각각 써 <b>한쪽 갱신이
     * 유실</b>된다 — 계수가 임계에 닿지 못해 <b>죽은 장비가 가용으로 남는다</b>. 즉 「안 도는 쪽이 더
     * 위험하다」의 전제가 뒤집혔다: 폴백이 도는 쪽이 더 위험하다. 전 계통을 <b>의도적으로</b> 훑는
     * 길은 폴러에 명시 얼굴로 남아 있다({@link AiSrvrHealthPoller#pollAll()}).
     *
     * <p>⚠ <b>도달성은 낮다</b> — 계통 전용 이름으로 새로 등록되므로 정상 배포에서는 키가 늘 채워진다.
     * 남는 경로는 <b>옛 이름으로 등록된 채 남아 있는 정의</b>({@link #LEGACY_JOB_NAME})와 수동·
     * 프로그래밍 등록, 되돌림 배포다. <b>그 경로가 이 fail-closed 가 지키는 바로 그 자리</b>다.
     */
    private LsAiSrvr.SrvrType srvrTypeOf(JobExecutionContext context) {
        String raw = context.getMergedJobDataMap().getString(SRVR_TYPE_KEY);
        if (raw == null || raw.isBlank()) {
            warnSkipped(context, "계통이 지정되지 않았습니다(계통 축 이전의 등록 행)");
            return null;
        }
        try {
            return LsAiSrvr.SrvrType.valueOf(raw.trim());
        } catch (IllegalArgumentException unknown) {
            warnSkipped(context, "계통 값을 알 수 없습니다");
            return null;
        }
    }

    /**
     * 건너뛴 사실을 <b>드러내는 유일한 자리</b> — 두 분기가 이 하나를 함께 쓴다.
     *
     * <p>조용히 건너뛰면 <b>관측이 멈춘 이유</b>를 아무도 찾지 못한다. 특히 옛 이름으로 등록된 정의가
     * 남아 있는 배포에서는 이 경고가 <b>그 행을 걷어내라</b>는 유일한 신호다.
     *
     * <p>⚠ <b>잡 데이터 값을 로그에 싣지 않는다</b>. 그 값은 원장 밖 저장소에서 오고 손으로 고칠 수
     * 있어 개행이 섞이면 기록 위조 통로가 된다(CWE-117). 사유는 이 클래스가 소유한 <b>고정 문구</b>라
     * 외부 입력이 아니다.
     *
     * <p>⚠ <b>잡 식별자도 같은 값이다</b>(2026-09-08 정정) — 잡 이름 역시 <b>같은 잡 저장소</b>에서
     * 오고 사람이 손으로 고칠 수 있으므로 위 근거가 그대로 적용된다. 그런데 그 근거를 적어 놓고
     * 정작 이 자리에서는 정제 없이 싣고 있었다 — <b>자기 근거와 어긋난 자리</b>였다. 어느 잡인지는
     * 운영자가 알아야 하므로 값을 빼지 않고 <b>정제해서</b> 싣는다. 판정은 공용 유틸이 단독으로
     * 갖는다({@code AiSrvrHealthTxService}·{@code AiSrvrHealthPoller} 와 같은 함수).
     */
    private void warnSkipped(JobExecutionContext context, String reason) {
        log.warn("[AiSrvr] 상태점검 잡이 계통을 알 수 없어 아무것도 하지 않았습니다({}) — 계통 축"
                        + " 이전에 등록된 옛 잡 정의일 수 있습니다. 그 정의와 발화 등록 행을"
                        + " 걷어내세요. jobKey={}",
                reason, VlmClient.safeForLog(context.getJobDetail().getKey().getName()));
    }
}
