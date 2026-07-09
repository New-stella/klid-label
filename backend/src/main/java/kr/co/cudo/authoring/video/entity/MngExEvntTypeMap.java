package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 관제 이벤트 타입 매핑 (klid_system.MNG_EX_EVNT_TYPE_MAP). 저작도구는 읽기 전용 (@Immutable).
 *
 * <p>실제 관제 스키마(klid_system 실 DB 조회로 확정)와 정합한다. 복합 PK(5컬럼) =
 * (CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD).
 * {@code CD_TYPE='01'}=대분류명행, {@code CD_TYPE='02'}=카테고리명행이며 두 경우 DTL_EVNT/
 * EVNT_TYPE_CD 는 빈 문자열('')이다. {@code EVNT_NM} 이 한글명(라벨 소스)이다.
 *
 * <p>라벨 도출: 이벤트코드의 (EVNT_CLS_CD, EVNT_CTGRY_CD) 로 CD_TYPE='02' 행을 찾으면 카테고리
 * 한글명(EVNT_NM)을 얻는다. 예: EV02000201(cls=02, ctgry=0002) → (02,02,0002,'','').EVNT_NM='쓰러짐'.
 * MNG_* 는 관제팀 소유이므로 어떤 쓰기도 하지 않는다.
 */
@Entity
@Table(name = "MNG_EX_EVNT_TYPE_MAP")
@IdClass(MngExEvntTypeMapId.class)
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngExEvntTypeMap {

    /** 코드 구분 (복합 PK) — '01'=대분류명행 / '02'=카테고리명행. */
    @Id
    @Column(name = "CD_TYPE", length = 2)
    private String cdType;

    /** 대분류 코드 (복합 PK). */
    @Id
    @Column(name = "EVNT_CLS_CD", length = 2)
    private String evntClsCd;

    /** 카테고리 코드 (복합 PK) — 대분류명행은 ''. */
    @Id
    @Column(name = "EVNT_CTGRY_CD", length = 4)
    private String evntCtgryCd;

    /** 상세 이벤트 (복합 PK) — 카테고리/대분류명행은 ''. */
    @Id
    @Column(name = "DTL_EVNT", length = 2)
    private String dtlEvnt;

    /** 이벤트 유형 코드 (복합 PK) — 카테고리/대분류명행은 ''. */
    @Id
    @Column(name = "EVNT_TYPE_CD", length = 20)
    private String evntTypeCd;

    /** 한글명(라벨 소스). */
    @Column(name = "EVNT_NM", length = 4000)
    private String evntNm;

    /** 사용 여부 Y/N — 실제 관제 스키마 NOT NULL. */
    @Column(name = "USE_YN", length = 2, nullable = false)
    private String useYn;
}
