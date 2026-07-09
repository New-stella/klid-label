package kr.co.cudo.authoring.video.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link MngExEvntTypeMap} 복합 식별자 — 관제 klid_system.MNG_EX_EVNT_TYPE_MAP 의 PK
 * (CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD).
 *
 * <p>관제 소유 테이블의 실제 스키마와 정합한다. 카테고리명행(CD_TYPE='02')·대분류명행(CD_TYPE='01')은
 * DTL_EVNT/EVNT_TYPE_CD 가 빈 문자열('')이라 단일 EVNT_TYPE_CD 만으로는 식별이 불가하여 5컬럼 복합
 * 키가 필요하다. {@code @IdClass} 로 엔티티에 결합되며 직렬화 가능해야 한다.
 */
public class MngExEvntTypeMapId implements Serializable {

    private static final long serialVersionUID = 1L;

    private String cdType;
    private String evntClsCd;
    private String evntCtgryCd;
    private String dtlEvnt;
    private String evntTypeCd;

    protected MngExEvntTypeMapId() {
    }

    public MngExEvntTypeMapId(String cdType, String evntClsCd, String evntCtgryCd,
                              String dtlEvnt, String evntTypeCd) {
        this.cdType = cdType;
        this.evntClsCd = evntClsCd;
        this.evntCtgryCd = evntCtgryCd;
        this.dtlEvnt = dtlEvnt;
        this.evntTypeCd = evntTypeCd;
    }

    public String getCdType() {
        return cdType;
    }

    public String getEvntClsCd() {
        return evntClsCd;
    }

    public String getEvntCtgryCd() {
        return evntCtgryCd;
    }

    public String getDtlEvnt() {
        return dtlEvnt;
    }

    public String getEvntTypeCd() {
        return evntTypeCd;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MngExEvntTypeMapId that)) {
            return false;
        }
        return Objects.equals(cdType, that.cdType)
                && Objects.equals(evntClsCd, that.evntClsCd)
                && Objects.equals(evntCtgryCd, that.evntCtgryCd)
                && Objects.equals(dtlEvnt, that.dtlEvnt)
                && Objects.equals(evntTypeCd, that.evntTypeCd);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cdType, evntClsCd, evntCtgryCd, dtlEvnt, evntTypeCd);
    }
}
