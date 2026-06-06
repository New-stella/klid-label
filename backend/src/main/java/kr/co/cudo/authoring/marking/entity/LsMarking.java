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
 *   <li>AUTO: frmeIntvNocs(프레임간격수) 기반으로 marks(프레임 인덱스/타임스탬프) 자동 생성</li>
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

    @Column(name = "EVNT_NM", nullable = false, length = 100)
    private String evntNm;

    @Column(name = "MARK_MODE_CD", nullable = false, length = 16)
    private String markModeCd;

    @Column(name = "FRME_INTV_NOCS")
    private Integer frmeIntvNocs;

    @Column(name = "VIDEO_FILE_PATH_NM", nullable = false, length = 500)
    private String videoFilePathNm;

    @Column(name = "MARK_CN", nullable = false, columnDefinition = "TEXT")
    private String markCn;

    @Column(name = "STTS_CD", nullable = false, length = 16)
    private String sttsCd;

    @Column(name = "REG_USER_NO")
    private Long createdBy;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

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
        m.evntNm = eventName;
        m.markModeCd = MODE_AUTO;
        m.frmeIntvNocs = intervalFrames;
        m.videoFilePathNm = videoPath;
        m.markCn = marksJson;
        m.sttsCd = STATUS_PENDING;
        m.createdBy = createdBy;
        LocalDateTime now = LocalDateTime.now();
        m.regDt = now;
        m.mdfcnDt = now;
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
        m.evntNm = eventName;
        m.markModeCd = MODE_MANUAL;
        m.frmeIntvNocs = null;
        m.videoFilePathNm = videoPath;
        m.markCn = marksJson;
        m.sttsCd = STATUS_PENDING;
        m.createdBy = createdBy;
        LocalDateTime now = LocalDateTime.now();
        m.regDt = now;
        m.mdfcnDt = now;
        return m;
    }

    /** VLM 요청 발송 상태 전이. */
    public void markVlmRequested() {
        this.sttsCd = STATUS_VLM_REQUESTED;
        this.mdfcnDt = LocalDateTime.now();
    }

    /** VLM 처리 완료 상태 전이. */
    public void markVlmCompleted() {
        this.sttsCd = STATUS_VLM_COMPLETED;
        this.mdfcnDt = LocalDateTime.now();
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.regDt == null) this.regDt = now;
        if (this.mdfcnDt == null) this.mdfcnDt = now;
        if (this.sttsCd == null) this.sttsCd = STATUS_PENDING;
    }

    @PreUpdate
    void preUpdate() {
        this.mdfcnDt = LocalDateTime.now();
    }
}
