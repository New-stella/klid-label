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
 * LogiCraft <b>ERD-024</b>(관제 공유 클립 ERD)에 기록된 12컬럼을 전부 매핑한다(V147 — 실 테이블의
 * 그 밖의 컬럼은 여전히 미매핑이며 {@code ddl-auto=validate} 는 매핑 컬럼만 검사한다). 컬럼 물리명·
 * 타입·길이는 관제가 소유한 실제 스키마 그대로이며 저작도구가 임의로 정하지 않는다.
 * MNG_* 는 관제팀 소유이므로 어떤 쓰기도 하지 않는다({@code @Immutable} — 제거 금지).
 *
 * <p><b>코드값 해석은 allowlist 판정만 한다(중요)</b>: V147 로 촬영환경(WTHR_CD/SESN_CD/HR_TYPE_CD)·
 * 개인정보유형(PRVC_TYPE_CD) 컬럼을 읽을 수 있게 되었으나, 관제 코드값↔저작도구 코드도메인의
 * <b>대응표는 여전히 미확정</b>이다(관제팀 확인 필요). 값을 임의 변환하면 그 변환이 곧 추정(self-fill)이
 * 되므로, {@code ControlClipMetaResolver} 는 저작도구 허용 어휘와 <b>그대로 일치하는 값만 채택</b>하고
 * 미매칭 값은 채택하지 않은 채 WARN 으로 드러낸다(미검증 문자열이 동결·export 로 새는 것을 차단).
 * 대응표가 확정되면 변환 규칙은 그 해석기 한 곳에만 추가한다.
 *
 * <p><b>{@code WTHR_CD} 는 소비하지 않는다</b>(2026-07-31 사용자 확정) — 코드값↔표시명 대응표가 없어
 * 영구 미매칭이라, 날씨는 저작도구 수동 입력이 유일한 원천이다. 컬럼 매핑은 {@code validate} 정합을 위해
 * 유지하되 해석기는 읽지 않는다.
 *
 * <p>채택되지 않은 촬영환경은 {@code LS_DATA_RAW} 에서 null(미상)로 남고, 기존대로 작업자 수동 입력
 * (V130)이 원천이 되며 미입력은 null 로 동결된다(E-ISSUE-42 — 촬영일시 파생 추정값은 적재하지 않는다).
 *
 * <p>적재 매핑(MNG_CLIP_EVNT_LST → LS_DATA_RAW):
 * <ul>
 *   <li>{@code EVNT_TYPE_CD} → LS_DATA_RAW.evntTypeCd (관제 마스터에 직접 컬럼 부재 → 이벤트리스트 조인)</li>
 *   <li>{@code SHT_DT}(촬영 일자) → LS_DATA_RAW.shtDt (CRT_DT 근사를 실제값으로 교정)</li>
 *   <li>{@code PRVC_TYPE_CD} → LS_DATA_RAW.prvcTypeCd (ANONY/PRVC/PSDO 만 채택, 그 외·미제공은
 *       <b>PRVC</b> — 관제가 값을 주지 않으므로 "개인정보 있음"으로 본다, 2026-07-31 확정)</li>
 *   <li>{@code HR_TYPE_CD}/{@code SESN_CD} → LS_DATA_RAW.dayNgtCd/sesnCd
 *       (허용 어휘 매칭 시에만 채택, 그 외·미제공은 null)</li>
 *   <li>{@code WTHR_CD} → <b>매핑 없음</b>(소비하지 않음). LS_DATA_RAW.wthrNm 은 수동 입력 전용</li>
 * </ul>
 *
 * <p><b>⚠ 후속 — 배포 전 실 관제 스키마 1회 확인 필요 (2026-07-31)</b>: 이 클래스와
 * {@link MngClipMaster} 의 컬럼 매핑은 <b>ERD-024(2026-06-10 조회) 신뢰에 전적으로 의존</b>한다.
 * {@code ddl-auto=validate} 는 매핑 컬럼의 이름·타입을 검사하므로 <b>실 관제 스키마와 하나라도 다르면
 * stg/prd 기동이 실패</b>한다. 로컬/테스트는 저작도구 stub(V147)을 보므로 이 드리프트를 잡지 못한다 —
 * <b>배포 전 dev 환경에서 실 관제 DB 를 대상으로 1회 검증</b>할 것.
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

    /** 이벤트명. */
    @Column(name = "EVNT_NM", length = 200)
    private String evntNm;

    /** 지자체 코드. */
    @Column(name = "LCLGV_CD", length = 20)
    private String lclgvCd;

    /** 계절 코드 — 대응표 미확정, 허용 어휘 매칭 시에만 적재 채택(위 클래스 주석 참조). */
    @Column(name = "SESN_CD", length = 10)
    private String sesnCd;

    /** 날씨 코드 — 대응표 미확정, 허용 어휘 매칭 시에만 적재 채택. */
    @Column(name = "WTHR_CD", length = 10)
    private String wthrCd;

    /** 시간 유형 코드 — 대응표 미확정, 허용 어휘(DAY/NGT) 매칭 시에만 적재 채택. */
    @Column(name = "HR_TYPE_CD", length = 10)
    private String hrTypeCd;

    /**
     * 개인정보 유형 — ANONY/PRVC/PSDO 만 적재 채택, 그 외·미제공은 <b>PRVC</b> 폴백
     * (관제가 값을 주지 않으므로 "개인정보 있음"으로 본다, 2026-07-31 확정 — {@code ControlClipMetaResolver}).
     *
     * <p>⚠ 폴백을 {@code ANONY} 로 되돌리지 말 것 — {@code ANONY} 는 {@code needsDeidentify()} 를 false 로
     * 만들어 비식별본이 없는 영상의 <b>원본 프레임 노출 폴백</b>을 다시 연다(CWE-359).
     */
    @Column(name = "PRVC_TYPE_CD", length = 20)
    private String prvcTypeCd;

    /** 비식별화 처리 여부 (Y/N) — 관제 측 처리 이력. */
    @Column(name = "IDNTF_YN", length = 1)
    private String idntfYn;

    /** 수집 경로. */
    @Column(name = "CLCT_PATH", length = 255)
    private String clctPath;

    /** 수집 출처. */
    @Column(name = "CLCT_SRC", length = 255)
    private String clctSrc;
}
