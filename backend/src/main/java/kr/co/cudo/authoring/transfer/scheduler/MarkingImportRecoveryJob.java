package kr.co.cudo.authoring.transfer.scheduler;

import kr.co.cudo.authoring.transfer.service.MarkingImportRecoveryTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 멈춘 일괄 적재를 <b>주기적으로</b> 다시 굴린다 — 재기동 복구의 발화 지점 (AC-1033).
 *
 * <h3>이 잡은 순서만 정한다</h3>
 * <p>실제 되돌리기·마감·일꾼 띄우기는 {@link MarkingImportRecoveryTxService} 가 한다.
 * ★<b>이 잡 안에서 자기 자신의 트랜잭션 메서드를 부르면 프록시를 우회해 트랜잭션이 적용되지 않는다</b> —
 * 그러면 되돌리기 UPDATE 가 조용히 반영되지 않아 <b>복구가 있는 것처럼 보이면서 아무것도 하지 않는다</b>.
 * 이 저장소가 같은 이유로 스윕 잡과 트랜잭션 빈을 나눠 둔 선례가 있어 그대로 따른다.
 *
 * <h3>무엇을 복구하는가</h3>
 * <ol>
 *   <li><b>처리 중인 채로 멈춘 항목</b> — 되돌리지 않으면 그 작업은 영원히 끝나지 않는다.</li>
 *   <li><b>다시 집을 횟수를 다 쓴 항목</b> — 마감하지 않으면 처리중과 대기 사이를 영원히 오간다.</li>
 *   <li><b>일꾼이 없는 작업</b> — 노드가 다시 뜨면 작업 행은 남지만 일꾼은 사라진다.</li>
 * </ol>
 *
 * <h3>토글과 스케줄링 활성화는 <b>같은 키</b>여야 한다</h3>
 * <p>한쪽만 걸면 남의 기능이 켜 둔 스케줄링 위에서 이 잡이 자기 토글과 무관하게 돈다. 반대로 잡만
 * 게이팅하고 활성화를 안 걸면 아무도 스케줄링을 켜지 않은 형상에서 <b>소리 없이 멈춘다</b>.
 *
 * @design DOMAIN-017
 * @design API-217
 * @design AC-1033
 * @design SEQ-030
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "authoring.import.marking.recovery.enabled",
        havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class MarkingImportRecoveryJob {

    private final MarkingImportRecoveryTxService recoveryTxService;

    /**
     * 주기 실행.
     *
     * <p>주기와 첫 지연은 {@code @Scheduled} 속성이 상수 표현식만 허용해 설정 record 로 옮기지 못하고
     * 자리표시자로 남는다(이 저장소의 기존 스윕 잡과 같다). 두 키를 공통 설정에 명시해 운영자가
     * 찾아 조정할 수 있게 한다.
     *
     * <p>예외를 삼킨다 — 한 순번이 실패했다고 주기 실행이 멈추면 그 뒤로 <b>복구가 영영 돌지 않는다</b>.
     */
    @Scheduled(fixedDelayString = "${authoring.import.marking.recovery.interval-ms:120000}",
            initialDelayString = "${authoring.import.marking.recovery.initial-delay-ms:60000}")
    public void run() {
        try {
            // ★되돌리기를 먼저 부른다. 두 조건은 재시도 횟수로 갈라져 겹치지 않지만, 되돌리기가
            //  상태를 바꾸므로 순서를 명시해 두면 조건을 손댔을 때 무엇이 먼저인지가 드러난다.
            int reclaimed = recoveryTxService.reclaimStale();
            int exhausted = recoveryTxService.failExhausted();
            int resumed = recoveryTxService.resumeRunningJobs();
            if (reclaimed > 0 || exhausted > 0 || resumed > 0) {
                log.info("[MarkingImport][Recovery] reclaimed={} exhausted={} resumedJobs={}",
                        reclaimed, exhausted, resumed);
            }
        } catch (RuntimeException e) {
            log.error("[MarkingImport][Recovery] failed cause={}", e.getClass().getSimpleName());
        }
    }
}
