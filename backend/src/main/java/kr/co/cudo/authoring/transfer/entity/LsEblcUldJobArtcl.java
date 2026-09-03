package kr.co.cudo.authoring.transfer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/**
 * 일괄업로드작업항목 ({@code LS_EBLC_ULD_JOB_ARTCL}) — 마킹 문서 한 건이 항목 한 건이다.
 *
 * <h3>항목마다 따로 트랜잭션을 갖는다</h3>
 * <p>백 건을 한 트랜잭션에 묶으면 한 건 때문에 전부 되돌아간다(SEQ-030). 그래서 상태·사유·결과가
 * <b>항목 단위</b>로 남고, 한 건이 실패해도 나머지는 이어진다.
 *
 * <h3>건너뜀과 실패를 구분한다</h3>
 * <p>{@link #ARTCL_STTS_SKIPPED} 는 짝을 찾지 못했거나 적재할 수 없는 상태여서 <b>처리하지 않은</b>
 * 것이고, {@link #ARTCL_STTS_FAILED} 는 처리하다 멈춘 것이다. 둘을 합치면 「이미 들어와 있어서
 * 넘어감」이 「적재 실패」로 집계되어 사람이 무엇을 고쳐야 하는지 알 수 없게 된다.
 *
 * <h3>영상이 지워져도 이 행은 남는다</h3>
 * <p>{@code RAW_SN} FK 가 {@code ON DELETE SET NULL} 인 것은 이 표가 영상에 종속된 데이터가 아니라
 * <b>작업 이력</b>이기 때문이다. 함께 지우면 무엇이 왜 실패했는지가 감사 기록째 사라지고, 부모의
 * 성공·실패 건수만 남아 진행률의 분자와 분모가 어긋난다.
 *
 * @design DOMAIN-017
 * @design ERD-032
 * @design API-218
 * @design AC-1033
 */
@Entity
@Table(name = "LS_EBLC_ULD_JOB_ARTCL")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsEblcUldJobArtcl {

    /** 항목상태 — 대기. 아직 아무도 집지 않았다. */
    public static final String ARTCL_STTS_PENDING = "PENDING";

    /** 항목상태 — 처리중. 한 노드가 집어 갔다. */
    public static final String ARTCL_STTS_PROCESSING = "PROCESSING";

    /** 항목상태 — 성공. */
    public static final String ARTCL_STTS_SUCCESS = "SUCCESS";

    /** 항목상태 — 실패. 처리하다 멈췄다. */
    public static final String ARTCL_STTS_FAILED = "FAILED";

    /** 항목상태 — 건너뜀. 짝을 찾지 못했거나 적재할 수 없는 상태여서 처리하지 않았다. */
    public static final String ARTCL_STTS_SKIPPED = "SKIPPED";

    /** {@code MARK_FILE_PATH_NM}·{@code VDO_FILE_PATH_NM} 컬럼 폭 — 표준 파일경로명 도메인 명V300. */
    public static final int FILE_PATH_NM_MAX = 300;

    /**
     * {@code FAIL_RSN} 컬럼 폭 — 실패사유 표준 도메인(내용V4000)을 따른다.
     *
     * <p>이 값은 마이그레이션의 컬럼 폭 · 이 매핑 · {@link #truncateReason} 의 자르는 자리
     * <b>셋이 반드시 같아야</b> 한다. 갈리면 기동 시 매핑 검증이 깨지거나, 잘리는 지점이 DB 와 달라
     * 저장 시점에 알 수 없는 오류가 난다.
     *
     * <p>넘는 사유는 <b>잘라 담는다</b> — 길이 초과로 기록 자체를 잃는 것이 사유가 잘리는 것보다 나쁘다.
     */
    public static final int FAIL_RSN_MAX = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "EBLC_ULD_JOB_ARTCL_SN")
    private Long eblcUldJobArtclSn;

    /** 소속 일괄업로드작업일련번호. */
    @Column(name = "EBLC_ULD_JOB_SN", nullable = false)
    private Long eblcUldJobSn;

    /** 마킹 문서의 위치. 같은 작업 안에서 유일하다({@code UK_LEUJA_JOB_MARK}). */
    @Column(name = "MARK_FILE_PATH_NM", nullable = false, length = FILE_PATH_NM_MAX)
    private String markFilePathNm;

    /**
     * 짝지은 영상 파일의 위치. 짝을 찾지 못하면 비어 있다.
     *
     * <p>마킹 문서가 함께 담은 원래 경로는 다른 체계에서 만들어진 값이라 위치로 쓰지 않는다 —
     * 짝짓기는 문서가 적어 둔 <b>파일 이름</b>으로 한다.
     */
    @Column(name = "VDO_FILE_PATH_NM", length = FILE_PATH_NM_MAX)
    private String vdoFilePathNm;

    @Column(name = "ARTCL_STTS_CD", nullable = false, length = 20)
    private String artclSttsCd;

    /** 적재에 성공해 만들어진 영상의 일련번호. 성공하기 전에는 비어 있다. */
    @Column(name = "RAW_SN")
    private Long rawSn;

    /** 실패했거나 건너뛴 사유 — 사람이 읽고 무엇을 고쳐야 하는지 알 수 있는 문장이며 내부 구조를 드러내지 않는다. */
    @Column(name = "FAIL_RSN", length = FAIL_RSN_MAX)
    private String failRsn;

    /**
     * 재시도횟수 — 처리 중이던 항목을 다시 집을 수 있게 되돌린 횟수.
     *
     * <p>정해진 횟수를 넘게 집힌 항목은 실패로 마감해 <b>영원히 맴돌지 않게</b> 한다(AC-1033).
     */
    @Column(name = "RTRY_NMTM", nullable = false)
    private Integer rtryNmtm;

    @Column(name = "BGNG_DT")
    private LocalDateTime bgngDt;

    @Column(name = "CMPTN_DT")
    private LocalDateTime cmptnDt;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    private LsEblcUldJobArtcl(Long eblcUldJobSn, String markFilePathNm, String vdoFilePathNm) {
        this.eblcUldJobSn = eblcUldJobSn;
        this.markFilePathNm = requireWithin(markFilePathNm, FILE_PATH_NM_MAX, "마킹문서경로명");
        this.vdoFilePathNm = requireWithinOrNull(vdoFilePathNm, FILE_PATH_NM_MAX, "영상파일경로명");
        this.artclSttsCd = ARTCL_STTS_PENDING;
        this.rtryNmtm = 0;
        this.regDt = LocalDateTime.now();
        this.mdfcnDt = this.regDt;
    }

    /**
     * 처리할 항목을 만든다 — 대기 상태로 눕혀 두고, 실제 처리는 일꾼이 원장에서 집어 간다.
     *
     * <p>영상 경로를 <b>만들 때 함께 적어 둔다</b>. 처리 시점에 폴더를 다시 훑어 찾으면, 훑기와 처리
     * 사이에 폴더가 바뀌었을 때 사람이 확인한 짝과 실제로 적재되는 짝이 달라진다.
     */
    public static LsEblcUldJobArtcl pending(Long eblcUldJobSn, String markFilePathNm, String vdoFilePathNm) {
        return new LsEblcUldJobArtcl(eblcUldJobSn, markFilePathNm, vdoFilePathNm);
    }

    /** 이 항목이 끝났는가(성공·실패·건너뜀 어느 쪽이든). */
    public boolean isFinished() {
        return ARTCL_STTS_SUCCESS.equals(this.artclSttsCd)
                || ARTCL_STTS_FAILED.equals(this.artclSttsCd)
                || ARTCL_STTS_SKIPPED.equals(this.artclSttsCd);
    }

    /** 사유를 컬럼 폭에 맞춰 자른다 — 길이 초과로 <b>기록 자체가 사라지는</b> 것이 더 나쁘다. */
    public static String truncateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.length() <= FAIL_RSN_MAX ? reason : reason.substring(0, FAIL_RSN_MAX);
    }

    private static String requireWithin(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "는 필수입니다.");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(label + "가 허용 길이를 넘습니다.");
        }
        return value;
    }

    /**
     * 있으면 폭을 확인하고 없으면 비운다 — <b>잘라 담지 않는다</b>. 잘린 경로는 존재하지 않는 자리를
     * 가리키므로, 그 값으로 파일을 열면 짝을 찾지 못한 것과 구분되지 않는 실패가 된다.
     */
    private static String requireWithinOrNull(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(label + "가 허용 길이를 넘습니다.");
        }
        return value;
    }
}
