package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>전이 불가 상태(상태 머신 위반) 여부 검증은 {@link LsRawDataStatus} 의 책임이며, 본 서비스는
 * 단순 갱신만 위임한다. 작업 상태 row 가 없거나 영상 row 가 없으면 WARN 로깅 후 배치 진행을 막지 않는다.
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
     * 배치 시작 — 작업(워크플로우) 상태 LS_RAW_DATA_STATUS.DATA_STTS_CD → PROCESSING,
     * 배치 단계 상태 LS_DATA_RAW.DATA_STTS_CD → PROCESSING (Bug 2 — '처리중' 도입).
     *
     * <p>두 컬럼을 같은 타이밍에 전이한다. 작업 상태 row 는 배정 시점 lazy 생성이라 적재 직후엔
     * 없을 수 있으나, 마킹 완료로 배치가 시작되는 시점에는 배정·작업 상태 row 가 존재한다.
     * 영상(LS_DATA_RAW) row 는 항상 존재하므로 MARKING_READY → PROCESSING 으로 전이해
     * 마킹 완료~배치 완료 구간이 "처리중"으로 표시되게 한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markRawDataProcessing(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_PROCESSING);
        videoRepository.findById(rawSn).ifPresentOrElse(
                LsDataRaw::markProcessing,
                () -> log.warn("[BatchTransition] raw video not found rawSn={} (processing)", rawSn));
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
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markRawDataCompleted(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_ASSIGNED);
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
        transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_FAILED);
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

    private void transitionRawDataStatus(Long rawSn, String newStatus) {
        if (rawSn == null) {
            return;
        }
        rawDataStatusRepository.findById(rawSn).ifPresentOrElse(
                stts -> {
                    stts.transitionTo(newStatus);
                    rawDataStatusRepository.save(stts);
                },
                () -> log.warn("[BatchTransition] raw data status not found rawSn={} target={}",
                        rawSn, newStatus));
    }
}
