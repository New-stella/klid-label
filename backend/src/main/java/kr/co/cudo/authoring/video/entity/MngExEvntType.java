package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 관제 이벤트 타입 마스터 (klid_system.MNG_EX_EVNT_TYPE). 저작도구는 읽기 전용 (@Immutable).
 *
 * <p>실제 관제 스키마(klid_system 실 DB 조회로 확정)와 정합한다. PK = EVNT_TYPE_CD
 * ('EV' + 클래스2 + 카테고리2 + 상세2, 예 EV02000201). 적재/조회에 필요한 컬럼만 매핑한다 —
 * {@code ddl-auto=validate} 는 매핑된 컬럼만 검사하므로 수집 파라미터(SMP_CYCL/MAX_CLCT_NOCS/
 * VLM_THLD 등)는 매핑하지 않는다. MNG_* 는 관제팀 소유이므로 어떤 쓰기도 하지 않는다.
 *
 * <p>라벨 도출: (EVNT_CLS_CD, EVNT_CTGRY_CD) 로 {@link MngExEvntTypeMap} 의 CD_TYPE='02'
 * 행을 찾으면 카테고리 한글명(EVNT_NM)이 라벨이 된다. {@code CLCT_EVNT_NM} 은 수집 키워드이며
 * 라벨이 아니다.
 */
@Entity
@Table(name = "MNG_EX_EVNT_TYPE")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngExEvntType {

    /** 이벤트 유형 코드 (PK) — 'EV'+클래스2+카테고리2+상세2. */
    @Id
    @Column(name = "EVNT_TYPE_CD", length = 20)
    private String evntTypeCd;

    /** 대분류 코드(01자연재난/02생활안전/03교통안전/05범죄안전/07기타/08ignore 등) — NOT NULL. */
    @Column(name = "EVNT_CLS_CD", length = 2, nullable = false)
    private String evntClsCd;

    /** 카테고리 코드 — MAP CD_TYPE='02' 조인 키, NOT NULL. */
    @Column(name = "EVNT_CTGRY_CD", length = 4, nullable = false)
    private String evntCtgryCd;

    /** 수집 키워드(라벨 아님). 콤마 구분 다수 키워드 — 길이 큼(varchar 4000), nullable. */
    @Column(name = "CLCT_EVNT_NM", length = 4000)
    private String clctEvntNm;

    /** 수집 여부 Y/N — NOT NULL. */
    @Column(name = "CLCT_YN", length = 2, nullable = false)
    private String clctYn;
}
