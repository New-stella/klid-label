package kr.co.cudo.authoring.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Phase 7 — 검수 반려 사유 (LS_DATA_ISSUE).
 *
 * <p>DB설계서 §5A.6 — 좌표 컬럼 없음 (영상 단위 반려). 계층형 (UP_DATA_ISSUE_SN 자기참조).
 * DATA_RAW_SN(=LS_DATA_RAW.RAW_SN) 영상 단위 참조 (행안부 공통표준 약어 정합).
 * 외래키는 정의하지 않음 (klid_system 공유 DB 정책 — 운영 안정성 우선).
 */
@Entity
@Table(name = "LS_DATA_ISSUE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataIssue {

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

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Builder
    private LsDataIssue(Long upDataIssueSn, Long dataRawSn, String issueRsn,
                        String reportedUserNo, LocalDateTime regDt) {
        this.upDataIssueSn = upDataIssueSn;
        this.dataRawSn = dataRawSn;
        this.issueRsn = issueRsn;
        this.reportedUserNo = reportedUserNo;
        this.regDt = regDt;
    }

    /**
     * 신규 반려 사유 등록. UP_DATA_ISSUE_SN 은 첫 반려 시 NULL,
     * 동일 영상 재반려 시 직전 반려를 가리키도록 호출자에서 지정.
     */
    public static LsDataIssue create(Long dataRawSn, String reason, String reportedUserNo) {
        return LsDataIssue.builder()
                .dataRawSn(dataRawSn)
                .issueRsn(reason)
                .reportedUserNo(reportedUserNo)
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
                .regDt(LocalDateTime.now())
                .build();
    }
}
