package kr.co.cudo.authoring.dataset.export;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 학습데이터 산출(export)의 <b>stale PENDING 회수</b> 로직 컴포넌트.
 *
 * <p>{@link DatasetExportTxService#insertNextVersion} 이 PENDING 레코드를 커밋한 뒤 파일 쓰기/상태 마감
 * (markSucceeded/markPartial/markFailed) 전에 프로세스가 크래시하면 그 export 가 {@code PENDING} 으로
 * 영구 고착된다. 승인엔 무해하나 잔재가 쌓이므로 주기 잡({@code DatasetExportPendingSweepJob})이 이 컴포넌트를
 * 호출해 stale-minutes 임계를 넘긴 PENDING 을 FAILED 로 마감한다(<b>파일 삭제는 하지 않는다 — 상태만 회수</b>).
 *
 * <p>Job 이 아닌 별도 스프링 빈으로 분리한 이유: {@code @Value} 주입과 트랜잭션 게이트웨이 호출을
 * 스프링 컨텍스트에서 수행하기 위함(Quartz Job 은 얇은 어댑터로 유지).
 * 로그는 건수만 출력하며 경로/PII 는 남기지 않는다(CWE-359/117).
 */
@Slf4j
@Component
public class DatasetExportPendingSweeper {

    /** 오설정(0/음수) 시 안전 폴백 임계(분). */
    private static final int DEFAULT_STALE_MINUTES = 30;

    private final DatasetExportTxService txService;

    /** 이 분(minute)을 초과해 PENDING 에 머문 export 를 stale 로 간주(기본 30분). */
    @Value("${authoring.dataset-export.pending-sweep.stale-minutes:30}")
    private int staleMinutes;

    public DatasetExportPendingSweeper(DatasetExportTxService txService) {
        this.txService = txService;
    }

    /**
     * stale PENDING export 를 FAILED 로 일괄 회수한다.
     *
     * <p>staleMinutes 가 0/음수로 오설정되면 cutoff=now(또는 미래)가 되어 아직 파일을 쓰는 중인 정상 PENDING 까지
     * 회수돼 매 tick 무차별 FAILED 로 오분류된다(last-writer-wins 로 자가치유되나 노이즈). 이를 막기 위해
     * {@code staleMinutes < 1} 이면 안전 기본값 {@value #DEFAULT_STALE_MINUTES} 분으로 폴백한다.
     *
     * @return 회수(FAILED 마감)한 건수
     */
    public int sweep() {
        int effective = staleMinutes < 1 ? DEFAULT_STALE_MINUTES : staleMinutes;
        if (effective != staleMinutes) {
            // 오설정은 상시 상태라 sweep 당 1회만 warn(매 tick 스팸 방지는 interval-sec 간격으로 완화됨).
            log.warn("[DatasetExportPendingSweep] invalid stale-minutes={} — fallback to {}",
                    staleMinutes, DEFAULT_STALE_MINUTES);
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(effective);
        int n = txService.sweepStalePending(cutoff);
        if (n > 0) {
            log.info("[DatasetExportPendingSweep] swept stale PENDING count={} staleMinutes={}", n, staleMinutes);
        } else {
            log.debug("[DatasetExportPendingSweep] no stale PENDING staleMinutes={}", staleMinutes);
        }
        return n;
    }
}
