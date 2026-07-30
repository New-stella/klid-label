package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.webhook.service.AugmentApplyResult;
import kr.co.cudo.authoring.webhook.service.AugmentJobRollup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 비동기 제출 <b>시퀀스 종료 시점의 종결 판정</b> 트랜잭션 경계 — Phase C-3.
 *
 * <h3>왜 필요한가 — {@code accepted==0} 즉시 롤업의 이관처</h3>
 * <p>구 동기 구현은 위탁 루프가 끝나면 "202 수락 건수" 를 즉시 알 수 있어, {@code accepted==0} 이면
 * 호출부({@code AugmentRequestBridge})가 그 자리에서 실패 롤업했다. 제출이 논블로킹이 되면 반환
 * 시점에는 <b>개시 사실</b>밖에 알 수 없으므로, 그 판정을 여기로 옮긴다.
 *
 * <p>이관이 없으면 실제 결손이 생긴다: 전 청크의 제출이 비동기로 실패하면 job 은 모두 terminal
 * {@code FAILED} 가 되는데, 만료 스윕의 회수 축은 ①<b>비종결</b> job ②job 행 <b>0건</b> PENDING 증강
 * 둘뿐이라 <b>전부 terminal-FAILED 인 증강</b>은 어느 축도 집지 못한다 → PENDING 영구 고착.
 *
 * <h3>새 스위퍼를 만들지 않는다</h3>
 * <p>본 빈은 주기 잡이 아니라 <b>제출 시퀀스 완료 직후 1회</b> 호출되는 판정 경계다. 회수(주기)는
 * 기존 {@code AugmentJobExpirySweeper} 가 그대로 담당하며, 여기서 놓친 건(핸들러 기록 자체가 유실된
 * 경우)은 그 스윕이 비종결 job 축으로 회수한다.
 *
 * <h3>잠금 순서 (롤업 유실 방지)</h3>
 * <p>웹훅 수신부·만료 스윕과 <b>같은 순서</b>로 잠근다: ①증강 행 {@code FOR UPDATE} → ②job 전량 재조회
 * → ③롤업. 순서가 다르면 두 트랜잭션이 서로의 미커밋 갱신을 못 봐서 양쪽 다 "아직 남은 job 있음" 으로
 * 판정하고 롤업이 통째로 사라진다.
 *
 * <p>{@code REQUIRES_NEW} 인 이유: 호출자({@link AugmentSubmitOutcomeRecorder})는 트랜잭션이 없는 전용
 * 풀 스레드에서 돌지만, 어떤 경로에서도(테스트·재진입) 상위 스코프에 참여하지 않도록 경계를 못박는다.
 * 반드시 <b>별도 빈</b>이어야 프록시가 적용된다(자기호출이면 경계가 통째로 사라진다 — 이 레포 실사고).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentSubmitRollupTxService {

    private final LsDataAugRepository augRepository;
    private final LsDataAugJobRepository jobRepository;
    /** 전 job 종결 판정 + 증강 1건 확정 — 웹훅/만료 스윕과 <b>같은 규칙</b>을 쓰기 위한 단일 원천. */
    private final AugmentJobRollup rollup;

    /**
     * 제출 시퀀스가 끝난 뒤 종결 판정 1회.
     *
     * @return {@link AugmentApplyResult#DEFERRED} = 비종결 job 이 남아 보류(정상 — 콜백이 확정한다) /
     *         그 외 = 롤업이 증강 1건을 확정한 결과
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public AugmentApplyResult rollUpIfAllTerminal(Long dataAugSn) {
        if (augRepository.findByDataAugSnForUpdate(dataAugSn).isEmpty()) {
            log.warn("[Augment][Submit] aug row not found — rollup skipped dataAugSn={}", dataAugSn);
            return AugmentApplyResult.DEFERRED;
        }
        List<LsDataAugJob> jobs = jobRepository.findByDataAugSnOrderByJobSeqAsc(dataAugSn);
        if (jobs.isEmpty()) {
            // 선기록이 전량 실패한 경로 — 호출부(브리지)가 즉시 실패 롤업하므로 여기서는 관여하지 않는다.
            return AugmentApplyResult.DEFERRED;
        }
        return rollup.rollUpIfAllTerminal(dataAugSn, jobs, anchorJobId(jobs));
    }

    /**
     * 롤업 멱등 앵커로 적재할 외부 job_id — 확보된 값이 있으면 그것을 쓴다.
     *
     * <p>제출이 실패한 job 은 {@code null} 인데 그대로 넘기면 증강 행의 기존 앵커를 null 로 덮어쓴다.
     */
    private String anchorJobId(List<LsDataAugJob> jobs) {
        return jobs.stream()
                .map(LsDataAugJob::getExternalJobId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
    }
}
