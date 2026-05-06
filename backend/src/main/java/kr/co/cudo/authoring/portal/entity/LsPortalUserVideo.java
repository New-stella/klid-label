package kr.co.cudo.authoring.portal.entity;

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
 * Phase 11 — 포털 사용자 업로드 영상 메타 (LS_PORTAL_USER_VIDEO).
 *
 * <ul>
 *   <li>PORTAL_USER_NO : 포털 발급 토큰의 sub 클레임 그대로 저장 (IDOR 차단 키).</li>
 *   <li>FILE_PATH      : STORAGE_RAW_PATH/portal/{userId}/{fileId} (서버 측 저장 경로).</li>
 *   <li>DOWNLOAD_DDLN_DT : V1.5 정책 — 컬럼만 보유. 만료 정책/UI 는 포털 자체 책임.</li>
 * </ul>
 */
@Entity
@Table(name = "LS_PORTAL_USER_VIDEO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPortalUserVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "PORTAL_VIDEO_SN")
    private Long portalVideoSn;

    @Column(name = "PORTAL_USER_NO", nullable = false, length = 50)
    private String portalUserNo;

    @Column(name = "FILE_NAME", length = 255)
    private String fileName;

    @Column(name = "FILE_PATH", length = 500)
    private String filePath;

    @Column(name = "FILE_SIZE")
    private Long fileSize;

    @Column(name = "MIME_TYPE", length = 50)
    private String mimeType;

    @Column(name = "THUMBNAIL_PATH", length = 500)
    private String thumbnailPath;

    @Column(name = "DOWNLOAD_DDLN_DT")
    private LocalDateTime downloadDdlnDt;

    @Column(name = "REGISTERED_AT", nullable = false)
    private LocalDateTime registeredAt;

    @Builder
    private LsPortalUserVideo(String portalUserNo, String fileName, String filePath,
                              Long fileSize, String mimeType, String thumbnailPath) {
        this.portalUserNo = portalUserNo;
        this.fileName = fileName;
        this.filePath = filePath;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.thumbnailPath = thumbnailPath;
        this.registeredAt = LocalDateTime.now();
    }

    public static LsPortalUserVideo create(String portalUserNo, String fileName, String filePath,
                                           Long fileSize, String mimeType) {
        return LsPortalUserVideo.builder()
                .portalUserNo(portalUserNo)
                .fileName(fileName)
                .filePath(filePath)
                .fileSize(fileSize)
                .mimeType(mimeType)
                .build();
    }
}
