package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * D-ISSUE-04(b) — <b>실패한 학습데이터 산출(export) 회수</b> 컴포넌트.
 *
 * <h3>왜 필요한가</h3>
 * 검수 승인 후 export 는 {@code DatasetExportBridge}(AFTER_COMMIT) → {@code AsyncDatasetExportRunner}
 * 로 <b>승인 트랜잭션 밖</b>에서 실행된다. 그래서 실패해도 승인은 롤백되지 않는데, <b>재시도 큐가 없어</b>
 * {@code LS_DATASET_EXPORT} 에 FAILED 행만 남고 데이터마트 {@code V_COMPLETED_VIDEO} 에는
 * <b>뷰 출력</b> {@code OUTPUT_PATH_NM}·{@code FRME_CNT} 가 NULL 인 행이 영구히 노출됐다. 승인 사전 게이트(a)는
 * "라벨 0건" 유형만 막을 뿐, <b>라벨이 있는데 산출이 실패한</b> 유형(실측 rawSn=13: 프레임 11·라벨 35)은
 * 회수 경로가 있어야만 복구된다.
 *
 * <h3>기존 메커니즘 재사용 (새 테이블·새 메커니즘 발명 금지)</h3>
 * <ul>
 *   <li><b>큐 = {@code LS_DATASET_EXPORT} 자체</b>(별도 큐 테이블 없음). 단 <b>시도 횟수는 행 수로 세지
 *       않는다</b> — DEV_FIX(H7①): 재시도가 항상 FAILED 행을 만들지는 않아(NO_INPUT early return ·
 *       버전 채번 소진 · {@code AsyncDatasetExportRunner} 의 예외 삼킴) 행 수 카운트는 그 유형에서 영원히
 *       고정됐고 {@code max-attempts} 가 무효인 채 무한 재시도됐다. 이제 클레임 시점에 증가하는
 *       {@code RTY_NMTM}(V136) 합으로 센다.</li>
 *   <li><b>실행 = 기존 {@link AsyncDatasetExportRunner}</b> 그대로 재사용(승인 경로와 동일한 산출 로직·
 *       동일한 실패 격리). 재산출이 성공하면 최신 export 가 SUCCEEDED/PARTIAL 이 되어 자연히 대상에서 빠지고,
 *       뷰의 NULL 행도 해소된다.</li>
 *   <li><b>주기 실행 = 기존 Quartz sweeper 패턴</b>({@code DatasetExportPendingSweeper} 와 동일 구조 —
 *       {@code @Value} 설정 + 얇은 Job 어댑터).</li>
 * </ul>
 *
 * <h3>동시성 (2노드 Active-Active) — DEV_FIX(H7③)</h3>
 * <b>Quartz 클러스터링에 의존하지 않는다.</b> 실제 설정은 {@code org.quartz.jobStore.isClustered} 가
 * <b>기본 false</b> 이고(프로파일 override 0건, onprem env 템플릿도 false) 활성화는 Phase 9 소관이라,
 * "매 tick 을 한 노드만 실행한다"는 전제는 지금 성립하지 않는다. 그 전제로는 두 노드가 같은 영상을 동시에
 * {@code force=true} 로 재산출해 <b>서로 다른 버전 폴더가 중복 산출</b>된다(버전 UK 는 버전이 갈리므로
 * 막지 못한다).
 *
 * <p>그래서 재산출 트리거 전에 <b>DB 레벨 조건부 UPDATE 클레임</b>
 * ({@code LsDatasetExportRepository#claimForRetry})을 통과한 건만 실행한다 — 같은 앵커 행을 두 노드가
 * 동시에 노려도 PostgreSQL 이 UPDATE 시 WHERE 를 재평가하므로 한쪽만 1행을 얻는다. 승인 경로 러너와의
 * 동시 산출은 대상 선정 단계에서 배제된다: 산출이 진행 중이면 최신 export 행이 {@code PENDING} 이라
 * "최신이 FAILED" 조건에 걸리지 않는다.
 *
 * <h3>DEV_FIX(H14) — 승인 러너와의 잔여 경합 창: 실재하나 무해(판정 근거)</h3>
 * <p>위 배제 근거("진행 중이면 최신이 PENDING")는 <b>PENDING 행이 이미 INSERT 된 뒤에만</b> 성립한다.
 * {@code DatasetExportService.export()} 는 ① {@code loadPreparation}(프레임/메타 로딩) → ② 멱등 해시 판정
 * → ③ {@code insertWithRetry}(PENDING INSERT) 순이므로, <b>러너 시작 ~ ③ 사이</b>에는 최신 행이 여전히
 * 직전 {@code FAILED} 다. 이 구간에 회수 잡 tick 이 겹치면 같은 rawSn 에 대해 승인 러너와 회수 러너가
 * 동시에 산출을 시작할 수 있다. 즉 <b>창은 실재한다</b>.
 *
 * <p>그럼에도 <b>막지 않는다</b>. 결과가 다음과 같이 한정되기 때문이다:
 * <ul>
 *   <li>두 산출은 각자 {@code insertWithRetry} 로 <b>서로 다른 버전</b>을 채번한다(UK 위반 시 재채번).
 *       따라서 출력 디렉터리 {@code {rawSn}/v{n}} 가 겹치지 않아 <b>파일 덮어쓰기·부분 뒤섞임이 없다</b>.</li>
 *   <li>데이터마트 뷰({@code V_COMPLETED_VIDEO.OUTPUT_PATH_NM})는 <b>최신 SUCCEEDED/PARTIAL</b>(V160)을
 *       조인하므로 결과 정합이 깨지지 않는다 — 어느 쪽이 이겨도 실재하는 산출물을 가리킨다.</li>
 *   <li>실해는 "중복 버전 폴더 1개 + 중복 연산" 에 그친다 — 데이터 부패·유실·PII 노출 경로가 아니다.</li>
 * </ul>
 * <p>창을 완전히 닫으려면 rawSn 단위 원자 클레임(예: 비종결 export 부분 유니크 인덱스 + 선(先) 예약)이
 * 필요한데, 이는 검증을 통과한 재시도 앵커/상한/유예 machinery 를 재설계해야 하는 범위 밖 변경이다.
 * 위험(중복 폴더) 대비 비용이 커서 <b>의도적으로 현 상태를 유지</b>하며, 여기 근거를 남긴다.
 *
 * <p>{@code @DisallowConcurrentExecution} 은 같은 노드 안에서 tick 겹침만 막는 보조 장치다.
 *
 * <p>로그는 건수·rawSn 만 남기며 경로/PII 는 출력하지 않는다(CWE-209/359).
 */
@Slf4j
@Component
public class DatasetExportFailureRecoverer {

    /** 오설정(0/음수) 시 안전 폴백 — 재시도 유예(분). */
    private static final int DEFAULT_RETRY_DELAY_MINUTES = 10;
    /** 오설정 시 안전 폴백 — 최대 누적 재시도 횟수. */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** 오설정 시 안전 폴백 — tick 당 재시도 건수 상한. */
    private static final int DEFAULT_BATCH_SIZE = 20;

    private final LsDatasetExportRepository exportRepository;
    private final AsyncDatasetExportRunner runner;
    /** 클레임(원자 UPDATE)을 짧은 REQUIRES_NEW 트랜잭션으로 수행 — 회수 잡은 트랜잭션 밖에서 돈다. */
    private final DatasetExportTxService txService;
    /** M2 — 신고 구간 판정 단일 원천({@code "F".equals} 재구현 금지). 클레임 <b>이전</b>에 본다. */
    private final DeidentReportGate deidentReportGate;

    /** 실패 후 이 분(minute)이 지나야 재시도 대상으로 본다(일시 장애 진정 대기). */
    @Value("${authoring.dataset-export.failure-recovery.retry-delay-minutes:10}")
    private int retryDelayMinutes;

    /** 마지막 성공/부분 산출 이후 허용하는 최대 누적 실패(=시도) 횟수. 초과하면 자동 회수를 멈춘다. */
    @Value("${authoring.dataset-export.failure-recovery.max-attempts:3}")
    private int maxAttempts;

    /** 한 번의 tick 에서 재시도를 트리거할 최대 영상 수(폭주 방지 — CWE-770). */
    @Value("${authoring.dataset-export.failure-recovery.batch-size:20}")
    private int batchSize;

    public DatasetExportFailureRecoverer(LsDatasetExportRepository exportRepository,
                                        AsyncDatasetExportRunner runner,
                                        DatasetExportTxService txService,
                                        DeidentReportGate deidentReportGate) {
        this.exportRepository = exportRepository;
        this.runner = runner;
        this.txService = txService;
        this.deidentReportGate = deidentReportGate;
    }

    /**
     * 실패한 export 를 재시도 대상으로 회수해 재산출을 트리거한다.
     *
     * @return 재시도를 트리거한 영상 수
     */
    public int recover() {
        int delay = retryDelayMinutes < 1 ? DEFAULT_RETRY_DELAY_MINUTES : retryDelayMinutes;
        int attempts = maxAttempts < 1 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        int size = batchSize < 1 ? DEFAULT_BATCH_SIZE : batchSize;

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(delay);
        List<Object[]> anchors = exportRepository.findRetryableFailedAnchors(cutoff, attempts, size);
        if (anchors.isEmpty()) {
            log.debug("[DatasetExportRecovery] no retryable failed export");
            return 0;
        }
        List<Long> claimed = new ArrayList<>(anchors.size());
        int deidentSkipped = 0;
        for (Object[] anchor : anchors) {
            long exportSn = ((Number) anchor[0]).longValue();
            long rawSn = ((Number) anchor[1]).longValue();
            // M2 — <b>클레임 이전에</b> 신고 구간을 확인해 건너뛴다. 클레임이 먼저면 산출은 export 게이트에서
            //   어차피 막히는데 시도 이력(RTY_NMTM)만 올라가, 신고가 길어질수록 상한이 소진되고 resolve 후
            //   자동 회수가 영구 불가가 된다. 신고 구간은 "실패"가 아니라 정책적 보류이므로 예산을 쓰지 않는다.
            //   판정은 DeidentReportGate 단일 원천 재사용(무잠금 1컬럼 projection — 스킵 판단이라 잠금 불필요:
            //   오판해 클레임해도 export 진입부/마감 게이트가 재차 막고, 반대 오판은 다음 tick 에서 회복된다).
            //   신고가 resolve 되면 승인 영상에 재검토 표시(REVLT_YN='Y')가 서고, 재승인 시점에 재산출·통지가
            //   복구된다. ⚠ 구 기재 "M1 재트리거(DeidentReportResolvedEvent)" 는 폐기 — 그 이벤트는 발행처가
            //   없는 휴면 확장점이다(판정 원천 DeidentReportService#publishResolvedForExportRecovery).
            if (deidentReportGate.isUnderDeidentReport(rawSn)) {
                deidentSkipped++;
                continue;
            }
            // 클레임 성공(1행)한 건만 트리거한다. 클레임은 ①시도 이력을 남기고(상한이 실제로 걸림)
            //   ②다른 노드/tick 의 동시 재산출을 배제한다(Quartz 클러스터링 설정과 무관).
            if (!txService.claimForRetry(exportSn, attempts, cutoff)) {
                continue;
            }
            claimed.add(rawSn);
            // HIGH-D(Phase 5C) — 승인 러너(runApprovalAsync)로 재산출한다: force=true 로 내용 해시 멱등 skip
            //   없이 다시 만들고, <b>성공 시 DatasetExportCompletedEvent 를 발행</b>해 통지를 재개한다.
            //   구 runAsync(force=true) 는 완료 이벤트를 발행하지 않아, 실패 export 가 회수돼 성공해도 관제가
            //   최신 버전을 통지받지 못했다(관제 영구 구버전). 완료 통지는 sendCompleted 의 409 자기치유로
            //   원 통지가 완료/수정이든 관제 상태에 맞춰 정합화된다. 재산출이 또 실패하면 완료 이벤트가
            //   발행되지 않아(HIGH-D) 통지가 안 나가고 다음 tick 에서 상한까지 재시도된다.
            runner.runApprovalAsync(rawSn);
        }
        if (deidentSkipped > 0) {
            log.info("[DatasetExportRecovery] skipped under deident report (retry budget preserved) count={}",
                    deidentSkipped);
        }
        if (claimed.isEmpty()) {
            log.debug("[DatasetExportRecovery] all candidates already claimed candidates={}", anchors.size());
            return 0;
        }
        log.warn("[DatasetExportRecovery] retriggered failed exports count={} rawSns={} maxAttempts={}",
                claimed.size(), claimed, attempts);
        return claimed.size();
    }
}
