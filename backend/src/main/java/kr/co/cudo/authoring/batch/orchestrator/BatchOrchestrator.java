package kr.co.cudo.authoring.batch.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.batch.step.BbHint;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.TrackInterpolationStep;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 배치 파이프라인 오케스트레이터 (V2.1 — 비식별 분리, post-marking 시퀀스 재정렬).
 * <p>
 * post-marking 시퀀스 단계 순서:
 *   1. MARKING        (마킹 데이터 확인 — 마킹 없으면 FRAME_EXTRACT 진입 전 INVALID_INPUT)
 *   2. VLM            (VlmTimeseriesStep.runWithMarking/run — 외부 VLM 서비스 비동기 위탁. enabled=false 면 NO-OP.)
 *   3. FRAME_EXTRACT  (FfmpegFrameExtractor.extractByMarks — 마킹 위치 기반 원본/비식별 2벌 추출.
 *                      비식별 영상 경로는 추출기가 저장된 비식별 결과에서 스스로 조회)
 *   4. YOLO           (YoloAutolabelStep.run)
 *   5. SAM2           (Sam2SegmentStep.run)
 *   6. INTERPOLATE    (TrackInterpolationStep.run)
 *   7. COMPLETED      (statusService.markCompleted)
 * <p>
 * Phase 1 (파이프라인 재정렬) 변경:
 *  - post-marking 시퀀스에서 DEIDENTIFY 단계를 제거했다. 비식별은 적재 직후 "선두" 단계로
 *    분리된다 (Phase 2 에서 구현). 본 orchestrator 는 더 이상 DeidentifyStep 을 호출하지 않으며,
 *    FfmpegFrameExtractor 가 이미 완료된 비식별 결과 경로를 스스로 조회한다.
 * <p>
 * 영상 단위 시계열 메타 추출:
 *  - 책임을 외부 VLM 서비스로 위탁 (ccarch if-vlm-timeseries-spi).
 *  - stage 코드는 {@link BatchStage#VLM} 사용. 결과 적재는 webhook (POST /v1/vlm/result) 로 비동기 수신.
 * <p>
 * 실패 처리:
 *  - 어느 단계에서든 예외 발생 시 statusService.markFailed + retryQueue.enqueueIfRetryable.
 *  - retryQueue 가 maxAttempts 초과면 false 반환 → FAILED 상태 고정.
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

    private final VlmTimeseriesStep vlmTimeseriesStep;
    private final FfmpegFrameExtractor frameExtractor;
    private final YoloAutolabelStep yoloStep;
    private final Sam2SegmentStep sam2Step;
    private final TrackInterpolationStep trackInterpolationStep;
    private final BatchStatusService statusService;
    private final BatchTransitionService transitionService;
    private final BatchRetryQueue retryQueue;
    private final VideoRepository videoRepository;
    private final LsMarkingRepository markingRepository;
    private final ObjectMapper objectMapper;

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

        // 배치 시작: 작업 상태 PROCESSING 전이 (REQUIRES_NEW 별도 트랜잭션으로 명시 영속).
        transitionService.markRawDataProcessing(rawSn);

        try {
            // 0. 마킹 확인 — Phase 3: VLM 호출 전에 마킹 데이터 조회.
            statusService.markStage(rawSn, BatchStage.MARKING);
            List<LsMarking> markings = markingRepository.findByRawSnOrderByRegDtDesc(rawSn);
            log.info("[BatchOrchestrator] marking check rawSn={} count={}", rawSn, markings.size());

            // 1. VLM 시계열 메타 — Phase 1: 외부 위탁 (enabled=false 면 NO-OP).
            //    마킹이 있으면 최신 마킹 데이터를 포함하여 호출.
            statusService.markStage(rawSn, BatchStage.VLM);
            if (!markings.isEmpty()) {
                vlmTimeseriesStep.runWithMarking(rawSn, markings.get(0));
            } else {
                vlmTimeseriesStep.run(rawSn);
            }

            // 2. 프레임 추출 — 마킹 필수 (자동/수동). 마킹 없으면 파이프라인 중단.
            //    비식별은 적재 직후 선두 단계에서 완료됨 (Phase 2). 추출기가 저장된
            //    비식별 결과 경로를 스스로 조회해 원본+비식별 2벌을 추출한다.
            statusService.markStage(rawSn, BatchStage.FRAME_EXTRACT);
            if (markings.isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "마킹 데이터가 없습니다. rawSn=" + rawSn);
            }
            List<MarkItem> marks = parseMarks(markings.get(0).getMarkCn());
            List<LsDataSrc> frames = frameExtractor.extractByMarks(raw, marks);
            if (frames.isEmpty()) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR,
                        "프레임 추출 결과가 0건입니다 rawSn=" + rawSn);
            }

            // 3. YOLO + Track — 인메모리 BBOX 힌트(POLYGON_ONLY 라벨 포함) 반환.
            //    정적 필드/싱글톤 저장 금지 — 지역 변수로만 전달 (스레드 안전).
            statusService.markStage(rawSn, BatchStage.YOLO);
            List<BbHint> hints = yoloStep.run(rawSn);

            // 4. SAM2.
            statusService.markStage(rawSn, BatchStage.SAM2);
            sam2Step.run(rawSn, hints);

            // 5. 트랙 보간 — 같은 trackId 의 누락 프레임 BBOX 를 선형 보간으로 채움.
            statusService.markStage(rawSn, BatchStage.INTERPOLATE);
            trackInterpolationStep.run(rawSn);

            // 작업 상태 COMPLETED 전이 + LS_DATA_RAW.DATA_STTS_CD=COMPLETED
            // (REQUIRES_NEW 별도 트랜잭션으로 명시 영속 — self-invocation/비트랜잭션 회피).
            transitionService.markRawDataCompleted(rawSn);
            statusService.markCompleted(rawSn);
            retryQueue.clear(rawSn);
            log.info("[BatchOrchestrator] completed rawSn={}", rawSn);
            return BatchStage.COMPLETED;
        } catch (RuntimeException e) {
            statusService.markFailed(rawSn, e);
            // 실패 시 작업 상태 FAILED 전이 (REQUIRES_NEW 별도 트랜잭션으로 명시 영속).
            transitionService.markRawDataFailed(rawSn);
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

    private List<MarkItem> parseMarks(String marksJson) {
        try {
            return objectMapper.readValue(marksJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "마킹 데이터 파싱 실패", e);
        }
    }
}
