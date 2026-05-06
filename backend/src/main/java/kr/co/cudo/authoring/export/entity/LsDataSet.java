package kr.co.cudo.authoring.export.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Phase 10 — 학습데이터셋 내보내기 작업 (LS_DATA_SET).
 *
 * <p>V1.4 정책: 데이터마트 검색·다운로드는 외부제공 시스템 책임. 본체는 NAS 디렉토리까지 생성.
 *
 * <p>상태 전이:
 * <ul>
 *   <li>PENDING      → IN_PROGRESS  (Quartz Job 시작 시)</li>
 *   <li>IN_PROGRESS  → COMPLETED    (NAS 쓰기 성공)</li>
 *   <li>IN_PROGRESS  → FAILED       (예외 발생 시 errorMessage 기록)</li>
 * </ul>
 */
@Entity
@Table(name = "LS_DATA_SET")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataSet {

    public static final String STTS_PENDING     = "PENDING";
    public static final String STTS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STTS_COMPLETED   = "COMPLETED";
    public static final String STTS_FAILED      = "FAILED";

    public static final String FORMAT_YOLO = "YOLO";
    public static final String FORMAT_COCO = "COCO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "EXPORT_SN")
    private Long exportSn;

    @Column(name = "PJT_ID", nullable = false)
    private Long pjtId;

    @Column(name = "EXPORT_FORMAT", nullable = false, length = 20)
    private String exportFormat;

    @Column(name = "EXPORT_STTS_CD", nullable = false, length = 20)
    private String exportSttsCd;

    @Column(name = "NAS_PATH", length = 500)
    private String nasPath;

    @Column(name = "ERROR_MESSAGE", length = 1000)
    private String errorMessage;

    @Column(name = "EXPORTED_AT")
    private LocalDateTime exportedAt;

    @Column(name = "REGISTERED_USER_NO", length = 50)
    private String registeredUserNo;

    @Column(name = "REGISTERED_AT", nullable = false)
    private LocalDateTime registeredAt;

    @Builder
    private LsDataSet(Long pjtId, String exportFormat, String exportSttsCd,
                      String nasPath, String registeredUserNo, LocalDateTime registeredAt) {
        this.pjtId = pjtId;
        this.exportFormat = exportFormat;
        this.exportSttsCd = exportSttsCd;
        this.nasPath = nasPath;
        this.registeredUserNo = registeredUserNo;
        this.registeredAt = registeredAt;
    }

    /**
     * 신규 PENDING 작업 생성. nasPath 는 Job 실행 단계에서 결정 (exportSn 기반).
     */
    public static LsDataSet createPending(Long pjtId, String exportFormat, String registeredUserNo) {
        if (!FORMAT_YOLO.equals(exportFormat) && !FORMAT_COCO.equals(exportFormat)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 포맷입니다: " + exportFormat + " (YOLO/COCO 만 지원)");
        }
        return LsDataSet.builder()
                .pjtId(pjtId)
                .exportFormat(exportFormat)
                .exportSttsCd(STTS_PENDING)
                .registeredUserNo(registeredUserNo)
                .registeredAt(LocalDateTime.now())
                .build();
    }

    /**
     * Quartz Job 시작 시 IN_PROGRESS 로 전이. PENDING 일 때만 가능.
     */
    public void markInProgress() {
        if (!STTS_PENDING.equals(this.exportSttsCd)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 처리 중이거나 완료된 export 입니다. status=" + this.exportSttsCd);
        }
        this.exportSttsCd = STTS_IN_PROGRESS;
    }

    /**
     * NAS 쓰기 성공. nasPath 확정 + COMPLETED 전이.
     */
    public void markCompleted(String nasPath) {
        this.exportSttsCd = STTS_COMPLETED;
        this.nasPath = nasPath;
        this.exportedAt = LocalDateTime.now();
    }

    /**
     * 처리 실패. errorMessage 기록.
     */
    public void markFailed(String errorMessage) {
        this.exportSttsCd = STTS_FAILED;
        this.errorMessage = errorMessage == null
                ? "unknown"
                : (errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage);
    }
}
