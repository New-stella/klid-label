package kr.co.cudo.authoring.version.entity;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "LS_LABEL_VERSION")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsLabelVersion {

    public static final String ACTIVE_YES = "Y";
    public static final String ACTIVE_NO = "N";

    // SAVE_REASON_CD 표준 코드 (LS_LABEL_VERSION 정착).
    // 버전 스냅샷은 검수 승인(APPROVED) 시점에만 생성된다 (학습데이터 버전관리 단위 = 검수 완료).
    // 라벨 저장(임시저장) 단계에서는 스냅샷을 만들지 않는다 — MANUAL 자동 커밋 폐기.
    //
    // D-ISSUE-21 — 'ROLLBACK' 코드는 폐기했다. 롤백 결과 페이로드는 대상 스냅샷 그 자체라
    // 재계산 해시가 항상 대상 행과 같고, (DATA_SRC_SN, VERSION_HASH) UNIQUE 때문에 새 행을 적층할 수
    // 없다(도달 불가 분기였음). 롤백은 <b>대상 행 재활성</b>이 정본이며, "누가·언제·어느 버전으로"는
    // LS_DATA_LBL_HSTRY 롤백 이벤트(LsDataLblHstry.recordRollbackEvent)에 기록한다.
    public static final String SAVE_REASON_APPROVED = "APPROVED";
    public static final String SAVE_REASON_BATCH = "BATCH";
    /**
     * <b>레거시 값</b> — 구 정책(비식별 신고 시 라벨 전량 삭제 직전 복원 스냅샷)이 적재했던 사유 코드.
     * <p>D-25(2026-07-27 정책 반전)로 <b>신규 적재는 중단</b>됐다(라벨을 삭제하지 않으므로 스냅샷도
     * 남기지 않는다). 운영 DB 에 남은 기존 행을 식별·판독하기 위한 문서 목적으로만 유지한다 —
     * 이 값을 다시 쓰는 코드를 추가하지 말 것.
     */
    public static final String SAVE_REASON_DEIDENT_REPORT = "DEIDENT_REPORT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "LBL_VERSION_SN")
    private Long labelVersionSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "DATA_SRC_SN")
    private Long dataSrcSn;

    /** payload 의 SHA-256(hex) — 같은 프레임 내 동일 스냅샷 식별 (멱등 재커밋). */
    @Column(name = "VERSION_HASH", length = 64)
    private String versionHash;

    /** 라벨 전체 JSON 스냅샷 (LabelResponse 직렬화 결과). diff/rollback 의 원천. */
    @Column(name = "LBL_PAYLOAD", columnDefinition = "TEXT")
    private String labelPayload;

    /**
     * <b>영상 단위 산출 버전 번호</b>(V180 재정의) — {@code LS_DATASET_EXPORT.OUTPUT_VER_NO} 와 같은
     * 번호이며 관제가 픽업하는 산출 폴더 {@code v1}·{@code v2} 와 일치한다.
     * {@code null} = 버전 번호를 알 수 없음.
     *
     * <p><b>구 의미(프레임별 승인 순번)는 폐기됐다</b>. 검수 승인은 영상 단위인데 내용이 안 바뀐
     * 프레임은 {@code (DATA_SRC_SN, VERSION_HASH)} UNIQUE 때문에 스냅샷이 생기지 않아 프레임마다
     * 번호가 밀렸고, 그 값으로는 "이 영상의 N번째 승인본"을 지목할 수 없었다. V181 이 기존 행의
     * 값을 전량 무효화({@code NULL})했다 — 옛 값을 회차로 읽으면 한 영상 안에 서로 다른 시점의
     * 프레임이 섞인 혼합본이 만들어지기 때문이다.
     *
     * <p><b>실제 채번 배선은 후속 단계</b>다. 그때까지 신규 승인 스냅샷도 {@code null} 로 저장된다
     * ({@code VersionService.saveActiveVersion} — 구 {@code count + 1} 채번 중단). 원시 {@code int}
     * 로 두면 DB {@code NULL} 을 읽는 순간 언박싱에서 터지므로 {@link Integer} 여야 한다.
     *
     * @design D5
     * @req R6
     */
    @Column(name = "VER_NO")
    private Integer versionNo;

    @Column(name = "SAVE_REASON_CD", length = 20)
    private String saveReasonCd;

    /**
     * <b>현재 작업본과 일치하는 스냅샷을 가리키는 포인터</b> — 프레임마다 1건이다.
     *
     * <h3>이것은 "버전의 정본 표식"이 아니다 (2026-08-12 확정, 구속)</h3>
     * 이 플래그가 뜻하는 것은 <b>"이 스냅샷이 지금 작업본의 내용과 같다"</b> 하나뿐이다. 그래서
     * 승인·롤백은 매번 이 표식을 옮긴다 — 승인은 직전 정본을 {@code 'N'} 으로 내리고 새(또는 같은
     * 내용의 기존) 행을 {@code 'Y'} 로 올리며, 롤백도 대상 행을 다시 {@code 'Y'} 로 만든다.
     * <b>그 전이는 과거 데이터 훼손이 아니다</b>: 과거 회차의 매핑 행·스냅샷 본문({@code LBL_PAYLOAD})·
     * 산출 폴더는 어느 것도 바뀌지 않는다.
     *
     * <h3>회차별 정본의 진실원은 {@code LS_OUTPUT_VER_SNPSH} 다</h3>
     * "회차 N 의 내용은 어느 스냅샷이었는가"는 그 매핑이 소유하며 <b>한 번 쓰이면 불변</b>이다
     * ({@code LsOutputVerSnpshRepository.recordActiveSnapshots} — {@code ON CONFLICT DO NOTHING}).
     * 과거 회차의 내용을 알아야 하면 이 플래그가 아니라 그 매핑을 읽는다. 이 플래그로 과거를 되짚으려
     * 하면 <b>그 회차에 존재한 적 없는 내용</b>을 고르게 된다(그것이 매핑 테이블을 만든 이유다).
     *
     * <h3>왜 남아 있나 — 승인 → 산출 마감의 인계 채널</h3>
     * 검수 승인 트랜잭션 시점에는 산출 회차 번호를 <b>알 수 없다</b>(채번은 승인 커밋 이후
     * {@code @Async} 산출 안의 {@code DatasetExportTxService.insertNextVersion} 에서 일어난다). 그래서
     * 승인은 "이번 내용"을 이 표식으로 남기고, 산출 마감({@code OutputVersionStamper.stamp})이 그
     * 표식을 읽어 번호와 매핑을 확정한다. 즉 이 컬럼은 <b>두 트랜잭션 사이의 인계 채널</b>이다.
     *
     * <p>⚠ 컬럼명·값을 바꾸지 말 것 — 마이그레이션과 조회 계약이 걸린다. 역할 명시는 문서로만 한다.
     *
     * @design D5
     * @req R6
     */
    @Column(name = "ACTVTN_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String activeYn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    /**
     * 승인 스냅샷 1건 생성.
     *
     * @param versionNo 영상 단위 산출 버전 번호. <b>{@code null} 허용</b> = 아직 알 수 없음
     *                  (채번 배선 전까지의 정상 값 — 지어내지 않는다).
     */
    public static LsLabelVersion create(Long rawSn, Long srcSn, String versionHash, String labelPayload,
                                        Integer versionNo, String saveReasonCd, String regId) {
        LsLabelVersion version = new LsLabelVersion();
        version.dataRawSn = rawSn;
        version.dataSrcSn = srcSn;
        version.versionHash = versionHash;
        version.labelPayload = labelPayload;
        version.versionNo = versionNo;
        version.saveReasonCd = saveReasonCd;
        version.activeYn = ACTIVE_YES;
        version.regId = regId;
        version.regDt = LocalDateTime.now();
        return version;
    }

    // D-25 (2026-07-27 정책 반전) — createInactiveRawSnapshot(영상 단위 DATA_SRC_SN=NULL 비활성
    //   스냅샷) 은 제거됐다. 비식별 신고가 라벨을 삭제하지 않으므로 적재 주체가 사라졌다.
    //   기존 DB 행(SAVE_REASON_CD='DEIDENT_REPORT', DATA_SRC_SN=NULL)은 그대로 보존되며,
    //   VersionService.diff 의 NULL DATA_SRC_SN 가드(D-ISSUE-26)가 그 행들을 안전하게 거부한다.

    public void deactivate() {
        this.activeYn = ACTIVE_NO;
    }

    /**
     * R12-1 — 롤백 시 대상 스냅샷의 해시가 기존 버전과 동일하면 신규 INSERT 대신 기존 행을 active 로 복원한다.
     * (DATA_SRC_SN, VERSION_HASH) UNIQUE 충돌(500) 방지. 식별자/페이로드/versionNo 는 보존하고
     * ACTIVE_YN 만 'Y' 로 전환한다.
     */
    public void activate() {
        this.activeYn = ACTIVE_YES;
    }
}
