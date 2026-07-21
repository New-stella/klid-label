package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 해상도 변경(다운스케일) 산출 추적 레코드 — Phase 1 (RQ-SFR-06-03 v1.8/1.10).
 *
 * <p>검수 완료(APPROVED) 원시 영상의 프레임 이미지셋을 표준 하위 해상도로 다운스케일한 결과
 * 1건당 1행을 기록한다. 영상(비디오) 파일·라벨/메타/프레임 행 복사는 하지 않으며, 신규
 * LS_DATA_RAW 행도 만들지 않는다. 동일 영상+해상도 중복 산출은 UNIQUE(DATA_RAW_SN, GOAL_RESL_CD)
 * 로 방어한다.
 */
@Entity
@Table(name = "LS_RESOLUTION_EXPORT",
        uniqueConstraints = @UniqueConstraint(
                name = "UK_LS_RESL_EXPORT_RAW_RESL",
                columnNames = {"DATA_RAW_SN", "GOAL_RESL_CD"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsResolutionExport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RESL_EXPORT_SN")
    private Long resExportSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "GOAL_RESL_CD", nullable = false, length = 16)
    private String targetResCd;

    @Column(name = "ORGNL_W", nullable = false)
    private Integer orgnlW;

    @Column(name = "ORGNL_H", nullable = false)
    private Integer orgnlH;

    @Column(name = "TARGET_W", nullable = false)
    private Integer targetW;

    @Column(name = "TARGET_H", nullable = false)
    private Integer targetH;

    @Column(name = "FRAME_CNT", nullable = false)
    private Integer frameCnt;

    @Column(name = "OUTPUT_DIR_PATH", nullable = false, length = 500)
    private String outputDirPath;

    /**
     * 파생영상 RAW_SN (LS_DATA_RAW.RAW_SN) — Phase 2. Phase 1(다운스케일 이미지셋만)은 새 RAW 를
     * 만들지 않아 null. Phase 2 파생영상은 새 RAW 를 만들고 예약 시점 이후 {@link #attachNewRawSn} 로 연결한다.
     */
    @Column(name = "NEW_RAW_SN")
    private Long newRawSn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Builder
    private LsResolutionExport(Long dataRawSn, String targetResCd, int orgnlW, int orgnlH,
                               int targetW, int targetH, int frameCnt, String outputDirPath, String regId) {
        this.dataRawSn = dataRawSn;
        this.targetResCd = targetResCd;
        this.orgnlW = orgnlW;
        this.orgnlH = orgnlH;
        this.targetW = targetW;
        this.targetH = targetH;
        this.frameCnt = frameCnt;
        this.outputDirPath = outputDirPath;
        this.regId = regId;
        this.regDt = LocalDateTime.now();
    }

    /**
     * Phase 2 — 파생영상 산출 <b>예약(reservation)</b> 행. 새 RAW/파일을 만들기 전에 먼저 INSERT 하여
     * UNIQUE(DATA_RAW_SN, GOAL_RESL_CD) 로 동일 (원본, 해상도) 동시요청 TOCTOU 를 차단한다. FRAME_CNT 는
     * 예약 시점 0 으로 두고, 파생 확정({@link #updateFrameCnt}) 시 실제 프레임 수로 갱신한다.
     */
    public static LsResolutionExport createReservation(Long dataRawSn, String targetResCd, int orgnlW, int orgnlH,
                                                       int targetW, int targetH, String outputDirPath, String regId) {
        return LsResolutionExport.builder()
                .dataRawSn(dataRawSn)
                .targetResCd(targetResCd)
                .orgnlW(orgnlW)
                .orgnlH(orgnlH)
                .targetW(targetW)
                .targetH(targetH)
                .frameCnt(0)
                .outputDirPath(outputDirPath)
                .regId(regId)
                .build();
    }

    /** 파생영상 RAW_SN 을 연결한다(예약 후 새 RAW 생성 시점). */
    public void attachNewRawSn(Long newRawSn) {
        this.newRawSn = newRawSn;
    }

    /** 파생 확정 시 실제 리스케일된 프레임 개수를 반영한다(예약 시 0 → 확정 시 실측). */
    public void updateFrameCnt(int frameCnt) {
        this.frameCnt = frameCnt;
    }
}
