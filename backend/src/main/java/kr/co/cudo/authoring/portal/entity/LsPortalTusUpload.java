package kr.co.cudo.authoring.portal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 포털 전용 TUS 1.0 재개 가능 업로드 세션 (LS_PORTAL_TUS_ULD).
 *
 * <p>Phase 3 — 포털 사용자 대용량 영상 청크 업로드. 관제(내부) {@code LS_TUS_UPLOAD} 와 구조를
 * 준용하되 <b>별개 테이블·별개 엔티티</b>로 완전 분리한다(포털 채널 격리, 관제 비식별 파이프라인
 * 미연결). 청크 단위 PATCH 로 누적 기록하고 offset==length 일치 시 {@code LS_PORTAL_ULD}(VIDEO,
 * UPLOADED) 로 합류한다.
 *
 * <p>동시성·멱등 가드:
 * <ul>
 *   <li>{@code @Version}(VER) — 동시 PATCH 오프셋 충돌을 낙관적 잠금으로 차단(409).</li>
 *   <li>STTS_CD 원자 전이 — {@link #markCompleted(Long)} 는 IN_PROGRESS 일 때만(멱등).</li>
 *   <li>EXPRY_DT(+24h) — TTL 만료 세션은 410 처리/스윕 대상.</li>
 *   <li>PORTAL_USER_NO — 소유자만 HEAD/PATCH/DELETE(403).</li>
 * </ul>
 *
 * <p>{@code @Setter} 금지 — 상태 변경은 모두 의미 있는 비즈니스 메서드로만 수행한다.
 */
@Entity
@Table(name = "LS_PORTAL_TUS_ULD")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPortalTusUpload {

    public static final String STTS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STTS_COMPLETED = "COMPLETED";
    public static final String STTS_CANCELLED = "CANCELLED";

    /** 세션 TTL — 생성 시점 +24h. */
    public static final long TTL_HOURS = 24L;

    @Id
    @Column(name = "ULD_ID", nullable = false, updatable = false)
    private UUID uldId;

    @Column(name = "PORTAL_USER_NO", nullable = false, length = 100)
    private String portalUserNo;

    @Column(name = "ULD_LEN", nullable = false)
    private long lengthBytes;

    @Column(name = "ULD_OFFSET", nullable = false)
    private long offsetBytes;

    @Column(name = "STTS_CD", nullable = false, length = 16)
    private String sttsCd;

    @Column(name = "FILE_PATH_NM", nullable = false, length = 500)
    private String filePathNm;

    @Column(name = "ORGNL_FILE_NM", length = 255)
    private String orgnlFileNm;

    @Column(name = "ULD_SN")
    private Long uldSn;

    @Column(name = "EXPRY_DT", nullable = false)
    private LocalDateTime expiresAt;

    @Version
    @Column(name = "VER", nullable = false)
    private long version;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    /** 신규 포털 TUS 세션 생성. 저장 파일명은 UUID 강제, orgnlFileNm 은 표시용만 보존. */
    public static LsPortalTusUpload create(UUID uldId, String portalUserNo, long lengthBytes,
                                           String filePathNm, String orgnlFileNm) {
        LsPortalTusUpload u = new LsPortalTusUpload();
        u.uldId = uldId;
        u.portalUserNo = portalUserNo;
        u.lengthBytes = lengthBytes;
        u.offsetBytes = 0L;
        u.sttsCd = STTS_IN_PROGRESS;
        u.filePathNm = filePathNm;
        u.orgnlFileNm = orgnlFileNm;
        LocalDateTime now = LocalDateTime.now();
        u.regDt = now;
        u.mdfcnDt = now;
        u.expiresAt = now.plusHours(TTL_HOURS);
        return u;
    }

    public boolean isExpired(LocalDateTime now) {
        return STTS_IN_PROGRESS.equals(sttsCd) && now.isAfter(expiresAt);
    }

    public boolean isCompleted() {
        return STTS_COMPLETED.equals(sttsCd);
    }

    public boolean isCancelled() {
        return STTS_CANCELLED.equals(sttsCd);
    }

    public boolean isOwnedBy(String candidatePortalUserNo) {
        return portalUserNo != null && portalUserNo.equals(candidatePortalUserNo);
    }

    /** 청크 기록 후 오프셋 전진. 디스크 부분 쓰기 실패 시 호출하지 않아 offset 미갱신 보장. */
    public void advanceOffset(long newOffset) {
        if (newOffset < this.offsetBytes || newOffset > this.lengthBytes) {
            throw new IllegalArgumentException("invalid offset transition");
        }
        this.offsetBytes = newOffset;
        this.mdfcnDt = LocalDateTime.now();
    }

    public boolean isFullyUploaded() {
        return offsetBytes == lengthBytes;
    }

    /**
     * 완료 전이 — IN_PROGRESS 일 때만 적용(원자적). 이미 COMPLETED 면 멱등(false 반환).
     *
     * @return 이번 호출에서 전이가 발생했으면 true, 이미 완료 상태면 false
     */
    public boolean markCompleted(Long uldSn) {
        if (STTS_COMPLETED.equals(this.sttsCd)) {
            return false;
        }
        this.sttsCd = STTS_COMPLETED;
        this.uldSn = uldSn;
        this.mdfcnDt = LocalDateTime.now();
        return true;
    }

    public void markCancelled() {
        this.sttsCd = STTS_CANCELLED;
        this.mdfcnDt = LocalDateTime.now();
    }
}
