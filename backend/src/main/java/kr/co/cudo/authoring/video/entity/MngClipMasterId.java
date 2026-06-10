package kr.co.cudo.authoring.video.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link MngClipMaster} 복합 식별자 — 관제 klid_system.MNG_CLIP_MASTER 의 PK (EVNT_ID, CLIP_TYPE_CD).
 *
 * <p>관제 소유 테이블의 실제 스키마와 정합한다. {@code @IdClass} 로 엔티티에 결합되며 직렬화 가능해야 한다.
 */
public class MngClipMasterId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String evntId;
    private String clipTypeCd;

    protected MngClipMasterId() {
    }

    public MngClipMasterId(String evntId, String clipTypeCd) {
        this.evntId = evntId;
        this.clipTypeCd = clipTypeCd;
    }

    public String getEvntId() {
        return evntId;
    }

    public String getClipTypeCd() {
        return clipTypeCd;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MngClipMasterId that)) {
            return false;
        }
        return Objects.equals(evntId, that.evntId) && Objects.equals(clipTypeCd, that.clipTypeCd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(evntId, clipTypeCd);
    }
}
