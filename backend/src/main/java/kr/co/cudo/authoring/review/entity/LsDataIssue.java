package kr.co.cudo.authoring.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Phase 7 / Phase 1(이슈 스레드) — 검수 이슈 (LS_DATA_ISSUE).
 *
 * <p>DB설계서 §5A.6 — 좌표 컬럼 없음 (영상 단위 이슈). 계층형 (UP_DATA_ISSUE_SN 자기참조).
 * DATA_RAW_SN(=LS_DATA_RAW.RAW_SN) 영상 단위 참조 (행안부 공통표준 약어 정합).
 * 외래키는 정의하지 않음 (klid_system 공유 DB 정책 — 운영 안정성 우선).
 *
 * <p>Phase 1(이슈 스레드, V57) 확장:
 * <ul>
 *   <li>{@code ISSUE_TYPE_CD} — REJECTION(검수 반려 이력) / INQUIRY(작업자 문의).</li>
 *   <li>{@code ISSUE_STTS_CD} — OPEN → ANSWERED → RESOLVED 상태 머신 (INQUIRY 만 전이, 역행 불가).
 *       REJECTION 은 RESOLVED 고정(이력 성격, 상태 전이 비대상).</li>
 *   <li>{@code SRC_SN} — 프레임 단위 선택 참조 (NULL 가능).</li>
 *   <li>{@code @Version} — resolve↔addComment 동시성 보호 (시나리오 #2).</li>
 * </ul>
 */
@Entity
@Table(name = "LS_DATA_ISSUE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataIssue {

    /** 검수 반려 이력 (REVIEWER → WORKER, 상태 전이 비대상). */
    public static final String TYPE_REJECTION = "REJECTION";
    /** 작업자 문의 (WORKER/REVIEWER, 상태 머신 적용). */
    public static final String TYPE_INQUIRY = "INQUIRY";

    /** 문의 등록 직후. */
    public static final String STTS_OPEN = "OPEN";
    /** REVIEWER 가 답변(댓글) 작성 후. */
    public static final String STTS_ANSWERED = "ANSWERED";
    /** 해소 완료 (REVIEWER resolve). */
    public static final String STTS_RESOLVED = "RESOLVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATA_ISSUE_SN")
    private Long dataIssueSn;

    @Column(name = "UP_DATA_ISSUE_SN")
    private Long upDataIssueSn;

    @Column(name = "DATA_RAW_SN", nullable = false)
    private Long dataRawSn;

    @Column(name = "ISSUE_RSN", length = 1000)
    private String issueRsn;

    @Column(name = "REPORTED_USER_NO", length = 50)
    private String reportedUserNo;

    @Column(name = "ISSUE_TYPE_CD", length = 20, nullable = false)
    private String issueTypeCd;

    @Column(name = "ISSUE_STTS_CD", length = 20, nullable = false)
    private String issueSttsCd;

    @Column(name = "SRC_SN")
    private Long srcSn;

    @Version
    @Column(name = "VER", nullable = false)
    private Long version;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Builder
    private LsDataIssue(Long upDataIssueSn, Long dataRawSn, String issueRsn,
                        String reportedUserNo, String issueTypeCd, String issueSttsCd,
                        Long srcSn, LocalDateTime regDt) {
        this.upDataIssueSn = upDataIssueSn;
        this.dataRawSn = dataRawSn;
        this.issueRsn = issueRsn;
        this.reportedUserNo = reportedUserNo;
        this.issueTypeCd = issueTypeCd;
        this.issueSttsCd = issueSttsCd;
        this.srcSn = srcSn;
        this.regDt = regDt;
    }

    /**
     * 신규 반려 사유 등록 (검수 반려 경로). REJECTION 타입 — 상태 전이 비대상으로 RESOLVED 고정.
     * UP_DATA_ISSUE_SN 은 첫 반려 시 NULL, 동일 영상 재반려 시 직전 반려를 호출자에서 지정.
     */
    public static LsDataIssue create(Long dataRawSn, String reason, String reportedUserNo) {
        return LsDataIssue.builder()
                .dataRawSn(dataRawSn)
                .issueRsn(reason)
                .reportedUserNo(reportedUserNo)
                .issueTypeCd(TYPE_REJECTION)
                .issueSttsCd(STTS_RESOLVED)
                .regDt(LocalDateTime.now())
                .build();
    }

    public static LsDataIssue createWithParent(Long dataRawSn, String reason, String reportedUserNo,
                                                Long upDataIssueSn) {
        return LsDataIssue.builder()
                .upDataIssueSn(upDataIssueSn)
                .dataRawSn(dataRawSn)
                .issueRsn(reason)
                .reportedUserNo(reportedUserNo)
                .issueTypeCd(TYPE_REJECTION)
                .issueSttsCd(STTS_RESOLVED)
                .regDt(LocalDateTime.now())
                .build();
    }

    /**
     * 작업자(또는 검수자) 문의 등록. INQUIRY 타입 — OPEN 으로 시작하는 상태 머신.
     * srcSn 은 프레임 단위 선택 참조 (NULL 가능).
     */
    public static LsDataIssue createInquiry(Long dataRawSn, String content, String reportedUserNo,
                                            Long srcSn) {
        return LsDataIssue.builder()
                .dataRawSn(dataRawSn)
                .issueRsn(content)
                .reportedUserNo(reportedUserNo)
                .issueTypeCd(TYPE_INQUIRY)
                .issueSttsCd(STTS_OPEN)
                .srcSn(srcSn)
                .regDt(LocalDateTime.now())
                .build();
    }

    public boolean isInquiry() {
        return TYPE_INQUIRY.equals(this.issueTypeCd);
    }

    public boolean isResolved() {
        return STTS_RESOLVED.equals(this.issueSttsCd);
    }

    /**
     * REVIEWER 답변 시 OPEN → ANSWERED 자동 전이 (INQUIRY 만). 이미 ANSWERED/RESOLVED 면 전이 없음(멱등).
     * REJECTION 은 상태 전이 비대상.
     */
    public void markAnswered() {
        if (isInquiry() && STTS_OPEN.equals(this.issueSttsCd)) {
            this.issueSttsCd = STTS_ANSWERED;
        }
    }

    /**
     * 해소 처리 (REVIEWER). INQUIRY 의 OPEN/ANSWERED → RESOLVED. 이미 RESOLVED 면 멱등(예외 없음).
     * REJECTION 은 이미 RESOLVED 이므로 멱등 처리된다. 역행은 발생하지 않는다.
     */
    public void resolve() {
        this.issueSttsCd = STTS_RESOLVED;
    }

    /**
     * 댓글 작성 가능 여부 검증 (RESOLVED INQUIRY 는 409). REJECTION 은 상태와 무관하게 허용.
     */
    public void assertCommentable() {
        if (isInquiry() && isResolved()) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 해소된 문의에는 댓글을 작성할 수 없습니다.");
        }
    }
}
