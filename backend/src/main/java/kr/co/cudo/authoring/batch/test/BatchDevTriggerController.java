package kr.co.cudo.authoring.batch.test;

import jakarta.validation.constraints.Max;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    /** 페이지 크기 기본값 — 다른 목록 API 와 동일. */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 페이지 크기 상한 (CWE-770) — 다른 목록 API 와 동일. */
    private static final int MAX_PAGE_SIZE = 100;
    /** PENDING 목록 정렬 — 오래된 순(다음 실행 대상 순). PK 보조 정렬로 페이지 경계를 안정화한다. */
    private static final Sort PENDING_SORT =
            Sort.by(Sort.Order.asc("regDt"), Sort.Order.asc("rawSn"));

    private final BatchOrchestrator orchestrator;
    private final VideoRepository videoRepository;
    private final TrainingVideoIngestService trainingVideoIngestService;

    /**
     * 관제 학습용 자동 적재 픽업을 1회 동기 실행한다 (외부 관제 DB 없이 로컬 검증용).
     *
     * <p>{@link TrainingVideoIngestService#scanAndIngest()} 를 그대로 호출한다 —
     * 관제 인입({@code LS_DATA_INGEST}) 미처리 행을 픽업해 {@code LS_DATA_RAW} 로 적재(PENDING)하고
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

    /**
     * PENDING 상태 rawSn 목록 — 다음 실행 대상 확인용 (페이징).
     *
     * <p>API 설계 규약상 목록은 전량 조회를 두지 않는다. 페이징은 <b>저장소 계층</b>
     * ({@link VideoRepository#findAllByDataSttsCd(String, Pageable)})에서 수행하며, 전량을 읽어
     * 메모리에서 자르지 않는다(CWE-770 — 대상 건수에 비례하는 자원 소모 차단).
     *
     * <p>파라미터는 둘 다 선택이며 기본값은 {@code page=0} / {@code size=}{@value #DEFAULT_PAGE_SIZE},
     * {@code size} 상한은 {@value #MAX_PAGE_SIZE} 다. 위반(음수 page / size 범위 밖)은 선언적 검증
     * ({@code @Min}/{@code @Max})이 {@code ConstraintViolationException} 으로 걸러 400
     * ({@code INVALID_INPUT}) 이 된다 — 다른 목록 API({@code GET /v1/notices})와 동일한 방식.
     *
     * <p>정렬은 {@code REG_DT ASC}(오래된 순, PK 보조 정렬)로 고정한다. 정렬이 없으면 페이지 경계에서
     * 행이 중복·누락될 수 있고, {@code POST /v1/dev/batch/trigger/next} 가 고르는 순서(가장 오래된 1건)와
     * 같은 순서라 "다음 실행 대상 확인" 용도에도 맞는다.
     *
     * @return {@code content}(rawSn 배열) · {@code totalElements} · {@code totalPages} ·
     *         {@code number} · {@code size} 를 담은 페이지 객체
     */
    @GetMapping("/pending")
    public ApiResponse<Page<Long>> listPending(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        Pageable pageable = PageRequest.of(page, size, PENDING_SORT);
        return ApiResponse.ok(videoRepository.findAllByDataSttsCd(STATUS_PENDING, pageable)
                .map(LsDataRaw::getRawSn));
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
