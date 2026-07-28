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
    //
    // D-ISSUE-21 — 'ROLLBACK' 코드는 폐기했다. 롤백 결과 페이로드는 대상 스냅샷 그 자체라
    // 재계산 해시가 항상 대상 행과 같고, (DATA_SRC_SN, VERSION_HASH) UNIQUE 때문에 새 행을 적층할 수
    // 없다(도달 불가 분기였음). 롤백은 <b>대상 행 재활성</b>이 정본이며, "누가·언제·어느 버전으로"는
    // LS_DATA_LBL_HSTRY 롤백 이벤트(LsDataLblHstry.recordRollbackEvent)에 기록한다.
    public static final String SAVE_REASON_APPROVED = "APPROVED";
    public static final String SAVE_REASON_BATCH = "BATCH";
    /**
     * <b>레거시 값</b> — 구 정책(비식별 신고 시 라벨 전량 삭제 직전 복원 스냅샷)이 적재했던 사유 코드.
     * <p>D-25(2026-07-27 정책 반전)로 <b>신규 적재는 중단</b>됐다(라벨을 삭제하지 않으므로 스냅샷도
     * 남기지 않는다). 운영 DB 에 남은 기존 행을 식별·판독하기 위한 문서 목적으로만 유지한다 —
     * 이 값을 다시 쓰는 코드를 추가하지 말 것.
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

    // D-25 (2026-07-27 정책 반전) — createInactiveRawSnapshot(영상 단위 DATA_SRC_SN=NULL 비활성
    //   스냅샷) 은 제거됐다. 비식별 신고가 라벨을 삭제하지 않으므로 적재 주체가 사라졌다.
    //   기존 DB 행(SAVE_REASON_CD='DEIDENT_REPORT', DATA_SRC_SN=NULL)은 그대로 보존되며,
    //   VersionService.diff 의 NULL DATA_SRC_SN 가드(D-ISSUE-26)가 그 행들을 안전하게 거부한다.

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
