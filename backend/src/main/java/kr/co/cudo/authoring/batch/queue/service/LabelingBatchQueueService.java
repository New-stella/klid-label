package kr.co.cudo.authoring.batch.queue.service;

import jakarta.persistence.PessimisticLockException;
import jakarta.persistence.LockTimeoutException;
import kr.co.cudo.authoring.batch.queue.entity.LsClipScheduleQue;
import kr.co.cudo.authoring.batch.queue.repository.LsClipScheduleQueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 라벨링 배치 큐 서비스.
 * - enqueue: 영상 수신 트랜잭션 내부에서 호출되며 동일한 controlTransactionManager 를 사용.
 * - dequeueOne: 워커가 단일 작업을 꺼낼 때 사용. PENDING 상태만 반환하고 IN_PROGRESS 로 전이.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class LabelingBatchQueueService {

    private final LsClipScheduleQueRepository repository;

    @Transactional("controlTransactionManager")
    public LsClipScheduleQue enqueue(Long rawSn) {
        LsClipScheduleQue saved = repository.save(LsClipScheduleQue.enqueueLabelingBatch(rawSn));
        log.info("[BatchQueue] enqueued rawSn={} queSn={} jobType={}",
                rawSn, saved.getQueSn(), LsClipScheduleQue.JOB_LABELING_BATCH);
        return saved;
    }

    /**
     * PENDING 상태 1건을 꺼내 IN_PROGRESS 로 전이.
     * - PESSIMISTIC_WRITE + lock.timeout=0 (no-wait) 으로 동시성 갭 방지.
     *   다른 워커가 같은 행을 잠고 있으면 lock 획득 실패 → empty 반환 (다음 tick 에 재시도).
     * - H2(local) / MariaDB(dev/stg/prd) 모두 지원. SKIP LOCKED 는 향후 최적화 시 도입.
     */
    @Transactional("controlTransactionManager")
    public Optional<LsClipScheduleQue> dequeueOne() {
        try {
            Optional<LsClipScheduleQue> opt = repository.findOldestPendingForUpdate(LsClipScheduleQue.JOB_LABELING_BATCH);
            opt.ifPresent(q -> {
                q.markInProgress();
                log.info("[BatchQueue] dequeued queSn={} rawSn={}", q.getQueSn(), q.getRawSn());
            });
            return opt;
        } catch (PessimisticLockingFailureException
                 | PessimisticLockException | LockTimeoutException e) {
            log.debug("[BatchQueue] dequeueOne lock contention — skipping this tick: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
