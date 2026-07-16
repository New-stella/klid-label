package kr.co.cudo.authoring.notice.entity;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 게시판(공지사항) Aggregate Root.
 *
 * <p>발행 상태({@link PublishStatus})는 DRAFT(작성중) → PUBLISHED(발행) 전이를 가지며,
 * publish/unpublish 는 멱등(이미 같은 상태면 no-op)으로 동작한다. 상태 변경은 반드시
 * 본 Root 의 도메인 메서드(publish/unpublish/update)를 통해서만 수행한다 (Setter 금지).
 */
@Entity
@Table(name = "LS_NOTICE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsNotice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "NOTICE_SN")
    private Long noticeSn;

    @Column(name = "NOTICE_TITLE", nullable = false, length = 200)
    private String title;

    @Column(name = "NOTICE_CN", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 상단 고정 여부 — DB 컨벤션상 "Y"/"N" 문자열로 저장 (boolean 접근자는 {@link #isPinned()}). */
    @Column(name = "UPEND_FIX_YN", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String pinYn;

    @Enumerated(EnumType.STRING)
    @Column(name = "PBLCN_STTS_CD", nullable = false, length = 16)
    private PublishStatus pubStatus;

    @Column(name = "PBLCN_DT")
    private LocalDateTime pubDt;

    @Column(name = "REG_ID", length = 64, updatable = false)
    private String regId;

    @Column(name = "REG_DT", nullable = false, updatable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFR_ID", length = 64)
    private String mdfrId;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    private static final String Y = "Y";
    private static final String N = "N";

    private LsNotice(String title, String content, boolean pinned, String regId) {
        this.title = title;
        this.content = content;
        this.pinYn = toYn(pinned);
        this.pubStatus = PublishStatus.DRAFT;
        this.regId = regId;
        this.mdfrId = regId;
    }

    private static String toYn(boolean value) {
        return value ? Y : N;
    }

    /**
     * 정적 팩토리. 기본 상태는 DRAFT(미발행) 이다.
     *
     * @param title   제목 (1~200자)
     * @param content 내용
     * @param pinned  상단 고정 여부
     * @param regId   등록자 ID
     */
    public static LsNotice create(String title, String content, boolean pinned, String regId) {
        return new LsNotice(title, content, pinned, regId);
    }

    /** 기본 정보(제목/내용/고정) 갱신. 발행 상태는 본 메서드로 변경하지 않는다. */
    public void update(String title, String content, boolean pinned, String mdfrId) {
        this.title = title;
        this.content = content;
        this.pinYn = toYn(pinned);
        this.mdfrId = mdfrId;
    }

    /** 상단 고정 여부 (Y/N 문자열 → boolean). */
    public boolean isPinned() {
        return Y.equals(this.pinYn);
    }

    /**
     * 발행 처리. 이미 PUBLISHED 면 no-op(멱등) — PBLCN_DT 불변.
     * 아니면 PUBLISHED 로 전이하고 PBLCN_DT 를 현재 시각으로 설정한다.
     */
    public void publish() {
        if (this.pubStatus == PublishStatus.PUBLISHED) {
            return;
        }
        this.pubStatus = PublishStatus.PUBLISHED;
        this.pubDt = LocalDateTime.now();
    }

    /**
     * 발행 취소. 이미 DRAFT 면 no-op(멱등).
     * 아니면 DRAFT 로 전이하고 PBLCN_DT 를 null 로 초기화한다.
     */
    public void unpublish() {
        if (this.pubStatus == PublishStatus.DRAFT) {
            return;
        }
        this.pubStatus = PublishStatus.DRAFT;
        this.pubDt = null;
    }

    /** 발행 여부. */
    public boolean isPublished() {
        return this.pubStatus == PublishStatus.PUBLISHED;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.regDt = now;
        this.mdfcnDt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.mdfcnDt = LocalDateTime.now();
    }

    /** 발행 상태. */
    public enum PublishStatus {
        DRAFT,
        PUBLISHED
    }
}
