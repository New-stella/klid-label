package kr.co.cudo.authoring.batch.scheduler;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.batch.service.KpstDeidentTxService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
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
 *   <li>{@link DisallowConcurrentExecution}: <b>같은 노드</b> 내 동일 잡 동시 실행 금지.</li>
 *   <li><b>원자 클레임(B-ISSUE-82)</b>: 배포는 2노드 Active-Active 인데 Quartz 클러스터링
 *       ({@code isClustered})은 기본 꺼져 있어 <b>같은 틱이 양 노드에서 발화</b>한다. 따라서 건별로
 *       {@link KpstDeidentTxService#tryClaimPoll} 로 DB 레벨 선점에 성공한 노드만 폴링한다 —
 *       Quartz 설정에 의존하지 않는 방어다. 완료 처리에는 별도의 멱등 가드가 한 겹 더 있다
 *       ({@code finishDownloadAndComplete}).</li>
 *   <li><b>틱당 상한</b>: 폴링 1건이 외부 HTTP 호출이므로 대상 조회에 상한을 둔다(자원 소진 방지,
 *       OWASP API4). 오래 대기한 건(POLL_LAST_DT 오름차순, 미폴링 우선)부터 처리해 기아를 막는다.</li>
 *   <li>재기동 복원: 대상은 DB 에서 조회하므로 인메모리 상태 유실 없이 폴링 재개.</li>
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

    /** 오설정(0/음수) 시 안전 폴백 — 틱당 폴링 대상 상한. */
    static final int DEFAULT_BATCH_SIZE = 200;

    /**
     * 클레임 리스에서 폴링 주기 대비 빼는 여유(초).
     *
     * <p>리스가 주기 이상이면 <b>단일 노드가 자기 리스에 막혀</b> 다음 틱을 건너뛴다. 주기보다 짧게 잡아
     * 단일 노드 동작(매 틱 폴링)을 그대로 유지하면서, 두 노드의 틱이 겹치는 구간만 배제한다.
     *
     * <p><b>경계 한계(Phase 9 QA 정정)</b>: {@code leaseCutoff} 는 tick 시작 시각 1회만 계산되는 반면
     * {@code POLL_LAST_DT} 는 건마다 <b>실제 클레임한 시각</b>에 찍힌다. 외부 HTTP 지연이 누적돼
     * tick 시작 + 리스(기본 25s) 이후에 클레임된 건은 다음 tick 에서 {@code POLL_LAST_DT > leaseCutoff}
     * 가 되어 그 tick 을 건너뛸 수 있다(실질 주기가 일시적으로 2배가 됨) — "단일 노드는 매 틱 폴링을
     * 유지한다"는 위 설명은 배치가 리스보다 오래 걸리지 않는 경우에 한한다. 이 경계는
     * {@code poll-max-attempts}(기본 240) · {@code poll-timeout-minutes}(기본 180) 안에서 흡수되므로
     * 리스를 늘려 회피하지 않는다(리스를 늘리면 노드 크래시 시 회수가 그만큼 느려지는 트레이드오프가
     * 생긴다).
     */
    private static final int LEASE_SLACK_SEC = 5;
    /** 리스 하한(초) — 주기가 아주 짧게 설정돼도 최소 배타 구간은 유지한다. */
    private static final int MIN_LEASE_SEC = 1;

    @Autowired
    private LsDeidentProcLogRepository procLogRepository;

    @Autowired
    private KpstDeidentService kpstDeidentService;

    @Autowired
    private KpstDeidentTxService kpstDeidentTxService;

    @Value("${kpst.deid.poll-interval-sec:30}")
    private int pollIntervalSec;

    @Value("${kpst.deid.poll-batch-size:200}")
    private int pollBatchSize;

    @Override
    public void execute(JobExecutionContext context) {
        List<LsDeidentProcLog> targets = procLogRepository.findByPollSttsCdIn(
                POLL_TARGET_STATUSES, pollPage());
        if (targets.isEmpty()) {
            log.debug("[KpstDeidPoll] no pending poll target — skipping tick");
            return;
        }
        LocalDateTime leaseCutoff = LocalDateTime.now().minusSeconds(leaseSeconds());
        log.info("[KpstDeidPoll] polling targets count={}", targets.size());
        for (LsDeidentProcLog procLog : targets) {
            try {
                // 원자 클레임에 성공한 노드만 폴링한다(2노드 중복 폴링/중복 완료 차단).
                if (!kpstDeidentTxService.tryClaimPoll(procLog.getProcLogSn(), leaseCutoff)) {
                    log.debug("[KpstDeidPoll] skip — claimed by another node rawSn={}",
                            procLog.getDataRawSn());
                    continue;
                }
                kpstDeidentService.pollOne(procLog);
            } catch (RuntimeException e) {
                // 건별 격리 — 한 건 실패가 다른 건을 막지 않는다(CWE-209: 클래스명만).
                log.warn("[KpstDeidPoll] poll failed rawSn={} prjId={} errType={}",
                        procLog.getDataRawSn(), procLog.getKpstPrjId(), e.getClass().getSimpleName());
            }
        }
    }

    /** 틱당 대상 상한 + 기아 방지 정렬(미폴링 → 가장 오래 대기한 순). */
    private PageRequest pollPage() {
        int size = pollBatchSize < 1 ? DEFAULT_BATCH_SIZE : pollBatchSize;
        return PageRequest.of(0, size,
                Sort.by(Sort.Order.asc("pollLastDt").nullsFirst()).and(Sort.by("procLogSn").ascending()));
    }

    /** 클레임 리스 길이(초) — 폴링 주기보다 짧게(단일 노드 매 틱 폴링 유지). */
    private int leaseSeconds() {
        return Math.max(MIN_LEASE_SEC, pollIntervalSec - LEASE_SLACK_SEC);
    }
}
