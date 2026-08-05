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
        LsDataLblHstry h = base(srcSn, regId, changes);
        h.chgDtlCn = LabelHistoryDiffSerializer.serialize(changes);
        return h;
    }

    /**
     * D-ISSUE-21 — 버전 롤백 행위 이력. "누가(REG_ID)·언제(REG_DT)·<b>어느 버전으로</b>" 를 남긴다.
     *
     * <p>롤백은 대상 스냅샷 행을 다시 active 로 전환하므로(적층 없음) LS_LABEL_VERSION 만으로는 행위자·시각이
     * 복원되지 않는다(재활성 행의 REG_ID/REG_DT 는 최초 승인자·승인시각). 그래서 기존 이력 축인 본 테이블에
     * 저장 이벤트로 함께 기록한다 — <b>신규 컬럼/테이블 없이</b> 대상 버전 해시는 diff 페이로드
     * ({@code CHG_DTL_CN}) 봉투에 담는다({@link LabelHistoryDiffSerializer#serializeRollback}).
     *
     * <p>{@link #recordSaveEvent} 와 달리 <b>변경 0건도 기록</b>한다 — 라벨 델타가 없어도 되돌리기 행위 자체는
     * 감사 추적에 남아야 하기 때문이다.
     *
     * @param targetVersionHash 롤백 대상 버전 해시 (필수)
     * @param changes           롤백으로 발생한 라벨 변경 목록 (비어 있을 수 있음)
     */
    public static LsDataLblHstry recordRollbackEvent(Long srcSn, String regId,
                                                     String targetVersionHash, List<LabelChange> changes) {
        if (targetVersionHash == null || targetVersionHash.isBlank()) {
            throw new IllegalArgumentException("롤백 이력은 대상 버전 해시가 필요합니다.");
        }
        List<LabelChange> safe = changes == null ? List.of() : changes;
        LsDataLblHstry h = base(srcSn, regId, safe);
        h.chgDtlCn = LabelHistoryDiffSerializer.serializeRollback(targetVersionHash, safe);
        return h;
    }

    /**
     * DEV_FIX-B(M5) — 개인정보 3필드(익명/가명/개인정보 포함여부) 리셋 <b>행 단위 감사</b> 이력.
     *
     * <p>★ <b>신규 발생 없음 — 과거 행 판독용으로 존치한다 (2026-08-04)</b>: 비식별 누락 신고 시 개인정보
     * 3필드를 NULL 로 되돌리던 동작이 폐기됐으므로({@code DeidentReportService} 5-1 주석) 이 팩토리를
     * 호출하는 프로덕션 경로는 없다. 그러나 <b>이미 적재된 이력 행</b>이 존재하고 이력 조회·필터가 그
     * 행들을 읽어야 하므로 이벤트 타입·팩토리·역직렬화 경로를 <b>그대로 유지</b>한다.
     *
     * <p>구 동작(폐기, 근거 보존): 신고 시 해당 영상 전 프레임의 개인정보 표기를 NULL 로 되돌렸고, 이는
     * PII 표기 변경이므로 "어느 프레임이 어느 신고로 리셋됐는가"가 감사 추적에 남아야 했다(OWASP A09).
     * <b>신규 테이블/컬럼 없이</b> 기존 이력 축에 프레임당 1행으로 남기고, 신고 식별자는 diff 페이로드
     * 봉투({@code CHG_DTL_CN})에 담았다({@link LabelHistoryDiffSerializer#serializePrivacyMetaReset}).
     *
     * <p>라벨 델타는 0건이다(라벨을 건드리지 않는 이벤트) — {@code ADD/MDFCN/DEL_CNT} 는 모두 0 이며,
     * 데이터마트 뷰 {@code V_COMPLETED_LABEL_CHANGE} 는 V139 의 "건수>0" 필터로 이 행을 노출하지 않는다.
     *
     * <p>보안(CWE-359): 지워진 값이나 PII 는 담지 않는다. regId 는 사용자 ID 수준만 저장한다.
     *
     * @param srcSn           리셋 대상 프레임 SRC_SN
     * @param regId           신고자 식별자 (nullable)
     * @param deidentReportSn 리셋을 유발한 비식별 신고 RPRT_SN (필수)
     */
    public static LsDataLblHstry recordPrivacyMetaResetEvent(Long srcSn, String regId, Long deidentReportSn) {
        if (deidentReportSn == null) {
            throw new IllegalArgumentException("개인정보 메타 리셋 이력은 신고 식별자가 필요합니다.");
        }
        LsDataLblHstry h = base(srcSn, regId, List.of());
        h.chgDtlCn = LabelHistoryDiffSerializer.serializePrivacyMetaReset(deidentReportSn);
        return h;
    }

    /** 공통 헤더(프레임/행위자/시각) + 종류별 건수 집계. */
    private static LsDataLblHstry base(Long srcSn, String regId, List<LabelChange> changes) {
        LsDataLblHstry h = new LsDataLblHstry();
        h.srcSn = srcSn;
        h.regId = regId;
        h.regDt = LocalDateTime.now();
        h.addCnt = (int) changes.stream().filter(c -> c.kind() == LabelChangeKind.ADDED).count();
        h.mdfcnCnt = (int) changes.stream().filter(c -> c.kind() == LabelChangeKind.UPDATED).count();
        h.delCnt = (int) changes.stream().filter(c -> c.kind() == LabelChangeKind.DELETED).count();
        return h;
    }
}
