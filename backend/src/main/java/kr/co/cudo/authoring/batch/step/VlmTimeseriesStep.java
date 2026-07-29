package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.config.WebhookCallbackDefaults;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * VLM describe 시계열 메타 위탁 단계 — 벤더 확정 계약(v2.0.1) describe 규격 정합 (Phase 2).
 *
 * <p>{@code POST /v1/videovlm/describe} 로 <b>비식별 영상</b>의 시계열 메타 분석을 외부에 위탁하고,
 * 결과는 {@code POST /v1/vlm/callback} 콜백으로 수신한다.
 *
 * <h3>상관관계 배선 (결함1/2 폐쇄, 핵심)</h3>
 * <p>describe 콜백 바디에는 rawSn 이 없다. 위탁 직전 발급한 {@code request_id} 를
 * {@link WebhookIdempotencyLedger#recordIssued(String, String, String, Long)} 로
 * <b>(request_id → CHANNEL_VLM, rawSn)</b> 매핑으로 등록해, 콜백 수신부가 {@code resolveRawSn} 로
 * 역조회하고 무단 콜백(미발급 request_id)을 401 로 차단하게 한다. 등록을 하지 않으면 모든 콜백이
 * 100% UNAUTHORIZED 로 거부된다(폐쇄 대상 결함1/2).
 *
 * <h3>등록의 원자성</h3>
 * <p>본 Step 은 동기 배치 단계이므로 <b>describe 호출 직전</b> {@code recordIssued} 를 수행한다
 * (외부 호출 <b>전</b> 등록). 증강 경로는 본 원장을 쓰지 않는다 — 발급 원장이
 * {@code LS_DATA_AUG_JOB.IDMP_KEY} 로 분리됐다(청크 단위 키). 영속 ledger 의 {@code recordIssued}
 * 는 {@code REQUIRES_NEW} 로 <b>독립 커밋</b>되므로, 이후 describe 실패나 본 Step 트랜잭션 롤백과
 * 무관하게 매핑이 durable 하게 남아 콜백이 항상 역조회에 성공한다. 등록 실패 시에는 describe 를
 * 호출하지 않고 실패 전파(fail-closed) — 매핑 없는 위탁으로 인한 콜백 유실을 원천 차단한다.
 *
 * <h3>실행 정책</h3>
 * <ul>
 *   <li>{@code vlm.client.enabled=false}(기본) 일 때 외부 호출 0건 + 즉시 SKIPPED(NO-OP) — 등록도 하지 않음.</li>
 *   <li>media.path 는 <b>비식별 영상 경로</b>({@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM})만 사용.
 *       비식별 경로가 없으면 원본을 외부로 전송하지 않고 fail-closed(개인정보 보호).</li>
 *   <li><b>비식별 누락 신고 구간이면 외부 호출 0건 + SKIPPED(보류)</b> — 그 비식별본이 바로 마스킹
 *       실패가 확인된 파일이므로 외부 벤더로 내보내지 않는다({@link DeidentReportGate}). 실패가 아닌
 *       보류로 기록해 해소 후 재처리로 이어진다.</li>
 *   <li>frame_policy 는 frame_interval + framerate(설정값 {@code vlm.client.frame-policy.framerate}, 기본 25).</li>
 *   <li>eventName/marks 는 describe 규격 밖이므로 전송하지 않는다(R9).</li>
 *   <li>외부 호출 실패 시 Resilience4j(VlmClient) Retry 후 {@link CustomException ErrorCode.EXTERNAL_API_ERROR}
 *       로 래핑 → BatchOrchestrator FAILED + BatchRetryQueue 처리.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VlmTimeseriesStep implements BatchStep {

    /** 외부 호출 1건 동기 wait 최대 시간. VlmClient 내부 timeout 보다 약간 길게. */
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(45);

    /** frame_policy framerate 기본값 — 설정 미주입(단위 테스트 등) 시 폴백. */
    private static final int DEFAULT_FRAMERATE = 25;

    /** VLM 단계 미수행 사유 — 운영 재처리 대상 식별용으로 DB 에 그대로 적재된다(B-ISSUE-24). */
    static final String SKIP_REASON_DISABLED = "VLM 위탁 비활성 (vlm.client.enabled=false)";

    /**
     * VLM 단계 <b>보류</b> 사유 — 비식별 누락 신고 구간(재비식별 대기). {@link #SKIP_REASON_DISABLED} 과
     * 동일하게 {@code LS_BATCH_PROC_LOG} 에 적재되어 해소 후 재처리 대상 식별에 쓰인다(B-ISSUE-24).
     *
     * <p><b>재개 배선의 키</b>이므로 public 이다: 신고 해소 시
     * {@code VlmWithheldResumeRunner} 가 이 문자열로 남은 보류 기록을 찾아 위탁을 재개한다
     * ({@code BatchStatusService.isStageSkippedWithReason}). 보류는 실패가 아니라 재시도 큐가 집지 않으므로
     * 이 재개가 유일한 복구 경로다 — <b>값을 바꾸면 재개 배선이 끊긴다</b>(상수를 공유해 드리프트를 막는다).
     */
    public static final String SKIP_REASON_DEIDENT_REPORT = "비식별 누락 신고 구간 — VLM 위탁 보류(재비식별 대기)";

    private final VlmClient vlmClient;
    private final VideoRepository videoRepository;
    private final BatchStatusService batchStatusService;
    private final ObjectMapper objectMapper;
    private final WebhookIdempotencyLedger ledger;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final LsMarkingRepository markingRepository;
    /** 비식별 누락 신고 구간 판정 단일 원천 — {@code 'F'} 비교·트리 순회를 여기서 재구현하지 않는다. */
    private final DeidentReportGate deidentReportGate;

    /** 콜백 base URL — 외부 시스템이 describe 결과를 push 할 엔드포인트 prefix(고정, 사용자 입력 미반영). */
    @Value(WebhookCallbackDefaults.VALUE_EXPRESSION)
    private String callbackBaseUrl;

    /** frame_interval 정책 framerate. */
    @Value("${vlm.client.frame-policy.framerate:25}")
    private int framerate;

    @Override
    public BatchStage stage() {
        return BatchStage.VLM;
    }

    /**
     * 파이프라인 진입점 — 마킹 유무에 따라 {@link #runWithMarking} / {@link #run} 분기.
     */
    @Override
    public void execute(BatchContext ctx) {
        List<LsMarking> markings = ctx.getMarkings();
        if (!markings.isEmpty()) {
            runWithMarking(ctx.getRawSn(), markings.get(0));
        } else {
            run(ctx.getRawSn());
        }
    }

    /**
     * 단일 영상에 대해 시계열 메타 분석을 describe 로 위탁한다(마킹 없음).
     *
     * @param rawSn 영상 식별자
     * @return 외부 시스템 수락 응답(NO-OP 모드면 status=skipped)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public VlmTimeseriesResponse run(Long rawSn) {
        return doSubmit(rawSn, null);
    }

    /**
     * 마킹 상태 전이를 포함하여 describe 위탁을 수행한다.
     *
     * <p>describe 규격상 마킹 데이터(eventName/marks)는 요청 바디에 포함하지 않으나, 위탁 성공 시
     * 마킹 상태를 {@link LsMarking#STATUS_VLM_REQUESTED} 로 전이한다(파이프라인 상태 머신 유지).
     *
     * @param rawSn   영상 식별자
     * @param marking 마킹 엔티티(null 가능 — null 이면 {@link #run} 과 동일)
     * @return 외부 시스템 수락 응답
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public VlmTimeseriesResponse runWithMarking(Long rawSn, LsMarking marking) {
        return doSubmit(rawSn, marking);
    }

    /**
     * describe 위탁 공통 로직 — run/runWithMarking 양쪽에서 호출.
     */
    private VlmTimeseriesResponse doSubmit(Long rawSn, LsMarking marking) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }

        // enabled=false 인 경우 외부 호출/등록/전이 없이 즉시 SKIPPED 반환(NO-OP).
        //  단, B-ISSUE-24 — "건너뛴 사실" 은 DB(LS_BATCH_PROC_LOG)에 사유와 함께 남긴다. 로그만 남기면
        //  VLM 비활성/장애 구간에 처리된 영상이 "메타 없음 + 무기록" 이 되어, 재처리 대상 식별이
        //  애플리케이션 로그 보존기간에 종속된다(운영에서 복구 불가).
        if (!vlmClient.isEnabled()) {
            log.info("[Batch][VlmTimeseries] skipped (disabled) rawSn={}", rawSn);
            batchStatusService.recordVlmSkipped(rawSn, SKIP_REASON_DISABLED);
            return VlmTimeseriesResponse.skipped(null);
        }

        // 영상 존재 확인(NOT_FOUND). path 는 원본이 아닌 비식별 경로에서 도출한다.
        if (!videoRepository.existsById(rawSn)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다 rawSn=" + rawSn);
        }

        // ── S7-VLM (HIGH · CWE-359) — 비식별 누락 신고 게이트: <b>외부 전송 직전</b> 단일 통과 지점.
        //
        //  무엇을 막는가: 아래 resolveDeidentifiedPath 가 넘기는 media.path 는 비식별본이다. 신고는
        //  "그 비식별본에 마스킹 누락(PII)이 있다"는 확인이므로, 신고 구간에 배치가 다시 돌면
        //  (BatchReprocessService.retry · Quartz 재큐 · 마킹 브리지) 마스킹 실패가 확인된 영상 파일이
        //  그대로 외부 VLM 벤더로 나간다. 회수 불가능한 유출이다.
        //
        //  왜 여기인가(스텝 진입 vs 오케스트레이터): ①차단해야 할 것은 "파이프라인"이 아니라 <b>외부
        //  전송</b> 하나다 — 같은 파이프라인의 YOLO/SAM2 는 원본만 쓰고 전송도 내부 ai-server 라 대상이
        //  아니며, 프레임 추출은 로컬 산출이라 게이트는 export 단계가 이미 담당한다. ②run/runWithMarking
        //  은 dev 트리거 등에서 직접 호출될 수 있는 public 진입점이라, 오케스트레이터에 두면 그 경로가
        //  전부 샌다. 전송 코드와 같은 메서드에 두면 어떤 호출자도 우회할 수 없다.
        //
        //  실패가 아니라 <b>보류</b>: 기존 NO-OP(enabled=false) 규약과 동일하게 SKIPPED 응답 + 사유를
        //  LS_BATCH_PROC_LOG 에 적재한다(B-ISSUE-24). 예외로 실패시키면 ①정책적 차단이 장애로 오분류되고
        //  ②BatchRetryQueue 가 반드시 다시 막힐 재시도로 시도 상한을 소진하며 ③작업 상태가 FAILED 로
        //  내려가 라벨링·검수 동선이 끊긴다.
        //
        //  보류는 <b>스스로 재개되지 않는다</b>(실패 행이 없어 재시도 큐·회수기가 집지 않는다). 그래서
        //  해소(resolve) 시 DeidentGateReopenedEvent → VlmResumeBridge → VlmWithheldResumeRunner 가
        //  이 SKIPPED 기록을 근거로 재위탁한다 — 그 배선이 없으면 시계열 메타가 영구 결손된다.
        //
        //  판정 조회가 DB 오류로 실패하면 예외가 그대로 전파돼 위탁이 진행되지 않는다(fail-closed).
        if (deidentReportGate.isUnderDeidentReport(rawSn)) {
            log.warn("[Batch][VlmTimeseries] withheld — deident report open rawSn={}", rawSn);
            batchStatusService.recordVlmSkipped(rawSn, SKIP_REASON_DEIDENT_REPORT);
            return VlmTimeseriesResponse.skipped(null);
        }

        String mediaPath = resolveDeidentifiedPath(rawSn);

        // request_id 발급(UUIDv4 — 예측 불가) + 콜백 URL 구성(고정 base, 사용자 입력 미반영).
        String requestId = UUID.randomUUID().toString();
        String callbackUrl = resolveCallbackUrl();

        // [결함1/2 폐쇄] describe 호출 전에 (request_id → CHANNEL_VLM, rawSn) 매핑을 durable 등록.
        //  - 영속 ledger 는 REQUIRES_NEW 독립 커밋 → describe 실패/본 tx 롤백과 무관하게 콜백이 역조회 성공.
        //  - 등록 실패 시 describe 를 호출하지 않고 실패 전파(fail-closed) — 매핑 없는 위탁 원천 차단.
        try {
            ledger.recordIssued(requestId, LsWebhookIdempotency.CHANNEL_VLM, null, rawSn);
        } catch (RuntimeException e) {
            log.error("[Batch][VlmTimeseries] ledger recordIssued failed (abort submit) rawSn={} err={}",
                    rawSn, VlmClient.safeForLog(e.getMessage()));
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "VLM 위탁 상관키 등록 실패 rawSn=" + rawSn, e);
        }

        VlmTimeseriesRequest req = VlmTimeseriesRequest.ofFrameInterval(
                requestId, mediaPath, resolveFramerate(), callbackUrl);

        log.info("[Batch][VlmTimeseries] describe submit rawSn={} request_id={} hasMarking={}",
                rawSn, VlmClient.safeForLog(requestId), marking != null);
        try {
            VlmTimeseriesResponse resp = vlmClient.submitTimeseries(req).block(BLOCK_TIMEOUT);
            if (resp == null) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "VLM describe 응답이 비어있습니다 rawSn=" + rawSn);
            }
            log.info("[Batch][VlmTimeseries] accepted rawSn={} request_id={} status={}",
                    rawSn, VlmClient.safeForLog(resp.requestId()), VlmClient.safeForLog(resp.status()));
            persistResult(rawSn, resp);

            // 마킹 상태 전이: PENDING → VLM_REQUESTED (DB 영속 보장, DEV_FIX #1/#2)
            //  ctx 의 marking 은 MarkingLoadStep 리포지토리 tx 종료 후 detached 이고, execute() 가
            //  이 메서드를 self-invoke 하여 @Transactional(REQUIRES_NEW) 프록시가 적용되지 않는다.
            //  따라서 detached 필드 변경만으로는 flush 되지 않아 전이가 유실된다(재현 확인).
            //  markingRepository.save(=merge) 로 명시 영속하여, 프록시/ambient tx 유무와 무관하게
            //  전이가 durable 하게 커밋되도록 한다(최소 blast-radius — 다른 Step tx 경계 불변).
            persistMarkingTransition(marking);
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
     * 비식별 영상 경로 도출 — {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM}(최신 성공).
     *
     * <p>원본(비-비식별) 경로는 외부 VLM 으로 절대 전송하지 않는다(개인정보 보호). 비식별 경로가
     * 없으면 fail-closed 로 위탁을 중단한다.
     */
    private String resolveDeidentifiedPath(Long rawSn) {
        String path = deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
        if (path == null) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                    "비식별 영상 경로가 없어 VLM describe 위탁을 진행할 수 없습니다 rawSn=" + rawSn);
        }
        return path;
    }

    /**
     * VLM_REQUESTED 전이를 DB 에 명시 영속한다(merge). detached/무-tx 경로에서도 durable.
     *
     * <p>{@link LsMarking#markVlmRequested()} 는 {@code PENDING} 에서만 전이하고 그 외 상태
     * (이미 VLM_REQUESTED/VLM_COMPLETED/VLM_FAILED)에서는 no-op 이다. retry 재실행으로 이미
     * 전이된 마킹이 들어오면 no-op → <b>save 하지 않아</b> VLM_COMPLETED 를 VLM_REQUESTED 로
     * 덮어쓰는 durable 역행을 차단한다. markingSn 미발급(테스트 등 미영속 마킹)이면 저장을 생략한다.
     */
    private void persistMarkingTransition(LsMarking marking) {
        if (marking == null) {
            return;
        }
        boolean transitioned = marking.markVlmRequested();
        if (transitioned && marking.getMarkingSn() != null) {
            markingRepository.save(marking);
        }
    }

    /** 콜백 URL 구성 — 고정 base + {@link HmacWebhookFilter#PATH_VLM}. 사용자 입력 미반영(SSRF/오픈리다이렉트 차단). */
    private String resolveCallbackUrl() {
        String base = (callbackBaseUrl == null || callbackBaseUrl.isBlank())
                ? WebhookCallbackDefaults.DEFAULT_BASE_URL
                : callbackBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + HmacWebhookFilter.PATH_VLM;
    }

    /** framerate 해석 — 미주입(≤0) 시 기본값. */
    private int resolveFramerate() {
        return framerate > 0 ? framerate : DEFAULT_FRAMERATE;
    }

    /**
     * describe 수락 응답(request_id, status)을 {@code LS_BATCH_PROC_LOG.RESP_PAYLOAD_CN} 에 JSON 적재.
     * 영속화 실패는 위탁 자체를 실패로 보지 않고 WARN 로깅만 남긴다.
     */
    private void persistResult(Long rawSn, VlmTimeseriesResponse resp) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("requestId", resp.requestId());
        payload.put("status", resp.status());
        try {
            String json = objectMapper.writeValueAsString(payload);
            batchStatusService.recordVlmTimeseriesResult(rawSn, json);
        } catch (JsonProcessingException e) {
            log.warn("[Batch][VlmTimeseries] persist res_payload failed rawSn={} err={}",
                    rawSn, VlmClient.safeForLog(e.getMessage()));
        }
    }
}
