package kr.co.cudo.authoring.version.entity;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "LS_LABEL_VERSION")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsLabelVersion {

    public static final String ACTIVE_YES = "Y";
    public static final String ACTIVE_NO = "N";

    // SAVE_REASON_CD 표준 코드 (LS_LABEL_VERSION 정착).
    // 버전 스냅샷은 검수 승인(APPROVED) 시점에만 생성된다 (학습데이터 버전관리 단위 = 검수 완료).
    // 라벨 저장(임시저장) 단계에서는 스냅샷을 만들지 않는다 — MANUAL 자동 커밋 폐기.
    public static final String SAVE_REASON_APPROVED = "APPROVED";
    public static final String SAVE_REASON_ROLLBACK = "ROLLBACK";
    public static final String SAVE_REASON_BATCH = "BATCH";
    /**
     * 비식별 누락 신고로 영상 전체 라벨을 삭제하기 직전에 남기는 복원용 스냅샷(R1 v1.14).
     * <p>ACTIVE_YN='N' 로 적재되며 diff/rollback(APPROVED 대상)에는 간섭하지 않는다.
     */
    public static final String SAVE_REASON_DEIDENT_REPORT = "DEIDENT_REPORT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "LBL_VERSION_SN")
    private Long labelVersionSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "DATA_SRC_SN")
    private Long dataSrcSn;

    /** payload 의 SHA-256(hex) — 같은 프레임 내 동일 스냅샷 식별 (멱등 재커밋). */
    @Column(name = "VERSION_HASH", length = 64)
    private String versionHash;

    /** 라벨 전체 JSON 스냅샷 (LabelResponse 직렬화 결과). diff/rollback 의 원천. */
    @Column(name = "LBL_PAYLOAD", columnDefinition = "TEXT")
    private String labelPayload;

    @Column(name = "VER_NO", nullable = false)
    private int versionNo;

    @Column(name = "SAVE_REASON_CD", length = 20)
    private String saveReasonCd;

    @Column(name = "ACTVTN_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String activeYn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    public static LsLabelVersion create(Long rawSn, Long srcSn, String versionHash, String labelPayload,
                                        int versionNo, String saveReasonCd, String regId) {
        LsLabelVersion version = new LsLabelVersion();
        version.dataRawSn = rawSn;
        version.dataSrcSn = srcSn;
        version.versionHash = versionHash;
        version.labelPayload = labelPayload;
        version.versionNo = versionNo;
        version.saveReasonCd = saveReasonCd;
        version.activeYn = ACTIVE_YES;
        version.regId = regId;
        version.regDt = LocalDateTime.now();
        return version;
    }

    /**
     * 영상(rawSn) 단위 복원용 비활성 스냅샷 생성 (R1 v1.14 — 비식별 신고 시 라벨 전체 삭제 직전 기록).
     * <p>dataSrcSn 은 프레임 종속이 아닌 영상 전체이므로 null, ACTIVE_YN='N' 로 적재한다.
     */
    public static LsLabelVersion createInactiveRawSnapshot(Long rawSn, String versionHash, String labelPayload,
                                                           int versionNo, String saveReasonCd, String regId) {
        LsLabelVersion version = new LsLabelVersion();
        version.dataRawSn = rawSn;
        version.dataSrcSn = null;
        version.versionHash = versionHash;
        version.labelPayload = labelPayload;
        version.versionNo = versionNo;
        version.saveReasonCd = saveReasonCd;
        version.activeYn = ACTIVE_NO;
        version.regId = regId;
        version.regDt = LocalDateTime.now();
        return version;
    }

    public void deactivate() {
        this.activeYn = ACTIVE_NO;
    }

    /**
     * R12-1 — 롤백 시 대상 스냅샷의 해시가 기존 버전과 동일하면 신규 INSERT 대신 기존 행을 active 로 복원한다.
     * (DATA_SRC_SN, VERSION_HASH) UNIQUE 충돌(500) 방지. 식별자/페이로드/versionNo 는 보존하고
     * ACTIVE_YN 만 'Y' 로 전환한다.
     */
    public void activate() {
        this.activeYn = ACTIVE_YES;
    }
}
