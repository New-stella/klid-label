package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * KPST 비식별 완료감지 폴링 Quartz Job (Phase 3 / UC018).
 *
 * <p>WAITING/POLLING 상태의 위탁 건을 조회해 {@code retrieve_progress} 폴링 → 완료(state=2) 시
 * 다운로드 + DE_IDNTF_YN='Y' + MARKING_READY 전이를 수행한다. {@code kpst.deid.enabled=true} 일 때만
 * 트리거가 등록된다({@link KpstDeidentPollTriggerConfig}).
 *
 * <h3>방어</h3>
 * <ul>
 *   <li>{@link DisallowConcurrentExecution}: 동일 잡 동시 실행 금지(중복 다운로드/전이 방지).</li>
 *   <li>재기동 복원: 대상은 DB({@code findByPollSttsCdIn})에서 조회하므로 인메모리 상태 유실 없이 폴링 재개.</li>
 *   <li>건별 try/catch 격리: 한 건 실패가 다른 건 폴링을 막지 않는다.</li>
 *   <li>빈 리스트 호출 방지: 대상이 없으면 외부 호출 없이 즉시 종료(no-op).</li>
 *   <li>로그 마스킹: rawSn/prjId 만 출력. PII/원본 경로/외부 본문 미출력.</li>
 * </ul>
 */
@Slf4j
@DisallowConcurrentExecution
public class KpstDeidentPollJob implements Job {

    public static final String JOB_NAME = "kpstDeidentPollJob";
    public static final String JOB_GROUP = "batch";
    public static final String TRIGGER_NAME = "kpstDeidentPollTrigger";

    /** 폴링 대상 상태 — 위탁 대기(WAITING) + 진행중(POLLING). */
    private static final List<String> POLL_TARGET_STATUSES =
            List.of(LsDeidentProcLog.POLL_WAITING, LsDeidentProcLog.POLL_POLLING);

    @Autowired
    private LsDeidentProcLogRepository procLogRepository;

    @Autowired
    private KpstDeidentService kpstDeidentService;

    @Override
    public void execute(JobExecutionContext context) {
        List<LsDeidentProcLog> targets = procLogRepository.findByPollSttsCdIn(POLL_TARGET_STATUSES);
        if (targets.isEmpty()) {
            log.debug("[KpstDeidPoll] no pending poll target — skipping tick");
            return;
        }
        log.info("[KpstDeidPoll] polling targets count={}", targets.size());
        for (LsDeidentProcLog procLog : targets) {
            try {
                kpstDeidentService.pollOne(procLog);
            } catch (RuntimeException e) {
                // 건별 격리 — 한 건 실패가 다른 건을 막지 않는다(CWE-209: 클래스명만).
                log.warn("[KpstDeidPoll] poll failed rawSn={} prjId={} errType={}",
                        procLog.getDataRawSn(), procLog.getKpstPrjId(), e.getClass().getSimpleName());
            }
        }
    }
}
