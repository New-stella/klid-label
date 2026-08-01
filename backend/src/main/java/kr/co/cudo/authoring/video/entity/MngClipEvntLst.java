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
 * <p><b>촬영환경(WTHR_CD/SESN_CD/HR_TYPE_CD)·개인정보유형(PRVC_TYPE_CD) 관제 원천 매핑은 코드도메인
 * 확정 후 별건</b>이다 — 관제 코드값↔의미 매핑(ERD-024)이 미상이라 지금 매핑하면 그 해석 자체가
 * 추정(self-fill)이 된다. 확정 전까지 저작도구의 촬영환경 원천은 작업자 수동 입력(LS_DATA_RAW, V130)
 * 하나이며, 미입력은 null(미상)로 동결된다(E-ISSUE-42).
 *
 * <p><b>미해소 — {@code PRVC_TYPE_CD}(L-2, Phase 10B)</b>: 관제 원천이 여기 실재하는데도 적재는
 * {@code TrainingVideoIngestTx.DEFAULT_PRVC_TYPE = PRVC_TYPE_ANONY} 하드코딩을 쓴다. 그 결과 모든 적재
 * 영상이 "익명"으로 고정되어 export 의 {@code pseudonymity} 가 항상 {@code "N"} 으로 나간다(관제 실값이
 * 가명이어도 그렇다). 위 코드도메인 확정 전에는 매핑 자체가 추정이 되므로 <b>의도적으로 미해소로 둔다</b> —
 * 확정 시 이 컬럼을 매핑하고 하드코딩을 제거해야 한다.
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
