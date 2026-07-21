package kr.co.cudo.authoring.version.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cudo.authoring.version.util.LabelHistoryDiffSerializer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LS_DATA_LBL_HSTRY: 라벨 <b>저장 이벤트</b> 이력 테이블 매핑 (V114 재구조화).
 *
 * <p>구조 변경: "라벨 1건=1행"(구 LBL_SN/CHG_KIND_CD) 에서 <b>"저장 이벤트=1행 + diff 페이로드"</b> 로
 * 전환했다. 한 번의 저장/삭제 행위(프레임 단위)를 1행으로 기록하고, 종류별 건수(ADD/MDFCN/DEL)와
 * 상세 diff(CHG_DTL_CN, {@code List<LabelChange>} JSON) 를 담는다.
 *
 * <p>라벨 버전(스냅샷) 은 LS_LABEL_VERSION 에서 별도 관리한다.
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

    @Column(name = "SRC_SN", nullable = false)
    private Long srcSn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    /**
     * 등록자(작업자) 식별자 — 감사 추적용. nullable(비식별 신고 삭제 경로는 미전달).
     * <p>보안(Privacy, CWE-359): 사용자 ID 수준만 저장하며 토큰/PII 는 저장하지 않는다.
     */
    @Column(name = "REG_ID", length = 30)
    private String regId;

    /** 이 저장 이벤트의 추가(ADDED) 라벨 건수. */
    @Column(name = "ADD_CNT", nullable = false)
    private Integer addCnt;

    /** 이 저장 이벤트의 수정(UPDATED) 라벨 건수. */
    @Column(name = "MDFCN_CNT", nullable = false)
    private Integer mdfcnCnt;

    /** 이 저장 이벤트의 삭제(DELETED) 라벨 건수. */
    @Column(name = "DEL_CNT", nullable = false)
    private Integer delCnt;

    /**
     * 변경 상세(diff) 페이로드 — {@code List<LabelChange>} JSON 직렬화(TEXT).
     * <p>보안(CWE-502): 역직렬화는 {@link LabelHistoryDiffSerializer} 의 고정 명시 타입만 사용한다.
     */
    @Column(name = "CHG_DTL_CN", columnDefinition = "TEXT")
    private String chgDtlCn;

    /**
     * 저장 이벤트 이력 기록 — 라벨 저장/삭제 경로에서 프레임 단위 1건을 남길 때 호출.
     *
     * <p>PK(LBL_HSTRY_SN)는 IDENTITY 자동 생성, REG_DT 는 앱 시각(LocalDateTime.now()).
     * 종류별 건수(ADD/MDFCN/DEL)는 {@code changes} 를 kind 별로 집계하고, CHG_DTL_CN 은 diff 직렬화값이다.
     * <p>무변경 이벤트는 만들지 않는다 — 호출측이 {@code changes} 비었으면 호출을 스킵한다(방어적으로 여기서도 거부).
     * <p>보안(Privacy, CWE-359): regId 는 사용자 ID 수준만 저장(토큰/PII 미저장).
     *
     * @param srcSn   라벨이 속한 프레임 LS_DATA_SRC.SRC_SN
     * @param regId   작업자 식별자 (nullable — 신고 경로)
     * @param changes 이 저장 행위의 라벨 변경 목록 (non-empty)
     */
    public static LsDataLblHstry recordSaveEvent(Long srcSn, String regId, List<LabelChange> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("저장 이벤트 이력은 최소 1건의 변경이 필요합니다(무변경 이벤트 미기록).");
        }
        LsDataLblHstry h = new LsDataLblHstry();
        h.srcSn = srcSn;
        h.regId = regId;
        h.regDt = LocalDateTime.now();
        h.addCnt = (int) changes.stream().filter(c -> c.kind() == LabelChangeKind.ADDED).count();
        h.mdfcnCnt = (int) changes.stream().filter(c -> c.kind() == LabelChangeKind.UPDATED).count();
        h.delCnt = (int) changes.stream().filter(c -> c.kind() == LabelChangeKind.DELETED).count();
        h.chgDtlCn = LabelHistoryDiffSerializer.serialize(changes);
        return h;
    }
}
