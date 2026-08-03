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
import java.util.List;

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
 *   PENDING ──┬─▶ VLM_REQUESTED ──┬─▶ VLM_COMPLETED
 *             │                   └─▶ VLM_FAILED (VLM describe 실패 콜백 수신 시)
 *             └─▶ SKIPPED (배치 트리거가 정당하게 skip 되어 소비될 일이 없는 마킹 — B-ISSUE-41)
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

    /**
     * <b>종결</b> — 배치 트리거가 정당하게 skip 되어 이 마킹이 소비될 일이 없음 (B-ISSUE-41).
     *
     * <p>마킹 저장(커밋)과 배치 트리거 판단({@code MarkingBatchBridge}, AFTER_COMMIT)이 분리돼 있어,
     * 브리지가 skip 을 결정해도 방금 커밋된 {@code PENDING} 마킹은 남는다. {@code PENDING→VLM_*} 전이는
     * <b>오직 VLM 단계</b>에서만 일어나고 그 단계는 배치가 돌아야 도달하므로, skip 된 마킹은 아무도
     * 전이시키지 않는 <b>영구 고아</b>가 된다. 활성 마킹은 후속 마킹을 409 로 막으므로(=그 영상은 다시는
     * 마킹할 수 없음) 종결시켜 활성 집합에서 빼야 한다.
     *
     * <p>{@code VLM_FAILED} 를 재사용하지 않는 이유: 그 상태는 "VLM 위탁이 실패했다"는 뜻이라 운영자·후속
     * 복구 로직이 재위탁 대상으로 오독한다. skip 은 <b>위탁된 적이 없는</b> 마킹이므로 별도 코드로 구분한다.
     */
    public static final String STATUS_SKIPPED = "SKIPPED";

    /**
     * <b>활성(미종결) 마킹</b> 상태 집합 — 영상당 1건만 존재할 수 있다 (B-ISSUE-22).
     *
     * <h3>"활성" 의 정의와 근거</h3>
     * <p>위 상태 머신에서 {@code PENDING}(위탁 대기)과 {@code VLM_REQUESTED}(위탁 진행 중)는
     * <b>VLM 위탁 사이클이 끝나지 않은</b> 상태다. 배치는 영상당 <b>최신 마킹 1건</b>만 위탁하므로
     * (MarkingLoadStep/VlmTimeseriesStep), 이 구간에 마킹이 2건 이상 쌓이면 나머지는 영원히 위탁되지
     * 않는 <b>고아 행</b>이 된다 — 실측 결함의 형태 그대로다. 따라서 이 두 상태에 한해 1건으로 수렴시킨다.
     *
     * <p>반대로 {@code VLM_COMPLETED}/{@code VLM_FAILED}/{@code SKIPPED} 는 <b>종결</b> 상태라 활성에서 제외한다.
     * 종결 마킹만 남은 영상의 재마킹은 새 배치 사이클을 여는 정당한 시나리오이며(예: VLM 실패 후
     * 재마킹, VLM 은 성공했으나 후속 단계에서 실패해 배치 단계가 여전히 {@code MARKING_READY} 인 영상),
     * 이때 생성되는 새 마킹은 최신 행이라 실제로 위탁된다(고아가 아니다). 이 재마킹 동선을 막지 않기
     * 위해 "모든 마킹 1건" 이 아니라 "미종결 마킹 1건" 으로 정의한다.
     */
    public static final List<String> ACTIVE_STATUSES = List.of(STATUS_PENDING, STATUS_VLM_REQUESTED);

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

    /**
     * 배치 트리거 skip 종결 전이 — {@code PENDING} 에서만 {@link #STATUS_SKIPPED} 로 전이한다 (B-ISSUE-41).
     *
     * <p>{@code PENDING} 한정 가드가 핵심이다. 이미 {@code VLM_REQUESTED}(위탁 진행 중) 이거나 종결된
     * 마킹을 skip 으로 덮으면 <b>진행 중인 VLM 사이클을 지워버리거나 종결 사실을 역행</b>시킨다.
     * 브리지 skip 분기는 "방금 커밋된 이 마킹"만 대상으로 하므로 {@code PENDING} 이 정상이며,
     * 그렇지 않은 값이면 <b>no-op</b> 으로 아무것도 하지 않는다.
     *
     * @return 실제로 전이가 발생하면 {@code true}, no-op 이면 {@code false}
     */
    public boolean markSkipped() {
        if (!STATUS_PENDING.equals(this.sttsCd)) {
            return false;
        }
        this.sttsCd = STATUS_SKIPPED;
        this.mdfcnDt = LocalDateTime.now();
        return true;
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
