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
 *   PENDING ──▶ VLM_REQUESTED ──┬─▶ VLM_COMPLETED
 *                               └─▶ VLM_FAILED (VLM describe 실패 콜백 수신 시)
 * </pre>
 *
 * <p>{@code VLM_FAILED} 는 {@code VLM_REQUESTED} 고착(dead-lock)을 해제하는 <b>종결 실패 상태</b>다.
 * 실패 콜백을 받고도 {@code VLM_REQUESTED} 에 방치하면 마킹이 영구 고착된다.
 * 이 상태를 소비해 자동 재요청/복구하는 잡은 아직 <b>미구현</b>이며, 수동/후속 재처리 대상이다(DEV_FIX 2차 #3).
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
    public static final String STATUS_VLM_FAILED = "VLM_FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "MARKING_SN")
    private Long markingSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "EVNT_NM", nullable = false, length = 200)
    private String evntNm;

    @Column(name = "MARK_MODE_CD", nullable = false, length = 16)
    private String markModeCd;

    @Column(name = "FRME_INTV_NOCS")
    private Integer frmeIntvNocs;

    @Column(name = "VIDEO_FILE_PATH_NM", nullable = false, length = 500)
    private String videoFilePathNm;

    @Column(name = "MARK_CN", nullable = false, columnDefinition = "TEXT")
    private String markCn;

    /**
     * 마킹 시점에 고정(pin)된 실 프레임레이트 — TOCTOU 제거의 핵심(M-3 후속 근본 수정).
     *
     * <p>마킹 생성 시 {@code VideoFpsResolver.resolveFps} 로 얻은 fps 를 이 컬럼에 저장한다.
     * 프레임추출({@code FfmpegFrameExtractor})은 fps 를 재조회하지 않고 이 pin 값을 읽어
     * {@code seekMillis} 를 계산하므로, 마킹이 {@code frameIndex} 를 산출할 때 쓴 fps 와
     * 추출이 쓰는 fps 가 <b>동일 레코드의 동일 값</b>으로 구조적으로 일치한다(정합성 불변식).
     *
     * <p>{@code null} 허용: 이 컬럼 도입 이전에 생성된 기존 행은 값이 없으므로, 추출이
     * {@code resolveFps} 폴백을 사용한다(하위호환·fail-safe).
     */
    @Column(name = "FPS")
    private Double fps;

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
        return createAuto(rawSn, eventName, intervalFrames, videoPath, marksJson, createdBy, null);
    }

    /**
     * 자동 모드 마킹 생성 (fps pin 포함) — TOCTOU 제거용.
     *
     * <p>{@code fps} 는 마킹 시점에 해석한 실 프레임레이트다. 프레임추출이 이 값을 재조회 없이
     * 사용하여 마킹↔추출 fps 를 구조적으로 일치시킨다. 미상 폴백값(30.0)을 넘겨도 되고,
     * 아예 {@code null} 을 넘기면 추출이 조회 폴백을 쓴다(하위호환).
     *
     * @param fps 마킹 시점 고정 프레임레이트 (nullable — null 이면 추출이 resolveFps 폴백)
     */
    public static LsMarking createAuto(Long rawSn, String eventName, int intervalFrames,
                                        String videoPath, String marksJson, Long createdBy, Double fps) {
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
        m.fps = fps;
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
        return createManual(rawSn, eventName, videoPath, marksJson, createdBy, null);
    }

    /**
     * 수동 모드 마킹 생성 (fps pin 포함) — TOCTOU 제거용.
     *
     * <p>수동 모드는 marks 가 사용자 지정 frameIndex 이지만, 추출 단계는 모드와 무관하게
     * frameIndex→seekMillis 변환에 fps 를 쓴다. 따라서 수동 마킹도 fps 를 고정 저장해
     * 추출이 재조회 없이 동일 값을 사용하게 한다.
     *
     * @param fps 마킹 시점 고정 프레임레이트 (nullable — null 이면 추출이 resolveFps 폴백)
     */
    public static LsMarking createManual(Long rawSn, String eventName,
                                          String videoPath, String marksJson, Long createdBy, Double fps) {
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
        m.fps = fps;
        m.sttsCd = STATUS_PENDING;
        m.createdBy = createdBy;
        LocalDateTime now = LocalDateTime.now();
        m.regDt = now;
        m.mdfcnDt = now;
        return m;
    }

    /**
     * VLM 요청 발송 상태 전이 — {@code PENDING} 에서만 {@code VLM_REQUESTED} 로 전이한다.
     *
     * <p>이미 {@code VLM_REQUESTED}/{@code VLM_COMPLETED}/{@code VLM_FAILED} 인 마킹은 <b>no-op</b>
     * (상태 유지)다. retry 로 파이프라인이 MARKING 부터 전량 재실행될 때 {@code VLM_COMPLETED} 마킹을
     * {@code VLM_REQUESTED} 로 덮어써 유효 META 를 가진 마킹이 고착되는 durable 역행을 원천 차단한다.
     *
     * @return 실제로 전이가 발생하면 {@code true}, no-op 이면 {@code false}
     */
    public boolean markVlmRequested() {
        if (!STATUS_PENDING.equals(this.sttsCd)) {
            return false;
        }
        this.sttsCd = STATUS_VLM_REQUESTED;
        this.mdfcnDt = LocalDateTime.now();
        return true;
    }

    /** VLM 처리 완료 상태 전이. */
    public void markVlmCompleted() {
        this.sttsCd = STATUS_VLM_COMPLETED;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * VLM describe 실패 상태 전이 — 실패 콜백 수신 시 VLM_REQUESTED 고착을 해제한다.
     * 배치 파이프라인이 재요청/복구를 판단할 수 있는 종료 상태로 남긴다.
     */
    public void markVlmFailed() {
        this.sttsCd = STATUS_VLM_FAILED;
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
