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

    @Column(name = "REG_ID", length = 64)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 64)
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

    public void release(String actorId, String reason) {
        this.lockSttsCd = STATUS_RELEASED;
        this.releaseRsn = reason;
        this.releaseDt = LocalDateTime.now();
        this.mdfcnId = actorId;
        this.mdfcnDt = this.releaseDt;
    }
}
