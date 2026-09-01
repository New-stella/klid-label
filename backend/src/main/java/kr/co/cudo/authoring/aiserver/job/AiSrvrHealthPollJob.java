package kr.co.cudo.authoring.aiserver.job;

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

    public static final String JOB_NAME = "aiSrvrHealthPollJob";
    public static final String JOB_GROUP = "aiserver";
    public static final String TRIGGER_NAME = "aiSrvrHealthPollTrigger";

    @Autowired
    private AiSrvrHealthPoller poller;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            poller.pollAll();
        } catch (RuntimeException failure) {
            log.warn("[AiSrvr] 상태점검 틱이 실패했습니다. 원인={}", failure.getClass().getSimpleName());
        }
    }
}
