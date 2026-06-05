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

    @Column(name = "REGISTERED_AT", nullable = false)
    private LocalDateTime registeredAt;

    /**
     * 라벨 삭제 이력 기록 — 비식별 누락 신고 시 영상 전체 라벨 삭제 직전 호출.
     *
     * <p>매핑된 컬럼(LBL_SN / SRC_SN / REGISTERED_AT)만 기록한다. PK(LBL_HSTRY_SN)는 IDENTITY 자동 생성.
     * <p>보안(Privacy, CWE-359): 라벨 식별자만 보존하며 신고 사유·행위자 토큰 등 PII 는 저장하지 않는다
     * (사유는 LS_DEIDENT_REPORT, 복원 본문은 LS_LABEL_VERSION 스냅샷이 보존).
     *
     * @param lblSn 삭제 대상 라벨 LS_DATA_LBL.LBL_SN
     * @param srcSn 라벨이 속한 프레임 LS_DATA_SRC.SRC_SN
     */
    public static LsDataLblHstry recordDeletion(Long lblSn, Long srcSn) {
        LsDataLblHstry h = new LsDataLblHstry();
        h.lblSn = lblSn;
        h.srcSn = srcSn;
        h.registeredAt = LocalDateTime.now();
        return h;
    }
}
