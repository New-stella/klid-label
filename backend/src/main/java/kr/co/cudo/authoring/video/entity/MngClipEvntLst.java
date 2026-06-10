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

import java.time.LocalDateTime;

/**
 * 관제서버 클립 이벤트 리스트 (klid_system.MNG_CLIP_EVNT_LST). 저작도구는 읽기 전용 (@Immutable).
 *
 * <p>실제 관제 스키마(DB 직접 조회로 확정)와 정합한다. 복합 PK = (EVNT_ID, EVNT_TYPE_CD).
 * 적재에 필요한 컬럼만 매핑한다 — {@code ddl-auto=validate} 는 매핑된 컬럼만 검사하므로 미매핑
 * 컬럼(SESN_CD/WTHR_CD/HR_TYPE_CD/PRVC_TYPE_CD/LCLGV_CD 등 28컬럼 중 나머지)은 생략한다
 * (향후 enrich 여지). MNG_* 는 관제팀 소유이므로 어떤 쓰기도 하지 않는다.
 *
 * <p>적재 매핑(MNG_CLIP_EVNT_LST → LS_DATA_RAW):
 * <ul>
 *   <li>{@code EVNT_TYPE_CD} → LS_DATA_RAW.evntTypeCd (관제 마스터에 직접 컬럼 부재 → 이벤트리스트 조인)</li>
 *   <li>{@code SHT_DT}(촬영 일자) → LS_DATA_RAW.shtDt (CRT_DT 근사를 실제값으로 교정)</li>
 * </ul>
 */
@Entity
@Table(name = "MNG_CLIP_EVNT_LST")
@IdClass(MngClipEvntLstId.class)
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngClipEvntLst {

    /** 이벤트 식별자 (복합 PK) — MNG_CLIP_MASTER.EVNT_ID 와 조인. */
    @Id
    @Column(name = "EVNT_ID", length = 50)
    private String evntId;

    /** 이벤트 유형 코드 (복합 PK) — LS_DATA_RAW.evntTypeCd 로 적재. */
    @Id
    @Column(name = "EVNT_TYPE_CD", length = 20)
    private String evntTypeCd;

    /** 촬영 일자 — LS_DATA_RAW.shtDt 로 적재(CRT_DT 근사 교정). */
    @Column(name = "SHT_DT")
    private LocalDateTime shtDt;
}
