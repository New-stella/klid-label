package kr.co.cudo.authoring.batch.test;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.BbHint;
import kr.co.cudo.authoring.batch.step.DeidentifyStep;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 오토라벨링 파이프라인 테스트 트리거 서비스.
 * 외부 서버(Gitea/VLM) 없이 FRAME_EXTRACT → DEIDENTIFY → YOLO → SAM2 4단계를 실행한다.
 * 각 단계는 호출자가 enabledStages 토글로 ON/OFF 할 수 있다.
 *
 * 트랜잭션 정책: 각 step 이 자체 REQUIRES_NEW 트랜잭션을 사용하므로 본 서비스는 비트랜잭션.
 * 상태 갱신은 VideoRepository.updateStatus 의 @Modifying 쿼리(자체 트랜잭션)로 수행 —
 * AOP self-invocation 문제를 피하기 위해 같은 빈 내부 메서드 호출이 아닌 repository 직접 호출.
 *
 * DEIDENTIFY 호출은 BatchOrchestrator 와 동일한 시그니처(raw 객체 직접 전달)로 처리한다 —
 * 별도 wrapper 없이 DeidentifyStep.run(LsDataRaw) 를 그대로 사용해 V2 정책(PRVC/PSDO/ANONY 무조건)
 * 을 위임한다. 외부 비식별 API 실패는 DeidentifyStep 내부에서 DE_IDNTF_YN='F' 마킹 + 원본 보존 +
 * CustomException 전파 처리되므로, AutolabelTestService 는 단계 실행만 위임하고 graceful 분기를
 * 추가하지 않는다 (이중 try-catch 금지 — 작업 가이드 제약).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutolabelTestService {

    /** 영구 SKIP 단계 — 현재 없음. 향후 영구 스킵 단계 추가 시 여기에 등록. */
    private static final List<String> PERMANENT_SKIPPED = List.of();

    /** AutolabelTestRequest 의 stage 키와 동일 (DTO 의존 회피용 로컬 상수). */
    public static final String STAGE_FRAME_EXTRACT = "FRAME_EXTRACT";
    public static final String STAGE_DEIDENTIFY = "DEIDENTIFY";
    public static final String STAGE_YOLO = "YOLO";
    public static final String STAGE_SAM2 = "SAM2";

    private final YoloAutolabelStep yoloStep;
    private final Sam2SegmentStep sam2Step;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final DeidentifyStep deidentifyStep;
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
            List<BbHint> hints = yoloStep.run(rawSn);
            int yoloCount = hints.size();

            statusService.markStage(rawSn, BatchStage.SAM2);
            int sam2Count = sam2Step.run(rawSn, hints);

            long elapsed = System.currentTimeMillis() - started;
            statusService.markCompleted(rawSn);
            videoRepository.updateStatus(rawSn, "COMPLETED");

            log.info("[AutolabelTest] run rawSn={} yolo={} sam2={} elapsed={}ms",
                    rawSn, yoloCount, sam2Count, elapsed);

            // run() 는 DEIDENTIFY 를 실행하지 않으므로 DEIDENTIFY 도 skipped 에 포함.
            List<String> runSkipped = new ArrayList<>(List.of("DEIDENTIFY"));
            runSkipped.addAll(PERMANENT_SKIPPED);
            return new AutolabelRunResponse(rawSn, framesFound, false, yoloCount, sam2Count, elapsed, runSkipped);
        } catch (Exception e) {
            statusService.markFailed(rawSn, e);
            videoRepository.updateStatus(rawSn, "FAILED");
            throw e;
        }
    }

    /**
     * 기본 시그니처 — 4단계 모두 실행 (back-compat).
     */
    public AutolabelRunResponse runFull(Long rawSn) {
        return runFull(rawSn, Collections.emptyMap());
    }

    /**
     * 단계 토글 받는 확장 시그니처.
     * <p>각 토글 키 누락/{@code null} → {@code true} 로 처리한다 (back-compat).
     * <p>단계 간 의존성(SAM2 가 YOLO 결과를 입력으로 받는 등) 위반은 강제로 차단하지 않는다 —
     * 사용자가 YOLO=OFF + SAM2=ON 으로 보내면 SAM2 는 빈 hints 로 호출되어 자연스럽게 0건 결과가 된다.
     *
     * @param rawSn 영상 식별자
     * @param stageToggles 단계별 ON/OFF 맵. null/누락 키는 모두 true 로 간주.
     */
    public AutolabelRunResponse runFull(Long rawSn, Map<String, Boolean> stageToggles) {
        Map<String, Boolean> toggles = stageToggles != null ? stageToggles : Collections.emptyMap();
        boolean runFrameExtract = toggles.getOrDefault(STAGE_FRAME_EXTRACT, true);
        boolean runDeident = toggles.getOrDefault(STAGE_DEIDENTIFY, true);
        boolean runYolo = toggles.getOrDefault(STAGE_YOLO, true);
        boolean runSam2 = toggles.getOrDefault(STAGE_SAM2, true);

        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT,
                        "rawSn=" + rawSn + " 영상 레코드가 없습니다."));

        long framesFound = srcRepository.countByRawSn(rawSn);
        boolean frameExtracted = false;
        List<String> skippedStages = new ArrayList<>();

        if (runFrameExtract) {
            if (framesFound == 0L) {
                // V2.0: 마킹 기반 프레임 추출은 BatchOrchestrator 에서만 수행.
                // 테스트 서비스에서는 이미 추출된 프레임이 있어야 한다.
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "rawSn=" + rawSn + " 추출된 프레임이 없습니다. 배치 파이프라인을 먼저 실행하세요.");
            }
            // 프레임이 이미 존재하면 추출 스킵 (idempotent).
        } else {
            skippedStages.add(BatchStage.FRAME_EXTRACT.name());
        }

        lblRepository.deleteByRawSnAutoLbl(rawSn);

        long started = System.currentTimeMillis();
        int yoloCount = 0;
        int sam2Count = 0;
        try {
            if (runDeident) {
                statusService.markStage(rawSn, BatchStage.DEIDENTIFY);
                // BatchOrchestrator 와 동일한 호출 방식 — DeidentifyStep 내부에서 V2 정책 적용.
                deidentifyStep.run(raw);
            } else {
                skippedStages.add(BatchStage.DEIDENTIFY.name());
            }

            if (runYolo) {
                statusService.markStage(rawSn, BatchStage.YOLO);
                List<BbHint> hints = yoloStep.run(rawSn);
                yoloCount = hints.size();

                if (runSam2) {
                    statusService.markStage(rawSn, BatchStage.SAM2);
                    sam2Count = sam2Step.run(rawSn, hints);
                } else {
                    skippedStages.add(BatchStage.SAM2.name());
                }
            } else {
                skippedStages.add(BatchStage.YOLO.name());
                if (runSam2) {
                    // 단계 의존성 위반 — 빈 hints 로 호출 (자연스러운 0건 결과).
                    statusService.markStage(rawSn, BatchStage.SAM2);
                    sam2Count = sam2Step.run(rawSn, Collections.emptyList());
                } else {
                    skippedStages.add(BatchStage.SAM2.name());
                }
            }

            long elapsed = System.currentTimeMillis() - started;
            statusService.markCompleted(rawSn);
            videoRepository.updateStatus(rawSn, "COMPLETED");

            log.info("[AutolabelTest] runFull rawSn={} frameExtracted={} deident={} yolo={} sam2={} elapsed={}ms",
                    rawSn, frameExtracted, runDeident, yoloCount, sam2Count, elapsed);

            // 영구 SKIP + 이번 실행에서 토글 OFF 된 단계를 합산해 응답.
            List<String> responseSkipped = new ArrayList<>(skippedStages);
            responseSkipped.addAll(PERMANENT_SKIPPED);
            return new AutolabelRunResponse(rawSn, framesFound, frameExtracted, yoloCount, sam2Count, elapsed, responseSkipped);
        } catch (Exception e) {
            statusService.markFailed(rawSn, e);
            videoRepository.updateStatus(rawSn, "FAILED");
            throw e;
        }
    }

    /**
     * 4단계 모두 true 인 기본 토글 맵 생성.
     */
    public static Map<String, Boolean> defaultStageToggles() {
        Map<String, Boolean> m = new HashMap<>();
        m.put(STAGE_FRAME_EXTRACT, true);
        m.put(STAGE_DEIDENTIFY, true);
        m.put(STAGE_YOLO, true);
        m.put(STAGE_SAM2, true);
        return m;
    }
}
