package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VLM 시계열 메타 분석 외부 위탁 단계 — Phase 1 신설.
 *
 * <p>ccarch {@code if-vlm-timeseries-spi} 인터페이스 호출을 담당하는 배치 Step.
 * 영상 1건의 시계열 메타 분석을 외부 시스템에 비동기 위탁하고, 외부가 발급한 작업 ID 를
 * {@code LS_BATCH_PROC_LOG.RES_PAYLOAD} 에 JSON 형태로 적재한다.
 * 실제 결과 본문 적재는 Phase 2 결과 수신 webhook 책임이다.
 *
 * <h3>실행 정책</h3>
 * <ul>
 *   <li>{@code vlm.client.enabled=false} (기본) 일 때 외부 호출 0건 + 즉시 SKIPPED 반환 (NO-OP).</li>
 *   <li>외부 호출 실패 시 Resilience4j(VlmClient) 가 Retry 3회 후 예외 전파.</li>
 *   <li>예외 발생 시 {@link CustomException ErrorCode.EXTERNAL_API_ERROR} 로 래핑하여
 *       BatchOrchestrator 의 {@code FAILED} 경로 + {@code BatchRetryQueue} 가 처리.</li>
 *   <li>idempotencyKey 는 {@link VlmClient} 가 단일 발급 — 본 Step 은 null 만 전달.</li>
 * </ul>
 *
 * <h3>이전 VlmMetaStep 와의 관계</h3>
 * <p>본 Step 신설로 기존 {@code VlmMetaStep}(ai-server 직접 호출) 는 호출 경로에서 제거되었다.
 * <b>Phase 4 (2026-05-19)</b> 에서 {@code VlmMetaStep} 클래스 자체가 완전 폐기되어 git 에서 삭제되었다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VlmTimeseriesStep {

    /** 외부 호출 1건 동기 wait 최대 시간. VlmClient 내부 timeout(10s) 보다 약간 길게. */
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(45);

    private final VlmClient vlmClient;
    private final VideoRepository videoRepository;
    private final BatchStatusService batchStatusService;
    private final ObjectMapper objectMapper;

    /**
     * 단일 영상에 대해 시계열 메타 분석을 외부에 위탁한다.
     *
     * @param rawSn 영상 식별자
     * @return 외부 시스템 수락 응답 (NO-OP 모드면 status=SKIPPED, externalJobId=null)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public VlmTimeseriesResponse run(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }

        // enabled=false 인 경우 영상 조회/외부 호출/영속화 모두 생략 — 외부 통신 0건.
        if (!vlmClient.isEnabled()) {
            log.info("[Batch][VlmTimeseries] skipped (disabled) rawSn={}", rawSn);
            return VlmTimeseriesResponse.skipped(null);
        }

        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "영상을 찾을 수 없습니다 rawSn=" + rawSn));

        // DEV_FIX-1: idempotencyKey 발급 책임은 VlmClient 가 단일화. 여기서는 null 전달.
        VlmTimeseriesRequest req = new VlmTimeseriesRequest(
                rawSn, raw.getFilePath(), /* idempotencyKey */ null, /* callbackUrl */ null);

        log.info("[Batch][VlmTimeseries] submit rawSn={}", rawSn);
        try {
            VlmTimeseriesResponse resp = vlmClient.submitTimeseries(req).block(BLOCK_TIMEOUT);
            if (resp == null) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "VLM 위탁 응답이 비어있습니다 rawSn=" + rawSn);
            }
            log.info("[Batch][VlmTimeseries] accepted rawSn={} externalJobId={} status={}",
                    rawSn, VlmClient.safeForLog(resp.externalJobId()),
                    VlmClient.safeForLog(resp.status()));
            persistResult(rawSn, resp);
            return resp;
        } catch (CustomException ce) {
            throw ce;
        } catch (RuntimeException e) {
            log.error("[Batch][VlmTimeseries] failed rawSn={} err={}", rawSn,
                    VlmClient.safeForLog(e.getMessage()));
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "VLM 위탁 호출 실패 rawSn=" + rawSn, e);
        }
    }

    /**
     * 외부 위탁 응답({@code externalJobId}, {@code status}) 을
     * {@code LS_BATCH_PROC_LOG.RES_PAYLOAD} 에 JSON 으로 적재.
     *
     * <p>Phase 2 webhook 이 externalJobId 로 영상을 역추적할 수 있도록 인덱스 역할.
     */
    private void persistResult(Long rawSn, VlmTimeseriesResponse resp) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("externalJobId", resp.externalJobId());
        payload.put("status", resp.status());
        payload.put("idempotencyKey", resp.idempotencyKey());
        try {
            String json = objectMapper.writeValueAsString(payload);
            batchStatusService.recordVlmTimeseriesResult(rawSn, json);
        } catch (JsonProcessingException e) {
            // 영속화 실패는 외부 위탁 자체를 실패로 보지 않고 WARN 로깅만 남긴다.
            log.warn("[Batch][VlmTimeseries] persist res_payload failed rawSn={} err={}",
                    rawSn, VlmClient.safeForLog(e.getMessage()));
        }
    }
}
