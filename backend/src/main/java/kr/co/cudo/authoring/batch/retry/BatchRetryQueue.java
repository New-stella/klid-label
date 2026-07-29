package kr.co.cudo.authoring.batch.retry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 실패 영상 재시도 큐 (B2 — DB 영속화).
 *
 * <p>기존 in-memory {@code ConcurrentHashMap} 큐는 <b>실패 등록 노드 ≠ 재시도 발화 노드</b>일 때
 * (2노드 Active-Active — CLAUDE.md 배포 토폴로지) 재시도가 조용히 유실되던 결함이 있었다. 이를
 * 제거하기 위해 재시도 항목을 {@code LS_BAT_RTY_WTNG} 테이블로 영속화한다. 공개 API 는
 * 종전과 동일하게 유지해 {@code BatchOrchestrator}/{@code BatchRetryQuartzJob} 호출부는 무변경이다.
 *
 * <p>동작:
 * <ul>
 *   <li>지수백오프(60s, 120s, 240s, …) — {@code initialDelaySec * 2^(attempt-1)}, shift 30 캡.</li>
 *   <li>최대 {@code maxAttempts} 초과 시 큐 등록 거부 + 행을 {@code EXHAUSTED} 로 소진 마킹(삭제 대신
 *       이력 보존).</li>
 *   <li>폴링 클레임은 조건부 원자 UPDATE(PENDING→RETRYING)로 2노드 동시 폴링을 직렬화(CWE-362).</li>
 * </ul>
 *
 * <p>모든 메서드는 호출부가 활성 트랜잭션을 갖지 않는 경로(orchestrator NOT_SUPPORTED / Quartz Job)에서
 * 불리므로 {@code REQUIRES_NEW} 로 독립 트랜잭션을 열어 즉시 커밋한다.
 */
@Slf4j
@Service
public class BatchRetryQueue {

    /** 폴링 시 한 번에 후보로 조회할 최대 행 수 (클레임 경쟁 시 다음 후보로 넘어가기 위함). */
    private static final int CLAIM_CANDIDATES = 10;

    private final LsBatRtyWtngRepository repository;
    private final int maxAttempts;
    private final int initialDelaySec;

    public BatchRetryQueue(LsBatRtyWtngRepository repository,
                           @Value("${authoring.batch.retry.max-attempts:3}") int maxAttempts,
                           @Value("${authoring.batch.retry.initial-delay-sec:60}") int initialDelaySec) {
        this.repository = repository;
        this.maxAttempts = maxAttempts;
        this.initialDelaySec = initialDelaySec;
    }

    /**
     * 재시도 등록.
     * <ul>
     *   <li>최초 실패면 PENDING 행 신규 생성(RTY_NMTM=1).</li>
     *   <li>기존 행이면 RTY_NMTM++ 후 재스케줄. 최대 초과면 {@code false} 반환 + EXHAUSTED 마킹.</li>
     * </ul>
     *
     * <p>CWE-362 — find-or-create 경쟁: 2노드가 동일 rawSn 의 <b>최초 실패</b>를 거의 동시에 기록하면
     * 두 트랜잭션 모두 {@code findByRawSn} 에서 empty 를 보고 신규 행을 INSERT 시도해 {@code UK_LBRW_RAW_SN}
     * 위반 {@code DataIntegrityViolationException} 이 전파되던 결함이 있었다. 이를 예외 없이 흡수하기 위해
     * ① {@code INSERT ... ON CONFLICT (RAW_SN) DO NOTHING}(원자 upsert)로 행 존재를 보장한 뒤 ② 반드시
     * 존재하는 행을 {@code SELECT ... FOR UPDATE}(비관적 잠금)로 로드해 증가 처리를 직렬화한다(중복
     * 증가·lost update 차단). REQUIRES_NEW 로 즉시 커밋한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean enqueueIfRetryable(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        // 1) 없으면 원자 INSERT(ON CONFLICT DO NOTHING) — 동시 최초 등록 UK 경쟁을 예외 없이 흡수한다.
        repository.insertIfAbsent(rawSn, maxAttempts);
        // 2) 이제 행이 반드시 존재 → FOR UPDATE 로 잠금 로드하여 동시 증가를 직렬화한다.
        LsBatRtyWtng entry = repository.findByRawSnForUpdate(rawSn)
                .orElseGet(() -> LsBatRtyWtng.create(rawSn, maxAttempts));

        int attempt = entry.incrementAttempt();
        if (attempt > maxAttempts) {
            entry.markExhausted();
            repository.save(entry);
            log.warn("[BatchRetry] max attempts exceeded -- exhausted rawSn={} attempt={} max={}",
                    rawSn, attempt, maxAttempts);
            return false;
        }
        // 지수백오프 — attempt 가 커져도 overflow 안 되도록 shift 를 30 으로 캡 (최대 ~34시간).
        int shift = Math.min(attempt - 1, 30);
        long delaySec = (long) initialDelaySec * (1L << shift); // 60, 120, 240, ...
        entry.scheduleNext(delaySec);
        repository.save(entry);
        log.info("[BatchRetry] enqueued rawSn={} attempt={} delaySec={}", rawSn, attempt, delaySec);
        return true;
    }

    /**
     * 재시도 가능 시각 도래한 가장 오래된 1건을 클레임하여 반환. 없으면 empty.
     *
     * <p>2노드 동시 폴링 안전: 후보들을 순회하며 조건부 원자 UPDATE(PENDING→RETRYING)로 클레임하고
     * 성공(영향 행수 1)한 첫 항목만 반환한다. 경쟁에서 진 후보는 다음 후보로 넘어간다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> pollReady() {
        LocalDateTime now = LocalDateTime.now();
        List<LsBatRtyWtng> due = repository
                .findBySttsCdAndRtyPrnmntDtLessThanEqualOrderByRtyPrnmntDtAsc(
                        LsBatRtyWtng.STATUS_PENDING, now, PageRequest.of(0, CLAIM_CANDIDATES));
        for (LsBatRtyWtng candidate : due) {
            if (repository.claimAtomically(candidate.getBatRtySn(), now) == 1) {
                return Optional.of(candidate.getRawSn());
            }
        }
        return Optional.empty();
    }

    /** 현재까지의 총 재시도 시도 횟수 (없으면 0). */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    public int retryCount(Long rawSn) {
        if (rawSn == null) {
            return 0;
        }
        return repository.findByRawSn(rawSn).map(LsBatRtyWtng::getRtyNmtm).orElse(0);
    }

    /** 성공 처리 시 호출 — 재시도 항목 제거. */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void clear(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        repository.deleteByRawSn(rawSn);
    }

    /**
     * 수동 재처리(reprocess) 전용 clear — RETRYING(자동 폴러가 처리 중) 행은 보존하고 유휴 대기 행만 리셋한다.
     *
     * <p>CWE-362 — {@link #clear(Long)} 는 상태 무관 무조건 삭제라, 자동 폴러가 방금 클레임한 RETRYING
     * 부기를 파괴할 수 있다. 수동 재처리는 이미 RAW FAILED→PROCESSING 원자 클레임으로 소유권을 확보한
     * 뒤 이 메서드로 PENDING/EXHAUSTED 대기 행만 제거해 새 재시도 기회를 부여한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void clearIfIdle(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        repository.deleteIdleByRawSn(rawSn);
    }

    /**
     * B-ISSUE-83 — <b>stale RETRYING 회수</b>. 클레임 후 노드가 죽어 영구 {@code RETRYING} 으로 남은
     * 항목을 재시도 가능 상태로 되돌리거나(상한 이내) 소진 종결한다(상한 초과).
     *
     * <h3>왜 필요한가</h3>
     * {@code RETRYING → PENDING} 복귀는 {@code BatchRetryQuartzJob.execute} 가 정상적으로 예외를 받을
     * 때만 일어난다. 처리 중 프로세스가 죽으면(kill -9 · OOM · 순단) 그 항목은 아무도 건드리지 않는
     * 영구 RETRYING 이 되어 해당 영상의 재시도가 무음 중단된다.
     *
     * <h3>오회수 방지</h3>
     * 후보는 <b>마지막 갱신({@code MDFCN_DT})이 {@code cutoff} 이전</b>인 행뿐이다. 클레임 시각이
     * MDFCN_DT 에 찍히므로, 정상 처리 중(=방금 클레임한) 항목은 cutoff 를 넘지 않아 대상이 아니다.
     * 회수 UPDATE 자체에도 같은 조건을 실어 조회~회수 사이의 상태 변화를 fail-safe 로 재판정한다.
     *
     * <h3>무한 부활 금지</h3>
     * 회수는 죽은 시도를 <b>1회로 계상</b>({@code RTY_NMTM+1})하고, 상한({@code MAX_RTY_NMTM})에 도달한
     * 항목은 복귀시키지 않고 {@code EXHAUSTED} 로 종결한다.
     *
     * <p>2노드 동시 회수 안전: 상태 전이를 조건부 원자 UPDATE 로 수행해 DB 가 직렬화한다 —
     * 한쪽만 영향 행수 1 을 받는다(CWE-362).
     *
     * @param cutoff    이 시각 이전에 마지막으로 갱신된 RETRYING 만 대상(= now - stale 임계)
     * @param batchSize 한 번에 처리할 후보 상한(자원 소진 방지)
     * @return 회수/종결 건수
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public StaleReclaimResult sweepStaleRetrying(LocalDateTime cutoff, int batchSize) {
        List<Long> anchors = repository.findStaleRetryingAnchors(cutoff, Math.max(1, batchSize));
        int reclaimed = 0;
        int exhausted = 0;
        for (Long batRtySn : anchors) {
            LocalDateTime now = LocalDateTime.now();
            // 상한 이내 → PENDING 복귀(다음 폴링에서 재시도). 지수백오프가 아니라 고정 지연을 준다 —
            //   죽은 시도는 "실패한 실행"이 아니라 "실행되지 못한 시도"라 추가 냉각 근거가 없고,
            //   폴러가 tick 당 1건만 집으므로 즉시 복귀시켜도 폭주하지 않는다.
            if (repository.reclaimStaleRetrying(batRtySn, cutoff, now.plusSeconds(initialDelaySec), now) == 1) {
                reclaimed++;
                continue;
            }
            // 상한 초과 → 소진 종결(무한 부활 금지). 둘 다 0 이면 타 노드가 이미 처리한 것이다.
            if (repository.exhaustStaleRetrying(batRtySn, cutoff, now) == 1) {
                exhausted++;
            }
        }
        return new StaleReclaimResult(reclaimed, exhausted);
    }

    /**
     * stale RETRYING 회수 결과.
     *
     * @param reclaimed PENDING 으로 복귀시킨 건수(재시도 재개)
     * @param exhausted 재시도 상한 초과로 EXHAUSTED 종결한 건수(무한 부활 차단)
     */
    public record StaleReclaimResult(int reclaimed, int exhausted) {

        public int total() {
            return reclaimed + exhausted;
        }
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
