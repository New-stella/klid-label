package kr.co.cudo.authoring.version.entity;

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
 * LS_DATA_LBL_HSTRY: 기존 라벨 이력 테이블 매핑.
 * 라벨 버전(스냅샷) 은 LS_LABEL_VERSION 에서 관리한다.
 */
@Entity
@Table(name = "LS_DATA_LBL_HSTRY")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataLblHstry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "LBL_HSTRY_SN")
    private Long lblHstrySn;

    @Column(name = "LBL_SN")
    private Long lblSn;

    @Column(name = "SRC_SN", nullable = false)
    private Long srcSn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    /**
     * 변경종류 코드 (ADDED/UPDATED/DELETED) — {@link LabelChangeKind#name()}.
     * <p>V112 신설. 하위호환을 위해 nullable(기존 삭제 이력 row 는 backfill='DELETED').
     */
    @Column(name = "CHG_KIND_CD", length = 20)
    private String chgKindCd;

    /**
     * 등록자(작업자) 식별자 — 감사 추적용. nullable(삭제 이력 경로는 미전달).
     * <p>보안(Privacy, CWE-359): 사용자 ID 수준만 저장하며 토큰/PII 는 저장하지 않는다.
     */
    @Column(name = "REG_ID", length = 30)
    private String regId;

    /**
     * 라벨 변경 이력 기록 — 라벨 저장 경로(bulkUpsert)에서 신규/수정 이력을 남길 때 호출.
     *
     * <p>PK(LBL_HSTRY_SN)는 IDENTITY 자동 생성, REG_DT 는 앱 시각(LocalDateTime.now()).
     * <p>보안(Privacy, CWE-359): regId 는 사용자 ID 수준만 저장(토큰/PII 미저장).
     *
     * @param lblSn 대상 라벨 LS_DATA_LBL.LBL_SN
     * @param srcSn 라벨이 속한 프레임 LS_DATA_SRC.SRC_SN
     * @param kind  변경종류 (ADDED/UPDATED/DELETED)
     * @param regId 작업자 식별자 (nullable)
     */
    public static LsDataLblHstry recordChange(Long lblSn, Long srcSn, LabelChangeKind kind, String regId) {
        LsDataLblHstry h = new LsDataLblHstry();
        h.lblSn = lblSn;
        h.srcSn = srcSn;
        h.regDt = LocalDateTime.now();
        h.chgKindCd = kind != null ? kind.name() : null;
        h.regId = regId;
        return h;
    }

    /**
     * 라벨 삭제 이력 기록 — 비식별 누락 신고 시 영상 전체 라벨 삭제 직전 호출.
     *
     * <p>매핑된 컬럼(LBL_SN / SRC_SN / REG_DT)만 기록한다. PK(LBL_HSTRY_SN)는 IDENTITY 자동 생성.
     * <p>V112 이후: 시그니처 유지(호출부 무수정). 내부에서 CHG_KIND_CD 를 'DELETED' 자동 세팅,
     * REG_ID 는 null(신고 경로는 행위자 토큰 등 PII 를 저장하지 않는 기존 정책 유지).
     * <p>보안(Privacy, CWE-359): 라벨 식별자만 보존하며 신고 사유·행위자 토큰 등 PII 는 저장하지 않는다
     * (사유는 LS_DEIDENT_REPORT, 복원 본문은 LS_LABEL_VERSION 스냅샷이 보존).
     *
     * @param lblSn 삭제 대상 라벨 LS_DATA_LBL.LBL_SN
     * @param srcSn 라벨이 속한 프레임 LS_DATA_SRC.SRC_SN
     */
    public static LsDataLblHstry recordDeletion(Long lblSn, Long srcSn) {
        return recordChange(lblSn, srcSn, LabelChangeKind.DELETED, null);
    }
}
