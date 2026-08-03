package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;

/**
 * 배치 작업 상태 전이를 DB 에 영속하는 전용 서비스.
 *
 * <p>{@link kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator#process(Long)} 는
 * NOT_SUPPORTED(비트랜잭션) 로 동작하므로, orchestrator 내부에서 직접 엔티티를 변경해도
 * 활성 트랜잭션이 없어 dirty checking 이 작동하지 않는다. 또한 같은 빈 내부의
 * {@code @Transactional} 메서드 self-invocation 은 Spring AOP 프록시를 우회한다.
 *
 * <p>이 두 문제를 회피하기 위해 상태 전이 로직을 별도 빈의 {@code @Transactional(REQUIRES_NEW)}
 * public 메서드로 분리하고, orchestrator 가 이 빈을 주입받아 호출한다. 각 전이는 독립
 * 트랜잭션에서 load → 비즈니스 메서드 호출 → save 로 명시 영속된다.
 *
 * <p><b>상태 검증 책임(B-ISSUE-03 정정)</b>: 작업 상태({@code LS_RAW_DATA_STATUS}) 전이 검증은
 * <b>본 서비스가 직접</b> 수행한다. 과거 주석은 검증을 {@link LsRawDataStatus} 책임이라 했고
 * 엔티티는 다시 {@code ReviewStateMachine} 책임이라 했지만, 배치 경로는 상태 머신을 호출하지 않아
 * 결과적으로 <b>아무도 검증하지 않았다</b>(APPROVED → PROCESSING → ASSIGNED 로 검수 승인 소실).
 * 이제 {@link #REVIEW_OWNED_STATUSES}(PENDING/IN_REVIEW/APPROVED/REJECTED) 는 조건부 UPDATE 로 차단되며,
 * 차단 시 {@link #markRawDataProcessingBlocked} 가 {@code true} 를 반환해 <b>파이프라인 자체가 중단</b>된다
 * (상태만 지키고 step 을 계속 돌리면 APPROVED 영상에 AUTO 라벨이 적재되는 무증상 오염이 된다 — DEV_FIX H8).
 *
 * <p>단, 배치는 <b>예외를 던지지 않는다</b>. 전이가 차단되면 상태를 그대로 두고 WARN 로깅 후 진행한다
 * (배치가 검수 워크플로우를 막으면 안 되며, 반대로 검수 진행 상태를 배치가 덮어써도 안 된다).
 * 작업 상태 row 가 없거나 영상 row 가 없을 때도 동일하게 WARN 로깅 후 진행을 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchTransitionService {

    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final VideoRepository videoRepository;
    private final LsDeidentProcLogRepository procLogRepository;

    /** 비식별 실패 기록의 고정 에러코드 — 외부 원문/원본경로/PII 비노출(CWE-209). */
    public static final String DEIDENT_FAIL_PROC_REG_ID = "batch-deident-fail";

    /**
     * 배치가 <b>덮어쓰면 안 되는</b> 검수 워크플로우 소유 작업 상태 (B-ISSUE-03 / DEV_FIX H1-b).
     *
     * <p>{@code PENDING}(검수 대기)·{@code IN_REVIEW}(검수 진행 중)·{@code APPROVED}(검수 승인 종결)·
     * {@code REJECTED}(반려)는 모두 {@code ReviewStateMachine} 이 소유하는 상태다. 배치가 이를
     * PROCESSING/ASSIGNED/FAILED 로 바꾸면 검수 진행/승인이 조용히 사라지고 {@code V_COMPLETED_*}
     * 데이터마트 뷰에서 영상이 이탈한다.
     *
     * <p><b>왜 ASSIGNED 만 제외인가 (호출자 전수 추적 결과 — 구 Javadoc 의 "차단 집합을 넓히면 정상
     * 파이프라인이 끊긴다"는 근거는 사실과 달라 정정한다):</b> {@link #transitionRawDataStatus} 를 타는
     * 호출자는 {@link kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator} 3 지점
     * (processing/completed/failed) 뿐이고, 파이프라인 진입 상태는 ①{@link #tryClaimBatchQueued}/
     * {@link #tryCreateBatchQueuedRow} 로 만들어진 {@code BATCH_QUEUED} ②{@link
     * #tryClaimReprocessFromFailed} 가 클레임한 {@code FAILED}→{@code PROCESSING} ③작업 상태 row 자체가
     * 없는 파생 RAW 뿐이다. 즉 <b>{@code PENDING}·{@code REJECTED} 에서 출발하는 정상 배치 전이는 코드에
     * 존재하지 않는다</b>. 반면 배치 완료가 {@code ASSIGNED} 로 복귀시키는 것은 의도된 설계이므로
     * ({@code markRawDataCompleted} → 검수 제출 ASSIGNED→PENDING 이 막히지 않도록) ASSIGNED 는 제외한다.
     *
     * <p>진입 차단(파이프라인 자체 중단)에도 같은 집합을 사용한다 —
     * {@link kr.co.cudo.authoring.marking.listener.MarkingBatchBridge} 의 클레임 skip 집합과
     * {@code BatchOrchestrator.process} 진입 가드가 이 상수를 공유해 "입구·본체·출구"가 동일 기준으로 막힌다.
     */
    public static final Set<String> REVIEW_OWNED_STATUSES = Set.of(
            LsRawDataStatus.STTS_PENDING,
            LsRawDataStatus.STTS_IN_REVIEW,
            LsRawDataStatus.STTS_APPROVED,
            LsRawDataStatus.STTS_REJECTED);

    /**
     * 배치 시작 — 작업(워크플로우) 상태 LS_RAW_DATA_STATUS.DATA_STTS_CD → PROCESSING,
     * 배치 단계 상태 LS_DATA_RAW.DATA_STTS_CD → PROCESSING (Bug 2 — '처리중' 도입).
     *
     * <p>두 컬럼을 같은 타이밍에 전이한다. 작업 상태 row 는 배정 시점 lazy 생성이라 적재 직후엔
     * 없을 수 있으나, 마킹 완료로 배치가 시작되는 시점에는 배정·작업 상태 row 가 존재한다.
     * 영상(LS_DATA_RAW) row 는 항상 존재하므로 MARKING_READY → PROCESSING 으로 전이해
     * 마킹 완료~배치 완료 구간이 "처리중"으로 표시되게 한다.
     *
     * <p><b>진입 게이트 (DEV_FIX H8):</b> 작업 상태가 {@link #REVIEW_OWNED_STATUSES} 면 전이를 차단하고
     * {@code true}(=차단됨)를 반환한다. 이때 <b>{@code LS_DATA_RAW} 도 건드리지 않는다</b> — 상태만 보존하고
     * 파이프라인을 계속 돌리면 APPROVED 영상에 AUTO 라벨이 새로 적재되면서도 상태가 APPROVED 로 남아
     * "탐지 불가능한 데이터 오염"이 되기 때문이다. 호출자
     * ({@link kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator#process})는 {@code true} 를 받으면
     * <b>step 을 한 건도 실행하지 않고</b> 즉시 종료해야 한다. 본 메서드 자체는 두 테이블을 함께 멈춘다.
     *
     * <p><b>정정(DEV_FIX H10)</b>: 과거 주석은 "{@code (work=APPROVED, stage=PROCESSING|FAILED)} 같은
     * 불일치쌍은 생기지 않는다"고 단언했으나 <b>사실이 아니었다</b>. 수동 재처리
     * ({@link #tryClaimReprocessFromFailed})는 본 가드보다 <b>먼저</b> {@code LS_DATA_RAW} 를
     * FAILED→PROCESSING 으로 선점하므로, 그 뒤 이 가드가 발화하면 {@code (work=검수소유, stage=PROCESSING)}
     * 불일치쌍이 실제로 만들어지고 stage 가 영구 고착된다(이후 재처리는 stage/work 어느 쪽도 FAILED 가
     * 아니라 영구 409). 따라서 <b>클레임을 건 호출자가 SKIPPED 를 받으면 반드시 보상 롤백</b>
     * ({@link #releaseReprocessClaim})해야 하며, 이 계약은 {@code BatchReprocessService} 가 지킨다.
     *
     * @return {@code true} = 검수 소유 상태라 배치 진입이 <b>차단</b>됨(호출자는 파이프라인 중단),
     *         {@code false} = 전이 완료(또는 작업 상태 row 부재 — 파생 RAW) → 진행
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean markRawDataProcessingBlocked(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        if (transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_PROCESSING)) {
            return true;
        }
        videoRepository.findById(rawSn).ifPresentOrElse(
                LsDataRaw::markProcessing,
                () -> log.warn("[BatchTransition] raw video not found rawSn={} (processing)", rawSn));
        return false;
    }

    /**
     * 배치 완료 — 작업(워크플로우) 상태 LS_RAW_DATA_STATUS.DATA_STTS_CD → ASSIGNED 복귀,
     * 배치 단계 상태 LS_DATA_RAW.DATA_STTS_CD → COMPLETED.
     *
     * <p>두 테이블의 책임이 다르다. {@code LS_DATA_RAW.DATA_STTS_CD} 는 <b>배치 단계</b> 상태이므로
     * 배치가 완료되면 COMPLETED 로 마감한다. 반면 {@code LS_RAW_DATA_STATUS.DATA_STTS_CD} 는
     * <b>작업(검수 워크플로우)</b> 상태다. 여기서 COMPLETED 는 <b>검수 승인(작업 종결)</b> 시점에만
     * 도달해야 하는 종결 상태이므로(CLAUDE.md "검수 완료=작업 완료"), 배치 완료가 이를 점프시키면
     * 작업자의 검수 제출(ASSIGNED→PENDING)이 상태 머신에서 차단된다. 따라서 배치 완료 시 작업 상태는
     * 배정 완료(ASSIGNED) 로 복귀시켜 라벨링/검수 워크플로우가 정상 진행되도록 한다.
     * 검수 승인 시점의 COMPLETED 전이는 ReviewService(approve) 가 담당한다.
     *
     * <p><b>미배정 경로 주의(FIX B 결합):</b> 미배정 REVIEWER 가 직접 마킹하면
     * {@link #tryCreateBatchQueuedRow} 가 <b>{@code LS_TASK_ASSIGNMENT} 배정 레코드 없이</b> 상태 row 를
     * 생성하므로, 본 메서드가 그 row 를 ASSIGNED 로 복귀시킬 수 있다. 즉 <b>실제 배정 레코드가 없는 영상이
     * ASSIGNED 상태</b>가 될 수 있다. 이는 다운스트림이 배정 여부를 {@code LS_RAW_DATA_STATUS.DATA_STTS_CD}
     * 단독이 아니라 <b>{@code LS_TASK_ASSIGNMENT} 존재</b>로 판정하므로 무해하다(조사 근거):
     * {@code TaskBoardService.mapBoardStatus}(hasLabeler 게이트 → 배정 없으면 UNASSIGNED),
     * {@code VideoQueryService.lookupCurrentAssignments}(배정 없으면 배정 필드 null),
     * {@code AssignmentResponse}(배정 엔티티에서 파생), {@code StatsService} 카운트(모두
     * {@code LsTaskAssignment} JOIN 또는 PENDING/APPROVED/REJECTED 집계라 ASSIGNED-무배정은 미집계).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markRawDataCompleted(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        // 검수 소유 상태면 LS_DATA_RAW 도 건드리지 않는다 — 두 테이블을 함께 멈춰 불일치쌍을 만들지 않는다(H2).
        if (transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_ASSIGNED)) {
            return;
        }
        videoRepository.findById(rawSn).ifPresentOrElse(
                LsDataRaw::markCompleted,
                () -> log.warn("[BatchTransition] raw video not found rawSn={} (completed)", rawSn));
    }

    /**
     * 선두 비식별 성공 — LS_DATA_RAW.DATA_STTS_CD → MARKING_READY (Phase 2).
     *
     * <p>marking-ready 신호는 LS_RAW_DATA_STATUS 가 아닌 LS_DATA_RAW 에 둔다 (작업 상태 row 는
     * 배정 시점 lazy 생성이라 적재 직후 전이 불가). 따라서 본 메서드는 LS_DATA_RAW 만 전이한다.
     * row 가 없으면 WARN 로깅 후 진행을 막지 않는다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markRawDataMarkingReady(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        videoRepository.findById(rawSn).ifPresentOrElse(
                LsDataRaw::markMarkingReady,
                () -> log.warn("[BatchTransition] raw video not found rawSn={} (marking-ready)", rawSn));
    }

    /**
     * 배치 실패 — 작업(워크플로우) 상태 LS_RAW_DATA_STATUS.DATA_STTS_CD → FAILED,
     * 배치 단계 상태 LS_DATA_RAW.DATA_STTS_CD → FAILED (Bug 2 — MARKING_READY 고착 방지).
     *
     * <p>기존엔 작업 상태 row 만 FAILED 로 바꾸고 LS_DATA_RAW.DATA_STTS_CD 는 손대지 않아,
     * 영상 처리 현황이 영구 MARKING_READY("마킹 대기")로 고착되던 결함을 수정한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markRawDataFailed(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        // 검수 소유 상태면 LS_DATA_RAW 도 FAILED 로 바꾸지 않는다 — (work=APPROVED, stage=FAILED) 라는
        // 이전엔 없던 불일치쌍을 만들지 않기 위함(H2). 진입 가드로 이 경로 자체가 도달 불가이나 fail-closed 로 둔다.
        if (transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_FAILED)) {
            return;
        }
        videoRepository.findById(rawSn).ifPresentOrElse(
                LsDataRaw::markBatchFailed,
                () -> log.warn("[BatchTransition] raw video not found rawSn={} (batch-failed)", rawSn));
    }

    /**
     * 비식별 실패 기록을 <b>독립 커밋 트랜잭션</b>으로 영속한다 (라이브 검증 결함 수정).
     *
     * <p>{@code DeidentifyStep.run()/runMock()} 은 {@code REQUIRES_NEW} 트랜잭션이라, 실패 분기에서
     * 인라인으로 {@code markDeidentified("F")} 한 뒤 예외를 던지면 그 트랜잭션이 전체 롤백되어 'F' 가
     * 사라진다(DB 엔 'N' 만 남음). 따라서 실패 기록은 <b>반드시 별도 빈</b>의 본 {@code REQUIRES_NEW}
     * 메서드로 위임해 run() 의 롤백과 독립적으로 커밋되게 한다(자기호출 금지 — self-invocation 은
     * 프록시 우회로 새 트랜잭션이 열리지 않음).
     *
     * <p>수행: ① {@code LS_DATA_RAW.DE_IDENT_YN → 'F'} ② {@code LS_DEIDENT_PROC_LOG} FAIL 신규 저장.
     * run() T1 에 저장됐던 REQUESTED procLog 는 롤백으로 소멸하므로, 커밋되는 FAIL 레코드를 여기서 남긴다.
     * MARKING_READY 미전이는 그대로 유지된다(이 메서드는 상태 전이를 하지 않으며, run() T1 롤백으로
     * 성공 위장이 발생하지 않는다).
     *
     * <p>보안(CWE-209): {@code errorCode}/{@code detail} 에 외부 API 원문 메시지·원본 경로·PII 를 담지
     * 않는다(호출자가 고정 코드 + 예외 클래스명만 전달). 원본 경로는 procLog 의 ORGNL_FILE_PATH_NM(NOT NULL)
     * 컬럼 컨벤션상 성공 경로와 동일하게 raw 의 저장 경로를 사용한다(에러 detail 에는 포함하지 않음).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordDeidentFailure(Long rawSn, String errorCode, String detail) {
        if (rawSn == null) {
            return;
        }
        videoRepository.findById(rawSn).ifPresentOrElse(
                r -> r.markDeidentified("F"),
                () -> log.warn("[BatchTransition] raw video not found rawSn={} (deident-fail)", rawSn));

        String orgnlPath = videoRepository.findById(rawSn)
                .map(LsDataRaw::getRawFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .orElse("N/A");
        LsDeidentProcLog failLog =
                LsDeidentProcLog.request(rawSn, null, orgnlPath, DEIDENT_FAIL_PROC_REG_ID);
        failLog.fail(errorCode, detail);
        procLogRepository.save(failLog);
        log.warn("[BatchTransition] deident failure recorded rawSn={} errCd={}", rawSn, errorCode);
    }

    /**
     * 배치 트리거 멱등성 보장용 조건부 원자 전이 (D1/D2 수정).
     *
     * <p>{@link kr.co.cudo.authoring.marking.listener.MarkingBatchBridge} 의
     * {@code @TransactionalEventListener(AFTER_COMMIT)} 는 활성
     * 트랜잭션 밖에서 실행되어 엔티티 dirty-write 가 영속되지 않는다(D1). 또한 동일 rawSn 에 마킹
     * 이벤트가 거의 동시에 2회 오면 두 브리지가 모두 가드를 통과해 배치를 2회 트리거할 수 있다(D2).
     *
     * <p>이를 막기 위해 작업 상태 전이를 <b>단일 조건부 UPDATE</b>(check-and-set)로 수행한다. 현재
     * 작업 상태가 SKIP 대상(BATCH_QUEUED/PROCESSING/COMPLETED)이 아닐 때만 BATCH_QUEUED 로 전이하며,
     * DB 가 동시 UPDATE 를 직렬화하므로 정확히 1건만 전이에 성공(영향 행수 1)하고 나머지는 skip(0)된다.
     * 본 메서드는 {@code REQUIRES_NEW} 로 즉시 커밋되어 영속이 보장된다.
     *
     * @return {@code true}=이번 호출이 BATCH_QUEUED 전이에 성공(트리거 권한 획득), {@code false}=이미
     *         진행 중이거나 작업 상태 row 미존재(skip)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaimBatchQueued(Long rawSn, Collection<String> skipStatuses) {
        if (rawSn == null) {
            return false;
        }
        int affected = rawDataStatusRepository.transitionToBatchQueuedIfNotSkipped(
                rawSn, LsRawDataStatus.STTS_BATCH_QUEUED, skipStatuses);
        return affected == 1;
    }

    /**
     * 배치 트리거용 작업 상태 row 를 <b>부재 시 생성</b>해 BATCH_QUEUED 로 큐잉한다 (FIX B — CRITICAL 재설계).
     *
     * <p><b>왜 row 를 생성하는가:</b> {@code LS_RAW_DATA_STATUS} row 는 작업자 배정 시점에 lazy 생성된다
     * ({@code AssignmentService.upsertDataStts} → {@link LsRawDataStatus#initial}). 그런데 REVIEWER 가
     * <b>미배정 영상에서 직접 마킹</b>하는 UX 에서는 이 row 가 없어, 조건부 전이
     * ({@link #tryClaimBatchQueued})가 영향 행수 0 → false 를 반환해 <b>배치가 트리거되지 않고 영상이
     * 영구 MARKING_READY("마킹 대기")로 고착</b>된다. 이를 막기 위해 row 부재 시 새 BATCH_QUEUED row 를
     * 생성해 배치가 진행되게 한다.
     *
     * <p><b>왜 이 메서드에서 재시도/catch 하지 않는가 (CWE-362 재설계 핵심):</b>
     * <ul>
     *   <li>{@link LsRawDataStatus} 는 {@code @GeneratedValue} 없는 <b>할당형 PK</b>(rawSn)라, Hibernate 는
     *       {@code save()}(persist) 시 INSERT 를 즉시 flush 하지 않고 트랜잭션 커밋 시점까지 지연한다. 그러면
     *       unique 위반이 catch 를 이미 벗어난 커밋 단계에서 터져 {@link DataIntegrityViolationException} 으로
     *       번역되지 않고 호출자까지 전파된다. 이를 막기 위해 {@code saveAndFlush} 로 INSERT 를 <b>이 메서드
     *       안에서 동기 flush</b> 시켜 Spring 이 즉시 {@code DataIntegrityViolationException} 으로 번역하게 한다.</li>
     *   <li>PostgreSQL 은 unique 위반 시 <b>트랜잭션 전체를 abort</b> 하므로, 같은 트랜잭션(같은 커넥션)
     *       안에서 재시도 쿼리를 실행하면 {@code current transaction is aborted} 로 실패한다. 즉 <b>같은 tx
     *       내 재시도는 구조적으로 불가능</b>하다. 따라서 예외를 이 {@code REQUIRES_NEW} 메서드 밖으로
     *       전파시켜 해당 tx 를 깨끗이 롤백시키고, 재시도/스킵 판정은 호출자
     *       ({@link kr.co.cudo.authoring.marking.listener.MarkingBatchBridge})가 <b>별도 프록시 호출(=새
     *       REQUIRES_NEW=새 커넥션)</b>로 수행한다.</li>
     * </ul>
     *
     * <p><b>호출 계약:</b> 호출자는 먼저 {@link #tryClaimBatchQueued}(tx1)로 조건부 전이를 시도하고, 그것이
     * false(=row 부재 이거나 이미 SKIP 상태)일 때만 본 메서드(tx2)를 호출한다. 본 메서드는 row 가 이미
     * 존재하면(다른 주체 소유/멱등 스킵) {@code false} 를 반환하고, 부재면 생성 후 {@code true} 를 반환한다.
     * 동시 노드가 그 사이 먼저 INSERT 하면 {@code saveAndFlush} 가
     * {@link DataIntegrityViolationException} 을 던지며, 이는 활성 tx 없는 AFTER_COMMIT 컨텍스트인 호출자에서
     * 안전하게 잡혀 skip 처리된다. 결과적으로 <b>정확히 1건만</b> 배치를 트리거한다.
     *
     * @return {@code true}=이번 호출이 row 를 생성해 BATCH_QUEUED 큐잉에 성공(트리거 권한 획득),
     *         {@code false}=row 가 이미 존재(다른 주체 소유/멱등 스킵)
     * @throws DataIntegrityViolationException 동시 노드가 먼저 INSERT 해 PK 가 충돌한 경우(호출자가 skip 처리)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean tryCreateBatchQueuedRow(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        // row 존재 → 이 경로 대상 아님(호출자가 tx1 에서 이미 조건부 전이를 시도해 SKIP 판정됨) → 멱등 스킵.
        if (rawDataStatusRepository.existsById(rawSn)) {
            return false;
        }
        LsRawDataStatus row = LsRawDataStatus.initial(rawSn); // PENDING (@Version=0 신규 insert)
        row.markBatchQueued();                                // → BATCH_QUEUED
        // saveAndFlush — INSERT 를 이 tx 안에서 즉시 flush 해 unique 위반을 동기 발생시킨다(위 Javadoc 참조).
        // DataIntegrityViolationException 은 여기서 잡지 않고 전파시켜 이 REQUIRES_NEW tx 를 롤백한다.
        rawDataStatusRepository.saveAndFlush(row);
        return true;
    }

    /**
     * 수동 배치 재처리 원자 클레임 (CWE-362) — FAILED→PROCESSING 조건부 UPDATE 로 소유권을 획득한다.
     *
     * <p>기존 {@code BatchReprocessService.retry()} 는 상태를 read(findById) 한 뒤 재기동(act)하는 사이에
     * 원자성이 없어, 자동 재시도 폴러 또는 동시 수동 요청과 경합 시 동일 rawSn 파이프라인이 이중 실행될 수
     * 있었다. 이를 막기 위해 상태 판정과 전이를 <b>단일 조건부 UPDATE</b>(check-and-set)로 통합한다.
     *
     * <p>두 테이블 책임 분리에 맞춰 ① 배치 단계(LS_DATA_RAW.DATA_STTS_CD) FAILED→PROCESSING 을 우선
     * 클레임하고, ② 배치 단계가 FAILED 가 아니면 작업 상태(LS_RAW_DATA_STATUS.DATA_STTS_CD)
     * FAILED→PROCESSING 을 클레임한다. 정상 배치 실패는 두 컬럼을 함께 FAILED 로 두므로 대개 ①에서 성공한다.
     *
     * <h3>0행의 원인을 반드시 구분한다 (B-ISSUE-101 — CWE-362)</h3>
     * <p>구 구현은 ①이 0행이면 <b>원인을 구분하지 않고</b> 곧바로 ②로 폴백했다. 그런데 정상 배치 실패는
     * 두 컬럼이 <b>함께</b> FAILED 이므로, 동시 호출자 A 가 RAW 컬럼을, B 가 작업상태 컬럼을 각각 선점해
     * <b>둘 다 true</b> 를 받았다(실측: 동일 rawSn 5요청 → 200 이 2건, 파이프라인 2벌 동시 실행 + 재시도
     * 카운터 이중 증가). 즉 "①이 0행" 의 지배적 원인은 <b>남이 방금 선점</b>인데 그것을 "RAW 는 원래 대상이
     * 아니다" 로 오독한 것이다.
     *
     * <p>따라서 ①이 0행이면 RAW 의 현재 단계를 <b>다시 읽어</b> 판정한다.
     * <ul>
     *   <li>{@code PROCESSING} — 다른 주체가 방금 클레임했다 → 즉시 {@code false}(폴백 금지).</li>
     *   <li>{@code FAILED} — UPDATE 가 0행인데 여전히 FAILED 인 모순 상황(재전이 레이스) → fail-closed
     *       로 {@code false}(호출자가 409, 재시도 가능).</li>
     *   <li>그 외(row 부재·COMPLETED·MARKING_READY 등) — RAW 는 애초에 클레임 대상이 아니었던 예외
     *       형상이므로, "작업 상태만 FAILED" 인 경우에 한해 ②를 허용한다. ② 자체도 단일 조건부 UPDATE 라
     *       그 축에서도 정확히 1건만 성공한다.</li>
     * </ul>
     * 결과적으로 <b>어떤 조합에서도 동시 호출자 중 정확히 1건만</b> {@code true} 를 받는다.
     *
     * <p><b>REQUIRES_NEW 로 즉시 커밋</b>: 클레임을 호출자 트랜잭션 밖에서 커밋해, 이어지는
     * {@code BatchOrchestrator.process()}(NOT_SUPPORTED) 내부의 {@code markRawDataProcessing}
     * (REQUIRES_NEW)가 같은 raw row 를 UPDATE 할 때 자기-교착(self-deadlock)이 발생하지 않도록 한다.
     *
     * @return {@code true}=이번 호출이 FAILED→PROCESSING 클레임에 성공(재기동 권한 획득),
     *         {@code false}=FAILED 아님/이미 다른 주체가 클레임(→ 호출자가 409 로 거부)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaimReprocessFromFailed(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        int rawClaimed = videoRepository.claimReprocessFromFailed(
                rawSn, LsDataRaw.DATA_STTS_FAILED, LsDataRaw.DATA_STTS_PROCESSING);
        if (rawClaimed == 1) {
            return true;
        }
        // B-ISSUE-101 — 0행의 원인을 구분한다(위 Javadoc). 남이 선점했거나(PROCESSING) 판정이 모순
        // (여전히 FAILED)이면 작업상태 폴백을 허용하지 않는다 — 두 컬럼이 함께 FAILED 인 정상 실패
        // 형상에서 두 호출자가 서로 다른 컬럼을 선점해 상호배제가 깨지던 결함의 원인이다.
        String rawStage = videoRepository.findDataSttsCdByRawSn(rawSn).orElse(null);
        if (LsDataRaw.DATA_STTS_PROCESSING.equals(rawStage) || LsDataRaw.DATA_STTS_FAILED.equals(rawStage)) {
            log.warn("[BatchTransition] reprocess claim rejected — raw stage owned by another caller rawSn={}",
                    rawSn);
            return false;
        }
        int statusClaimed = rawDataStatusRepository.claimReprocessFromFailed(
                rawSn, LsRawDataStatus.STTS_FAILED, LsRawDataStatus.STTS_PROCESSING);
        return statusClaimed == 1;
    }

    /**
     * 수동 배치 재처리 클레임 <b>보상 롤백</b> (DEV_FIX H10) — 배치 단계 PROCESSING → FAILED 로 되돌린다.
     *
     * <p>{@link #tryClaimReprocessFromFailed} 성공 후 {@code BatchOrchestrator.process()} 가
     * {@code SKIPPED}(검수 소유 작업 상태) 로 즉시 반환하면 파이프라인이 한 건도 돌지 않고
     * {@code markRawDataFailed}/{@code markRawDataCompleted} 도 호출되지 않아 <b>클레임으로 바꾼
     * PROCESSING 을 되돌릴 코드가 없다</b>. 그 결과 stage 가 영구 PROCESSING 으로 고착되고, 이후
     * 재처리 요청은 stage/work 어느 쪽도 FAILED 가 아니라 <b>영구 409</b> 가 된다.
     *
     * <p>따라서 클레임 주체가 SKIPPED 를 관측하면 본 메서드로 원상복구한다. 조건부 UPDATE 라
     * 그 사이 다른 주체가 상태를 바꿨으면 0행으로 안전하게 포기한다(fail-closed).
     * {@code REQUIRES_NEW} 로 즉시 커밋한다.
     *
     * @return {@code true} = 보상 롤백 성공(PROCESSING→FAILED), {@code false} = 이미 다른 상태
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean releaseReprocessClaim(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        int reverted = videoRepository.compensateReprocessClaim(
                rawSn, LsDataRaw.DATA_STTS_PROCESSING, LsDataRaw.DATA_STTS_FAILED);
        if (reverted == 1) {
            log.warn("[BatchTransition] reprocess claim compensated (PROCESSING->FAILED) rawSn={}", rawSn);
            return true;
        }
        // 작업 상태를 클레임했던 경로(work FAILED→PROCESSING)도 함께 복구 시도한다. 통상 이 경로는
        // 진입 가드에 걸리지 않으므로 도달하지 않지만, 보상을 특정 컬럼에만 걸어 두면 경로 추가 시
        // 다시 고착이 생기므로 두 컬럼 모두 조건부로 되돌린다(fail-closed).
        int workReverted = rawDataStatusRepository.claimReprocessFromFailed(
                rawSn, LsRawDataStatus.STTS_PROCESSING, LsRawDataStatus.STTS_FAILED);
        if (workReverted == 1) {
            log.warn("[BatchTransition] reprocess work-status claim compensated rawSn={}", rawSn);
            return true;
        }
        log.warn("[BatchTransition] reprocess claim compensation skipped (status already changed) rawSn={}", rawSn);
        return false;
    }

    /**
     * 작업 상태 전이 — 검수 소유 상태({@link #REVIEW_OWNED_STATUSES})가 아닐 때만 전이한다 (B-ISSUE-03).
     *
     * <p>조건 판정과 전이를 <b>단일 조건부 UPDATE</b>(check-and-set)로 수행한다. 2노드 Active-Active
     * 배포라 read-then-write 나 JVM 락은 방어가 되지 않으며, DB 가 UPDATE 를 직렬화해야 한다.
     * 전이가 차단(영향 행수 0)되면 <b>예외를 던지지 않고</b> 원인을 구분해 WARN 로깅만 남긴다.
     *
     * @return {@code true} = 검수 소유 상태라 차단됨(호출자는 후속 부수효과도 수행하지 말 것),
     *         {@code false} = 전이 성공 또는 작업 상태 row 부재(파생 RAW — 배치 진행을 막지 않는다)
     */
    private boolean transitionRawDataStatus(Long rawSn, String newStatus) {
        if (rawSn == null) {
            return false;
        }
        int affected = rawDataStatusRepository.transitionByBatchIfNotBlocked(
                rawSn, newStatus, REVIEW_OWNED_STATUSES);
        if (affected == 1) {
            return false;
        }
        // skip 경로에서만 추가 조회 — row 부재인지 검수 소유 상태인지 구분해 운영자가 원인을 알 수 있게 한다.
        // row 부재(파생 RAW 등)는 차단이 아니다 → false 로 진행을 허용한다.
        return rawDataStatusRepository.findById(rawSn)
                .map(stts -> {
                    log.warn("[BatchTransition] work status transition skipped (review-owned) "
                            + "rawSn={} current={} target={}", rawSn, stts.getDataSttsCd(), newStatus);
                    return true;
                })
                .orElseGet(() -> {
                    log.warn("[BatchTransition] raw data status not found rawSn={} target={}",
                            rawSn, newStatus);
                    return false;
                });
    }
}
