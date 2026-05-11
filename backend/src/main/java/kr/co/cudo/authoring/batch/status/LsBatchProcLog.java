package kr.co.cudo.authoring.batch.status;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LS_BATCH_PROC_LOG: 배치 파이프라인 단계별 처리 이력.
 * RAW_SN을 PK로 사용하는 단일 행 upsert 방식 — 영상 1건당 1행 유지.
 */
@Entity
@Table(name = "LS_BATCH_PROC_LOG")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsBatchProcLog {

    @Id
    @Column(name = "RAW_SN")
    private Long rawSn;

    @Column(name = "STAGE_CD", nullable = false, length = 32)
    private String stageCd;

    @Column(name = "STARTED_AT", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "RETRY_CNT", nullable = false)
    private int retryCnt;

    @Column(name = "ERR_MSG", length = 500)
    private String errMsg;

    /**
     * 배치 파이프라인 로그 최초 생성.
     * startedAt·updatedAt 모두 현재 시각으로 설정한다.
     */
    public static LsBatchProcLog create(Long rawSn, BatchStage stage) {
        LsBatchProcLog log = new LsBatchProcLog();
        log.rawSn = rawSn;
        log.stageCd = stage.name();
        LocalDateTime now = LocalDateTime.now();
        log.startedAt = now;
        log.updatedAt = now;
        log.retryCnt = 0;
        return log;
    }

    /**
     * 현재 단계 코드를 갱신하고 updatedAt을 현재 시각으로 갱신한다.
     */
    public void updateStage(BatchStage stage) {
        this.stageCd = stage.name();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 파이프라인 실패 처리.
     * stageCd를 FAILED로 전이하고 예외 클래스명을 errMsg에 기록한다.
     */
    public void fail(Throwable cause) {
        this.stageCd = BatchStage.FAILED.name();
        this.errMsg = cause.getClass().getSimpleName();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 재시도 횟수를 1 증가시킨다.
     */
    public void incrementRetry() {
        this.retryCnt++;
    }
}
