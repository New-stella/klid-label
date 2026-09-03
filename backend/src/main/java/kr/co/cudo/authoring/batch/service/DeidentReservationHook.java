package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.marking.listener.MarkingBatchTriggerReport;
import kr.co.cudo.authoring.marking.service.MarkingActivationTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

/**
 * <b>비식별 결과 → 예약 마킹 활성화·마감 배선</b> (ADR-052 · SEQ-030).
 * [design: ADR-052] [design: SEQ-030] [design: AC-1032] [design: AC-1033]
 *
 * <h3>무엇을 하는가</h3>
 * <p>외부에서 이벤트 마킹까지 끝난 영상을 폴더째 받아 적재하는 경로는, 적재 시점에 영상이 아직
 * 비식별 전이라 마킹을 활성화할 수 없어 예약 상태로 담아 둔다. <b>비식별이 끝나 영상이 마킹 가능
 * 상태가 되면 그 예약을 깨우는 것</b>이 비식별 도메인의 몫이며, 이 클래스가 그 호출 배선을 모은다.
 * 실제 전이는 {@link MarkingActivationTxService}(마킹 도메인 소유)가 수행하고 여기서는 <b>부르기만</b>
 * 한다.
 *
 * <h3>★ 왜 커밋 이후여야 하는가 — 순서를 뒤집으면 예약이 조용히 사라진다</h3>
 * <p>{@code activateReserved} 가 발행하는 {@code MarkingCompletedEvent} 를 {@code MarkingBatchBridge}
 * 가 {@code AFTER_COMMIT} 으로 받아 <b>그 시점의 영상 행을 다시 읽는다</b>. 비식별 결과
 * ({@code DE_IDENT_YN='Y'} · 배치 단계 {@code MARKING_READY})가 아직 커밋되지 않았으면 브리지가
 * 자기 가드({@code REASON_NOT_DEIDENTIFIED})에 막혀 skip 으로 판정하고, 그 skip 이 방금 깨운 마킹을
 * {@code SKIPPED} 로 종결시킨다(활성 마킹 고아 방지 규약). 활성화 자체는 성공하는데 결과만 사라진다.
 *
 * <p>그래서 {@link #activateAfterCommit(Long)} 은 <b>활성 트랜잭션이 있으면 {@code afterCommit} 으로
 * 미루고</b>, 없으면(=호출자가 이미 커밋을 끝낸 비트랜잭션 컨텍스트) 즉시 실행한다.
 * {@code StreamMetaCacheEvictor.evictAfterCommit} 과 같은 골격이며, 트랜잭션 격리 문제가 아니라
 * <b>브리지가 읽는 시점</b> 문제라 {@code REQUIRES_NEW} 만으로는 해결되지 않는다.
 *
 * <h3>예외를 밖으로 내보내지 않는다</h3>
 * <p>여기까지 왔다는 것은 <b>비식별이 이미 끝났고 그 사실이 영속됐다</b>는 뜻이다. 예약 활성화가
 * 실패했다고 그것을 되돌릴 수 없고 되돌려서도 안 된다. {@code afterCommit} 에서 예외를 던지면 커밋을
 * 수행한 스레드로 전파되어 호출자(폴러·비동기 러너)가 <b>비식별 실패로 오인</b>한다. 따라서 모든
 * 예외를 삼키고 WARN 만 남긴다 — 예약이 남으면 사람이 그 영상을 다시 마킹할 수 있으므로 fail-open
 * 이 아니라 <b>회복 가능한 상태로의 후퇴</b>다.
 *
 * <h3>예약이 없는 영상이 정상이다</h3>
 * <p>관제 인입·dev 업로드·증강/해상도 파생 등 대다수 영상에는 예약이 없다. 그때
 * {@code activateReserved} 는 {@link Optional#empty()} 를 돌려주며 이는 오류가 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeidentReservationHook {

    /** 예약 마감 사유 — 로그에만 남는 고정 상수다(외부 유래 문자열 금지, CWE-117). */
    public static final String REASON_DEIDENT_FAILED = "DEIDENT_FAILED";

    private final MarkingActivationTxService markingActivationTxService;

    /**
     * 비식별 완료가 <b>커밋된 뒤</b> 예약 마킹을 활성화한다.
     *
     * <p>활성 트랜잭션 안에서 불리면 {@code afterCommit} 으로 미루므로, 호출부는 완료 전이를 기록한
     * 바로 그 자리에 붙여도 안전하다. 트랜잭션이 롤백되면 활성화는 <b>실행되지 않는다</b>.
     *
     * @param rawSn 비식별이 끝나 마킹 가능 상태가 된 영상 PK (nullable — null 이면 no-op)
     */
    public void activateAfterCommit(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        runAfterCommit(() -> activateQuietly(rawSn));
    }

    /**
     * 비식별 <b>최종 실패</b>가 커밋된 뒤 예약 마킹을 마감한다({@code RESERVED → SKIPPED}).
     *
     * <p>적재 자체는 되돌리지 않는다 — 영상 행과 복사된 파일은 그대로 두고 마킹만 마감한다. 마감된
     * 마킹은 활성 집합 밖이라 <b>사람이 그 영상을 다시 마킹할 수 있다</b>. 예약이 없으면 0건으로
     * 조용히 끝난다(멱등).
     *
     * @param rawSn  대상 영상 PK (nullable — null 이면 no-op)
     * @param reason 마감 사유 — 본 클래스의 고정 상수만 전달한다
     */
    public void closeAfterCommit(Long rawSn, String reason) {
        if (rawSn == null) {
            return;
        }
        runAfterCommit(() -> closeQuietly(rawSn, reason));
    }

    private void activateQuietly(Long rawSn) {
        try {
            markingActivationTxService.activateReserved(rawSn)
                    .ifPresent(markingSn -> reportBatchTrigger(rawSn, markingSn));
        } catch (RuntimeException e) {
            // 비식별 완료는 이미 영속됐다 — 되돌리지 않고 사실만 남긴다. 예약은 그대로 남아 사람이
            // 그 영상을 다시 마킹하거나 운영이 손댈 수 있다. CWE-209: 예외 클래스명만 남긴다.
            log.warn("[DeidentReservation] activate failed rawSn={} errType={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    private void closeQuietly(Long rawSn, String reason) {
        try {
            markingActivationTxService.closeReservations(rawSn, reason);
        } catch (RuntimeException e) {
            log.warn("[DeidentReservation] close failed rawSn={} errType={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }

    /**
     * 활성화 직후 브리지가 남긴 트리거 판정을 <b>소비</b>해 로그로 남긴다.
     *
     * <p>두 가지를 한 번에 한다. ①{@code MarkingBatchTriggerReport} 는 마킹 API 응답용 스레드 로컬인데
     * 이 경로에는 그 값을 읽어 갈 요청 스레드가 없어, 비우지 않으면 풀 스레드에 잔여값이 남는다.
     * ②브리지가 skip 했다면 <b>깨운 마킹이 곧바로 종결됐다</b>는 뜻이라 운영이 그 사실을 알아야 한다
     * (이 배선의 최대 함정이 정확히 그 경로다). 사유는 고정 상수라 그대로 남겨도 안전하다.
     */
    private void reportBatchTrigger(Long rawSn, Long markingSn) {
        MarkingBatchTriggerReport.Outcome outcome = MarkingBatchTriggerReport.consume();
        if (outcome == null) {
            log.info("[DeidentReservation] activated rawSn={} markingSn={}", rawSn, markingSn);
        } else if (outcome.triggered()) {
            log.info("[DeidentReservation] activated and batch triggered rawSn={} markingSn={}",
                    rawSn, markingSn);
        } else {
            log.warn("[DeidentReservation] activated but batch skipped rawSn={} markingSn={} reason={}",
                    rawSn, markingSn, outcome.reason());
        }
    }

    /**
     * 활성 트랜잭션이 있으면 커밋 성공 후에만, 없으면 즉시 실행한다.
     * ({@code StreamMetaCacheEvictor.evictAfterCommit} 준용 — 같은 골격을 쓴다.)
     */
    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
