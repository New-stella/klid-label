package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * [개발/검수 전용] dev 업로드 경로의 선두 비식별 실행기 (운영 시나리오 1:1 고정 플로우).
 *
 * <p>운영(prd) 환경에서는 {@code @Profile("!prd")} 로 빈 자체가 등록되지 않는다.
 *
 * <p>dev 업로드는 {@code VideoIngestedEvent} 가 없으므로 이 러너가 선두 비식별을 직접 수행한다.
 * 플로우는 <b>비식별(무조건) → MARKING_READY 전이 → 정지</b> 로 고정된다. 합성 마킹 생성과
 * {@code BatchOrchestrator.process} 직접 호출은 폐지되었으며, 잔여 배치(VLM→프레임추출→YOLO/SAM2)는
 * 사용자가 마킹 화면에서 실제 마킹→완료할 때 {@code MarkingCompletedEvent → MarkingBatchBridge}
 * 경로로만 트리거된다 (운영 경로와 동일).
 *
 * <h3>설계 결정</h3>
 * <ul>
 *   <li>비식별 실패 시 {@code AsyncDeidentifyRunner} 패턴을 따라 try/catch + WARN + 예외 삼킴.
 *       재시도 큐는 주입하지 않아 구조적으로 큐 사용을 차단한다(외부 수동 재비식별 정책).
 *       실패 시 {@code deIdntfYn} 미설정 상태로 영상은 목록에 그대로 보이며, MARKING_READY 로
 *       전이하지 않아 마킹 가드({@code deIdntfYn='Y'})에 막힌다.</li>
 * </ul>
 */
@Slf4j
@Service
@Profile("!prd")
public class DevPipelineRunner {

    private final DeidentifyStep deidentifyStep;
    private final BatchTransitionService transitionService;
    private final VideoRepository videoRepository;

    public DevPipelineRunner(DeidentifyStep deidentifyStep,
                             BatchTransitionService transitionService,
                             VideoRepository videoRepository) {
        this.deidentifyStep = deidentifyStep;
        this.transitionService = transitionService;
        this.videoRepository = videoRepository;
    }

    /**
     * dev 업로드 영상에 대해 선두 비식별을 비동기로 수행하고 MARKING_READY 에서 정지한다.
     *
     * @param rawSn 영상 식별자
     */
    @Async("batchAsyncExecutor")
    public void runAsync(Long rawSn) {
        log.info("[DevPipelineRunner] starting dev deidentify rawSn={}", rawSn);
        LsDataRaw raw = loadRaw(rawSn).orElse(null);
        if (raw == null) {
            log.warn("[DevPipelineRunner] raw not found rawSn={} — skip", rawSn);
            return;
        }

        try {
            // 선두 비식별(무조건) — 성공 시에만 MARKING_READY 전이. 실패 시 예외 삼킴 + WARN.
            deidentifyStep.run(raw);
            transitionService.markRawDataMarkingReady(rawSn);
            log.info("[DevPipelineRunner] deidentify done — stopped at MARKING_READY rawSn={}", rawSn);
        } catch (RuntimeException e) {
            // @Async 이므로 예외 삼킴 + WARN. 재시도 큐 미사용(외부 수동 재비식별 정책).
            log.warn("[DevPipelineRunner] dev deidentify failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /** 영상 메타 조회 — REQUIRES_NEW readOnly (AsyncDeidentifyRunner.loadRaw 패턴). */
    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    protected Optional<LsDataRaw> loadRaw(Long rawSn) {
        if (rawSn == null) {
            return Optional.empty();
        }
        return videoRepository.findById(rawSn);
    }
}
