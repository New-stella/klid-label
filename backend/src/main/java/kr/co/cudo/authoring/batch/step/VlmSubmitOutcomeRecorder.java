package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.VlmMarkingTxService;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import kr.co.cudo.authoring.common.client.VlmClient;
import kr.co.cudo.authoring.common.client.dto.VlmTimeseriesResponse;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VLM 논블로킹 제출의 <b>완료 핸들러</b> — ACK 수신/제출 실패를 기존 원장·로그에 기록한다 (Phase C-1).
 *
 * <h3>실행 컨텍스트</h3>
 * <p>본 빈의 메서드는 {@code vlmSubmitScheduler}(전용 풀) 스레드에서 호출된다 — 파이프라인 스레드도,
 * reactor-netty 이벤트 루프도 아니다. 따라서 ambient 트랜잭션이 없으며, <b>모든 DB 쓰기는
 * {@code @Transactional(REQUIRES_NEW)} 를 선언한 별도 빈</b>({@link BatchStatusService#recordVlmSkippedInNewTx},
 * {@link BatchStatusService#recordVlmTimeseriesResult}, {@link VlmMarkingTxService})을 <b>프록시 경유</b>로
 * 호출한다. 이 클래스 자체에는 {@code @Transactional} 을 두지 않는다 — 두면 여기 안에서의 호출이
 * 자기호출로 보이는 착시가 생기고(이 레포의 실사고 패턴), 중첩 tx 로 커넥션 점유만 늘어난다.
 *
 * <h3>★ 상태 강등 금지 (회귀 위험 2)</h3>
 * <p>완료 신호는 <b>오케스트레이터가 이미 파이프라인을 끝낸 뒤</b>에 도착할 수 있다(제출은 논블로킹이고
 * 이후 단계는 계속 진행된다). 이 시점에 {@code markFailed}/{@code markRawDataFailed} 를 호출하면 이미
 * COMPLETED 로 마감되고 작업 상태가 ASSIGNED 로 복귀한 영상이 FAILED 로 <b>역행</b>해 라벨링·검수 동선이
 * 끊긴다. 그래서 본 핸들러는 <b>기록(감사 행)과 마킹 보상 전이까지만</b> 하고 배치/작업 상태는 건드리지
 * 않는다. 회수는 미결 스위퍼({@code VlmSubmitPendingSweeper})가 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VlmSubmitOutcomeRecorder {

    /**
     * 벤더 오류 코드 — <b>정의되지 않은 이벤트 유형</b>. 규격 §2.10.
     *
     * <p>규격은 <b>그 사유일 때만</b> 본문에 코드를 싣는다고 못 박으므로, 이 값이 곧 「미지원 이벤트
     * 유형」 판정의 <b>단일 근거</b>다. 우리는 이벤트 유형을 허용목록으로 사전 차단하지 않고
     * <b>벤더 응답이 판정하게</b> 하는데, 이 구분이 없으면 실패 기록이 "4xx 응답"으로만 남아
     * <b>왜 거부됐는지 알 수 없다</b>.
     *
     * <p>★ <b>적용 축은 묘사 하나다</b> — 추가 질문 축은 이벤트 유형을 보내지 않아 나올 수 없다.
     * [design: INTSPEC-003] [design: INT-002]
     */
    static final int VENDOR_CODE_UNDEFINED_EVENT_TYPE = 40001;

    private final BatchStatusService batchStatusService;
    private final VlmMarkingTxService markingTxService;
    private final ObjectMapper objectMapper;
    /** H1 — ACK 수신 사실을 원장에 남겨 미결 스위퍼가 <b>진행 중 위탁을 뺏지 않게</b> 한다. */
    private final WebhookIdempotencyLedger ledger;

    /**
     * ACK(수락) 수신 — 원장 {@code ISSUED → ACCEPTED} 전이 + {@code request_id}/{@code status} 를
     * {@code LS_BATCH_PROC_LOG.RESP_PAYLOAD_CN} 에 기록.
     *
     * <h3>★ 원장 전이가 핵심이다 (H1)</h3>
     * <p>구 구현은 ACK 를 {@code LS_BATCH_PROC_LOG} 에만 남기고 원장 행을 {@code ISSUED} 로 두었다.
     * 그래서 미결 스위퍼가 "ACK 미수신"과 "콜백 지연"을 구분하지 못해, describe 콜백이 임계
     * (기본 30분)보다 늦게 오는 <b>정상 위탁</b>을 회수하고 같은 비식별 영상을 최대 3회 중복 위탁했다.
     * KPST 가 {@code prjId} 유무로 같은 문제를 푸는 것의 대칭을 여기서 만든다.
     *
     * <p><b>전이 키는 우리가 발급한 requestId</b> 다 — 벤더 응답의 echo 값을 신뢰하지 않는다(응답이
     * 다른 키를 돌려주면 엉뚱한 행을 전이시킨다).
     *
     * <p>기록 실패는 위탁 자체를 실패로 보지 않고 WARN 로깅만 남긴다(기존 규약 유지) —
     * 위탁은 이미 성공했고 상관키는 원장에 durable 하다. 원장 전이가 실패하면 그 건은 종전처럼
     * ACK 미수신으로 회수될 뿐이다(안전한 방향).
     *
     * @param requestId 위탁 시 <b>우리가 발급</b>한 request_id (원장 키)
     */
    public void onAccepted(Long rawSn, String requestId, VlmTimeseriesResponse resp) {
        if (rawSn == null || resp == null) {
            return;
        }
        log.info("[Batch][VlmTimeseries] accepted rawSn={} request_id={} status={}",
                rawSn, VlmClient.safeForLog(requestId), VlmClient.safeForLog(resp.status()));
        try {
            ledger.recordAckReceived(requestId);
        } catch (RuntimeException e) {
            log.warn("[Batch][VlmTimeseries] ack ledger transition failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("requestId", resp.requestId());
        payload.put("status", resp.status());
        try {
            batchStatusService.recordVlmTimeseriesResult(rawSn, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("[Batch][VlmTimeseries] persist res_payload failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /**
     * 제출 <b>확정 실패</b>(4xx·타임아웃·서킷 오픈·응답 무결성 위반 등 onError 신호 수신).
     *
     * <p>기록 2건만 수행한다:
     * <ol>
     *   <li>{@code LS_BATCH_PROC_LOG} 에 {@code VLM/SKIPPED} + {@link VlmTimeseriesStep#SKIP_REASON_SUBMIT_FAILED}
     *       — 재개 대상 식별의 근거. {@code BatchRetryQueue} 는 쓰지 않는다(그 큐는 rawSn 단위로
     *       <b>파이프라인 전체</b>를 재실행해 VLM 제출 1건 실패에 프레임추출·YOLO·SAM2 까지 다시 돌린다).</li>
     *   <li>마킹 {@code VLM_REQUESTED → VLM_FAILED} 보상 — 선커밋으로 올려둔 상태의 고착 해제.
     *       그 사이 콜백이 먼저 완료시켰으면 no-op 이다.</li>
     * </ol>
     * 어느 기록이 실패해도 예외를 밖으로 던지지 않는다 — 여기서 던져도 받을 곳이 없고(비동기),
     * 원장의 ISSUED 행이 그대로 남아 미결 스위퍼가 회수한다.
     *
     * <h3>★ 「미지원 이벤트 유형」 구분 (규격 §2.10)</h3>
     * <p>벤더가 {@value #VENDOR_CODE_UNDEFINED_EVENT_TYPE} 을 실어 보내면 그것은 <b>정의되지 않은
     * 이벤트 유형</b>이라는 뜻이다. 그 사유를 <b>로그로 구분</b>해 남긴다 — 우리가 이벤트 유형을
     * 사전 차단하지 않는 이상 이 코드가 거부 사유를 아는 유일한 근거이기 때문이다.
     *
     * <p>⚠ <b>DB 에 적재하는 사유 문자열은 바꾸지 않는다</b>. {@link VlmTimeseriesStep#SKIP_REASON_SUBMIT_FAILED}
     * 값 자체가 {@code LS_BATCH_PROC_LOG} 재개 판정의 키이며 이미 적재된 과거 행과의 대조 키다 —
     * 「더 정확하게」 다듬는 순간 그 행들의 재개 배선이 끊긴다. 그래서 구분은 <b>그 값을 건드리지 않는
     * 방식</b>(로그 필드)으로만 한다.
     *
     * <p>⚠ <b>재시도 분류는 불변</b>이다 — 이 핸들러가 도는 시점에는 이미 재시도 판정이 끝나 있고,
     * 재시도 대상은 여전히 429 하나뿐이다. 이 구분은 <b>기록의 정밀도</b>만 올린다.
     *
     * @param markingSn 선커밋으로 VLM_REQUESTED 로 올린 마킹(없으면 null)
     */
    public void onSubmitFailed(Long rawSn, Long markingSn, Throwable cause) {
        if (rawSn == null) {
            return;
        }
        // CWE-117/209 — 외부 예외 메시지 원문은 남기지 않고 타입만 기록한다.
        Integer vendorCode = vendorCodeOf(cause);
        if (vendorCode != null && vendorCode == VENDOR_CODE_UNDEFINED_EVENT_TYPE) {
            log.error("[Batch][VlmTimeseries] submit failed — undefined event_type rejected by vendor "
                            + "(no status demotion) rawSn={} vendorCode={} cause={}",
                    rawSn, vendorCode, cause.getClass().getSimpleName());
        } else if (vendorCode != null) {
            log.error("[Batch][VlmTimeseries] submit failed (no status demotion) rawSn={} vendorCode={} cause={}",
                    rawSn, vendorCode, cause.getClass().getSimpleName());
        } else {
            log.error("[Batch][VlmTimeseries] submit failed (no status demotion) rawSn={} cause={}",
                    rawSn, cause == null ? "unknown" : cause.getClass().getSimpleName());
        }
        try {
            batchStatusService.recordVlmSkippedInNewTx(rawSn, VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED);
        } catch (RuntimeException e) {
            log.warn("[Batch][VlmTimeseries] submit-failure audit record failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
        try {
            markingTxService.markVlmFailedIfRequested(markingSn);
        } catch (RuntimeException e) {
            log.warn("[Batch][VlmTimeseries] marking failure transition failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /**
     * 실패 원인 사슬에서 <b>벤더 오류 코드</b>만 꺼낸다. 없으면 {@code null}.
     *
     * <p>메시지를 파싱하지 않는다 — 문구가 바뀌면 조용히 오분류된다(그 예외가 코드를 <b>값</b>으로
     * 들고 다니는 이유다). 사슬을 훑는 것은 제출 경로가 예외를 감싸 전달할 수 있기 때문이며,
     * 순환 참조에 대비해 깊이를 제한한다.
     */
    private static Integer vendorCodeOf(Throwable cause) {
        Throwable t = cause;
        for (int depth = 0; t != null && depth < 8; depth++) {
            if (t instanceof NonRetryableExternalException nre && nre.getVendorCode() != null) {
                return nre.getVendorCode();
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return null;
    }
}
