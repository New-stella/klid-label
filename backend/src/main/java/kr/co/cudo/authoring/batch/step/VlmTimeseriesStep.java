package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.async.SubmitSignalDispatch;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.config.WebhookCallbackDefaults;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.List;
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
 * </ul>
 *
 * <h3>★ 논블로킹 제출 (Phase C-1) — "외부연동은 모두 비동기" 의 스레드 축</h3>
 * <p>프로토콜은 원래 비동기였으나(ACK 만 받고 결과는 콜백) <b>ACK 왕복 동안 스레드를 점유</b>했다
 * ({@code .block(45s)}). 그 스레드는 {@code batch-async-}(core 2), Quartz 워커(3), 수동 재처리의 Tomcat
 * 요청 스레드였다. 이제 ACK 도 기다리지 않는다:
 * <ol>
 *   <li><b>선커밋</b> — 상관키 등록({@code ledger.recordIssued}) + 마킹 {@code PENDING→VLM_REQUESTED}
 *       ({@link VlmMarkingTxService})를 <b>제출 전에</b> 각각 독립 커밋한다. 콜백이 ACK 보다 먼저
 *       도착해도 역조회·전이가 성립한다(콜백 선행 레이스 폐쇄).</li>
 *   <li><b>제출</b> — {@code subscribe} 만 하고 즉시 반환({@code status="submitted"}).</li>
 *   <li><b>완료 핸들러</b> — 전용 풀({@code vlmSubmitScheduler})에서 {@link VlmSubmitOutcomeRecorder} 가
 *       ACK/실패를 기존 원장·로그에 기록한다. <b>배치·작업 상태는 강등하지 않는다</b> — 지각 실패가
 *       이미 완료된 파이프라인을 FAILED 로 역행시키면 라벨링·검수 동선이 끊긴다.</li>
 *   <li><b>회수</b> — 확정 실패는 {@link #SKIP_REASON_SUBMIT_FAILED}, 무신호(노드 사망 등)는 미결
 *       스위퍼({@code VlmSubmitPendingSweeper})가 {@link #SKIP_REASON_ACK_MISSING} 로 기록하고
 *       {@code VlmWithheldResumeRunner} 가 재개한다(멱등: 시계열 메타 0건일 때만). <b>ACK 는 받았으나
 *       결과 콜백이 오지 않는 건</b>은 같은 스위퍼의 <b>콜백 창</b> 패스가
 *       {@link #SKIP_REASON_CALLBACK_MISSING} 로 회수한다 — ACK 수신이 원장에 {@code ACCEPTED} 로
 *       남으므로 "미수락"과 "결과 대기"를 구분할 수 있다(H1).</li>
 * </ol>
 * <p>동기 실패 전파가 남아 있는 것은 <b>제출 이전</b>의 사전 조건뿐이다(rawSn null · 영상 미존재 ·
 * 비식별 경로 부재 · 상관키 등록 실패) — 이들은 여전히 {@link CustomException} 으로 던져
 * {@code BatchOrchestrator} FAILED + {@code BatchRetryQueue} 경로를 탄다.
 */
@Slf4j
@Component
public class VlmTimeseriesStep implements BatchStep {

    /** frame_policy framerate 기본값 — 설정 미주입(단위 테스트 등) 시 폴백. */
    private static final int DEFAULT_FRAMERATE = 25;

    /** 완료 신호 디스패치 로그 태그(고정 문자열 — 사용자 입력 미반영). */
    private static final String LOG_TAG = "Batch][VlmTimeseries";

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

    /**
     * VLM 단계 미수행 사유 — <b>비동기 제출이 확정 실패</b>(onError 수신)했다 (Phase C-1).
     *
     * <p>논블로킹 전환으로 제출 실패가 파이프라인 스레드 밖에서 발생하게 되면서, 기존 실패 전파 사슬
     * (예외 → {@code BatchOrchestrator} catch → {@code markFailed} + {@code BatchRetryQueue})이 끊겼다.
     * 그 사슬을 되살리지 <b>않는다</b> — 재시도 큐는 rawSn 단위로 파이프라인 전체를 재실행하므로
     * VLM 제출 1건 실패에 프레임추출·YOLO·SAM2 가 전부 다시 돌기 때문이다. 대신 이 사유로 감사 행을
     * 남기고 {@code VlmWithheldResumeRunner} 동형의 재개(멱등 조건: 시계열 메타 0건)로 회수한다.
     *
     * <p><b>재개 배선의 키</b>이므로 public 이며 값을 바꾸면 재개가 끊긴다.
     */
    public static final String SKIP_REASON_SUBMIT_FAILED = "VLM describe 비동기 제출 실패 — 재개 대기";

    /**
     * VLM 단계 미수행 사유 — <b>선기록만 되고 ACK·콜백이 모두 없었다</b>(미결 회수, Phase C-1).
     *
     * <p>노드 사망·재기동으로 in-flight subscription 이 유실되면 어떤 완료 신호도 오지 않는다.
     * {@code VlmSubmitPendingSweeper} 가 원장의 미결(ISSUED) 행을 원자 클레임한 뒤 이 사유로 감사 행을
     * 남기고 재개한다. 이 회수가 없으면 논블로킹 제출은 사실상 fire-and-forget 으로 퇴화한다.
     */
    public static final String SKIP_REASON_ACK_MISSING = "VLM describe 수락 응답·콜백 미수신 — 미결 회수 후 재개";

    /**
     * VLM 단계 미수행 사유 — <b>ACK 는 받았는데 결과 콜백이 끝내 오지 않았다</b>(콜백 창 만료 회수, H1).
     *
     * <p>{@link #SKIP_REASON_ACK_MISSING}(수락조차 못 받음)와 반드시 구분한다 — 이 코드가 남았다는 것은
     * 벤더가 요청을 <b>받아들인 뒤</b> 결과를 주지 않았다는 뜻이라, 외부에는 분석 작업이 실재할 수 있다.
     * ACK 창(수십 초)과 콜백 창(수십 분)은 임계가 자릿수로 다르므로 회수 임계도 분리한다
     * ({@code authoring.batch.vlm.submit-reclaim.callback-timeout-minutes}).
     *
     * <p><b>재개 배선의 키</b>이므로 public 이며 값을 바꾸면 재개가 끊긴다.
     */
    public static final String SKIP_REASON_CALLBACK_MISSING = "VLM describe 결과 콜백 미수신 — 콜백 창 만료 회수 후 재개";

    /** 재개 대상으로 인정하는 VLM 미수행 사유 전체 — 재개 판정의 단일 원천. */
    public static final List<String> RESUMABLE_SKIP_REASONS = List.of(
            SKIP_REASON_DEIDENT_REPORT, SKIP_REASON_SUBMIT_FAILED,
            SKIP_REASON_ACK_MISSING, SKIP_REASON_CALLBACK_MISSING);

    private final VlmClient vlmClient;
    private final VideoRepository videoRepository;
    private final BatchStatusService batchStatusService;
    private final WebhookIdempotencyLedger ledger;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    /** 비식별 누락 신고 구간 판정 단일 원천 — {@code 'F'} 비교·트리 순회를 여기서 재구현하지 않는다. */
    private final DeidentReportGate deidentReportGate;
    /** 마킹 상태 전이 전용 REQUIRES_NEW 빈 — 제출 <b>전</b> 선커밋을 위해 별도 빈으로 분리(자기호출 금지). */
    private final VlmMarkingTxService markingTxService;
    /** 비동기 완료 핸들러 — ACK/실패를 기존 원장·로그에 기록한다(상태 강등 없음). */
    private final VlmSubmitOutcomeRecorder outcomeRecorder;
    /** 완료 신호 전용 스케줄러 — 완료 핸들러의 JPA 쓰기가 이벤트 루프에서 돌지 않게 고정한다. */
    private final Scheduler vlmSubmitScheduler;

    /** 콜백 base URL — 외부 시스템이 describe 결과를 push 할 엔드포인트 prefix(고정, 사용자 입력 미반영). */
    @Value(WebhookCallbackDefaults.VALUE_EXPRESSION)
    private String callbackBaseUrl;

    /** frame_interval 정책 framerate. */
    @Value("${vlm.client.frame-policy.framerate:25}")
    private int framerate;

    /**
     * 명시 생성자 — {@code vlmSubmitScheduler} 를 {@link Qualifier} 로 못박기 위해 Lombok 대신 직접 선언한다
     * (프로젝트에 {@code lombok.config} 가 없어 필드 애노테이션이 생성자로 복사되지 않는다).
     */
    public VlmTimeseriesStep(VlmClient vlmClient,
                             VideoRepository videoRepository,
                             BatchStatusService batchStatusService,
                             WebhookIdempotencyLedger ledger,
                             LsDeidentProcLogRepository deidentProcLogRepository,
                             DeidentReportGate deidentReportGate,
                             VlmMarkingTxService markingTxService,
                             VlmSubmitOutcomeRecorder outcomeRecorder,
                             @Qualifier("vlmSubmitScheduler") Scheduler vlmSubmitScheduler) {
        this.vlmClient = vlmClient;
        this.videoRepository = videoRepository;
        this.batchStatusService = batchStatusService;
        this.ledger = ledger;
        this.deidentProcLogRepository = deidentProcLogRepository;
        this.deidentReportGate = deidentReportGate;
        this.markingTxService = markingTxService;
        this.outcomeRecorder = outcomeRecorder;
        this.vlmSubmitScheduler = vlmSubmitScheduler;
    }

    @Override
    public BatchStage stage() {
        return BatchStage.VLM;
    }

    /**
     * 파이프라인 진입점 — 마킹 유무에 따라 {@link #runWithMarking} / {@link #run} 분기.
     *
     * <p><b>트랜잭션 경계는 여기에 있다</b>(DEV_FIX — self-invocation 트랜잭션 부재). 오케스트레이터가
     * 빈(프록시)의 {@code execute} 를 호출하므로 애노테이션이 발효되고, 아래 두 분기는 자기호출이라
     * 어드바이스가 걸리지 않아 본 트랜잭션에 참여한다(REQUIRES_NEW 중첩 없음 — 스텝 1건 = 트랜잭션 1건).
     * 분기 메서드를 프록시 경유로 바꾸면 중첩되므로 바꾸지 말 것.
     *
     * <p>속성은 <b>쓰기 가능</b>(readOnly 아님) — 두 분기 중 {@link #runWithMarking} 이 마킹 상태를
     * 전이(저장)하므로 상위 경계는 그 상한을 따라야 한다. 마킹 없는 {@link #run} 분기는 스스로
     * {@code readOnly=true} 이지만 그 경로에는 dirty 엔티티가 없고(변경 대상 marking 이 null),
     * 상태 기록·ledger 는 별도 빈의 자체 트랜잭션이라 동작 차이가 없다.
     */
    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
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
     * @return {@code status="submitted"}(제출 개시) / NO-OP·보류 모드면 {@code status="skipped"}.
     *         수락({@code accepted}) 여부는 완료 핸들러가 비동기로 기록하므로 여기서 알 수 없다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public VlmTimeseriesResponse run(Long rawSn) {
        return doSubmit(rawSn, null);
    }

    /**
     * 마킹 상태 전이를 포함하여 describe 위탁을 수행한다.
     *
     * <p>describe 규격상 마킹 데이터(eventName/marks)는 요청 바디에 포함하지 않으나, <b>제출 직전</b>
     * 마킹 상태를 {@link LsMarking#STATUS_VLM_REQUESTED} 로 전이·선커밋한다(파이프라인 상태 머신 유지
     * + 콜백 선행 레이스 폐쇄). 전이는 {@code PENDING} 에서만 발생한다(종결 상태 역행 금지).
     *
     * @param rawSn   영상 식별자
     * @param marking 마킹 엔티티(null 가능 — null 이면 {@link #run} 과 동일)
     * @return {@code status="submitted"} / NO-OP·보류 모드면 {@code status="skipped"}
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

        // ── 선커밋 2/2: 마킹 PENDING → VLM_REQUESTED 를 <b>제출 전에</b> 독립 커밋한다.
        //
        //  왜 제출 앞인가 (콜백 선행 레이스 폐쇄): 제출이 논블로킹이 되면 벤더 콜백이 ACK 보다 먼저
        //  도착할 수 있다(mock/저지연 벤더에서 현실적). 전이를 ACK 이후에 두면 콜백 수신부가 전이 대상을
        //  찾지 못해 0건 전이로 끝나고, 그 뒤 이 코드가 PENDING→VLM_REQUESTED 로 올려 마킹이
        //  <b>VLM_REQUESTED 에 영구 고착</b>된다. 선커밋이 그 창을 닫는다(수신부 관용 확대와 양단 방어).
        //
        //  ctx 의 marking 은 MarkingLoadStep 리포지토리 tx 종료 후 detached 이므로 save(=merge) 로 명시
        //  영속해야 하며(전이 유실 재현 확인), 호출자 tx 와 운명을 분리해야 하므로 REQUIRES_NEW 를 가진
        //  별도 빈(VlmMarkingTxService)을 프록시 경유로 호출한다 — 자기호출이면 경계가 통째로 사라진다.
        markingTxService.persistVlmRequested(marking);
        Long markingSn = marking == null ? null : marking.getMarkingSn();

        VlmTimeseriesRequest req = VlmTimeseriesRequest.ofFrameInterval(
                requestId, mediaPath, resolveFramerate(), callbackUrl);

        log.info("[Batch][VlmTimeseries] describe submit rawSn={} request_id={} hasMarking={}",
                rawSn, VlmClient.safeForLog(requestId), marking != null);

        // ── 논블로킹 제출 (Phase C-1): ACK 왕복조차 스레드를 점유하지 않는다.
        //
        //  구 코드는 .block(45s) 로 파이프라인 스레드(batch-async- / Quartz 워커 / 수동 재처리의 Tomcat
        //  요청 스레드)를 최대 45초 붙잡았다. 외부가 느려지면 core 2 짜리 배치 풀이 통째로 마르고
        //  CallerRuns 역압이 호출 스레드까지 물고 늘어진다.
        //
        //  ★ 완료 신호의 기록은 publishOn 이 아니라 <b>명시적 디스패치</b>로 전용 풀에 넣는다 (M2).
        //   publishOn(전용풀) 은 풀이 포화(AbortPolicy)되면 스케줄 제출이 거부되고, 그 거부가
        //   <b>시그널을 나른 스레드(reactor-netty 이벤트 루프)</b>에서 onError 로 흘러 실패 핸들러의 JPA
        //   쓰기를 이벤트 루프에서 실행시킨다(모든 외부 호출 동반 지연). 아래 try/catch 는 동기 subscribe
        //   구간만 덮으므로 그 거부를 <b>잡지 못한다</b>. 그래서 핸들러 호출 자체를 SubmitSignalDispatch 로
        //   감싸 "전용 풀 안에서만 실행 · 거부되면 기록 포기(회수는 미결 스위퍼)" 로 못 박는다.
        //
        //  빈 응답(onComplete only)은 신호 없는 종료라 어느 핸들러도 타지 않으므로, 구 코드의
        //  "응답이 비어있습니다" 가드를 switchIfEmpty 로 옮겨 실패 경로로 흐르게 유지한다.
        try {
            vlmClient.submitTimeseries(req)
                    // 예외는 지연 생성한다(정상 경로에서 불필요한 스택트레이스 채움 방지).
                    .switchIfEmpty(Mono.error(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                            "VLM describe 응답이 비어있습니다 rawSn=" + rawSn)))
                    .subscribe(
                            resp -> SubmitSignalDispatch.run(vlmSubmitScheduler, LOG_TAG, rawSn,
                                    () -> outcomeRecorder.onAccepted(rawSn, requestId, resp)),
                            err -> SubmitSignalDispatch.run(vlmSubmitScheduler, LOG_TAG, rawSn,
                                    () -> outcomeRecorder.onSubmitFailed(rawSn, markingSn, err)));
        } catch (RuntimeException e) {
            // 조립/구독 자체가 동기 실패한 경우(클라이언트가 즉시 throw 등)에만 도달한다. 이 스레드는
            // 파이프라인 스레드(batch-async-/Quartz/Tomcat)라 JPA 를 직접 호출해도 이벤트 루프를 막지 않는다.
            // 위탁 상관키는 이미 durable 하므로 예외를 위로 던져 파이프라인을 FAILED 로 만들지 않고
            // 확정 실패와 동일하게 기록만 남긴다.
            outcomeRecorder.onSubmitFailed(rawSn, markingSn, e);
        }

        // 스텝이 확정적으로 말할 수 있는 사실은 "제출을 개시했다" 뿐이다. 수락(accepted) 여부는
        // 완료 핸들러가 LS_BATCH_PROC_LOG 에 비동기 기록하고, 아무 신호도 없으면 미결 스위퍼가 회수한다.
        return VlmTimeseriesResponse.submitted(requestId);
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
}
