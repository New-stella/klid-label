package kr.co.cudo.authoring.version.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LS_DATA_LBL_HSTRY: 라벨 변경 이력 + Gitea 커밋 추적 (Phase 8).
 *
 * <p>1 row = 1 commit. SRC_SN(프레임) 단위로 묶어서 commit 하므로 LBL_SN 은 nullable.
 * <p>장애 시에는 GITEA_CMT_HASH 가 null 인 채로 history 만 남고 fallback 큐에서 채워진다.
 */
@Entity
@Table(name = "LS_DATA_LBL_HSTRY")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDataLblHstry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "LBL_HSTRY_SN")
    private Long lblHstrySn;

    @Column(name = "LBL_SN")
    private Long lblSn;

    @Column(name = "SRC_SN", nullable = false)
    private Long srcSn;

    @Column(name = "GITEA_CMT_HASH", length = 40)
    private String giteaCmtHash;

    @Lob
    @Column(name = "LABELS_JSON_SNAPSHOT")
    private String labelsJsonSnapshot;

    @Column(name = "REGISTERED_USER_NO", length = 50)
    private String registeredUserNo;

    @Column(name = "REGISTERED_AT", nullable = false)
    private LocalDateTime registeredAt;

    private LsDataLblHstry(Long srcSn, String giteaCmtHash, String registeredUserNo, String labelsJsonSnapshot) {
        if (srcSn == null) {
            throw new IllegalArgumentException("srcSn 은 필수입니다.");
        }
        this.srcSn = srcSn;
        this.giteaCmtHash = giteaCmtHash;
        this.registeredUserNo = registeredUserNo;
        this.labelsJsonSnapshot = labelsJsonSnapshot;
        this.registeredAt = LocalDateTime.now();
    }

    /** Gitea 커밋 성공 시 — hash 채워진 이력 생성. */
    public static LsDataLblHstry create(Long srcSn, String giteaCmtHash,
                                         String registeredUserNo, String labelsJsonSnapshot) {
        return new LsDataLblHstry(srcSn, giteaCmtHash, registeredUserNo, labelsJsonSnapshot);
    }

    /** 장애 시 — hash 비어있는 이력 (fallback 큐에서 나중에 채움). */
    public static LsDataLblHstry createPending(Long srcSn, String registeredUserNo, String labelsJsonSnapshot) {
        return new LsDataLblHstry(srcSn, null, registeredUserNo, labelsJsonSnapshot);
    }

    /** fallback 큐가 재시도 성공 시 hash 보강. */
    public void fillGiteaHash(String giteaCmtHash) {
        this.giteaCmtHash = giteaCmtHash;
    }
}
