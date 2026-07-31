package kr.co.cudo.authoring.augment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 증강 파생영상 <b>폐기 원장</b> (LS_DATA_AUG_DSCD, V150) — Phase 7.
 *
 * <h2>수명 주기</h2>
 * <pre>
 *   반려(REVIEWER)  → 행 생성: DSCD_DT(표식·유예 기산점) + DSCD_RSN(반려 사유 스냅샷)
 *   유예 내 복구    → RSTR_DT/RSTR_RSN/MDFCN_ID 기록 후 <b>닫힘</b> (실삭제 대상에서 영구 제외)
 *   유예 경과       → DEL_PRCS_DT(원자 클레임) → DB 삭제 커밋 시 DEL_DT → 파일 정리 시 FILE_DEL_DT
 * </pre>
 *
 * <h2>이 행은 실삭제 이후에도 남는다(비석)</h2>
 * <p>삭제 대상인 {@code LS_DATA_AUG}·{@code LS_DATA_RAW} 행은 사라지므로, "무엇을 언제 왜 지웠는가"와
 * "커밋 후 파일 삭제를 재시도할 경로 단서"가 이 행에만 남는다. 그래서 FK 를 걸지 않는다(V150 주석).
 *
 * <h2>불변식</h2>
 * <ul>
 *   <li>{@link #newRawSn} 은 <b>파생 영상</b>이어야 한다 — 원본 영상에는 표식을 찍을 수 없다(C2).
 *       판정은 호출부({@code AugmentDiscardService})가 {@code LsDataRaw.isDerivative()} 로 수행하고,
 *       여기서는 표식 생성 자체가 그 판정을 통과한 뒤에만 일어난다.</li>
 *   <li>이미 닫힌(복구·삭제된) 행은 다시 클레임/삭제되지 않는다({@link #isOpen()}).</li>
 * </ul>
 */
@Entity
@Table(name = "LS_DATA_AUG_DSCD")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataAugDscd {

    /** 사유 컬럼 상한(표준도메인 내용V4000)에 맞춘 저장 전 절단 길이. */
    private static final int REASON_MAX = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_AUG_DSCD_SN")
    private Long dataAugDscdSn;

    @Column(name = "DATA_AUG_SN", nullable = false)
    private Long dataAugSn;

    /** 폐기 대상 <b>파생</b> 영상. NOT NULL — 매핑 없는 그랜드퍼더링 증강은 행 자체를 만들지 않는다. */
    @Column(name = "NEW_RAW_SN", nullable = false)
    private Long newRawSn;

    /** 파생의 부모(원본) 영상 — 감사 + 파생 비디오 경로 검증 단서. */
    @Column(name = "ORGNL_RAW_SN")
    private Long orgnlRawSn;

    @Column(name = "DSCD_DT", nullable = false)
    private LocalDateTime dscdDt;

    @Column(name = "DSCD_RSN", length = REASON_MAX)
    private String dscdRsn;

    @Column(name = "RSTR_DT")
    private LocalDateTime rstrDt;

    @Column(name = "RSTR_RSN", length = REASON_MAX)
    private String rstrRsn;

    @Column(name = "DEL_PRCS_DT")
    private LocalDateTime delPrcsDt;

    @Column(name = "DEL_DT")
    private LocalDateTime delDt;

    @Column(name = "FILE_DEL_DT")
    private LocalDateTime fileDelDt;

    @Column(name = "VDO_FILE_PATH", length = 1000)
    private String vdoFilePath;

    /**
     * 파일 정리 재시도 횟수 (V151) — 상한({@code file-cleanup-max-attempts})을 넘으면
     * {@link #fileDelFailDt} 가 찍혀 재시도 큐에서 빠진다.
     */
    @Column(name = "FILE_DEL_RTRY_NMTM", nullable = false)
    private int fileDelRtryNmtm;

    /**
     * 파일 정리 <b>포기(데드레터)</b> 시각 (V151) — 값이 있으면 자동 재시도 대상이 아니다(사람이 확인해
     * 수동 정리). {@link #fileDelDt}(정상 완료)와 배타적이며, 둘 다 없는 동안만 재시도 큐에 남는다.
     */
    @Column(name = "FILE_DEL_FAIL_DT")
    private LocalDateTime fileDelFailDt;

    /** 파일 정리 포기 사유 (V151) — 경로 원문은 담지 않는다(CWE-209/359). */
    @Column(name = "FILE_DEL_FAIL_RSN", length = REASON_MAX)
    private String fileDelFailRsn;

    /**
     * 폐기 당시 증강 종류 스냅샷 (V151) — {@code LS_DATA_AUG} 행은 실삭제로 사라진다.
     */
    @Column(name = "AUG_TYPE_CD", length = 20)
    private String augTypeCd;

    /**
     * 폐기 당시 생성 조건(프롬프트) 스냅샷 (V151).
     *
     * <p>중복 증강 요청이 허용된 뒤로 <b>같은 (영상 × 종류) 파생이 여러 건 공존</b>하며, 그것들을 구분하는
     * 유일한 축이 {@code PROMPT_CN} 이다(구속 정책). 증강 행이 사라진 뒤 비석에 이 값이 없으면 "무슨
     * 조건으로 만든 것을 왜 버렸는가" 의 앞 절반이 사라져 감사가 끊긴다.
     */
    @Column(name = "PROMPT_CN", length = REASON_MAX)
    private String promptCn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    /**
     * 폐기 표식 생성 — 반려 트랜잭션 안에서만 호출한다.
     *
     * @param dataAugSn  폐기 대상 증강 요청
     * @param newRawSn   그 요청이 만든 <b>파생</b> 영상 (null 금지 — 그랜드퍼더링은 호출부가 걸러낸다)
     * @param orgnlRawSn 파생의 부모 영상
     * @param reason     반려 사유(복구 시 검수행에서 지워지므로 여기 스냅샷)
     * @param actorId    표식을 찍은 REVIEWER
     * @param augTypeCd  증강 종류 스냅샷(증강 행이 실삭제로 사라지므로 여기 보존)
     * @param promptCn   생성 조건(프롬프트) 스냅샷 — 같은 (영상 × 종류) 파생을 구분하는 유일한 축
     */
    public static LsDataAugDscd mark(Long dataAugSn, Long newRawSn, Long orgnlRawSn,
                                     String reason, String actorId, LocalDateTime at,
                                     String augTypeCd, String promptCn) {
        if (dataAugSn == null || newRawSn == null) {
            throw new CustomException(ErrorCode.CONFLICT, "폐기 대상 식별자가 없습니다.");
        }
        LsDataAugDscd row = new LsDataAugDscd();
        row.dataAugSn = dataAugSn;
        row.newRawSn = newRawSn;
        row.orgnlRawSn = orgnlRawSn;
        row.dscdDt = at;
        row.dscdRsn = truncate(reason);
        row.regId = actorId;
        row.regDt = at;
        row.augTypeCd = augTypeCd;
        row.promptCn = truncate(promptCn);
        return row;
    }

    /** 열린 표식인가 = 복구되지도 삭제되지도 않았다. */
    public boolean isOpen() {
        return rstrDt == null && delDt == null;
    }

    /** 이 표식으로 실삭제를 집행할 수 있는가 — 열려 있고, 클레임을 이미 획득했다. */
    public boolean isClaimed() {
        return isOpen() && delPrcsDt != null;
    }

    /**
     * 유예 내 복구 — 폐기를 되돌린다.
     *
     * <p>되돌린 이력(누가={@code mdfcnId} · 언제={@code rstrDt} · 왜={@code rstrRsn})을 이 행에 남긴다.
     * 검수 행은 재결정을 위해 PENDING 으로 되돌아가며 반려 사유가 지워지므로, 원래 사유는
     * {@link #dscdRsn} 스냅샷으로만 남는다.
     */
    public void restore(String actorId, String reason, LocalDateTime at) {
        if (delDt != null) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 삭제된 파생영상은 복구할 수 없습니다.");
        }
        if (rstrDt != null) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 복구된 폐기 표식입니다.");
        }
        this.rstrDt = at;
        this.rstrRsn = truncate(reason);
        this.mdfcnId = actorId;
        this.mdfcnDt = at;
    }

    /**
     * 실삭제 직전 파생 비디오 경로를 비석에 기록한다 (H5).
     *
     * <p>RAW 행이 사라지면 경로를 재구성할 단서가 없으므로, 커밋 전에 반드시 남긴다. 프레임 디렉터리는
     * {@code frames/deid/{newRawSn}} 규약으로 rawSn 에서 결정되므로 별도 기록이 필요 없다.
     */
    public void recordVideoPath(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        this.vdoFilePath = path.length() > 1000 ? path.substring(0, 1000) : path;
    }

    /** DB 행 삭제 커밋 시각 기록 — 이후 이 행은 파일 정리 재시도 축의 후보가 된다. */
    public void markDbDeleted(String actorId, LocalDateTime at) {
        this.delDt = at;
        this.mdfcnId = actorId;
        this.mdfcnDt = at;
    }

    /** 생성 파일까지 정리 완료. 부분 실패 시 호출하지 않아 다음 tick 이 재시도한다(멱등). */
    public void markFilesDeleted(LocalDateTime at) {
        this.fileDelDt = at;
        this.mdfcnDt = at;
    }

    /**
     * 파일 정리 <b>시도 실패</b> 기록 — 재시도 횟수를 1 올린다.
     *
     * @return 올린 뒤의 누적 시도 횟수
     */
    public int recordFileCleanupAttempt(LocalDateTime at) {
        this.fileDelRtryNmtm = this.fileDelRtryNmtm + 1;
        this.mdfcnDt = at;
        return this.fileDelRtryNmtm;
    }

    /**
     * 파일 정리 <b>포기(데드레터)</b> — 이 비석은 자동 재시도 큐에서 빠지고 사람이 수동 정리한다.
     *
     * <p>포기하지 않으면 "재시도해도 결과가 같은" 비석(심링크 잔존 등)이 오래된 순 배치의 앞자리를
     * 영구 점유해 이후 비석의 파일 정리를 전면 정지시킨다(head-of-line blocking).
     *
     * @param reason 사유 — 경로 원문을 담지 않는다(CWE-209/359)
     */
    public void markFileCleanupAbandoned(String reason, LocalDateTime at) {
        if (this.fileDelFailDt != null) {
            return; // 이미 종결 — 사유·시각을 덮어쓰지 않는다(최초 판정 보존).
        }
        this.fileDelFailDt = at;
        this.fileDelFailRsn = truncate(reason);
        this.mdfcnDt = at;
    }

    /** 파일 정리가 자동 수렴에 실패해 사람 개입 대기 상태인가. */
    public boolean isFileCleanupAbandoned() {
        return fileDelFailDt != null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > REASON_MAX ? value.substring(0, REASON_MAX) : value;
    }
}
