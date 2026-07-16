package kr.co.cudo.authoring.dataset.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 학습데이터 파일 산출(export) 추적 원장 — {@code LS_DATASET_EXPORT}.
 *
 * <p>검수 승인 시 라벨링 산출물을 {@code {labeling_root}/{RAW_SN}/v{n}/{orgnl|deid}/} 로 저장하는
 * 기능(Phase 1 인프라)의 추적 엔티티. 영상(dataRawSn) 단위로 export 를 누적 기록하며,
 * 그 건수(+1)로 다음 산출 버전을 도출한다.
 *
 * <p>상태(EXPORT_STTS_CD)는 {@code PENDING → SUCCEEDED|FAILED|PARTIAL} 로 전이한다.
 * 상태 변경은 {@code @Setter} 가 아닌 의미 있는 비즈니스 메서드로만 수행한다.
 */
@Entity
@Table(name = "LS_DATASET_EXPORT")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDatasetExport {

    /** 산출 대기(레코드 생성 직후 초기 상태). */
    public static final String STATUS_PENDING = "PENDING";
    /** 산출 완료. */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    /** 산출 실패. */
    public static final String STATUS_FAILED = "FAILED";
    /** 일부 산출(원본/비식별 중 일부만 성공 등). */
    public static final String STATUS_PARTIAL = "PARTIAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "EXPORT_SN")
    private Long exportSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "EXPORT_VER_NO", nullable = false)
    private int exportVerNo;

    @Column(name = "EXPORT_PATH_NM", length = 500)
    private String exportPathNm;

    @Column(name = "EXPORT_STTS_CD", nullable = false, length = 20)
    private String exportSttsCd;

    @Column(name = "FRAME_CNT")
    private Integer frameCnt;

    /**
     * 산출 시점 라벨 상태의 콘텐츠 해시(SHA-256 hex). 무수정 재승인 멱등 판정 키 —
     * 직전 SUCCEEDED/PARTIAL(멱등 baseline) export 의 해시와 같으면 Phase 4 오케스트레이션이 재산출을 skip 한다.
     * Phase 1/3 경로(3-arg create)에서는 null 로 남을 수 있다(하위호환).
     */
    @Column(name = "CONTENT_HASH", length = 64)
    private String contentHash;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    /**
     * 산출 레코드 생성 — 상태는 {@code PENDING} 으로 시작한다(파일 쓰기 전 예약).
     *
     * @param rawSn   대상 영상 ID(LS_DATA_RAW.RAW_SN)
     * @param verNo   산출 버전 순번(≥1, 기존 export 건수 + 1로 도출)
     * @param path    산출 루트 경로({@code {labeling_root}/{RAW_SN}/v{n}})
     */
    public static LsDatasetExport create(Long rawSn, int verNo, String path) {
        return create(rawSn, verNo, path, null);
    }

    /**
     * 산출 레코드 생성(콘텐츠 해시 포함) — Phase 4 승인 오케스트레이션 전용.
     *
     * @param contentHash 산출 시점 라벨 상태 해시(무수정 재승인 멱등 판정 키)
     */
    public static LsDatasetExport create(Long rawSn, int verNo, String path, String contentHash) {
        LsDatasetExport export = new LsDatasetExport();
        export.dataRawSn = rawSn;
        export.exportVerNo = verNo;
        export.exportPathNm = path;
        export.exportSttsCd = STATUS_PENDING;
        export.contentHash = contentHash;
        export.regDt = LocalDateTime.now();
        return export;
    }

    /** 산출 성공 전이 — 상태를 {@code SUCCEEDED} 로 바꾸고 산출 프레임 수를 반영한다. */
    public void markSucceeded(int frameCnt) {
        this.exportSttsCd = STATUS_SUCCEEDED;
        this.frameCnt = frameCnt;
    }

    /** 일부 산출 전이 — 상태를 {@code PARTIAL} 로 바꾸고 정상 기록된 프레임 수를 반영한다. */
    public void markPartial(int frameCnt) {
        this.exportSttsCd = STATUS_PARTIAL;
        this.frameCnt = frameCnt;
    }

    /** 산출 실패 전이 — 상태를 {@code FAILED} 로 바꾼다. */
    public void markFailed() {
        this.exportSttsCd = STATUS_FAILED;
    }
}
