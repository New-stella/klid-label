package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 원시 영상 (LS_DATA_RAW). 관제서버로부터 수신한 라벨링 대상 영상 메타.
 * - VMS_CLIP_ID 가 UK 로 잡혀 있어 동일 클립 재수신 시 upsert.
 * - PRVC_TYPE_CD 값에 따라 PRVC_YN 이 자동 산출 (ANONY -> N, PRVC/PSDO -> Y).
 */
@Entity
@Table(name = "LS_DATA_RAW",
        uniqueConstraints = @UniqueConstraint(name = "UK_LS_DATA_RAW_VMS_CLIP", columnNames = "VMS_CLIP_ID"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataRaw {

    public static final String PRVC_TYPE_ANONY = "ANONY";
    public static final String PRVC_TYPE_PRVC = "PRVC";
    public static final String PRVC_TYPE_PSDO = "PSDO";
    /**
     * 개인정보 처리 유형 미상 — 마이그레이션/외부 적재로 분류가 확정되지 않은 영상의 잠정값.
     * 검수완료 재비식별(REDEIDENT)이 수행되면 개인정보 처리가 실제로 일어난 것이므로
     * {@link #correctPrvcTypeIfUnknown()} 가 PRVC 로 정정한다.
     */
    public static final String PRVC_TYPE_UNKNOWN = "UNKNOWN";

    public static final String STATUS_PENDING = "PENDING";

    /**
     * 적재 직후 자동 비식별이 성공해 마킹 단계로 진입 가능한 상태 (Phase 2).
     * <p>흐름: PENDING → (선두 비식별 성공) MARKING_READY → (마킹완료→배치) COMPLETED.
     * marking-ready 신호는 LS_RAW_DATA_STATUS 가 아닌 본 LS_DATA_RAW.DATA_STTS_CD 에 둔다
     * (작업 상태 row 는 배정 시점 lazy 생성이라 적재 직후 전이 불가).
     */
    public static final String DATA_STTS_MARKING_READY = "MARKING_READY";

    /**
     * 마킹 완료로 트리거된 배치가 진행 중인 상태 (Bug 2 — '처리중' 도입).
     * <p>흐름: MARKING_READY → (배치 시작) PROCESSING → (성공) COMPLETED / (실패) FAILED.
     * 이 상태가 없으면 마킹 완료~배치 완료 구간 내내 MARKING_READY("마킹 대기")로 남아
     * 사용자가 "마킹 안 됨"으로 오인한다.
     */
    public static final String DATA_STTS_PROCESSING = "PROCESSING";

    /** 배치 완료(성공) — 배치 단계 종결 상태. */
    public static final String DATA_STTS_COMPLETED = "COMPLETED";

    /**
     * 배치 실패 상태 (Bug 2 — MARKING_READY 고착 방지).
     * <p>배치 실패 시 LS_DATA_RAW.DATA_STTS_CD 가 MARKING_READY 로 남아 영구 "마킹 대기"로
     * 고착되던 결함을 막기 위해 도입. PROCESSING → FAILED 로 전이한다.
     */
    public static final String DATA_STTS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "RAW_SN")
    private Long rawSn;

    @Column(name = "VMS_CLIP_ID", nullable = false, length = 128)
    private String vmsClipId;

    @Column(name = "VMS_CCTV_ID", nullable = false, length = 64)
    private String vmsCctvId;

    @Column(name = "EVNT_TYPE_CD", length = 20)
    private String evntTypeCd;

    @Column(name = "LCLGV_CD", length = 20)
    private String lclgvCd;

    @Column(name = "PRVC_TYPE_CD", nullable = false, length = 16)
    private String prvcTypeCd;

    @Column(name = "PRVC_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String prvcYn;

    @Column(name = "DE_IDENT_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String deIdntfYn;

    @Column(name = "RAW_FILE_PATH_NM", nullable = false, length = 500)
    private String rawFilePathNm;

    @Column(name = "SHT_DT")
    private LocalDateTime shtDt;

    @Column(name = "VDO_LEN_SEC")
    private Integer durationSec;

    @Column(name = "ORGNL_RAW_SN")
    private Long orgnlRawSn;

    @Column(name = "DATA_STTS_CD", nullable = false, length = 20)
    private String dataSttsCd;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    @Builder
    private LsDataRaw(String vmsClipId, String vmsCctvId, String evntTypeCd, String lclgvCd,
                      String prvcTypeCd, String rawFilePathNm, LocalDateTime shtDt, Integer durationSec) {
        this.vmsClipId = vmsClipId;
        this.vmsCctvId = vmsCctvId;
        this.evntTypeCd = evntTypeCd;
        this.lclgvCd = lclgvCd;
        this.prvcTypeCd = prvcTypeCd;
        this.prvcYn = derivePrvcYn(prvcTypeCd);
        this.deIdntfYn = "N";
        this.rawFilePathNm = rawFilePathNm;
        this.shtDt = shtDt;
        this.durationSec = durationSec;
        this.dataSttsCd = STATUS_PENDING;
        this.regDt = LocalDateTime.now();
    }

    public static LsDataRaw createFromIngest(String vmsClipId, String vmsCctvId, String evntTypeCd,
                                              String lclgvCd, String prvcTypeCd, String rawFilePathNm,
                                              LocalDateTime shtDt, Integer durationSec) {
        return LsDataRaw.builder()
                .vmsClipId(vmsClipId)
                .vmsCctvId(vmsCctvId)
                .evntTypeCd(evntTypeCd)
                .lclgvCd(lclgvCd)
                .prvcTypeCd(prvcTypeCd)
                .rawFilePathNm(rawFilePathNm)
                .shtDt(shtDt)
                .durationSec(durationSec)
                .build();
    }

    /**
     * V2.0 증강 결과 수신 시 새 영상 생성. 원본 메타를 계승하되 PENDING 상태로 시작.
     * VMS_CLIP_ID 는 원본 + 증강 타입 + 타임스탬프로 유니크 보장.
     */
    public static LsDataRaw createFromAugment(LsDataRaw parent, String rawFilePathNm, String augType) {
        LsDataRaw raw = new LsDataRaw();
        raw.vmsClipId = parent.getVmsClipId() + "_AUG_" + augType + "_" + System.currentTimeMillis();
        raw.vmsCctvId = parent.getVmsCctvId();
        raw.evntTypeCd = parent.getEvntTypeCd();
        raw.lclgvCd = parent.getLclgvCd();
        raw.prvcTypeCd = parent.getPrvcTypeCd();
        raw.prvcYn = derivePrvcYn(parent.getPrvcTypeCd());
        raw.deIdntfYn = "N";
        raw.rawFilePathNm = rawFilePathNm;
        raw.shtDt = parent.getShtDt();
        raw.durationSec = parent.getDurationSec();
        raw.orgnlRawSn = parent.getRawSn();
        raw.dataSttsCd = STATUS_PENDING;
        raw.regDt = LocalDateTime.now();
        return raw;
    }

    /**
     * Phase 2 (해상도 파생영상) — 원본(APPROVED·비식별) 영상에서 목표 해상도의 새 파생영상을 생성한다.
     * 원본 메타를 계승하되 PENDING 으로 시작하고 ORGNL_RAW_SN 으로 원본을 참조한다.
     *
     * <p>{@code createFromAugment} 와 공통 골격이나 구분점:
     * <ul>
     *   <li>VMS_CLIP_ID = 원본 + {@code "_RES_"} + 목표 해상도 코드 + 타임스탬프 (UNIQUE 보장)</li>
     *   <li>비식별 계승 — 원본이 비식별 완료('Y')된 영상만 파생 대상이므로 파생본도 산출 확정 시 'Y' 로 마감된다.
     *       생성 시점 기본값은 'N'(추출/복사 성공 전까지 스트리밍/마킹 진입 차단, {@code createFromAugment} 동일).</li>
     * </ul>
     *
     * @param parent        원본 RAW (검수완료·비식별, ORGNL_RAW_SN=null)
     * @param rawFilePathNm 파생영상(비식별 비디오 복사본) 파일 경로
     * @param goalResCd     목표 해상도 코드 (예: RES_720P)
     */
    public static LsDataRaw createFromResolution(LsDataRaw parent, String rawFilePathNm, String goalResCd) {
        LsDataRaw raw = new LsDataRaw();
        raw.vmsClipId = parent.getVmsClipId() + "_RES_" + goalResCd + "_" + System.currentTimeMillis();
        raw.vmsCctvId = parent.getVmsCctvId();
        raw.evntTypeCd = parent.getEvntTypeCd();
        raw.lclgvCd = parent.getLclgvCd();
        raw.prvcTypeCd = parent.getPrvcTypeCd();
        raw.prvcYn = derivePrvcYn(parent.getPrvcTypeCd());
        raw.deIdntfYn = "N";
        raw.rawFilePathNm = rawFilePathNm;
        raw.shtDt = parent.getShtDt();
        raw.durationSec = parent.getDurationSec();
        raw.orgnlRawSn = parent.getRawSn();
        raw.dataSttsCd = STATUS_PENDING;
        raw.regDt = LocalDateTime.now();
        return raw;
    }

    /**
     * 관제서버로부터 동일 VMS_CLIP_ID 가 다시 송신되었을 때 변경 가능 메타만 갱신.
     * (RAW_SN, VMS_CLIP_ID 는 불변)
     */
    public void updateFromIngest(String vmsCctvId, String evntTypeCd, String lclgvCd, String prvcTypeCd,
                                  String rawFilePathNm, LocalDateTime shtDt, Integer durationSec) {
        this.vmsCctvId = vmsCctvId;
        this.evntTypeCd = evntTypeCd;
        this.lclgvCd = lclgvCd;
        this.prvcTypeCd = prvcTypeCd;
        this.prvcYn = derivePrvcYn(prvcTypeCd);
        this.rawFilePathNm = rawFilePathNm;
        this.shtDt = shtDt;
        this.durationSec = durationSec;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 비식별 처리가 필요한지 여부 (Phase 5).
     * - PRVC / PSDO 만 비식별 호출 대상. ANONY 는 원본 그대로 보존.
     */
    public boolean needsDeidentify() {
        return PRVC_TYPE_PRVC.equals(this.prvcTypeCd) || PRVC_TYPE_PSDO.equals(this.prvcTypeCd);
    }

    /**
     * 비식별 처리 결과를 마킹 (Phase 5).
     * - 'Y' = 성공 / 'F' = 실패 / 'N' = 미수행.
     * - 원본 rawFilePathNm 은 절대 변경되지 않는다 (원본 보존 원칙).
     */
    public void markDeidentified(String code) {
        if (code == null || (!"Y".equals(code) && !"F".equals(code) && !"N".equals(code))) {
            throw new IllegalArgumentException("DE_IDNTF_YN 은 Y/F/N 중 하나여야 합니다: " + code);
        }
        this.deIdntfYn = code;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 선두 비식별 성공 후 마킹 단계 진입 가능 상태로 전이 (Phase 2).
     * <p>원본 rawFilePathNm 은 절대 변경되지 않는다 (원본 보존 원칙).
     */
    public void markMarkingReady() {
        changeStatus(DATA_STTS_MARKING_READY);
    }

    /**
     * 마킹 완료로 트리거된 배치 시작 시 배치 단계 상태를 PROCESSING("처리중")으로 전이 (Bug 2).
     * <p>원본 rawFilePathNm 은 절대 변경되지 않는다 (원본 보존 원칙).
     */
    public void markProcessing() {
        changeStatus(DATA_STTS_PROCESSING);
    }

    /**
     * 배치 실패 시 배치 단계 상태를 FAILED("실패")로 전이 (Bug 2 — MARKING_READY 고착 방지).
     * <p>원본 rawFilePathNm 은 절대 변경되지 않는다 (원본 보존 원칙).
     */
    public void markBatchFailed() {
        changeStatus(DATA_STTS_FAILED);
    }

    /**
     * 배치 완료(성공) 시 배치 단계 상태를 COMPLETED("완료")로 전이.
     * <p>매직 스트링 제거 — {@link #DATA_STTS_COMPLETED} 상수를 사용하며,
     * {@code markProcessing()}/{@code markBatchFailed()} 와 동일한 도메인 메서드 형식으로 통일한다.
     * 원본 rawFilePathNm 은 절대 변경되지 않는다 (원본 보존 원칙).
     */
    public void markCompleted() {
        changeStatus(DATA_STTS_COMPLETED);
    }

    /** 배치 상태 코드 갱신 (PROCESSING / COMPLETED / FAILED). */
    public void changeStatus(String dataSttsCd) {
        if (dataSttsCd == null || dataSttsCd.isBlank()) {
            throw new IllegalArgumentException("DATA_STTS_CD 는 필수입니다.");
        }
        this.dataSttsCd = dataSttsCd;
        this.mdfcnDt = LocalDateTime.now();
    }

    /**
     * 검수완료 재비식별(REDEIDENT) 수행 시 개인정보 처리 유형 정정 — UNKNOWN(미상)이면 PRVC 로 보정.
     *
     * <p>재비식별을 실제로 수행했다는 것은 개인정보 처리가 일어났다는 의미이므로, 분류가 미상이던
     * 영상의 PRVC_TYPE_CD 를 PRVC 로 확정하고 PRVC_YN(파생값)도 함께 재산출한다. 이미 분류가
     * 확정된(ANONY/PRVC/PSDO) 영상은 변경하지 않는다(멱등·원본 보존).
     *
     * @return UNKNOWN→PRVC 정정이 실제 일어났으면 true.
     */
    public boolean correctPrvcTypeIfUnknown() {
        if (PRVC_TYPE_UNKNOWN.equals(this.prvcTypeCd)) {
            this.prvcTypeCd = PRVC_TYPE_PRVC;
            this.prvcYn = derivePrvcYn(this.prvcTypeCd);
            this.mdfcnDt = LocalDateTime.now();
            return true;
        }
        return false;
    }

    /** PRVC_TYPE_CD 기반 PRVC_YN 산출 (단일 진실의 원천). */
    public static String derivePrvcYn(String prvcTypeCd) {
        if (prvcTypeCd == null) {
            return "N";
        }
        return switch (prvcTypeCd) {
            case PRVC_TYPE_PRVC, PRVC_TYPE_PSDO -> "Y";
            default -> "N";
        };
    }
}
