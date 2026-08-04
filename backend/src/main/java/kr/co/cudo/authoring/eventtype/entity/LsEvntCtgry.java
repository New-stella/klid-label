package kr.co.cudo.authoring.eventtype.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 이벤트카테고리 마스터 (LS_EVNT_CTGRY, V168) — <b>저작도구 소유</b>.
 *
 * <p>원래 이벤트 구조는 <b>3계층</b>(대분류 → 카테고리 → 유형)이고, 관제 마스터에서 <b>사람이 읽는
 * 이름은 카테고리 레벨에만</b> 있었다(구 {@code MNG_EX_EVNT_TYPE_MAP} 의 {@code CD_TYPE='02'} 10건).
 * 이 테이블이 그 이름의 이관처이며, 유형에 고유 이름이 없을 때 표시명이 여기로 폴백한다
 * ({@link kr.co.cudo.authoring.eventtype.policy.EventTypeDisplayNamePolicy}).
 *
 * <p>어노테이션(export {@code NiaVideo})에는 카테고리가 나가지 않는다 — 그럼에도 유지하는 이유는
 * ①표시명 폴백의 근거이고 ②카테고리 단위 집계의 유일한 원천이기 때문이다.
 */
@Entity
@Table(name = "LS_EVNT_CTGRY")
@IdClass(LsEvntCtgry.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsEvntCtgry {

    /** 이벤트분류코드(대분류) — 복합 PK 상위. */
    @Id
    @Column(name = "EVNT_CLSF_CD", length = 20)
    private String evntClsfCd;

    /** 이벤트카테고리코드 — 복합 PK 하위. */
    @Id
    @Column(name = "EVNT_CTGRY_CD", length = 20)
    private String evntCtgryCd;

    /** 이벤트카테고리명 — 표시명 4단 폴백의 3순위. */
    @Column(name = "EVNT_CTGRY_NM", length = 200)
    private String evntCtgryNm;

    /** 등록일시. */
    @Column(name = "REG_DT", nullable = false, updatable = false)
    private java.time.LocalDateTime regDt;

    /** 복합 PK — (대분류, 카테고리). */
    @Getter
    @NoArgsConstructor
    public static class Key implements Serializable {
        private String evntClsfCd;
        private String evntCtgryCd;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return java.util.Objects.equals(evntClsfCd, other.evntClsfCd)
                    && java.util.Objects.equals(evntCtgryCd, other.evntCtgryCd);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(evntClsfCd, evntCtgryCd);
        }
    }
}
