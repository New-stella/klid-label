package kr.co.cudo.authoring.batch.test;

import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.test.dto.BatchTriggerResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * 배치 파이프라인 수동 트리거 (개발/검수 전용 — 운영(prd) 미노출).
 *
 * <p>관제서버 → Quartz 트리거 경로 없이 REST 호출로
 * {@link BatchOrchestrator#process(Long)} 를 동기 실행한다.
 *
 * <p>보호 장치:
 * <ul>
 *   <li>{@code @Profile("!prd")} — 운영 환경에서는 빈 자체가 등록되지 않음.</li>
 *   <li>{@code SecurityConfig} 의 dev endpoint allowlist 도 prd 에서는 비활성.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/v1/dev/batch")
@RequiredArgsConstructor
@Validated
@Profile("!prd")
public class BatchDevTriggerController {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_PENDING = "PENDING";

    private final BatchOrchestrator orchestrator;
    private final VideoRepository videoRepository;
    private final TrainingVideoIngestService trainingVideoIngestService;

    /**
     * 관제 학습용 자동 적재 픽업을 1회 동기 실행한다 (외부 관제 DB 없이 로컬 검증용).
     *
     * <p>{@link TrainingVideoIngestService#scanAndIngest()} 를 그대로 호출한다 —
     * {@code MNG_CLIP_MASTER.JOB_DMND_YN='Y'} 클립을 픽업해 {@code LS_DATA_RAW} 로 적재(PENDING)하고
     * {@code VideoIngestedEvent} 를 발행하는 주기 배치({@code ControlTrainingVideoScanJob}) 와 동일한
     * 경로를 REST 트리거로 노출한다. 픽업 대상이 없으면 0 건으로 200 응답한다(멱등).
     *
     * @return 이번 스캔에서 신규 적재된 건수
     */
    @PostMapping("/scan")
    public ApiResponse<Integer> scan() {
        int ingested = trainingVideoIngestService.scanAndIngest();
        log.info("[BatchDevTrigger] scan triggered ingested={}", ingested);
        return ApiResponse.ok(ingested);
    }

    /**
     * 지정 rawSn 으로 전체 파이프라인을 동기 실행한다.
     * - rawSn 미존재: 404 (NOT_FOUND)
     * - 이미 PROCESSING: 409 (CONFLICT, 중복 실행 방지)
     */
    @PostMapping("/trigger")
    public ResponseEntity<ApiResponse<BatchTriggerResponse>> trigger(
            @RequestParam @Min(1) Long rawSn) {
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "rawSn=" + rawSn + " 영상이 존재하지 않습니다."));

        if (STATUS_PROCESSING.equals(raw.getDataSttsCd())) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "rawSn=" + rawSn + " 은 이미 PROCESSING 상태입니다.");
        }

        BatchTriggerResponse body = runOrchestrator(rawSn);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /**
     * DATA_STTS_CD='PENDING' 중 REG_DT 가 가장 오래된 1건을 자동 선택해 실행한다.
     * 대상이 없으면 204 No Content.
     */
    @PostMapping("/trigger/next")
    public ResponseEntity<ApiResponse<BatchTriggerResponse>> triggerNext() {
        Optional<LsDataRaw> next = videoRepository
                .findFirstByDataSttsCdOrderByRegDtAsc(STATUS_PENDING);
        if (next.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        BatchTriggerResponse body = runOrchestrator(next.get().getRawSn());
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /** PENDING 상태 rawSn 목록 — 다음 실행 대상 확인용. */
    @GetMapping("/pending")
    public ApiResponse<List<Long>> listPending() {
        List<Long> ids = videoRepository.findAllByDataSttsCd(STATUS_PENDING).stream()
                .map(LsDataRaw::getRawSn)
                .toList();
        return ApiResponse.ok(ids);
    }

    private BatchTriggerResponse runOrchestrator(Long rawSn) {
        long started = System.currentTimeMillis();
        try {
            BatchStage finalStage = orchestrator.process(rawSn);
            long elapsed = System.currentTimeMillis() - started;
            boolean success = finalStage == BatchStage.COMPLETED;
            log.info("[BatchDevTrigger] triggered rawSn={} finalStage={} elapsed={}ms",
                    rawSn, finalStage, elapsed);
            return new BatchTriggerResponse(
                    rawSn,
                    finalStage != null ? finalStage.name() : "UNKNOWN",
                    success,
                    elapsed,
                    success ? null : "파이프라인이 COMPLETED 로 종료되지 않았습니다."
            );
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - started;
            log.warn("[BatchDevTrigger] failed rawSn={} elapsed={}ms cause={}",
                    rawSn, elapsed, e.getClass().getSimpleName());
            return new BatchTriggerResponse(rawSn, "FAILED", false, elapsed, e.getMessage());
        }
    }
}
