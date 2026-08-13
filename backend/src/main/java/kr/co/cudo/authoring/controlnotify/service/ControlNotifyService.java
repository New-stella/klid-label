package kr.co.cudo.authoring.controlnotify.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.client.ControlNotifyClient;
import kr.co.cudo.authoring.common.client.ControlNotifyStatusException;
import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
import kr.co.cudo.authoring.controlnotify.event.ReviewApprovedEvent;
import kr.co.cudo.authoring.controlnotify.fallback.ControlNotifyFallbackService;
import kr.co.cudo.authoring.controlnotify.fallback.LsControlNotifyFallback;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 관제서버 outbound 통지 오케스트레이션 — 페이로드 조립 → 전송 → 실패 시 폴백 큐 적재.
 *
 * <h3>최초/재통지 판정 = 관제 응답이 진실원 (self-healing)</h3>
 * 저작도구에 "이미 통지했는가" 플래그/테이블을 두지 않는다. 2노드 Active-Active 에서 로컬 플래그는
 * 신뢰할 수 없고, 관제 측 {@code datasets.job_id UNIQUE} 제약이 이미 중재자 역할을 하기 때문이다.
 * <ul>
 *   <li>승인 → {@code notify-completed} 먼저 전송. <b>409</b>(이미 등록) 면 즉시 {@code notify-updated}
 *       로 재전송한다.</li>
 *   <li>수정 → {@code notify-updated} 전송. <b>404</b>(선행 완료 없음) 면 {@code notify-completed} 로
 *       <b>1회</b> 폴백한다.</li>
 *   <li><b>재귀 금지</b>: 폴백이 또 폴백하지 않는다. 2단계에서 끝나고, 그래도 실패하면 폴백 큐로 간다.</li>
 * </ul>
 *
 * <p><b>주의(S4/S5)</b>: {@link ControlNotifyStatusException} 은 Resilience4j
 * {@code ignore-exceptions} 대상이지만 이는 "재시도하지 않는다" 는 뜻이지 "예외를 삼킨다" 는 뜻이
 * 아니다. 예외는 반드시 아래 catch 까지 전파되어 자기치유 분기를 발동시켜야 한다.
 *
 * <h3>통지 유실 금지 (A-1)</h3>
 * 통지는 {@code ReviewApprovedEvent} 를 <b>AFTER_COMMIT</b> 으로 소비한다 — 승인 트랜잭션은 이미
 * 커밋됐고 이벤트는 승인 1회만 발행되므로, 여기서 놓친 통지를 되살릴 상위 재시도 주체가 없다.
 * 따라서 <b>페이로드 조립 실패도 폴백 큐로 보낸다</b>: 페이로드가 없어도 {@code eventType + rawSn}
 * 만으로 적재하고({@link kr.co.cudo.authoring.controlnotify.fallback.LsControlNotifyFallback#PAYLOAD_REBUILD_REQUIRED})
 * 재시도 시점에 {@link ControlNotifyPayloadFactory} 로 <b>재조립</b>한다. 조립 실패의 원인(커넥션 고갈·
 * 락 타임아웃 등)은 대개 일시적이라 재시도가 유효하다.
 *
 * <p>같은 이유로 폴백 큐 적재 자체의 실패(큐 상한 초과 등)도 <b>호출자 밖으로 던지지 않는다</b> —
 * 디바운서 flush 루프가 끊기면 이미 윈도우에서 제거된 다른 영상의 변경까지 통째로 사라진다.
 */
@Service
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ControlNotifyService {

    private final ControlNotifyClient client;
    private final ControlNotifyFallbackService fallbackService;
    private final ControlNotifyMetrics metrics;
    private final ControlNotifyPayloadFactory payloadFactory;

    private static final String EVENT_COMPLETED = "TASK_COMPLETED";
    private static final String EVENT_MODIFIED = "TASK_MODIFIED";

    /**
     * {@code job_id} 허용 형식 — {@code LS_DATA_RAW.RAW_SN}(BIGINT) 의 문자열 표현만 허용한다.
     *
     * <p>즉시 전송 경로의 job_id 는 내부 PK 라 안전하지만, 폴백 재시도 경로는 <b>DB 에 저장된 JSON 을
     * 역직렬화해 URL 경로 변수로 넣는다</b>. DB 오염 시 경로 세그먼트 조작(CWE-22/CWE-88) 여지가 생기므로
     * dispatch 진입부에서 숫자만 통과시킨다(방어 심층화).
     */
    private static final Pattern JOB_ID_PATTERN = Pattern.compile("\\d{1,19}");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static {
        MAPPER.findAndRegisterModules();
    }

    /**
     * 실제로 전송에 성공한 통지의 종류와 본문.
     *
     * <p>자기치유(409 completed→updated / 404 updated→completed) 는 <b>요청한 종류와 다른 통지</b>를
     * 관제에 보낸다. 감사 행·메트릭이 요청 종류를 그대로 기록하면 "무엇이 관제에 도달했는가" 가 왜곡되어
     * 장애 조사·재전송 판단 근거가 어긋난다(B-3). 그래서 dispatch 는 <b>실제 전송분</b>을 돌려준다.
     *
     * @param eventType 실제 전송된 이벤트 타입 (TASK_COMPLETED / TASK_MODIFIED)
     * @param payload   실제 전송된 페이로드
     */
    public record SendOutcome(String eventType, Object payload) {
    }

    /**
     * 검수 완료 통지 전송(이벤트 오버로드). 409(이미 등록된 job) 면 수정 통지로 자기치유한다.
     *
     * <p>C-2 이후 이 오버로드는 하위호환/테스트 진입점으로 유지하며, 실 발송 트리거는
     * {@code ControlNotifyEventListener#onExportCompleted}(export 종결 후) → {@link #sendCompleted(Long)} 다.
     */
    public void sendCompleted(ReviewApprovedEvent event) {
        sendCompleted(event.rawSn());
    }

    /**
     * 검수 완료 통지 전송. 409(이미 등록된 job) 면 수정 통지로 자기치유한다.
     *
     * <p><b>C-2</b>: export SUCCEEDED 이후에 호출되어야 관제가 조회하는
     * {@code V_COMPLETED_VIDEO.OUTPUT_PATH_NM} 이 이번 승인의 새 버전 폴더를 담는다.
     */
    public void sendCompleted(Long rawSn) {
        String requestId = UUID.randomUUID().toString();
        TaskCompletedPayload payload;
        try {
            payload = payloadFactory.buildCompleted(rawSn);
        } catch (Exception e) {
            // fail-closed — 실측 조회 실패는 0/null 로 대체하지 않는다(D-ISSUE-41). 다만 통지를 버리지도
            // 않는다(A-1): 페이로드 없이 큐에 넣어 재시도 시점에 재조립한다.
            log.warn("[ControlNotify] TASK_COMPLETED payload build failed rawSn={} reason={} -> queued for rebuild",
                    rawSn, e.getClass().getSimpleName());
            metrics.incrementCompletedFailed();
            enqueueQuietly(requestId, EVENT_COMPLETED, rawSn,
                    LsControlNotifyFallback.PAYLOAD_REBUILD_REQUIRED);
            return;
        }

        try {
            SendOutcome outcome = dispatchCompleted(payload, rawSn);
            log.info("[ControlNotify] TASK_COMPLETED sent rawSn={} actual={}", rawSn, outcome.eventType());
            incrementSuccess(outcome.eventType());
            recordSendSuccess(requestId, outcome.eventType(), rawSn, outcome.payload());
        } catch (Exception e) {
            logFailure(EVENT_COMPLETED, rawSn, e);
            enqueueQuietly(requestId, EVENT_COMPLETED, rawSn, serializePayload(payload));
            metrics.incrementCompletedFailed();
        }
    }

    /**
     * 디바운스 윈도우 flush 후 전송. 404(선행 완료 통지 없음) 면 완료 통지로 1회 폴백한다.
     *
     * <h3>changed_items 범위는 "export 재생성을 동반했는가" 로 결정한다 (A-2)</h3>
     * <ul>
     *   <li><b>재생성 동반</b>({@code exportRegenerated=true}, 예: 승인 후 라벨/촬영환경 수정 →
     *       {@code TaskModifiedEvent(regen=true)} → 디바운스 flush 가 export 를 전량 재생성한 뒤 통지) —
     *       프레임 이미지·JSON 이 전량 재생성되므로
     *       {@link ControlNotifyPayloadFactory#buildModifiedForAllFrames} 로 전 프레임을 싣는다.
     *       여기서 changed_items 를 비우면 관제는 "변경 0건"으로 아무것도 재픽업하지 않아 디스크와 관제
     *       보유본이 영구 불일치한다.</li>
     *   <li><b>재생성 없음</b>(촬영환경 메타 수정 등) — 디스크가 1바이트도 바뀌지 않았다.
     *       <b>changed_items 를 비운 채 통지만 발송</b>한다. 재픽업할 파일이 없는 게 사실이고, 관제는
     *       통지를 받은 뒤 {@code V_COMPLETED_META} 등 뷰로 메타를 다시 읽는 것이 설계된 흐름이다
     *       (CLAUDE.md "관제서버 조회 패턴"). 전 프레임을 실으면 수천 개 파일을 헛 재픽업시킨다.
     *       <b>통지 자체는 반드시 나간다</b> — 생략하면 관제가 변경을 영원히 모른다(D-ISSUE-43).</li>
     * </ul>
     * 판별은 <b>발행처 클래스가 아니라 {@code TaskModifiedEvent.exportRegenerated}</b> 가 싣고 온다 —
     * 새 발행처가 생겨도 규칙이 유지된다.
     *
     * <h3>ver_expln 은 같은 분기 축에서 나온다</h3>
     * 관제 {@code dataset_versions.ver_expln} 이 NOT NULL 이라 값을 싣는다. 문구 판정은 위 두 값
     * (재생성 동반 여부 · 변경 프레임 건수)만 쓰며 규칙의 단일 원천은 {@link VersionExplanationPolicy} 다 —
     * <b>새 분기 축을 만들지 않는다.</b>
     *
     * @param frameChanges         프레임↔변경종류 페어 목록 (D-ISSUE-42)
     * @param videoLevelChangeTypes 영상 단위(srcSn=null) 변경 종류 — 비어 있지 않으면 프레임 변경이
     *                             없어도 통지를 발송한다(D-ISSUE-43 유실 금지)
     * @param exportRegenerated    윈도우에 축적된 변경 중 하나라도 export 폴더 재생성을 동반했는가
     */
    public void sendModified(Long rawSn, List<FrameChangeSet> frameChanges,
                             Set<String> videoLevelChangeTypes, boolean exportRegenerated) {
        String requestId = UUID.randomUUID().toString();
        // ver_expln 은 <이미 있는 분기 축>(재생성 동반 여부 + 변경 프레임 건수)에서만 나온다.
        //   판정 규칙은 VersionExplanationPolicy 한 곳이며 여기서 재유도하지 않는다.
        String verExpln = VersionExplanationPolicy.of(
                exportRegenerated, frameChanges == null ? 0 : frameChanges.size());
        TaskModifiedPayload payload;
        try {
            payload = exportRegenerated
                    ? payloadFactory.buildModifiedForAllFrames(rawSn, verExpln)
                    : payloadFactory.buildModified(rawSn, FrameChangeSet.srcSnsOf(frameChanges), verExpln);
        } catch (Exception e) {
            log.warn("[ControlNotify] TASK_MODIFIED payload build failed rawSn={} reason={} exportRegenerated={} -> queued",
                    rawSn, e.getClass().getSimpleName(), exportRegenerated);
            metrics.incrementModifiedFailed();
            // MED-2 — 폴백 재조립 시 exportRegenerated 플래그를 보존한다. 조립 실패로 REBUILD_REQUIRED
            //   로만 적재하면, 재시도 Job 이 dispatchModified(null) → buildModifiedForAllFrames 로 <b>무조건
            //   전 프레임</b>을 발송한다. 파일이 재생성되지 않은 메타 수정(exportRegenerated=false)이 그렇게
            //   재시도되면 관제가 안 바뀐 수천 파일을 헛 재픽업한다.
            //   - regen=true  → REBUILD_REQUIRED(재시도 시 전 프레임 재조립). 파일이 전량 재생성됐으므로 옳다.
            //   - regen=false → changed_items 를 빈 채로 확정 적재(DB 불필요, job_id 만 필요). 재시도 시
            //     그대로 전송돼 관제는 통지만 받고 V_COMPLETED_META 등 뷰로 메타를 재조회한다(설계된 흐름).
            String queuedPayload = exportRegenerated
                    ? LsControlNotifyFallback.PAYLOAD_REBUILD_REQUIRED
                    // [@design INT-007] output_ver_no 는 null(키 생략) — regen=false 라 산출 폴더가
                    //   새로 만들어지지 않았고, 조립 실패 경로라 DB 조회 없이 확정 적재해야 한다.
                    : serializePayload(new TaskModifiedPayload(
                            ControlNotifyPayloadFactory.toJobId(rawSn),
                            TaskModifiedPayload.ChangedItems.empty(), verExpln, null));
            enqueueQuietly(requestId, EVENT_MODIFIED, rawSn, queuedPayload);
            return;
        }

        try {
            SendOutcome outcome = dispatchModified(payload, rawSn);
            log.info("[ControlNotify] TASK_MODIFIED sent rawSn={} frames={} videoLevel={} reExport={} actual={}",
                    rawSn, frameChanges.size(),
                    videoLevelChangeTypes == null ? 0 : videoLevelChangeTypes.size(),
                    exportRegenerated, outcome.eventType());
            incrementSuccess(outcome.eventType());
            recordSendSuccess(requestId, outcome.eventType(), rawSn, outcome.payload());
        } catch (Exception e) {
            logFailure(EVENT_MODIFIED, rawSn, e);
            enqueueQuietly(requestId, EVENT_MODIFIED, rawSn, serializePayload(payload));
            metrics.incrementModifiedFailed();
        }
    }

    /**
     * 완료 통지 전송 + 409 자기치유. 폴백 큐 적재는 하지 않고 <b>실패를 그대로 던진다</b> —
     * 즉시 전송 경로와 폴백 재시도 Job 이 동일한 자기치유 규칙을 공유하기 위한 진입점이다.
     *
     * <p><b>재귀 금지</b>: 409 로 전환한 수정 통지가 다시 404 를 받아도 완료 통지로 되돌아가지 않는다.
     *
     * @param payload 전송할 페이로드. {@code null} 이면 <b>여기서 재조립</b>한다 — 폴백 큐에 페이로드
     *                없이 적재된 항목(조립 실패로 큐잉된 통지)의 재시도 경로다(A-1).
     * @return 실제 전송된 이벤트 타입·페이로드
     */
    public SendOutcome dispatchCompleted(TaskCompletedPayload payload, Long rawSn) {
        TaskCompletedPayload effective = payload != null ? payload : payloadFactory.buildCompleted(rawSn);
        assertValidJobId(effective.jobId());
        try {
            client.sendTaskCompleted(effective).block(ControlNotifyClient.BLOCK_TIMEOUT);
            return new SendOutcome(EVENT_COMPLETED, effective);
        } catch (ControlNotifyStatusException e) {
            if (!e.isConflict()) {
                throw e;
            }
            // 409 = 관제에 이미 등록된 job_id → 재승인으로 간주하고 수정 통지로 전환(1회, 재귀 없음).
            log.info("[ControlNotify] completed conflicted -> resend as updated rawSn={}", rawSn);
            metrics.incrementSelfHealCompletedToUpdated();
            // 어떤 프레임이 바뀌었는지 알 수 없는 경로다 — 건수를 추정하지 않고 중립 문구로 폴백한다.
            TaskModifiedPayload healed =
                    payloadFactory.buildModifiedForAllFrames(rawSn, VersionExplanationPolicy.REVIEW_COMPLETED);
            assertValidJobId(healed.jobId());
            client.sendTaskModified(healed).block(ControlNotifyClient.BLOCK_TIMEOUT);
            return new SendOutcome(EVENT_MODIFIED, healed);
        }
    }

    /**
     * 수정 통지 전송 + 404 자기치유(완료 통지 1회 폴백). 실패는 그대로 던진다.
     *
     * <p><b>재귀 금지</b>: 폴백한 완료 통지가 409 를 받아도 다시 수정 통지로 되돌아가지 않는다.
     *
     * @param payload 전송할 페이로드. {@code null} 이면 <b>전 프레임 기준으로 재조립</b>한다 — 어떤
     *                프레임이 바뀌었는지 큐에 남아 있지 않으므로 누락 없는 쪽(전 프레임)을 택한다(A-1/A-2).
     *                <b>이 경로엔 변경 프레임 건수가 없으므로</b> {@code ver_expln} 은
     *                {@link VersionExplanationPolicy#REVIEW_COMPLETED} 로 폴백한다(건수 추정 금지, B-2).
     * @return 실제 전송된 이벤트 타입·페이로드
     */
    public SendOutcome dispatchModified(TaskModifiedPayload payload, Long rawSn) {
        TaskModifiedPayload effective = payload != null ? payload
                : payloadFactory.buildModifiedForAllFrames(rawSn, VersionExplanationPolicy.REVIEW_COMPLETED);
        assertValidJobId(effective.jobId());
        try {
            client.sendTaskModified(effective).block(ControlNotifyClient.BLOCK_TIMEOUT);
            return new SendOutcome(EVENT_MODIFIED, effective);
        } catch (ControlNotifyStatusException e) {
            if (!e.isNotFound()) {
                throw e;
            }
            // 404 = 관제에 선행 완료 통지가 없음 → 완료 통지로 1회 폴백(재귀 금지 — 여기서 끝).
            log.info("[ControlNotify] updated not-found -> fallback to completed rawSn={}", rawSn);
            metrics.incrementSelfHealUpdatedToCompleted();
            TaskCompletedPayload healed = payloadFactory.buildCompleted(rawSn);
            assertValidJobId(healed.jobId());
            client.sendTaskCompleted(healed).block(ControlNotifyClient.BLOCK_TIMEOUT);
            return new SendOutcome(EVENT_COMPLETED, healed);
        }
    }

    /** 실제 전송된 이벤트 타입 기준 성공 메트릭(B-3). */
    private void incrementSuccess(String sentEventType) {
        if (EVENT_COMPLETED.equals(sentEventType)) {
            metrics.incrementCompletedSuccess();
            return;
        }
        metrics.incrementModifiedSuccess();
    }

    /** job_id 형식 검증 — 숫자(BIGINT 문자열)만 경로 변수로 나간다. */
    private static void assertValidJobId(String jobId) {
        if (jobId == null || !JOB_ID_PATTERN.matcher(jobId).matches()) {
            throw new IllegalArgumentException("허용되지 않는 job_id 형식입니다.");
        }
    }

    /**
     * 폴백 큐 적재 — <b>예외를 밖으로 던지지 않는다</b>.
     *
     * <p>큐 상한(10000) 초과 시 {@code enqueuePending} 은 {@link IllegalStateException} 을 던진다.
     * 그 예외가 여기서 새면 {@code ControlNotifyDebouncer.flushExpiredWindows} 루프가 끊겨, 이미
     * {@code windows.remove} 로 소유권을 가져간 윈도우(복구 불가)와 같은 tick 의 나머지 윈도우까지
     * 함께 소실된다. 통지 1건의 소실은 ERROR 로그로 남기고 루프는 살린다.
     *
     * <h3>소실은 <b>관측 가능</b>해야 한다 (B-3)</h3>
     * 여기서 삼킨 통지는 <b>실제로 사라진다</b> — 이벤트는 AFTER_COMMIT 1회 발행이고 디바운스 윈도우는
     * 이미 제거된 뒤라 되살릴 상위 주체가 없다. ERROR 로그만으로는 운영에서 감지되지 않으므로
     * 전용 카운터({@code control.notify.dropped})를 올려 알람을 걸 수 있게 한다.
     *
     * <p><b>왜 축적분을 복구하지 않는가</b>: 복구하려면 윈도우를 되돌려 넣어야 하는데,
     * ①큐 만재는 관제 장기 장애(=상한 10000 도달)라 되돌려도 다음 tick 에 같은 이유로 다시 실패하고
     * ②재축적은 만료 스캔이 매 10초마다 같은 윈도우를 무한 재시도하는 루프를 만들며
     * ③{@code @PreDestroy} 경로에서는 재축적해도 프로세스가 종료돼 의미가 없다.
     * 영속 재시도는 폴백 큐가 담당하는 책임이고, 그 큐 자체가 만재라면 <b>상한을 늘리거나 관제 장애를
     * 해소</b>하는 운영 대응이 정답이다. 따라서 여기서는 메트릭 + ERROR 로그로 관측만 보장한다.
     */
    private void enqueueQuietly(String requestId, String eventType, Long rawSn, String payloadJson) {
        try {
            fallbackService.enqueuePending(requestId, eventType, rawSn, payloadJson);
        } catch (Exception e) {
            metrics.incrementDropped();
            log.error("[ControlNotify] fallback enqueue failed (notification lost) eventType={} rawSn={} reason={}",
                    eventType, rawSn, e.getClass().getSimpleName());
        }
    }

    /**
     * 발송 성공 관찰 행(SEND_RSLT_CD=SUCCESS) 적재.
     *
     * <p>예외 격리: 관찰 적재가 실패해도 통지 성공(metrics·정상 반환)은 유지되도록
     * 예외를 삼키고 warn 로그만 남긴다 — 별도 REQUIRES_NEW 트랜잭션으로 커밋되므로
     * 상위 통지 흐름과 롤백 경계가 분리된다.
     */
    private void recordSendSuccess(String requestId, String eventType, Long rawSn, Object payload) {
        try {
            fallbackService.recordImmediateSuccess(requestId, eventType, rawSn, serializePayload(payload));
        } catch (Exception e) {
            log.warn("[ControlNotify] send-success record failed (notification kept) rawSn={} reason={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /** CWE-209/359 — 외부 응답 원문·스택트레이스를 그대로 남기지 않고 상태코드/예외 종류만 남긴다. */
    private void logFailure(String eventType, Long rawSn, Exception e) {
        if (e instanceof ControlNotifyStatusException se) {
            log.warn("[ControlNotify] {} rejected rawSn={} status={} body={}",
                    eventType, rawSn, se.statusCode(), se.bodySummary());
            return;
        }
        log.warn("[ControlNotify] {} failed rawSn={} reason={}",
                eventType, rawSn, e.getClass().getSimpleName());
    }

    /**
     * 폴백 큐/관찰 행에 적재할 페이로드 JSON. 직렬화 실패 시
     * {@link LsControlNotifyFallback#PAYLOAD_REBUILD_REQUIRED} 를 반환해 <b>재조립 경로</b>로 보낸다(B-2).
     *
     * <p>구 구현은 {@code "{}"} 를 반환했는데, 이는 blank 가 아니라 재조립 대상으로 인식되지 않고
     * 역직렬화하면 {@code jobId=null} → {@link #assertValidJobId} 예외 → 5회 재시도 후 dead-letter 로
     * <b>고착</b>됐다. 빈 표식이면 재시도 Job 이 {@code dispatch*(null, rawSn)} 으로 재조립해 살아난다.
     */
    private String serializePayload(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("[ControlNotify] payload serialize failed -> queued for rebuild");
            return LsControlNotifyFallback.PAYLOAD_REBUILD_REQUIRED;
        }
    }
}
