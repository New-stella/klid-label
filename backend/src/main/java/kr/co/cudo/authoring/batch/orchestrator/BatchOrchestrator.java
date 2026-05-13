package kr.co.cudo.authoring.batch.orchestrator;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.BbHint;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmObjectVerifyStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 배치 파이프라인 오케스트레이터 (Phase 5).
 * <p>
 * 단계 순서:
 *   1. FRAME_EXTRACT  (FfmpegFrameExtractor.extract)
 *   2. DEIDENTIFY     (조건부 — needsDeidentify(rawSn) 일 때만)
 *   3. YOLO           (YoloAutolabelStep.run)
 *   4. SAM2           (Sam2SegmentStep.run)
 *   5. INTERPOLATE    (TrackInterpolationStep.run — Phase 3 신규)
 *   6. VLM_VERIFY     (VlmObjectVerifyStep.run)
 *   7. COMPLETED      (statusService.markCompleted)
 * <p>
 * 실패 처리:
 *  - 어느 단계에서든 예외 발생 시 statusService.markFailed + retryQueue.enqueueIfRetryable.
 *  - retryQueue 가 maxAttempts 초과면 false 반환 → FAILED 상태 고정.
 *  - 비식별 단계는 자체적으로 DE_IDNTF_YN='F' 마킹 + 원본 보존 처리.
 * <p>
 * 트랜잭션 분리:
 *  - 본 process() 자체는 NOT_SUPPORTED — 각 Step 이 REQUIRES_NEW 로 자체 트랜잭션 보유.
 *  - 단계 실패가 다른 단계 결과(예: FRAME_EXTRACT INSERT) 에 영향 없도록 격리.
 * <p>
 * 영상 단위 직렬 호출 보장 (Phase 4):
 *  - {@link #process(Long)} 는 단일 영상(rawSn) 에 대해 단일 스레드에서 호출된다.
 *  - {@link YoloAutolabelStep#run(Long)} 가 내부적으로 frame_no ASC 정렬된 모든 프레임을
 *    순차로 {@code aiServerClient.predictYoloTrack(...)} 호출.
 *  - ultralytics tracker state 는 ai-server 측에서 clip_id 단위로 격리되므로, 같은 영상의
 *    프레임 호출 순서가 보장되어야 동일 객체에 동일 track_id 가 부여된다.
 *  - 외부 스케줄러(Quartz) 가 두 영상을 병렬로 처리하는 경우에도 clip_id 가 다르므로 격리됨 —
 *    즉 영상 간 track_id 누수는 ai-server 의 clip_id 기반 격리로 차단.
 *  - 본 클래스는 정적 필드/싱글톤 상태를 보유하지 않으므로 영상 간 독립성을 보장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchOrchestrator {

    private final FfmpegFrameExtractor frameExtractor;
    private final DeidentifyStep deidentifyStep;
    private final YoloAutolabelStep yoloStep;
    private final Sam2SegmentStep sam2Step;
    private final TrackInterpolationStep trackInterpolationStep;
    private final VlmObjectVerifyStep vlmStep;
    private final BatchStatusService statusService;
    private final BatchRetryQueue retryQueue;
    private final VideoRepository videoRepository;

    /**
     * 단일 영상 1건 처리.
     * - rawSn null/존재하지 않음 → INVALID_INPUT.
     * - process 자체는 트랜잭션을 시작하지 않으나(NOT_SUPPORTED), 영상 메타 조회를 위한
     *   짧은 readOnly 트랜잭션은 필요 → 별도 메서드로 격리.
     */
    public BatchStage process(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        LsDataRaw raw = loadRaw(rawSn);
        statusService.markStage(rawSn, BatchStage.FRAME_EXTRACT);

        try {
            List<LsDataSrc> frames = frameExtractor.extract(raw);
            if (frames.isEmpty()) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR,
                        "프레임 추출 결과가 0건입니다 rawSn=" + rawSn);
            }

            if (raw.needsDeidentify()) {
                statusService.markStage(rawSn, BatchStage.DEIDENTIFY);
                deidentifyStep.run(raw);
            }

            statusService.markStage(rawSn, BatchStage.YOLO);
            // Phase 2: YoloStep 이 인메모리 BBOX 힌트(POLYGON_ONLY 라벨 포함)를 반환.
            // 정적 필드/싱글톤 저장 금지 — 지역 변수로만 전달 (스레드 안전).
            List<BbHint> hints = yoloStep.run(rawSn);

            statusService.markStage(rawSn, BatchStage.SAM2);
            sam2Step.run(rawSn, hints);

            // Phase 3: 트랙 보간 — 같은 trackId 의 누락 프레임 BBOX 를 선형 보간으로 채움.
            // SAM2 다음, VLM 이전. VLM 검증은 보간 row 까지 포함하여 신뢰도 갱신할 수 있음.
            statusService.markStage(rawSn, BatchStage.INTERPOLATE);
            trackInterpolationStep.run(rawSn);

            statusService.markStage(rawSn, BatchStage.VLM_VERIFY);
            vlmStep.run(rawSn);

            markRawCompleted(rawSn);
            statusService.markCompleted(rawSn);
            retryQueue.clear(rawSn);
            log.info("[BatchOrchestrator] completed rawSn={}", rawSn);
            return BatchStage.COMPLETED;
        } catch (RuntimeException e) {
            statusService.markFailed(rawSn, e);
            boolean willRetry = retryQueue.enqueueIfRetryable(rawSn);
            log.warn("[BatchOrchestrator] failed rawSn={} willRetry={} cause={}",
                    rawSn, willRetry, e.getClass().getSimpleName());
            return BatchStage.FAILED;
        }
    }

    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    protected LsDataRaw loadRaw(Long rawSn) {
        return videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다 rawSn=" + rawSn));
    }

    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    protected void markRawCompleted(Long rawSn) {
        videoRepository.findById(rawSn).ifPresent(r -> r.changeStatus("COMPLETED"));
    }
}
