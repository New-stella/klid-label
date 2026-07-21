package kr.co.cudo.authoring.batch.retry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 배치 재시도 대기 영속 엔티티 (B2 — {@code LS_BAT_RTY_WTNG}).
 *
 * <p>배치 파이프라인 실패 영상의 재시도 항목을 DB 로 영속화한다. 기존 in-memory 큐는
 * 실패 등록 노드 ≠ 재시도 발화 노드일 때(2노드 Active-Active) 재시도가 유실되던 결함이 있었다.
 *
 * <h3>상태 전이</h3>
 * <pre>
 *   (없음) ──(실패 최초 enqueue)──▶ PENDING
 *   PENDING ──(폴링 노드 claim)──▶ RETRYING
 *   RETRYING ──(재실패, count&lt;max)──▶ PENDING (재시도 예정 += backoff)
 *   RETRYING ──(재실패, count&gt;=max)──▶ EXHAUSTED (소진 — 삭제 대신 이력 보존)
 *   PENDING/RETRYING ──(성공 clear)──▶ (행 삭제)
 * </pre>
 *
 * <p>물리 컬럼명은 사업(program) 표준용어(배치=BAT, 재시도=RTY, 횟수=NMTM, 예정=PRNMNT, 대기=WTNG)를
 * 따른다. 선존 테이블 {@code LS_CONTROL_NOTIFY_FALLBACK} 의 RTRY_* 드리프트는 미러하지 않는다.
 */
@Entity
@Table(name = "LS_BAT_RTY_WTNG")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsBatRtyWtng {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RETRYING = "RETRYING";
    public static final String STATUS_EXHAUSTED = "EXHAUSTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "BAT_RTY_SN")
    private Long batRtySn;

    @Column(name = "RAW_SN", nullable = false, unique = true)
    private Long rawSn;

    @Column(name = "RTY_NMTM", nullable = false)
    private int rtyNmtm;

    @Column(name = "MAX_RTY_NMTM", nullable = false)
    private int maxRtyNmtm;

    @Column(name = "STTS_CD", length = 16, nullable = false)
    private String sttsCd;

    @Column(name = "RTY_PRNMNT_DT")
    private LocalDateTime rtyPrnmntDt;

    @Column(name = "LAST_ERR_MSG_CN", length = 2000)
    private String lastErrMsg;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    /**
     * 최초 실패 항목 적재용 정적 팩토리 — RTY_NMTM=0, STTS_CD=PENDING 상태로 시작한다.
     * (횟수 증가·다음 시각 스케줄은 {@link #incrementAttempt()} / {@link #scheduleNext(long)} 가 담당.)
     */
    public static LsBatRtyWtng create(Long rawSn, int maxRtyNmtm) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 는 필수입니다.");
        }
        LsBatRtyWtng q = new LsBatRtyWtng();
        q.rawSn = rawSn;
        q.rtyNmtm = 0;
        q.maxRtyNmtm = maxRtyNmtm > 0 ? maxRtyNmtm : 3;
        q.sttsCd = STATUS_PENDING;
        LocalDateTime now = LocalDateTime.now();
        q.regDt = now;
        q.mdfcnDt = now;
        return q;
    }

    /** 재시도 횟수 1 증가 후 증가된 시도 번호를 반환한다. */
    public int incrementAttempt() {
        this.rtyNmtm += 1;
        this.mdfcnDt = LocalDateTime.now();
        return this.rtyNmtm;
    }

    /** 다음 재시도 스케줄 — STTS_CD=PENDING + RTY_PRNMNT_DT=now+delaySec. */
    public void scheduleNext(long delaySec) {
        this.sttsCd = STATUS_PENDING;
        this.rtyPrnmntDt = LocalDateTime.now().plusSeconds(Math.max(0L, delaySec));
        this.mdfcnDt = LocalDateTime.now();
    }

    /** 최대 재시도 초과 — 소진(EXHAUSTED) 마킹. 삭제하지 않고 이력을 보존한다. */
    public void markExhausted() {
        this.sttsCd = STATUS_EXHAUSTED;
        this.rtyPrnmntDt = null;
        this.mdfcnDt = LocalDateTime.now();
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.regDt == null) this.regDt = now;
        if (this.mdfcnDt == null) this.mdfcnDt = now;
        if (this.sttsCd == null) this.sttsCd = STATUS_PENDING;
        if (this.maxRtyNmtm <= 0) this.maxRtyNmtm = 3;
    }

    @PreUpdate
    void preUpdate() {
        this.mdfcnDt = LocalDateTime.now();
    }
}
