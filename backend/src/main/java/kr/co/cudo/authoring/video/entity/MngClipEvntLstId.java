package kr.co.cudo.authoring.video.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link MngClipEvntLst} 복합 식별자 — 관제 klid_system.MNG_CLIP_EVNT_LST 의 PK (EVNT_ID, EVNT_TYPE_CD).
 *
 * <p>관제 소유 테이블의 실제 스키마와 정합한다. {@code @IdClass} 로 엔티티에 결합되며 직렬화 가능해야 한다.
 */
public class MngClipEvntLstId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String evntId;
    private String evntTypeCd;

    protected MngClipEvntLstId() {
    }

    public MngClipEvntLstId(String evntId, String evntTypeCd) {
        this.evntId = evntId;
        this.evntTypeCd = evntTypeCd;
    }

    public String getEvntId() {
        return evntId;
    }

    public String getEvntTypeCd() {
        return evntTypeCd;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MngClipEvntLstId that)) {
            return false;
        }
        return Objects.equals(evntId, that.evntId) && Objects.equals(evntTypeCd, that.evntTypeCd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(evntId, evntTypeCd);
    }
}
