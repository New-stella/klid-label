package kr.co.cudo.authoring.upload.entity;

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
 * TUS 1.0 재개 가능 업로드 세션 (LS_TUS_UPLOAD).
 *
 * <p>CVAT 포팅 Phase 3 — 관리 화면 대용량 영상 적재. 청크 단위 PATCH 로 누적 기록하고
 * offset==length 일치 시 {@code LS_DATA_RAW} 로 합류한다.
 *
 * <p>동시성·멱등 가드:
 * <ul>
 *   <li>{@code @Version}(VERSION) — 동시 PATCH 오프셋 충돌을 낙관적 잠금으로 차단 (HIGH-1, 409).</li>
 *   <li>STATUS 원자적 전이 — {@link #markCompleted(Long)} 는 IN_PROGRESS 일 때만 (HIGH-3, 멱등).</li>
 *   <li>EXPIRES_AT(+24h) — TTL 만료 세션은 410 처리/정리 잡 대상 (HIGH-5).</li>
 *   <li>USER_NO — 소유자만 HEAD/PATCH/DELETE (HIGH-8, 403).</li>
 * </ul>
 *
 * <p>{@code @Setter} 금지 — 상태 변경은 모두 의미 있는 비즈니스 메서드로만 수행한다.
 */
@Entity
@Table(name = "LS_TUS_UPLOAD")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsTusUpload {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    /**
     * 사용자가 <b>명시적으로 취소</b>한 세션 — 포털 채널 전용 상태(ADR-058 흡수).
     *
     * <p>관제 채널은 취소를 「인입 행 종결 + 세션 만료」로 표현해 이 값을 쓰지 않는다. 포털은 취소된
     * 세션에 이어올리기가 들어오면 <b>만료(410)가 아니라 충돌(409)</b> 로 답해야 해서 만료와 구분되는
     * 값이 필요하다.
     *
     * @design ADR-058
     * @design ERD-028
     */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 세션 TTL — 생성 시점 +24h. */
    public static final long TTL_HOURS = 24L;

    @Id
    @Column(name = "ULD_ID", nullable = false, updatable = false)
    private UUID uploadId;

    /**
     * 사용자번호 — 이 세션의 소유자. 소유자만 진행 상태 조회·이어올리기·취소를 할 수 있다.
     *
     * <p>★ 폭이 100 인 이유(V28): 포털 채널 세션이 이 원장을 함께 쓰며(ADR-058 흡수) 포털이 발급한
     * 토큰의 주체 식별자를 담아야 한다. 좁히면 서로 다른 사용자가 같은 값으로 잘려 세션 인가가
     * <b>조용히</b> 어긋난다. 공통표준도메인 번호V100.
     *
     * @design ADR-058
     * @design ERD-028
     */
    @Column(name = "USER_NO", nullable = false, length = 100)
    private String userNo;

    @Column(name = "ULD_LEN", nullable = false)
    private long uploadLength;

    @Column(name = "ULD_OFFSET", nullable = false)
    private long uploadOffset;

    @Column(name = "STTS_CD", nullable = false, length = 16)
    private String status;

    @Column(name = "FILE_PATH", nullable = false, length = 500)
    private String filePath;

    @Column(name = "FILE_NM", length = 255)
    private String fileName;

    @Column(name = "VMS_CLIP_ID", length = 128)
    private String vmsClipId;

    @Column(name = "CCTV_ID", length = 64)
    private String cctvId;

    @Column(name = "EVNT_TYPE_CD", length = 20)
    private String eventTypeCd;

    @Column(name = "LCLGV_CD", length = 20)
    private String localGovCd;

    @Column(name = "PRVC_TYPE_CD", length = 8)
    private String prvcTypeCd;

    @Column(name = "SHT_DT")
    private LocalDateTime capturedAt;

    @Column(name = "RAW_SN")
    private Long rawSn;

    @Column(name = "EXPRY_DT", nullable = false)
    private LocalDateTime expiresAt;

    @Version
    @Column(name = "VER", nullable = false)
    private long version;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    /**
     * 신규 업로드 세션 생성. 저장 파일명은 UUID 강제(HIGH-7), filename 은 표시용만 보존.
     *
     * <p><b>Phase 3 — 인입 메타는 세션이 보관하지 않는다.</b> 관제 수신 29컬럼은 세션 생성 시점에
     * {@code LS_DATA_INGEST} 행으로 바로 들어가므로 세션은 <b>완료·취소 처리에 필요한 값</b>만 남긴다
     * ({@code vmsClipId}=인입 행 역참조 키이자 저장 파일명, {@code fileName}=확장자 출처). 여기 값을
     * 이중 보관하면 인입 행과 갈라진 두 번째 진실원이 된다. {@code EVNT_TYPE_CD}/{@code PRVC_TYPE_CD}
     * 컬럼은 인입에 대응 컬럼이 없어({@code PRVC} 는 적재 시 fail-closed 기본값) 더 이상 채우지 않는다 —
     * 컬럼 자체는 과거 행 호환을 위해 남긴다(DB 마이그레이션 불요).
     */
    public static LsTusUpload create(UUID uploadId, String userNo, long uploadLength,
                                     String filePath, String fileName,
                                     String vmsClipId, String cctvId,
                                     String localGovCd, LocalDateTime capturedAt) {
        LsTusUpload u = new LsTusUpload();
        u.uploadId = uploadId;
        u.userNo = userNo;
        u.uploadLength = uploadLength;
        u.uploadOffset = 0L;
        u.status = STATUS_IN_PROGRESS;
        u.filePath = filePath;
        u.fileName = fileName;
        u.vmsClipId = vmsClipId;
        u.cctvId = cctvId;
        u.localGovCd = localGovCd;
        u.capturedAt = capturedAt;
        LocalDateTime now = LocalDateTime.now();
        u.regDt = now;
        u.mdfcnDt = now;
        u.expiresAt = now.plusHours(TTL_HOURS);
        return u;
    }

    /**
     * 포털 채널 세션 생성 — 관제 인입 메타를 갖지 않는다(ADR-058 흡수).
     *
     * <h3>★ {@code VMS_CLIP_ID} 가 비어 있는 것이 곧 채널 판별자다</h3>
     * <p>관제 세션은 이 값이 <b>구조적으로 항상 채워진다</b> — 세션 생성 요청이 그것을
     * {@code @NotBlank} 로 강제하고, 그 값이 인입 행의 역참조 키이자 저장 파일명이다. 포털에는 관제
     * 클립 개념이 없어 채울 값 자체가 없으므로 <b>비어 있음 = 포털 세션</b>이 성립한다.
     *
     * <p>이 판별이 필요한 이유는 <b>정리 잡이 둘</b>이기 때문이다. 관제 정리 잡은 만료 세션의 인입 행을
     * 함께 종결시키는데 포털 세션에는 종결할 인입 행이 없고, 임시 파일도 <b>다른 저장 루트</b>에 있어
     * 그 잡의 경로 가드에 막힌다 — 즉 관제 잡이 포털 세션을 집으면 행만 사라지고 파일이 고아로 남는다.
     * 두 잡이 서로의 세션을 집지 않도록 이 값으로 가른다.
     *
     * <p>⚠ 더 나은 형태는 채널을 <b>양의 값</b>으로 적는 전용 칸이다. 지금은 흡수의 스키마 변경 범위가
     * 「식별자 폭 확대」로 확정돼 있어 부재를 판별자로 쓴다 — 그 확정이 바뀌면 이 자리를 먼저 고친다.
     *
     * @design ADR-058
     * @design ERD-028
     */
    public static LsTusUpload createPortalSession(UUID uploadId, String portalUserNo, long uploadLength,
                                                  String filePath, String fileName) {
        LsTusUpload u = new LsTusUpload();
        u.uploadId = uploadId;
        u.userNo = portalUserNo;
        u.uploadLength = uploadLength;
        u.uploadOffset = 0L;
        u.status = STATUS_IN_PROGRESS;
        u.filePath = filePath;
        u.fileName = fileName;
        // vmsClipId·cctvId·localGovCd·capturedAt 은 관제 인입 축이라 비운다 — 위 javadoc 참조.
        LocalDateTime now = LocalDateTime.now();
        u.regDt = now;
        u.mdfcnDt = now;
        u.expiresAt = now.plusHours(TTL_HOURS);
        return u;
    }

    /** 이 세션이 포털 채널 세션인가 — 판별 근거는 {@link #createPortalSession} javadoc. @design ADR-058 */
    public boolean isPortalSession() {
        return vmsClipId == null || vmsClipId.isBlank();
    }

    /** 사용자 취소(포털 채널). 완료된 세션은 호출부가 먼저 걸러 낸다. @design ADR-058 */
    public void markCancelled() {
        this.status = STATUS_CANCELLED;
        this.mdfcnDt = LocalDateTime.now();
    }

    public boolean isCancelled() {
        return STATUS_CANCELLED.equals(status);
    }

    /**
     * 포털 채널의 만료 판정 — <b>진행 중</b>인 세션만 만료로 본다.
     *
     * <p>{@link #isExpired(LocalDateTime)}(관제 축)는 «완료가 아니면 만료»라 취소된 세션도 만료로
     * 읽는다. 포털은 취소를 410 이 아니라 409 로 답해야 하므로 두 판정을 합치지 않는다 — 합치면
     * 취소한 사용자에게 「세션이 만료됐다」는 다른 사실이 안내된다.
     *
     * @design ADR-058
     */
    public boolean isPortalExpired(LocalDateTime now) {
        return STATUS_IN_PROGRESS.equals(status) && now.isAfter(expiresAt);
    }

    public boolean isExpired(LocalDateTime now) {
        return !STATUS_COMPLETED.equals(status) && now.isAfter(expiresAt);
    }

    public boolean isCompleted() {
        return STATUS_COMPLETED.equals(status);
    }

    public boolean isOwnedBy(String candidateUserNo) {
        return userNo != null && userNo.equals(candidateUserNo);
    }

    /**
     * 청크 기록 후 오프셋 전진. 디스크 부분 쓰기 실패 시 호출하지 않아 offset 미갱신 보장(HIGH-4).
     *
     * @param newOffset 기록 완료 후의 누적 오프셋 (직전 오프셋 + 청크 길이)
     */
    public void advanceOffset(long newOffset) {
        if (newOffset < this.uploadOffset || newOffset > this.uploadLength) {
            throw new IllegalArgumentException("invalid offset transition");
        }
        this.uploadOffset = newOffset;
        this.mdfcnDt = LocalDateTime.now();
    }

    public boolean isFullyUploaded() {
        return uploadOffset == uploadLength;
    }

    /**
     * 완료 전이 — IN_PROGRESS 일 때만 적용(원자적). 이미 COMPLETED 면 멱등(false 반환).
     *
     * @return 이번 호출에서 전이가 발생했으면 true, 이미 완료 상태면 false
     */
    public boolean markCompleted(Long rawSn) {
        if (STATUS_COMPLETED.equals(this.status)) {
            return false;
        }
        this.status = STATUS_COMPLETED;
        this.rawSn = rawSn;
        this.mdfcnDt = LocalDateTime.now();
        return true;
    }

    public void markExpired() {
        this.status = STATUS_EXPIRED;
        this.mdfcnDt = LocalDateTime.now();
    }
}
