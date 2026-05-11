package kr.co.cudo.authoring.batch.test;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.batch.step.Sam2SegmentStep;
import kr.co.cudo.authoring.batch.step.YoloAutolabelStep;
import kr.co.cudo.authoring.batch.test.dto.AutolabelRunResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 오토라벨링 파이프라인 테스트 트리거 서비스.
 * 외부 서버(비식별/Gitea/VLM) 없이 YOLO → SAM2 만 실행해서 라벨이 DB에 저장되는지 검증.
 *
 * 트랜잭션 정책: 각 step 이 자체 REQUIRES_NEW 트랜잭션을 사용하므로 본 서비스는 비트랜잭션.
 * 삭제는 repository 의 자체 트랜잭션을 통해 수행.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutolabelTestService {

    private static final List<String> SKIPPED_STEPS = List.of("DEIDENTIFY", "VLM_VERIFY");

    private final YoloAutolabelStep yoloStep;
    private final Sam2SegmentStep sam2Step;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final FfmpegFrameExtractor frameExtractor;
    private final VideoRepository videoRepository;
    private final BatchStatusService statusService;

    public AutolabelRunResponse run(Long rawSn) {
        long framesFound = srcRepository.countByRawSn(rawSn);
        if (framesFound == 0L) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "rawSn=" + rawSn + " 의 추출된 프레임이 없습니다.");
        }

        // idempotent: 기존 자동 라벨 제거 (재실행 대비)
        lblRepository.deleteByRawSnAutoLbl(rawSn);

        long started = System.currentTimeMillis();
        try {
            statusService.markStage(rawSn, BatchStage.YOLO);
            int yoloCount = yoloStep.run(rawSn);

            statusService.markStage(rawSn, BatchStage.SAM2);
            int sam2Count = sam2Step.run(rawSn);

            long elapsed = System.currentTimeMillis() - started;
            statusService.markCompleted(rawSn);

            log.info("[AutolabelTest] run rawSn={} yolo={} sam2={} elapsed={}ms",
                    rawSn, yoloCount, sam2Count, elapsed);

            return new AutolabelRunResponse(rawSn, framesFound, false, yoloCount, sam2Count, elapsed, SKIPPED_STEPS);
        } catch (Exception e) {
            statusService.markFailed(rawSn, e);
            throw e;
        }
    }

    public AutolabelRunResponse runFull(Long rawSn) {
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT,
                        "rawSn=" + rawSn + " 영상 레코드가 없습니다."));

        long framesFound = srcRepository.countByRawSn(rawSn);
        boolean frameExtracted = false;

        if (framesFound == 0L) {
            statusService.markStage(rawSn, BatchStage.FRAME_EXTRACT);
            List<LsDataSrc> extracted = frameExtractor.extract(raw);
            framesFound = extracted.size();
            frameExtracted = true;
            if (framesFound == 0L) {
                statusService.markFailed(rawSn, new IllegalStateException("프레임 추출 결과 0건"));
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "rawSn=" + rawSn + " 프레임 추출 결과가 0건입니다.");
            }
        }

        lblRepository.deleteByRawSnAutoLbl(rawSn);

        long started = System.currentTimeMillis();
        try {
            statusService.markStage(rawSn, BatchStage.YOLO);
            int yoloCount = yoloStep.run(rawSn);

            statusService.markStage(rawSn, BatchStage.SAM2);
            int sam2Count = sam2Step.run(rawSn);

            long elapsed = System.currentTimeMillis() - started;
            statusService.markCompleted(rawSn);

            log.info("[AutolabelTest] runFull rawSn={} frameExtracted={} yolo={} sam2={} elapsed={}ms",
                    rawSn, frameExtracted, yoloCount, sam2Count, elapsed);

            return new AutolabelRunResponse(rawSn, framesFound, frameExtracted, yoloCount, sam2Count, elapsed, SKIPPED_STEPS);
        } catch (Exception e) {
            statusService.markFailed(rawSn, e);
            throw e;
        }
    }
}
