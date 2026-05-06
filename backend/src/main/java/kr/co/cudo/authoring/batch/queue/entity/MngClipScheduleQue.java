package kr.co.cudo.authoring.batch.queue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 배치 작업 큐. 영상 수신(LS_DATA_RAW upsert) 직후 1건 INSERT 되어 라벨링 배치 파이프라인 입구로 사용.
 * - JOB_TYPE = "LABELING_BATCH" 만 본 Phase 에서 적재.
 * - STATUS: PENDING -> IN_PROGRESS -> DONE / FAILED.
 */
@Entity
@Table(name = "MNG_CLIP_SCHEDULE_QUE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngClipScheduleQue {

    public static final String JOB_LABELING_BATCH = "LABELING_BATCH";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "QUE_SN")
    private Long queSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "JOB_TYPE", nullable = false, length = 32)
    private String jobType;

    @Column(name = "STATUS", nullable = false, length = 16)
    private String status;

    @Column(name = "RETRY_COUNT", nullable = false)
    private Integer retryCount;

    @Column(name = "REGISTERED_AT", nullable = false)
    private LocalDateTime registeredAt;

    @Column(name = "STARTED_AT")
    private LocalDateTime startedAt;

    @Column(name = "COMPLETED_AT")
    private LocalDateTime completedAt;

    @Column(name = "LAST_ERROR", length = 2000)
    private String lastError;

    @Builder
    private MngClipScheduleQue(Long rawSn, String jobType) {
        this.rawSn = rawSn;
        this.jobType = jobType;
        this.status = STATUS_PENDING;
        this.retryCount = 0;
        this.registeredAt = LocalDateTime.now();
    }

    public static MngClipScheduleQue enqueueLabelingBatch(Long rawSn) {
        return MngClipScheduleQue.builder()
                .rawSn(rawSn)
                .jobType(JOB_LABELING_BATCH)
                .build();
    }

    /** 큐 워커가 작업 시작 시 호출. 외부에서 직접 status 를 set 하지 않도록 메서드로 노출. */
    public void markInProgress() {
        this.status = STATUS_IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
    }
}
