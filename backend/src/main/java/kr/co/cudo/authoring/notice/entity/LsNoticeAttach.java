package kr.co.cudo.authoring.notice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 게시판(공지) 첨부파일 엔티티.
 *
 * <p>공지({@link LsNotice}) Aggregate 에 종속되는 첨부파일이지만, 프로젝트 규칙(Aggregate 간
 * ID 참조)에 따라 객체 참조(@ManyToOne) 대신 {@code noticeSn} Long FK 값만 보관한다.
 * 생성은 정적 팩토리({@link #create}) 로만 수행하며 Setter 는 두지 않는다.
 *
 * <p>저장 파일명({@code storeFileNm}) 은 UUID 기반 안전 파일명이며, 사용자 원본 파일명
 * ({@code orgnlFileNm}) 은 DB 에만 보관해 다운로드 시 Content-Disposition 으로 복원한다.
 * {@code filePath}(절대 경로) 는 외부 응답 DTO 에 절대 포함하지 않는다 (CWE-209).
 */
@Entity
@Table(name = "LS_NOTICE_ATTACH")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsNoticeAttach {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ATTACH_SN")
    private Long attachSn;

    /** 소속 공지 PK — Aggregate 간 ID 참조 (객체 참조 금지). */
    @Column(name = "NOTICE_SN", nullable = false)
    private Long noticeSn;

    @Column(name = "ORGNL_FILE_NM", nullable = false, length = 255)
    private String orgnlFileNm;

    @Column(name = "STORE_FILE_NM", nullable = false, length = 255)
    private String storeFileNm;

    @Column(name = "FILE_PATH", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "FILE_SZ", nullable = false)
    private Long fileSize;

    @Column(name = "REG_DT", nullable = false, updatable = false)
    private LocalDateTime regDt;

    private LsNoticeAttach(Long noticeSn, String orgnlFileNm, String storeFileNm,
                          String filePath, Long fileSize) {
        this.noticeSn = noticeSn;
        this.orgnlFileNm = orgnlFileNm;
        this.storeFileNm = storeFileNm;
        this.filePath = filePath;
        this.fileSize = fileSize;
    }

    /**
     * 정적 팩토리.
     *
     * @param noticeSn    소속 공지 PK
     * @param orgnlFileNm 사용자 원본 파일명 (DB 보관용 — 다운로드 시 복원)
     * @param storeFileNm 저장 파일명 (UUID + 확장자)
     * @param filePath    저장 파일 절대 경로 (외부 미노출)
     * @param fileSize    파일 크기 (byte)
     */
    public static LsNoticeAttach create(Long noticeSn, String orgnlFileNm, String storeFileNm,
                                        String filePath, Long fileSize) {
        return new LsNoticeAttach(noticeSn, orgnlFileNm, storeFileNm, filePath, fileSize);
    }

    @PrePersist
    void onCreate() {
        this.regDt = LocalDateTime.now();
    }
}
