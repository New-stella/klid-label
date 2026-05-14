package kr.co.cudo.authoring.batch.entity;

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
 * LS_DATA_SRC: 영상에서 추출한 키프레임 (FRAME_EXTRACT 단계 산출).
 *  - rawSn: LS_DATA_RAW FK (객체 참조 대신 ID 참조)
 *  - frameNo: 프레임 인덱스 (0-base)
 *  - filePath: 프레임 이미지 경로 (RAW: storage.raw-path, DEID: storage.deidentified-path 기반)
 *  - deidFilePath: (V1 호환) 동일 프레임의 비식별 결과 경로 — Phase 2 부터는 별도 DEID row 가 정식.
 *  - frmTypeCd: Phase 2 — 'RAW'(원본 영상에서 추출) | 'DEID'(비식별 영상에서 추출).
 *
 * UK: (RAW_SN, FRAME_NO, FRM_TYPE_CD) — 동일 (raw,frame) 에 RAW/DEID 각각 1 row 가능.
 */
@Entity
@Table(name = "LS_DATA_SRC",
        uniqueConstraints = @UniqueConstraint(name = "UK_LS_DATA_SRC_RAW_FRAME_TYPE",
                columnNames = {"RAW_SN", "FRAME_NO", "FRM_TYPE_CD"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataSrc {

    /** Phase 2: 원본 영상에서 추출한 프레임. */
    public static final String FRM_TYPE_RAW = "RAW";
    /** Phase 2: 비식별 영상에서 추출한 프레임. */
    public static final String FRM_TYPE_DEID = "DEID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "SRC_SN")
    private Long srcSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @Column(name = "FRAME_NO", nullable = false)
    private Integer frameNo;

    @Column(name = "FILE_PATH", nullable = false, length = 500)
    private String filePath;

    @Column(name = "DEID_FILE_PATH", length = 500)
    private String deidFilePath;

    @Column(name = "FRM_TYPE_CD", nullable = false, length = 8)
    private String frmTypeCd;

    @Column(name = "CAPTURED_AT")
    private LocalDateTime capturedAt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "UPD_DT")
    private LocalDateTime updDt;

    @Builder
    private LsDataSrc(Long rawSn, Integer frameNo, String filePath, LocalDateTime capturedAt, String frmTypeCd) {
        this.rawSn = rawSn;
        this.frameNo = frameNo;
        this.filePath = filePath;
        this.capturedAt = capturedAt;
        this.frmTypeCd = (frmTypeCd == null || frmTypeCd.isBlank()) ? FRM_TYPE_RAW : frmTypeCd;
        this.regDt = LocalDateTime.now();
    }

    /** 원본(RAW) 프레임 row 생성. */
    public static LsDataSrc create(Long rawSn, int frameNo, String filePath, LocalDateTime capturedAt) {
        return LsDataSrc.builder()
                .rawSn(rawSn)
                .frameNo(frameNo)
                .filePath(filePath)
                .capturedAt(capturedAt)
                .frmTypeCd(FRM_TYPE_RAW)
                .build();
    }

    /** Phase 2: 비식별(DEID) 프레임 row 생성 — 영상 2벌 보관 정책. */
    public static LsDataSrc createDeid(Long rawSn, int frameNo, String filePath, LocalDateTime capturedAt) {
        return LsDataSrc.builder()
                .rawSn(rawSn)
                .frameNo(frameNo)
                .filePath(filePath)
                .capturedAt(capturedAt)
                .frmTypeCd(FRM_TYPE_DEID)
                .build();
    }

    /**
     * (V1 호환) 동일 row 의 deidFilePath 컬럼에 비식별 결과 경로를 attach.
     * Phase 2 부터는 별도 DEID row 가 정식이지만, 기존 비식별 단계의 attachDeidPath 흐름을 보존.
     */
    public void attachDeidPath(String deidFilePath) {
        this.deidFilePath = deidFilePath;
        this.updDt = LocalDateTime.now();
    }
}
