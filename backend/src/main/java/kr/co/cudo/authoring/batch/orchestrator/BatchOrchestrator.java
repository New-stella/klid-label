package kr.co.cudo.authoring.batch.orchestrator;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.BbHint;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmMetaStep;
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
 * 배치 파이프라인 오케스트레이터 (V2 — 파이프라인 재배치).
 * <p>
 * V2 단계 순서 (Phase 2 갱신):
 *   1. VLM            (VlmMetaStep.run — 영상 단위 메타, Phase 1 신규 첫 단계)
 *   2. DEIDENTIFY     (DeidentifyStep.run — Phase 2: 무조건 호출 — PRVC/PSDO/ANONY 모두 호출)
 *   3. FRAME_EXTRACT  (FfmpegFrameExtractor.extractBoth — Phase 2: 원본/비식별 영상 2벌 추출)
 *   4. YOLO           (YoloAutolabelStep.run)
 *   5. SAM2           (Sam2SegmentStep.run)
 *   6. INTERPOLATE    (TrackInterpolationStep.run)
 *   7. COMPLETED      (statusService.markCompleted)
 * <p>
 * V2 변경:
 *  - VlmObjectVerifyStep(객체 검증) 호출 제거 — 영상 단위 메타로 일원화. 코드/enum(VLM_VERIFY) 은 보존 (Phase 5 cleanup).
 *  - VLM 을 첫 단계로 전진 배치하여 비식별 이전 원본 기반 메타 추출.
 * <p>
 * 실패 처리:
 *  - 어느 단계에서든 예외 발생 시 statusService.markFailed + retryQueue.enqueueIfRetryable.
 *  - retryQueue 가 maxAttempts 초과면 false 반환 → FAILED 상태 고정.
 *  - 비식별 단계는 자체적으로 DE_IDNTF_YN='F' 마킹 + 원본 보존 처리.
 * <p>
 * 트랜잭션 분리:
 *  - 본 process() 자체는 NOT_SUPPORTED — 각 Step 이 REQUIRES_NEW 로 자체 트랜잭션 보유.
 *  - 단계 실패가 다른 단계 결과(예: VLM_META INSERT) 에 영향 없도록 격리.
 * <p>
 * 영상 단위 직렬 호출 보장:
 *  - {@link #process(Long)} 는 단일 영상(rawSn) 에 대해 단일 스레드에서 호출된다.
 *  - YoloAutolabelStep 내부에서 frame_no ASC 정렬된 모든 프레임을 순차로 ai-server 호출 (tracker state 격리).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchOrchestrator {

    private final VlmMetaStep vlmMetaStep;
    private final FfmpegFrameExtractor frameExtractor;
    private final DeidentifyStep deidentifyStep;
    private final YoloAutolabelStep yoloStep;
    private final Sam2SegmentStep sam2Step;
    private final TrackInterpolationStep trackInterpolationStep;
    private final BatchStatusService statusService;
    private final BatchRetryQueue retryQueue;
    private final VideoRepository videoRepository;

    /**
     * 단일 영상 1건 처리 (V2 순서).
     * - rawSn null/존재하지 않음 → INVALID_INPUT.
     * - process 자체는 트랜잭션을 시작하지 않으나(NOT_SUPPORTED), 영상 메타 조회를 위한
     *   짧은 readOnly 트랜잭션은 필요 → 별도 메서드로 격리.
     */
    public BatchStage process(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        LsDataRaw raw = loadRaw(rawSn);

        try {
            // 1. VLM 영상 단위 메타 — V2 정책상 비식별 이전 원본으로 메타 추출.
            statusService.markStage(rawSn, BatchStage.VLM);
            vlmMetaStep.run(rawSn);

            // 2. 비식별 (Phase 2 — 무조건화: PRVC/PSDO/ANONY 구분 없이 모든 영상 호출).
            //    원본 보존 원칙 + DE_IDNTF_YN 'Y'/'F' 마킹은 DeidentifyStep 자체 처리.
            statusService.markStage(rawSn, BatchStage.DEIDENTIFY);
            String deidVideoPath = deidentifyStep.run(raw);

            // 3. 프레임 추출 (Phase 2 — 영상 2벌 보관: 원본 + 비식별 영상 양쪽에서 추출).
            //    raw.deidFilePath 가 null 이거나 파일 미존재면 RAW 만 (V1 호환 / graceful fallback).
            statusService.markStage(rawSn, BatchStage.FRAME_EXTRACT);
            List<LsDataSrc> frames = frameExtractor.extractBoth(raw, deidVideoPath);
            if (frames.isEmpty()) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR,
                        "프레임 추출 결과가 0건입니다 rawSn=" + rawSn);
            }

            // 4. YOLO + Track — 인메모리 BBOX 힌트(POLYGON_ONLY 라벨 포함) 반환.
            //    정적 필드/싱글톤 저장 금지 — 지역 변수로만 전달 (스레드 안전).
            statusService.markStage(rawSn, BatchStage.YOLO);
            List<BbHint> hints = yoloStep.run(rawSn);

            // 5. SAM2.
            statusService.markStage(rawSn, BatchStage.SAM2);
            sam2Step.run(rawSn, hints);

            // 6. 트랙 보간 — 같은 trackId 의 누락 프레임 BBOX 를 선형 보간으로 채움.
            statusService.markStage(rawSn, BatchStage.INTERPOLATE);
            trackInterpolationStep.run(rawSn);

            // V2: VlmObjectVerifyStep 호출 제거 — 영상 단위 메타로 일원화.
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
