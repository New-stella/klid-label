package kr.co.cudo.authoring.marking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 영상 마킹 (LS_MARKING). 자동/수동 이벤트 마킹 정보를 저장한다.
 *
 * <h3>마킹 모드</h3>
 * <ul>
 *   <li>AUTO: intervalSec 기반으로 marks(프레임 인덱스/타임스탬프) 자동 생성</li>
 *   <li>MANUAL: 사용자가 직접 선택한 marks 배열 저장</li>
 * </ul>
 *
 * <h3>상태 전이</h3>
 * <pre>
 *   PENDING ──▶ VLM_REQUESTED ──▶ VLM_COMPLETED
 * </pre>
 */
@Entity
@Table(name = "LS_MARKING")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsMarking {

    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_MANUAL = "MANUAL";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_VLM_REQUESTED = "VLM_REQUESTED";
    public static final String STATUS_VLM_COMPLETED = "VLM_COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "MARKING_SN")
    private Long markingSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "EVENT_NAME", nullable = false, length = 100)
    private String eventName;

    @Column(name = "MARKING_MODE", nullable = false, length = 16)
    private String markingMode;

    @Column(name = "INTERVAL_FRAMES")
    private Integer intervalFrames;

    @Column(name = "VIDEO_PATH", nullable = false, length = 500)
    private String videoPath;

    @Column(name = "MARKS", nullable = false, columnDefinition = "TEXT")
    private String marks;

    @Column(name = "STATUS", nullable = false, length = 16)
    private String status;

    @Column(name = "CREATED_BY")
    private Long createdBy;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 자동 모드 마킹 생성.
     *
     * @param rawSn          영상 PK
     * @param eventName      이벤트명
     * @param intervalFrames 프레임 간격(프레임 수) — 1 이상 필수
     * @param videoPath      NAS 경로
     * @param marksJson      JSON 문자열
     * @param createdBy      생성자 사용자 번호
     */
    public static LsMarking createAuto(Long rawSn, String eventName, int intervalFrames,
                                        String videoPath, String marksJson, Long createdBy) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("eventName 은 필수입니다.");
        }
        if (intervalFrames <= 0) {
            throw new IllegalArgumentException("intervalFrames 는 1 이상이어야 합니다.");
        }
        if (videoPath == null || videoPath.isBlank()) {
            throw new IllegalArgumentException("videoPath 는 필수입니다.");
        }

        LsMarking m = new LsMarking();
        m.rawSn = rawSn;
        m.eventName = eventName;
        m.markingMode = MODE_AUTO;
        m.intervalFrames = intervalFrames;
        m.videoPath = videoPath;
        m.marks = marksJson;
        m.status = STATUS_PENDING;
        m.createdBy = createdBy;
        LocalDateTime now = LocalDateTime.now();
        m.createdAt = now;
        m.updatedAt = now;
        return m;
    }

    /**
     * 수동 모드 마킹 생성.
     *
     * @param rawSn      영상 PK
     * @param eventName  이벤트명
     * @param videoPath  NAS 경로
     * @param marksJson  JSON 문자열
     * @param createdBy  생성자 사용자 번호
     */
    public static LsMarking createManual(Long rawSn, String eventName,
                                          String videoPath, String marksJson, Long createdBy) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("eventName 은 필수입니다.");
        }
        if (videoPath == null || videoPath.isBlank()) {
            throw new IllegalArgumentException("videoPath 는 필수입니다.");
        }

        LsMarking m = new LsMarking();
        m.rawSn = rawSn;
        m.eventName = eventName;
        m.markingMode = MODE_MANUAL;
        m.intervalFrames = null;
        m.videoPath = videoPath;
        m.marks = marksJson;
        m.status = STATUS_PENDING;
        m.createdBy = createdBy;
        LocalDateTime now = LocalDateTime.now();
        m.createdAt = now;
        m.updatedAt = now;
        return m;
    }

    /** VLM 요청 발송 상태 전이. */
    public void markVlmRequested() {
        this.status = STATUS_VLM_REQUESTED;
        this.updatedAt = LocalDateTime.now();
    }

    /** VLM 처리 완료 상태 전이. */
    public void markVlmCompleted() {
        this.status = STATUS_VLM_COMPLETED;
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = now;
        if (this.status == null) this.status = STATUS_PENDING;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
