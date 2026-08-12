package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.batch.vlm.VlmTimeseriesMetaPresence;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesRequest;
import kr.co.cudo.authoring.common.async.SubmitSignalDispatch;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.common.config.WebhookCallbackDefaults;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.HmacWebhookFilter;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
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
import java.util.Objects;
import java.util.UUID;

/**
 * VLM <b>verify</b> 시계열 메타 위탁 단계 — 벤더 확정 계약(v2.0.1) verify 규격 정합.
 *
 * <p>{@code POST /v1/videovlm/verify} 로 <b>비식별 영상</b>의 시계열 메타 분석을 외부에 위탁하고,
 * 결과는 {@code POST /v1/vlm/callback} 콜백으로 수신한다 (@req R1).
 *
 * <h3>요청 구성의 두 축 (구 describe 대비 신규)</h3>
 * <ul>
 *   <li><b>{@code event_type}</b> (@req R6) — 분석 대상 이벤트 유형. 조달처는 <b>관제 인입값</b>
 *       {@code LS_DATA_INGEST.VRFC_EVNT_TYPE_CD} 이며, 허용목록은
 *       {@link kr.co.cudo.authoring.video.entity.LsDataIngest#VRFC_EVNT_TYPES} 6종이다. 관제 코드
 *       체계를 벤더 enum 으로 번역하는 <b>자체 매핑표를 만들지 않는다</b>(그 표가 조용히 낡으면 잘못
 *       번역된 값으로 외부 위탁이 나간다). 값이 없거나 허용목록 밖이면 <b>위탁하지 않고 보류</b>한다 —
 *       관제가 우리 코드를 거치지 않고 DB 에 직접 INSERT 하므로 <b>이 스텝이 유일한 검증 관문</b>이다.</li>
 *   <li><b>{@code frame_policy}</b> (@req R2) — 마킹에서 도출한다. 수동 마킹이면
 *       {@code frame_selected}(마킹 프레임 인덱스, 정렬·중복제거·상한 8), 그 외에는
 *       {@code frame_interval}(framerate = 마킹 프레임 간격 {@code FRME_INTV_NOCS}).</li>
 * </ul>
 *
 * <h3>상관관계 배선 (결함1/2 폐쇄, 핵심)</h3>
 * <p>콜백 바디에는 rawSn 이 없다. 위탁 직전 발급한 {@code request_id} 를
 * {@link WebhookIdempotencyLedger#recordIssued(String, String, String, Long)} 로
 * <b>(request_id → CHANNEL_VLM, rawSn)</b> 매핑으로 등록해, 콜백 수신부가 {@code resolveRawSn} 로
 * 역조회하고 무단 콜백(미발급 request_id)을 401 로 차단하게 한다. 등록을 하지 않으면 모든 콜백이
 * 100% UNAUTHORIZED 로 거부된다(폐쇄 대상 결함1/2).
 *
 * <h3>등록의 원자성</h3>
 * <p>본 Step 은 동기 배치 단계이므로 <b>위탁 호출 직전</b> {@code recordIssued} 를 수행한다
 * (외부 호출 <b>전</b> 등록). 증강 경로는 본 원장을 쓰지 않는다 — 발급 원장이
 * {@code LS_DATA_AUG_JOB.IDMP_KEY} 로 분리됐다(청크 단위 키). 영속 ledger 의 {@code recordIssued}
 * 는 {@code REQUIRES_NEW} 로 <b>독립 커밋</b>되므로, 이후 위탁 실패나 본 Step 트랜잭션 롤백과
 * 무관하게 매핑이 durable 하게 남아 콜백이 항상 역조회에 성공한다. 등록 실패 시에는 외부 호출을
 * 하지 않고 실패 전파(fail-closed) — 매핑 없는 위탁으로 인한 콜백 유실을 원천 차단한다.
 *
 * <p><b>모든 게이트(토글·영상 존재·신고 보류·event_type)는 선커밋보다 앞에 둔다.</b> 뒤에 두면
 * 위탁이 나가지도 않았는데 상관키 {@code ISSUED} + 마킹 {@code VLM_REQUESTED} 만 durable 커밋되어
 * <b>사유 없는 고착</b>이 되고, 미결 스위퍼가 그것을 "ACK 미수신" 으로 오인해 회수를 반복한다.
 *
 * <h3>실행 정책</h3>
 * <ul>
 *   <li>{@code vlm.client.enabled=false}(기본) 일 때 외부 호출 0건 + 즉시 SKIPPED(NO-OP) — 등록도 하지 않음.</li>
 *   <li>media.path 는 <b>비식별 영상 경로</b>({@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM})만 사용.
 *       비식별 경로가 없으면 원본을 외부로 전송하지 않고 fail-closed(개인정보 보호).</li>
 *   <li><b>비식별 누락 신고 구간이면 외부 호출 0건 + SKIPPED(보류)</b> — 그 비식별본이 바로 마스킹
 *       실패가 확인된 파일이므로 외부 벤더로 내보내지 않는다({@link DeidentReportGate}). 실패가 아닌
 *       보류로 기록해 해소 후 재처리로 이어진다.</li>
 *   <li><b>event_type 은 위탁을 막지 않는다</b>(2026-08-06 정책 반전) — 미조달·미허용이어도 조달값을
 *       그대로 실어 <b>항상 위탁</b>하고 수용 여부는 벤더 응답이 정한다. 값을 유추해 채우지는 않는다
 *       (없으면 {@code null} 전송 → 벤더 422 → 확정 실패로 기록). 구 동작(사전 차단 + SKIPPED)은
 *       {@link #resolveEventType} 주석 참조.</li>
 *   <li>frame_policy 는 마킹에서 도출하며, 마킹이 없으면 frame_interval + 설정값
 *       ({@code vlm.client.frame-policy.framerate}, 기본 25).</li>
 *   <li>eventName/marks 원문은 벤더 규격 밖이므로 전송하지 않는다 — 마킹은 frame_policy 로만 반영된다.</li>
 *   <li><b>재실행 멱등 (@req R1)</b> — ①시계열 메타가 이미 있거나 ②원장이 미결({@code ISSUED}/{@code ACCEPTED})
 *       이면 <b>외부 호출 0건</b>으로 통과한다. 자동 재시도 큐가 파이프라인을 선두부터 다시 돌리므로 이 판정이
 *       없으면 같은 비식별 영상이 매 재시도마다 중복 위탁된다. 이 통과는 <b>SKIPPED 로 기록하지 않는다</b> —
 *       그 축은 재개가 필요한 보류의 축이라 섞으면 재개 러너가 이미 끝난 영상을 재위탁 후보로 집는다.</li>
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

    /**
     * {@code selected_frames} 상한 — 벤더 규격 §3.2 (Video VLM 1회 추론 프레임 상한).
     *
     * <p>초과분을 그대로 보내면 422(비재시도 영구 실패)다. 자르기 <b>전에</b> 프레임 인덱스 오름차순
     * 정렬을 강제한다 — {@code MARK_CN} JSON 배열의 저장 순서가 시간순이라는 보장이 없어, 정렬 없이
     * 자르면 임의의 8개가 나간다.
     */
    private static final int MAX_SELECTED_FRAMES = 8;

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
     * VLM 단계 <b>보류</b> 사유 — 관제가 검증이벤트유형을 보내지 않았다 (@req R6).
     *
     * <p>⚠ <b>2026-08-06 정책 반전으로 신규 발생이 없다</b> — event_type 은 더 이상 위탁을 막지 않고
     * 조달값 없이도 그대로 위탁한다({@link #resolveEventType}). <b>이미 적재된 과거
     * {@code LS_BATCH_PROC_LOG} 행의 판독·재개 배선을 위해 상수는 존치</b>한다(값을 바꾸면 과거
     * 보류분의 재개가 끊긴다). 삭제하지 말 것.
     */
    public static final String SKIP_REASON_EVENT_TYPE_MISSING =
            "검증이벤트유형 미수신 — VLM 위탁 보류(관제 인입값 대기)";

    /**
     * VLM 단계 <b>보류</b> 사유 — 검증이벤트유형이 허용목록 밖이다 (@req R6).
     *
     * <p>⚠ {@link #SKIP_REASON_EVENT_TYPE_MISSING} 과 같은 이유로 <b>신규 발생이 없으며 과거 행 판독용
     * 으로 존치</b>한다. 미지의 값은 이제 차단되지 않고 그대로 위탁되며, 벤더가 거부하면
     * {@link #SKIP_REASON_SUBMIT_FAILED} 로 기록된다.
     */
    public static final String SKIP_REASON_EVENT_TYPE_UNSUPPORTED =
            "검증이벤트유형 미지원 값 — VLM 위탁 보류(허용목록 밖)";

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

    /**
     * 재개 대상으로 인정하는 VLM 미수행 사유 전체 — 재개 판정의 단일 원천.
     *
     * <p>⚠ 위 상수들의 <b>문자열 값</b>은 절대 바꾸지 않는다 — 그 값이 곧 재개 배선의 키이며
     * ({@code LS_BATCH_PROC_LOG.ERR_MSG_CN} 정확 일치 조회), 이미 적재된 과거 행과도 대조된다.
     * 규격이 describe → verify 로 바뀌었어도 <b>기존 사유 문자열은 그대로 둔다</b>(주석만 갱신).
     */
    public static final List<String> RESUMABLE_SKIP_REASONS = List.of(
            SKIP_REASON_DEIDENT_REPORT, SKIP_REASON_SUBMIT_FAILED,
            SKIP_REASON_ACK_MISSING, SKIP_REASON_CALLBACK_MISSING,
            SKIP_REASON_EVENT_TYPE_MISSING, SKIP_REASON_EVENT_TYPE_UNSUPPORTED);

    private final VlmClient vlmClient;
    private final VideoRepository videoRepository;
    /** 관제 인입 평면값 조회 — {@code event_type} 의 유일한 조달처(@req R6). */
    private final IngestSourceRepository ingestSourceRepository;
    private final BatchStatusService batchStatusService;
    private final WebhookIdempotencyLedger ledger;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    /** 비식별 누락 신고 구간 판정 단일 원천 — {@code 'F'} 비교·트리 순회를 여기서 재구현하지 않는다. */
    private final DeidentReportGate deidentReportGate;
    /** 마킹 상태 전이 전용 REQUIRES_NEW 빈 — 제출 <b>전</b> 선커밋을 위해 별도 빈으로 분리(자기호출 금지). */
    private final VlmMarkingTxService markingTxService;
    /**
     * 재실행 멱등 판정 1/2 — "시계열 메타가 이미 있는가" (@req R1). {@code VlmWithheldResumeRunner} 의
     * 재개 멱등 조건과 <b>같은 판정</b>을 공유한다(복제 금지).
     */
    private final VlmTimeseriesMetaPresence timeseriesMetaPresence;
    /** 비동기 완료 핸들러 — ACK/실패를 기존 원장·로그에 기록한다(상태 강등 없음). */
    private final VlmSubmitOutcomeRecorder outcomeRecorder;
    /** 마킹 본문({@code MARK_CN}) 파서 — frame_policy 도출용(@req R2). */
    private final ObjectMapper objectMapper;
    /** 완료 신호 전용 스케줄러 — 완료 핸들러의 JPA 쓰기가 이벤트 루프에서 돌지 않게 고정한다. */
    private final Scheduler vlmSubmitScheduler;

    /** 콜백 base URL — 외부 시스템이 verify 결과를 push 할 엔드포인트 prefix(고정, 사용자 입력 미반영). */
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
                             IngestSourceRepository ingestSourceRepository,
                             BatchStatusService batchStatusService,
                             WebhookIdempotencyLedger ledger,
                             LsDeidentProcLogRepository deidentProcLogRepository,
                             DeidentReportGate deidentReportGate,
                             VlmMarkingTxService markingTxService,
                             VlmSubmitOutcomeRecorder outcomeRecorder,
                             VlmTimeseriesMetaPresence timeseriesMetaPresence,
                             ObjectMapper objectMapper,
                             @Qualifier("vlmSubmitScheduler") Scheduler vlmSubmitScheduler) {
        this.vlmClient = vlmClient;
        this.videoRepository = videoRepository;
        this.ingestSourceRepository = ingestSourceRepository;
        this.batchStatusService = batchStatusService;
        this.ledger = ledger;
        this.deidentProcLogRepository = deidentProcLogRepository;
        this.deidentReportGate = deidentReportGate;
        this.markingTxService = markingTxService;
        this.outcomeRecorder = outcomeRecorder;
        this.timeseriesMetaPresence = timeseriesMetaPresence;
        this.objectMapper = objectMapper;
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
     * 단일 영상에 대해 시계열 메타 분석을 verify 로 위탁한다(마킹 없음).
     *
     * <p>마킹이 없으므로 frame_policy 는 {@code frame_interval} + 설정 framerate 다.
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
     * 마킹 상태 전이를 포함하여 verify 위탁을 수행한다.
     *
     * <p>마킹 원문(eventName/marks)은 규격 밖이라 바디에 싣지 않고 {@code frame_policy} 로만 반영하며
     * (@req R2), <b>제출 직전</b>
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
     * verify 위탁 공통 로직 — run/runWithMarking 양쪽에서 호출.
     */
    private VlmTimeseriesResponse doSubmit(Long rawSn, LsMarking marking) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }

        // ── [@design API-198] REVIEWER 수동 스킵 게이트 — <b>오케스트레이터 루프와 별개로</b> 여기에도 둔다.
        //
        //  왜 두 곳인가: run/runWithMarking 은 VlmWithheldResumeRunner 가 <b>직접</b> 부르는 public
        //  진입점이다. 그 러너는 과거의 재개 가능 SKIPPED 행(신고 보류 등)을 보고 재위탁하는데, 그 뒤에
        //  REVIEWER 가 시계열 묶음을 수동 스킵했다면 오케스트레이터를 거치지 않는 그 경로가 <b>사람의 결정을
        //  뒤집고 외부 벤더로 영상을 내보낸다</b>. 전송 코드와 같은 메서드에 두면 어떤 호출자도 우회할 수 없다
        //  (비식별 신고 게이트를 이 메서드에 둔 것과 동일한 논리).
        //
        //  판정 규칙은 재유도하지 않고 BatchStatusService.isStageManuallySkipped 단일 지점에 위임한다
        //  (그 메서드가 VLM 단계 → 시계열 묶음 해석까지 담당한다 — 여기서 묶음을 직접 알 필요가 없다).
        //  기록을 남기지 않는 이유: 표식 행이 이미 사유·행위자를 갖고 있고, 재기동마다 행을 덧붙이면
        //  감사 테이블이 무한히 커진다(CWE-770).
        if (batchStatusService.isStageManuallySkipped(rawSn, BatchStage.VLM)) {
            log.info("[Batch][VlmTimeseries] skipped (manual skip) rawSn={}", rawSn);
            return VlmTimeseriesResponse.skipped(null);
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

        // ── 재실행 멱등 게이트 (@req R1) — <b>이미 결과가 있거나 위탁이 미결이면 다시 위탁하지 않는다</b>.
        //
        //  왜 필요한가: 자동 재시도 큐(BatchRetryQuartzJob → BatchOrchestrator.process)는 사람의 조작 없이
        //  파이프라인을 선두부터 전부 다시 돈다. 이 게이트가 없으면 재시도마다 새 request_id 를 발급해
        //  <b>같은 비식별 영상을 외부 벤더로 중복 위탁</b>한다(외부 비용·레이트리밋 + 같은 영상에 상관키가
        //  둘 이상 생겨 어느 콜백이 정본인지 모호해진다).
        //
        //  판정 1 — 시계열 메타가 이미 있으면 위탁이 불필요하다. VlmWithheldResumeRunner 의 재개 멱등 조건과
        //    <b>같은 판정</b>을 공유한다(VlmTimeseriesMetaPresence — 복제하면 한쪽만 갱신돼 어긋난다).
        //  판정 2 — 원장이 미결(ISSUED/ACCEPTED)이면 콜백 대기 중이므로 여기서 재위탁하지 않는다.
        //    미결의 회수는 미결 스위퍼(VlmSubmitPendingSweeper)의 책임이고, 그것이 회수(FAILED 표식)한 뒤에는
        //    이 판정이 열려 재개가 정상 동작한다.
        //
        //  ★ 기록은 SKIPPED 로 남기지 않는다 — 그 축은 "재개가 필요한 보류"의 축이며(RESUMABLE_SKIP_REASONS)
        //    여기에 새 사유를 섞으면 재개 러너가 <b>이미 결과가 있는 영상</b>을 재위탁 후보로 집게 된다.
        //    멱등 no-op 은 실질 작업만 없는 정상 통과이므로 진행 행은 오케스트레이터의 stage 기록에 맡기고
        //    이번 회차에 실질 작업이 없었다는 사실은 INFO 로그로만 남긴다.
        //
        //  ★ 신고 게이트보다 <b>뒤</b>에 둔다 — 앞에 두면 신고 구간에 이 게이트가 먼저 반환해 신고 보류 사유가
        //    기록되지 않고, 해소 시 재개 트리거가 그 영상을 찾지 못한다(event_type 게이트와 같은 이유).
        long existingTimeseriesMeta = timeseriesMetaPresence.count(rawSn);
        if (existingTimeseriesMeta > 0) {
            log.info("[Batch][VlmTimeseries] idempotent skip — timeseries meta already present rawSn={} count={}",
                    rawSn, existingTimeseriesMeta);
            return VlmTimeseriesResponse.skipped(null);
        }
        if (ledger.hasOutstandingSubmit(LsWebhookIdempotency.CHANNEL_VLM, rawSn)) {
            log.info("[Batch][VlmTimeseries] idempotent skip — submit already outstanding (awaiting callback) rawSn={}",
                    rawSn);
            return VlmTimeseriesResponse.skipped(null);
        }

        // ── event_type 게이트 (@req R6 · CWE-20) — 선커밋보다 <b>앞</b>, 신고 게이트보다 <b>뒤</b>.
        //
        //  왜 신고 게이트 뒤인가: 순서를 뒤집으면 신고를 해소해도 "유형 미지원" 사유가 먼저 기록돼
        //  신고 보류 사유가 남지 않고, 해소 시 재개 트리거(DeidentGateReopenedEvent → 보류 사유 조회)가
        //  그 영상을 찾지 못해 재개 경로가 통째로 죽는다.
        //
        //  왜 선커밋 앞인가: 위탁이 나가지도 않았는데 상관키 ISSUED + 마킹 VLM_REQUESTED 만 durable
        //  커밋되면 사유 없는 고착이 되고, 미결 스위퍼가 "ACK 미수신" 으로 오인해 회수를 반복한다.
        //
        //  차단은 실패가 아니라 <b>보류</b>다(기존 NO-OP 규약과 동일) — 예외로 실패시키면 정책적 차단이
        //  장애로 오분류되고 재시도 큐가 반드시 다시 막힐 재시도로 상한을 소진한다.
        // ★ event_type 은 더 이상 위탁을 막지 않는다 (2026-08-06 사용자 확정) — 조달값을 그대로 실어
        //  보내고 수용 여부는 <b>벤더 응답</b>이 정한다. 아래 resolveEventType 은 조달·정규화만 한다.
        String eventType = resolveEventType(rawSn);

        String mediaPath = resolveDeidentifiedPath(rawSn);

        // request_id 발급(UUIDv4 — 예측 불가) + 콜백 URL 구성(고정 base, 사용자 입력 미반영).
        String requestId = UUID.randomUUID().toString();
        String callbackUrl = resolveCallbackUrl();

        // ── 요청 조립을 <b>선커밋 이전</b>에 끝낸다 (@req R2).
        //  frame_policy 도출은 MARK_CN JSON 파싱을 포함한다. 그 파싱이 선커밋 <b>뒤</b>에서 터지면
        //  파이프라인은 FAILED + 전량 재실행이 되고, 재개 경로(@Async)는 예외가 삼켜져 상관키만 남은
        //  영구 대기가 된다. 파싱 실패는 예외가 아니라 frame_interval 폴백으로 처리하지만(fail-secure),
        //  조립 자체를 앞에 두어 "선커밋 후 실패" 창을 구조적으로 없앤다.
        VlmTimeseriesRequest req = buildRequest(rawSn, marking, requestId, eventType,
                mediaPath, callbackUrl);

        // [결함1/2 폐쇄] 외부 호출 전에 (request_id → CHANNEL_VLM, rawSn) 매핑을 durable 등록.
        //  - 영속 ledger 는 REQUIRES_NEW 독립 커밋 → 위탁 실패/본 tx 롤백과 무관하게 콜백이 역조회 성공.
        //  - 등록 실패 시 외부 호출을 하지 않고 실패 전파(fail-closed) — 매핑 없는 위탁 원천 차단.
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

        // 로그에 싣는 외부/DB 유래 문자열은 sanitize 한다(CWE-117). event_type 은 허용목록 통과값이라
        // 이미 안전하지만, 판정 지점과 로그 지점이 분리되면 드리프트가 나므로 동일하게 통과시킨다.
        log.info("[Batch][VlmTimeseries] verify submit rawSn={} request_id={} event_type={} mode={} hasMarking={}",
                rawSn, VlmClient.safeForLog(requestId), VlmClient.safeForLog(eventType),
                VlmClient.safeForLog(req.media().framePolicy().mode()), marking != null);

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
                            "VLM verify 응답이 비어있습니다 rawSn=" + rawSn)))
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
     * 검증이벤트유형 조달·정규화 — <b>판정하지 않는다</b> (@req R6, 2026-08-06 정책 반전).
     *
     * <p>조달처는 관제 인입값 {@code LS_DATA_INGEST.VRFC_EVNT_TYPE_CD} 하나이며, 정규화는
     * {@link LsDataIngest#normalizeVrfcEvntType(String)} 을 <b>재사용</b>한다(리터럴을 복제해 새 상수를
     * 만들면 한쪽만 갱신돼 조용히 어긋난다).
     *
     * <h3>★ 구 동작(허용목록 사전 차단) 폐기 — 되돌리지 말 것</h3>
     * <p>구 동작은 값이 없거나 {@link LsDataIngest#VRFC_EVNT_TYPES} 6종이 아니면 <b>외부 호출 0건 +
     * SKIPPED(보류)</b> 였다. 사용자 확정으로 <b>항상 위탁하고 수용 여부는 벤더 응답이 정한다</b>로
     * 반전했다. 근거: 허용목록이 우리 쪽 사본이라 벤더가 enum 을 넓히면 <b>정상 값을 우리가 먼저
     * 막는다</b>. 이제 판정의 단일 진실원은 벤더 응답이며, 거부는 {@code onSubmitFailed} 가
     * {@link #SKIP_REASON_SUBMIT_FAILED} 로 기록한다.
     *
     * <p>⚠ <b>값을 지어내지 않는다</b> — 조달값이 없으면 {@code null} 을 그대로 실어 보내고(벤더 필수
     * 필드라 422 가 예상된다) 그 거부를 기록한다. 관제 이벤트 코드에서 유추해 채우지 않는다(그 유추표가
     * 곧 두 번째 진실원이 된다).
     *
     * @return 정규화된 event_type, 또는 조달값이 없으면 {@code null}(그대로 전송)
     */
    private String resolveEventType(Long rawSn) {
        // 영상 행이 없으면 null 행이 온다(인입 행만 없으면 전 필드 null 인 행).
        IngestSourceRow source = ingestSourceRepository.findSourceMeta(rawSn);
        String raw = source == null ? null : source.getVrfcEvntTypeCd();
        String normalized = LsDataIngest.normalizeVrfcEvntType(raw);

        if (normalized == null) {
            // 차단이 아니라 관측이다 — 위탁은 그대로 나가고 벤더 응답이 수용 여부를 정한다.
            log.warn("[Batch][VlmTimeseries] verification event type missing — submitting anyway rawSn={}", rawSn);
        } else if (!LsDataIngest.VRFC_EVNT_TYPES.contains(normalized)) {
            // 우리가 아는 6종 밖이어도 보낸다. 값은 우리 코드를 거치지 않고 DB 에 직접 INSERT 된
            // 외부 입력이므로 로그에는 반드시 sanitize 해서 남긴다(CWE-117).
            log.warn("[Batch][VlmTimeseries] verification event type outside known set — submitting anyway "
                    + "rawSn={} value={}", rawSn, VlmClient.safeForLog(normalized));
        }
        return normalized;
    }

    /**
     * verify 요청 조립 — frame_policy 를 <b>마킹 엔티티에서</b> 도출한다 (@req R2).
     *
     * <h3>★ 도출 입력은 {@code ctx.getMarks()} 가 아니라 {@code marking.getMarkCn()} 이다</h3>
     * <p>{@code VlmWithheldResumeRunner#resumeAsync} 와 {@code VlmSubmitPendingSweeper} 는
     * {@code BatchContext} 없이 마킹 엔티티만 들고 {@link #runWithMarking} 을 직접 호출한다. 컨텍스트에
     * 의존해 도출하면 <b>재개 경로에서 항상 빈 목록</b>이 되어 수동 마킹이 조용히 {@code frame_interval}
     * 로 강등된다(무증상 품질 저하). 그래서 파이프라인·재개가 <b>같은 입력</b>을 보게 한다.
     *
     * <h3>모드별 규칙</h3>
     * <ul>
     *   <li>{@code MANUAL} — {@code frame_selected} + 마킹 프레임 인덱스. 유효 프레임이 하나도 없으면
     *       {@code frame_interval} 로 폴백한다(빈 {@code selected_frames} 는 규격 위반).</li>
     *   <li>{@code AUTO}(및 미지의 모드) — {@code frame_interval}. framerate 는 마킹 프레임 간격
     *       {@code FRME_INTV_NOCS} 이며, 값이 없거나 {@code <= 0} 이면 설정 기본값으로 폴백한다
     *       (0 을 그대로 보내면 벤더 422 = 비재시도 영구 실패).</li>
     * </ul>
     */
    private VlmTimeseriesRequest buildRequest(Long rawSn, LsMarking marking, String requestId,
                                              String eventType, String mediaPath, String callbackUrl) {
        if (marking != null && LsMarking.MODE_MANUAL.equals(marking.getMarkModeCd())) {
            List<Integer> selected = resolveSelectedFrames(rawSn, marking.getMarkCn());
            if (!selected.isEmpty()) {
                return VlmTimeseriesRequest.ofFrameSelected(
                        requestId, eventType, mediaPath, resolveFramerate(), selected, callbackUrl);
            }
            log.warn("[Batch][VlmTimeseries] manual marking has no usable frame — fallback to frame_interval rawSn={}",
                    rawSn);
            return VlmTimeseriesRequest.ofFrameInterval(
                    requestId, eventType, mediaPath, resolveFramerate(), callbackUrl);
        }
        if (marking != null && !LsMarking.MODE_AUTO.equals(marking.getMarkModeCd())) {
            // 미지의 모드를 조용히 흡수하면 추적이 불가능하다 — 폴백하되 반드시 남긴다.
            log.warn("[Batch][VlmTimeseries] unknown mark mode — fallback to frame_interval rawSn={} mode={}",
                    rawSn, VlmClient.safeForLog(marking.getMarkModeCd()));
        }
        return VlmTimeseriesRequest.ofFrameInterval(
                requestId, eventType, mediaPath, resolveIntervalFramerate(marking), callbackUrl);
    }

    /**
     * 수동 마킹의 {@code selected_frames} 도출 — 정렬 · 중복제거 · 상한 8 (@req R2, CWE-20).
     *
     * <p>파싱 실패는 <b>예외를 던지지 않고</b> 빈 목록으로 끝낸다(호출자가 frame_interval 로 폴백).
     * 여기서 던지면 파이프라인은 FAILED + 전량 재실행, {@code @Async} 재개 경로는 예외가 삼켜져
     * 영구 대기가 된다 — 위탁을 통째로 잃는 것보다 정책을 낮춰 보내는 편이 안전하다(fail-secure).
     *
     * <p>음수·null 프레임은 버린다({@code MARK_CN} 은 DB 에 영속된 과거 값이라 현재 DTO 검증
     * ({@code MarkItem @Min(0)}) 이전에 적재된 비규격 값이 남아 있을 수 있다).
     */
    private List<Integer> resolveSelectedFrames(Long rawSn, String markCn) {
        if (markCn == null || markCn.isBlank()) {
            return List.of();
        }
        List<MarkItem> items;
        try {
            items = objectMapper.readValue(markCn, new TypeReference<List<MarkItem>>() {});
        } catch (JsonProcessingException | RuntimeException e) {
            // 원문(외부 유래 JSON)은 로그에 싣지 않는다 — 예외 타입만 남긴다(CWE-117/209).
            log.warn("[Batch][VlmTimeseries] mark content parse failed — fallback to frame_interval rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
            return List.of();
        }
        if (items == null) {
            return List.of();
        }
        List<Integer> frames = items.stream()
                .filter(Objects::nonNull)
                .map(MarkItem::frameIndex)
                .filter(Objects::nonNull)
                .filter(f -> f >= 0)
                .distinct()
                .sorted()
                .toList();
        if (frames.size() > MAX_SELECTED_FRAMES) {
            log.warn("[Batch][VlmTimeseries] selected_frames truncated to vendor limit rawSn={} total={} kept={}",
                    rawSn, frames.size(), MAX_SELECTED_FRAMES);
            return List.copyOf(frames.subList(0, MAX_SELECTED_FRAMES));
        }
        return frames;
    }

    /**
     * {@code frame_interval} 의 framerate 해석 — 마킹 프레임 간격 우선, 없으면 설정 기본값.
     *
     * <p>벤더 §2.1 상 {@code framerate} 는 "초당 프레임수"가 아니라 <b>몇 프레임당 1장을 뽑을지</b>
     * 이므로 {@code LS_MARKING.FRME_INTV_NOCS}(마킹 프레임 간격)가 대응값이다.
     * {@code LsMarking.fps}(추출 시각 계산용 실 프레임레이트 pin)를 넣으면 <b>의미가 다르다</b>.
     */
    private int resolveIntervalFramerate(LsMarking marking) {
        Integer interval = marking == null ? null : marking.getFrmeIntvNocs();
        return (interval != null && interval > 0) ? interval : resolveFramerate();
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
                    "비식별 영상 경로가 없어 VLM verify 위탁을 진행할 수 없습니다 rawSn=" + rawSn);
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
