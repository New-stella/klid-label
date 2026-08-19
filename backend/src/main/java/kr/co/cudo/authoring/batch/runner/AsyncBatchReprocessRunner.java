package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 수동 배치 재기동 <b>비동기 실행기</b>. [@design API-167] [@design API-199]
 *
 * <p>{@code BatchReprocessService} 가 요청 스레드에서 상태 선점(FAILED→PROCESSING 원자 클레임)까지만
 * 마친 뒤 본 러너로 파이프라인 실행을 넘긴다. 요청은 <b>접수 사실</b>만 돌려주고, 진행 상황은 영상 상세
 * 조회의 단계 표시로 확인한다.
 *
 * <h3>왜 별도 빈인가 (self-invocation 금지)</h3>
 * <p>{@code @Async} 는 Spring AOP 프록시가 가로채야 동작한다. 같은 빈 안에서 호출하면 프록시를 우회해
 * <b>여전히 동기로</b> 돌고, 테스트는 통과하는데 실기동만 안 고쳐진다(이 저장소의 실사고 이력).
 * 따라서 실행부를 별도 빈으로 분리하고 호출자는 이 빈을 주입받는다.
 *
 * <h3>진입점은 이 경로 하나다 (자동 재시도 큐 무영향)</h3>
 * <p>비동기 디스패치를 타는 것은 <b>수동 재기동</b>({@link BatchOrchestrator#processWithHeldStageClaim})
 * 뿐이다. 자동 재시도 큐({@code BatchRetryQuartzJob})는 이미 스케줄러 스레드에서 돌고 있어 요청 스레드를
 * 잡지 않으므로 <b>일반 진입</b>({@link BatchOrchestrator#process(Long)})을 그대로 쓴다 — 그 경로에 비동기를
 * 한 겹 더 씌우면 잡 1건이 즉시 반환해
 * {@code @DisallowConcurrentExecution}·{@code pollReady()} 클레임과 어긋난다(이번 범위 밖의 회귀).
 *
 * <h3>PROCESSING 고착을 남기지 않는다 (Critical)</h3>
 * <p>동기 시절에는 예외가 요청 스레드로 올라와 처리됐지만, 비동기에서는 <b>아무도 받지 않는다</b>.
 * 상태가 PROCESSING 에 남으면 이후 모든 재기동이 409 라 그 영상은 복구 불가가 된다. 마감 경로는
 * 새로 만들지 않고 기존 것을 그대로 탄다:
 * <ul>
 *   <li>단계 실패(RuntimeException) — {@link BatchOrchestrator} 자신이 마감한다. 전체 재기동은
 *       {@code markRawDataFailed}(→ FAILED), <b>묶음 재수행은 선점 직전 상태로 원상 복구</b>
 *       ([@design API-201] — 완주 영상을 실패로 강등하지 않고 자동 재시도 큐에도 넣지 않는다).
 *       본 러너는 아무것도 하지 않는다.</li>
 *   <li>{@link BatchStage#SKIPPED}(검수 소유 작업 상태) — 파이프라인이 한 건도 돌지 않고 마감 전이도
 *       타지 않으므로 {@link BatchTransitionService#releaseReprocessClaim} 으로 <b>보상 롤백</b>한다
 *       (동기 시절 {@code BatchReprocessService} 가 하던 그 처리를 그대로 옮긴 것이다).</li>
 *   <li>그 외 이탈(진입 조회 NOT_FOUND 등 — 오케스트레이터의 try 블록 <b>이전</b>에 터져 자체 마감을
 *       타지 못하는 경로) — 같은 보상 롤백으로 클레임을 되돌린다. {@code markRawDataFailed} 를 쓰지
 *       않는 이유는 그 메서드가 <b>검수 소유 작업 상태면 {@code LS_DATA_RAW} 를 건드리지 않고 반환</b>해
 *       클레임이 그대로 남기 때문이다(DEV_FIX H10 이 고친 지점).</li>
 *   <li>{@code Error}(OOM 등) — 같은 보상만 시도하고 <b>되던진다</b>. 삼켜서 스레드를 살려 두면 치명적
 *       오류가 "조용히 끝난 재기동"으로 위장된다. 단계 실행 중 Error 는 오케스트레이터가 이미 복구
 *       (전체 재기동=FAILED / 묶음 재수행=원상 복구, [@design API-201])하므로 이 보상은 0행 no-op 이고,
 *       try 블록 이전 이탈에서만 실제로 클레임을 되돌린다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncBatchReprocessRunner {

    /** 실행 종료로 선점 표식을 닫는 사유 문구 — 고정 상수(사용자 입력·경로·PII 비포함, CWE-209/532). */
    static final String CLAIM_CLOSED_RUN_FINISHED = "실행 종료로 선점 해제";

    private final BatchOrchestrator orchestrator;
    private final BatchTransitionService transitionService;
    private final BatchStatusService batchStatusService;

    /**
     * 선점된 클레임을 인계받아 파이프라인을 실행한다 — 호출자 스레드로 예외를 돌려보내지 않는다.
     *
     * <p><b>왜 출발 상태를 함께 받는가</b>: 클레임 출발 상태가 {@code FAILED}(배치 실패 복구 —
     * [@design API-167])와 {@code COMPLETED}(단계 지목 재수행 — [@design API-201]) 둘이다. 보상 롤백은
     * <b>선점 직전 상태로</b> 되돌려야 하는데, 그 상태를 아는 것은 선점한 호출자뿐이다. 이 값을 넘기지
     * 않으면 완주 영상의 보상이 {@code FAILED} 로 떨어져 <b>아무것도 실패하지 않았는데 실패로 표시</b>된다.
     *
     * @param rawSn             {@code BatchTransitionService} 의 원자 클레임으로 이미 선점된 영상 식별자
     * @param claimOriginStatus 선점 직전의 배치 단계 상태(보상 롤백 목표값)
     */
    @Async("batchReprocessExecutor")
    public void runAsync(Long rawSn, String claimOriginStatus) {
        log.info("[AsyncBatchReprocess] starting rawSn={} origin={}", rawSn, claimOriginStatus);
        run(rawSn, claimOriginStatus, () -> orchestrator.processWithHeldStageClaim(rawSn));
    }

    /**
     * <b>작업 묶음 지목 재수행</b> 전용 진입. [@design API-201]
     *
     * <p>되돌린 묶음의 구성 단계가 stage 토글로 환산돼 넘어온다. 클레임 인계·SKIPPED 보상 롤백·예외
     * 처리 계약은 위 진입과 <b>같다</b>.
     *
     * <p>⚠ <b>별도 메서드이지 오버로드 위임이 아니다</b> — 이 경로는 오케스트레이터의 <b>다른 진입</b>
     * ({@link BatchOrchestrator#processBundleRerun})을 타야 한다. 그 진입만이 단계 실패 시 영상 상태를
     * FAILED 로 강등하지 않고 선점 직전 상태로 되돌리며 자동 재시도 큐에 넣지 않는다. 두 경로를 한
     * 메서드로 합치면 그 차이가 인자 하나의 null 여부에 숨어 조용히 뒤바뀐다.
     *
     * <p><b>검수 소유 작업 상태 보존</b>({@code preserveReviewOwnedStatus}) — 「메타만 더하는 묶음」
     * (라벨을 다시 만들지 않는 묶음)의 재수행은 승인 완료 영상에도 열려야 한다. 이 값이 {@code true} 면
     * 오케스트레이터의 진입 가드가 검수 소유 상태를 차단 사유로 보지 않고, 마감도 작업 상태를 보존하는
     * 경로로 간다(작업 상태는 전이되지 않아 {@code APPROVED} 가 그대로 남는다). 판정 단일 지점은
     * {@code MetadataOnlyRerunPolicy} 이며 여기서 재유도하지 않는다.
     *
     * <p>SKIPPED 보상 계약은 그대로다 — 오케스트레이터가 실행 범위 검사에서 거부해도
     * {@link #runInner} 가 선점 클레임을 선점 직전 상태로 되돌린다.
     *
     * @param stageToggles              {@code null}/빈 맵이면 전 단계 실행(정상 경로에서는 항상 묶음 토글이 온다)
     * @param preserveReviewOwnedStatus 검수 소유 작업 상태를 차단 사유로 보지 않고 보존할지 여부
     */
    @Async("batchReprocessExecutor")
    public void runBundleRerunAsync(Long rawSn, String claimOriginStatus, Map<String, Boolean> stageToggles,
                                    boolean preserveReviewOwnedStatus) {
        log.info("[AsyncBatchReprocess] starting bundle rerun rawSn={} origin={} scoped={} preserveReview={}",
                rawSn, claimOriginStatus, stageToggles != null && !stageToggles.isEmpty(),
                preserveReviewOwnedStatus);
        run(rawSn, claimOriginStatus,
                () -> orchestrator.processBundleRerun(
                        rawSn, stageToggles, claimOriginStatus, preserveReviewOwnedStatus));
    }

    /**
     * 두 진입의 공통 후처리 — 트랜잭션·비동기 속성이 없는 {@code private} 헬퍼다(자기호출 프록시 우회
     * 문제 없음).
     */
    private void run(Long rawSn, String claimOriginStatus, java.util.function.Supplier<BatchStage> execution) {
        try {
            runInner(rawSn, claimOriginStatus, execution);
        } finally {
            // ★ 실행이 어떤 결과로 끝났든 선점 표식을 닫는다 (고착 회수의 오판 방지).
            //   열어 둔 채로 두면, 뒤에 <b>다른 진입 경로</b>(마킹 브리지·자동 재시도 잡·dev 트리거 —
            //   선점 직전 상태를 기록하지 않는 경로들)로 고착된 같은 영상을 회수 스윕이 이 옛 표식의
            //   출발 상태로 되돌린다. 예컨대 「전체 재기동(FAILED) 성공 → 완주 → 이후 다른 경로로 고착」
            //   이면 완주 영상이 FAILED 로 강등돼 전체 재기동 경로가 열리고, 그 경로가 사람이 손댄
            //   보간 라벨을 전량 삭제·재생성한다.
            //   ⚠ finally 라 Error 전파를 막지 않는다. 닫기 자체의 실패는 삼키고 로깅만 한다 —
            //     원인 예외를 덮으면 치명적 오류가 "기록 실패" 로 위장된다.
            closeClaimMarkerQuietly(rawSn);
        }
    }

    /** 실행 + 보상 롤백 본체 — 표식 닫기는 {@link #run} 의 {@code finally} 가 담당한다. */
    private void runInner(Long rawSn, String claimOriginStatus,
                          java.util.function.Supplier<BatchStage> execution) {
        try {
            BatchStage stage = execution.get();
            if (stage == BatchStage.SKIPPED) {
                // 진입 가드가 막았다 = step 0건 + 마감 전이 미실행. 클레임을 되돌리지 않으면 영구 고착이다.
                transitionService.releaseReprocessClaim(rawSn, claimOriginStatus);
                log.warn("[AsyncBatchReprocess] skipped — review-owned work status, claim compensated rawSn={}",
                        rawSn);
                return;
            }
            log.info("[AsyncBatchReprocess] finished rawSn={} stage={}", rawSn, stage);
        } catch (RuntimeException e) {
            // 단계 실패는 오케스트레이터가 이미 마감(전체 재기동=FAILED / 묶음 재수행=원상 복구)했으므로
            // 여기 오지 않는다. 여기 오는 것은 그 마감을 타지 못한 이탈이므로 클레임만 되돌린다
            // (이미 마감됐으면 조건부 UPDATE 가 0행 → no-op).
            transitionService.releaseReprocessClaim(rawSn, claimOriginStatus);
            // 원인은 유형까지만 남긴다 — 외부 메시지·경로가 로그에 실리지 않게 한다(CWE-209/532).
            log.error("[AsyncBatchReprocess] unexpected failure rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        } catch (Error e) {
            // ★ Error 는 <b>삼키지 않는다</b> — 되던져 스레드의 기본 처리(uncaught handler)로 올려보낸다.
            //   여기서 잡아 정상 반환하면 OOM 같은 치명적 오류가 "조용히 끝난 재기동"으로 위장된다.
            //   다만 오케스트레이터의 try 블록 <b>이전</b>(진입 조회·진입 가드)에서 Error 가 나면 자체
            //   복구를 타지 못해 선점 클레임이 그대로 남으므로, RuntimeException 갈래와 <b>같은 보상</b>만
            //   시도하고 전파한다(오케스트레이터가 이미 되돌렸으면 조건부 UPDATE 가 0행 → no-op).
            //   보상 자체가 실패해도 원인 Error 를 덮지 않는다.
            compensateQuietly(rawSn, claimOriginStatus);
            log.error("[AsyncBatchReprocess] fatal error rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
            throw e;
        }
    }

    /** 선점 표식 닫기 시도 — 실패해도 원인 예외를 덮지 않도록 삼키고 로깅만 한다. */
    private void closeClaimMarkerQuietly(Long rawSn) {
        try {
            batchStatusService.recordReprocessClaimClosed(rawSn, CLAIM_CLOSED_RUN_FINISHED);
        } catch (Throwable t) {
            log.error("[AsyncBatchReprocess] claim marker close failed rawSn={} reason={}",
                    rawSn, t.getClass().getSimpleName());
        }
    }

    /** 보상 롤백 시도 — 실패해도 원인 예외(Error)를 덮지 않도록 삼키고 로깅만 한다. */
    private void compensateQuietly(Long rawSn, String claimOriginStatus) {
        try {
            transitionService.releaseReprocessClaim(rawSn, claimOriginStatus);
        } catch (Throwable t) {
            log.error("[AsyncBatchReprocess] claim compensation failed rawSn={} reason={}",
                    rawSn, t.getClass().getSimpleName());
        }
    }
}
