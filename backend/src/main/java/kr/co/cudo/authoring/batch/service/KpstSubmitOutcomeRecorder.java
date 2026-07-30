package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * KPST 비식별 위탁(제출)의 <b>비동기 완료 핸들러</b> — ACK 수신/제출 실패를 원장에 기록한다 (Phase C-2).
 *
 * <h3>실행 컨텍스트</h3>
 * <p>본 빈의 메서드는 {@code kpstSubmitScheduler}(전용 풀) 스레드에서 호출된다 — 파이프라인 스레드도,
 * reactor-netty 이벤트 루프도 아니다. 따라서 <b>ambient 트랜잭션이 없다</b>. 모든 DB 쓰기는
 * {@code @Transactional(REQUIRES_NEW)} 를 선언한 별도 빈({@link KpstDeidentTxService})을 <b>프록시
 * 경유</b>로 호출한다. 이 클래스 자체에는 {@code @Transactional} 을 두지 않는다 — 두면 여기 안의 호출이
 * 자기호출로 보이는 착시가 생기고(이 레포의 실사고 패턴: 경계 유실로 배치 전면 불통), 중첩 tx 로
 * 커넥션 점유만 늘어난다.
 *
 * <h3>★ 상태 강등 금지 (회귀 위험)</h3>
 * <p>완료 신호는 <b>파이프라인이 이미 다음으로 넘어간 뒤</b>에 도착할 수 있다. 그래서 종결은 전부
 * 조건부 원자 UPDATE(WAITING + prjId null)로만 이뤄지며, ACK 를 받았거나 폴링이 완료시킨 건은
 * 어떤 지각 신호로도 강등되지 않는다({@link KpstDeidentTxService#failSubmit}).
 *
 * <h3>기록 실패는 삼킨다</h3>
 * <p>여기서 예외를 던져도 받을 곳이 없다(비동기). 기록이 실패하면 원장은 "WAITING + prjId null" 로
 * 남고, 폴링 잡이 ACK 대기 유예 만료로 회수한다({@code KpstDeidentService.ACK_MISSING_CODE}) —
 * 즉 <b>기록 유실은 위탁 유실이 아니다</b>.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class KpstSubmitOutcomeRecorder {

    private final KpstDeidentTxService txService;

    /**
     * 제출 수락(ACK) 수신 — {@code prj_id} 를 원장에 기록해 폴링이 시작될 수 있게 한다.
     *
     * <p>응답이 오긴 했으나 {@code prj_id} 가 없으면 폴링이 불가능하므로 <b>수락으로 보지 않고</b>
     * 실패로 종결한다(구 동기 코드의 "프로젝트 생성 응답이 비어있습니다" 가드와 동일 판정).
     */
    public void onAccepted(Long rawSn, Long procLogSn, KpstProjectResponse response) {
        if (response == null || response.prjId() == null) {
            log.error("[KpstDeid] submit ack without prjId rawSn={}", rawSn);
            recordFailure(rawSn, procLogSn, KpstDeidentService.SUBMIT_FAILED_CODE, "empty prjId");
            return;
        }
        try {
            txService.recordSubmitAck(procLogSn, rawSn, response.prjId());
        } catch (RuntimeException e) {
            // CWE-209: 외부 원문/스택트레이스 미노출(예외 클래스명만). 회수는 폴러의 ACK 유예가 담당.
            log.warn("[KpstDeid] submit ack record failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /**
     * 제출 <b>확정 실패</b>(4xx·타임아웃·서킷 오픈·빈 응답 등 onError 수신, 또는 구독 자체 거부).
     *
     * <p>기존 동기 계약("위탁 실패 = 예외 → 'F' 마킹 + MARKING_READY 미전이")과 <b>동등한 종단 상태</b>를
     * 비동기에서 재현한다: 원장 FAILED + 영상 {@code DE_IDNTF_YN='F'}(+ 재비식별이면 락 해제).
     * MARKING_READY 는 어느 경로에서도 전이되지 않으므로 마킹 조기 진입은 발생하지 않는다.
     *
     * <p>자동 재비식별 큐는 신설하지 않는다(설계 결정 3) — 'F' 기록이 외부 수동 재비식별 + 기존 resolve
     * 경로의 진입 신호다.
     */
    public void onSubmitFailed(Long rawSn, Long procLogSn, Throwable cause) {
        String errType = (cause == null) ? "unknown" : cause.getClass().getSimpleName();
        log.error("[KpstDeid] submit failed (async) rawSn={} cause={}", rawSn, errType);
        recordFailure(rawSn, procLogSn, KpstDeidentService.SUBMIT_FAILED_CODE, errType);
    }

    private void recordFailure(Long rawSn, Long procLogSn, String errorCd, String detail) {
        try {
            txService.failSubmit(procLogSn, rawSn, errorCd, detail);
        } catch (RuntimeException e) {
            log.warn("[KpstDeid] submit failure record failed rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }
}
