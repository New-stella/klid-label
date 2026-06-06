package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
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

    /**
     * 배치 시작 — LS_RAW_DATA_STATUS.DATA_STTS_CD → PROCESSING.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markRawDataProcessing(Long rawSn) {
        transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_PROCESSING);
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
        transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_ASSIGNED);
        videoRepository.findById(rawSn).ifPresentOrElse(
                r -> r.changeStatus("COMPLETED"),
                () -> log.warn("[BatchTransition] raw video not found rawSn={} (completed)", rawSn));
    }

    /**
     * 배치 실패 — LS_RAW_DATA_STATUS.DATA_STTS_CD → FAILED.
     * 영상(LS_DATA_RAW) 상태는 BatchStatusService.markFailed 와 별개로,
     * 작업 상태 row 만 FAILED 로 전이한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markRawDataFailed(Long rawSn) {
        transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_FAILED);
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
