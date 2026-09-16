package kr.co.cudo.authoring.auth.entity;

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
import java.util.UUID;

@Entity
@Table(name = "LS_AUTH_WORK_LOCK")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsAuthWorkLock {

    public static final String TARGET_RAW = "RAW";
    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final String REASON_REDEIDENT = "REDEIDENT";
    /** Phase 4 트랙 병합 배타 락 사유. */
    public static final String REASON_MERGE = "MERGE";

    /**
     * 선두 비식별 실패 영상의 배치 재시작이 잡은 락을 가리는 식별자 접두. [@design AC-1135]
     *
     * <p>같은 {@code TARGET_RAW} 락을 트랙 병합·검수완료 재비식별도 쓰는데, 이 엔티티에는 락 종류를 담는
     * 컬럼이 없다(생성 팩토리의 사유 인자는 저장되지 않는다). 스키마를 바꾸지 않고 종류를 가리기 위해
     * 락 식별자({@code LCK_ID}, 유일·64자) 앞에 이 접두를 붙인다 — 해제는 이 접두를 가진 락에만 한정된다.
     * 접두 10자 + UUID 36자 = 46자로 컬럼 폭 안이다.
     */
    public static final String LOCK_ID_PREFIX_DEIDENT_RETRY = "DEIDRETRY-";

    /**
     * 선두 비식별 재시작 락 만료 — 외부 위탁의 폴링 시한(기본 180분)보다 길게 둔다. 만료 회수는 안전망일 뿐이며
     * 정상 해제는 재수행의 종결 지점이 한다. 만료로 회수돼도 진행 중 위탁 원장 검사가 이중 수락을 막는다.
     */
    static final long DEIDENT_RETRY_LOCK_HOURS = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "WORK_LOCK_SN")
    private Long workLockSn;

    @Column(name = "LCK_TARGET_CD", nullable = false, length = 20)
    private String lockTargetCd;

    @Column(name = "DATA_RAW_SN")
    private Long dataRawSn;

    @Column(name = "DATA_SRC_SN")
    private Long dataSrcSn;

    @Column(name = "LCK_STTS_CD", nullable = false, length = 20)
    private String lockSttsCd;

    @Column(name = "LCK_ID", nullable = false, length = 64)
    private String lockId;

    @Column(name = "LOCK_OWNER_ID", length = 30)
    private String lockOwnerId;

    @Column(name = "LCK_DT", nullable = false)
    private LocalDateTime lockDt;

    @Column(name = "EXPRY_DT")
    private LocalDateTime expireDt;

    @Column(name = "RMV_DT")
    private LocalDateTime releaseDt;

    @Column(name = "RMV_RSN", length = 4000)
    private String releaseRsn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    public static LsAuthWorkLock lockRawForRedeident(Long rawSn, String ownerId) {
        LsAuthWorkLock lock = new LsAuthWorkLock();
        lock.lockTargetCd = TARGET_RAW;
        lock.dataRawSn = rawSn;
        lock.lockSttsCd = STATUS_LOCKED;
        lock.lockId = UUID.randomUUID().toString();
        lock.lockOwnerId = ownerId;
        lock.lockDt = LocalDateTime.now();
        lock.expireDt = lock.lockDt.plusHours(6);
        lock.regId = ownerId;
        lock.regDt = lock.lockDt;
        return lock;
    }

    /**
     * 영상(rawSn) 배타 편집 락 — Phase 4 트랙 병합용. redeident 락과 동일하게 {@code TARGET_RAW}
     * 이므로 {@code isRawLocked}(TARGET_RAW) 로 함께 관측되어 병합 중 라벨 편집·동시 병합·재비식별을
     * 상호 차단한다. 사유만 MERGE 로 구분 기록한다.
     */
    public static LsAuthWorkLock lockRaw(Long rawSn, String ownerId, String reason) {
        LsAuthWorkLock lock = new LsAuthWorkLock();
        lock.lockTargetCd = TARGET_RAW;
        lock.dataRawSn = rawSn;
        lock.lockSttsCd = STATUS_LOCKED;
        lock.lockId = UUID.randomUUID().toString();
        lock.lockOwnerId = ownerId;
        lock.releaseRsn = null;
        lock.lockDt = LocalDateTime.now();
        lock.expireDt = lock.lockDt.plusHours(1);
        lock.regId = ownerId;
        lock.regDt = lock.lockDt;
        return lock;
    }

    /**
     * 선두 비식별 실패 영상의 배치 재시작 락. [@design API-167] [@design AC-1135]
     * {@code TARGET_RAW} 라 다른 기능의 {@code isRawLocked} 로 함께 관측되고, 같은 영상의 활성 락
     * 유일 인덱스가 동시 선점을 1건으로 막는다. 식별자 접두로 이 기능이 잡은 락임을 가린다.
     */
    public static LsAuthWorkLock lockRawForDeidentRetry(Long rawSn, String ownerId) {
        LsAuthWorkLock lock = new LsAuthWorkLock();
        lock.lockTargetCd = TARGET_RAW;
        lock.dataRawSn = rawSn;
        lock.lockSttsCd = STATUS_LOCKED;
        lock.lockId = LOCK_ID_PREFIX_DEIDENT_RETRY + UUID.randomUUID();
        lock.lockOwnerId = ownerId;
        lock.lockDt = LocalDateTime.now();
        lock.expireDt = lock.lockDt.plusHours(DEIDENT_RETRY_LOCK_HOURS);
        lock.regId = ownerId;
        lock.regDt = lock.lockDt;
        return lock;
    }

    /** 선두 비식별 재시작이 잡은 락인가 — 해제 범위를 이 기능의 락으로 한정하는 판정. */
    public boolean isDeidentRetryLock() {
        return lockId != null && lockId.startsWith(LOCK_ID_PREFIX_DEIDENT_RETRY);
    }

    public void release(String actorId, String reason) {
        this.lockSttsCd = STATUS_RELEASED;
        this.releaseRsn = reason;
        this.releaseDt = LocalDateTime.now();
        this.mdfcnId = actorId;
        this.mdfcnDt = this.releaseDt;
    }
}
