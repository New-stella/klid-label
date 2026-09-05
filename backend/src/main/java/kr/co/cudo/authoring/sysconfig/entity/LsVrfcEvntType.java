package kr.co.cudo.authoring.sysconfig.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 검증 이벤트 유형 카탈로그 (LS_VRFC_EVNT_TYPE, V17) — <b>저작도구 소유</b>. [design: ERD-033]
 *
 * <p>외부 시계열 분석 위탁이 쓰는 이벤트 유형의 <b>표시명·화면 노출 순서</b>를 보관한다. 이 표의
 * {@code VRFC_EVNT_TYPE_CD} 는 관제 인입 원장({@code LS_DATA_INGEST.VRFC_EVNT_TYPE_CD})이 실어
 * 보내는 값과 <b>같은 값 공간</b>이다.
 *
 * <h3>★ 이 표는 허용목록이 아니다 (되돌리지 말 것)</h3>
 * <p>확정 정책은 <b>검증 이벤트 유형 값이 미수신이거나 우리 목록 밖이어도 그대로 실어 위탁하고,
 * 수용 여부는 사업자 응답이 정한다</b>이다. 위탁 가능 여부를 이 표로 게이팅하면 그 정책이 뒤집힌다 —
 * 과거에 사업자 열거값의 사본이 두 번째 진실원이 되어 <b>정상 값을 우리가 먼저 막은</b> 결함이 있었다.
 * 이 표에 없는 유형의 영상도 위탁은 그대로 나가고 <b>질문 칸만 빈다</b>.
 *
 * <h3>★ 관제 이벤트유형 마스터와는 다른 코드 체계다</h3>
 * <p>{@code LS_EVNT_TYPE}(관제가 채번한 {@code EV…} 코드)와 <b>값 공간이 다르다</b>. 두 표를 합치거나
 * 참조로 잇지 않는다 — 이으면 한쪽에 없는 값이 다른 쪽의 등록을 막는다. 두 코드를 짝지어 보여줘야
 * 하면 <b>관제 인입 원장이 이미 그 짝을 함께 실어 보내므로 그것을 읽는다</b>(별도 매핑표를 만들지
 * 않는다 — 사본은 두 번째 진실원이 된다).
 *
 * <p><b>등록·수정·삭제 통로를 두지 않는다</b> — 시드는 연동 규격서의 지원 이벤트 목록에서 왔고,
 * 이번 범위에서 운영자가 고치는 것은 <b>유형별 질문 목록</b>({@link LsVrfcEvntQstn})뿐이다.
 */
@Entity
@Table(name = "LS_VRFC_EVNT_TYPE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsVrfcEvntType {

    /**
     * 검증이벤트유형코드 (PK) — 외부 시계열 위탁 요청에 싣는 값이자 질문 목록의 소유 키.
     * 표준도메인 코드V20.
     */
    @Id
    @Column(name = "VRFC_EVNT_TYPE_CD", length = 20, nullable = false)
    private String vrfcEvntTypeCd;

    /** 검증이벤트유형명 — 화면에서 사람이 읽는 이름. 표준도메인 명V300. */
    @Column(name = "VRFC_EVNT_TYPE_NM", length = 300, nullable = false)
    private String vrfcEvntTypeNm;

    /** 검증이벤트유형설명 — 그 유형이 어떤 상황을 가리키는지의 서술. 비어 있어도 된다. */
    @Column(name = "VRFC_EVNT_TYPE_EXPLN", length = 4000)
    private String vrfcEvntTypeExpln;

    /**
     * 정렬순서 — <b>화면 표시 순서</b>. 질문의 「첫 번째」 판정과는 <b>다른 축</b>이다
     * (그 축은 {@link LsVrfcEvntQstn#getSortSeq()} 이며 유형 안에서 유일 제약이 걸려 있다).
     *
     * <p>컬럼은 표준도메인 순서N10({@code numeric(10)})이며 자바 타입은 이 저장소의 기존
     * {@code SORT_SEQ} 관례({@code LsLabel}·{@code LsLabelAttr}·{@code LsLabelPresetCode})와 같은
     * {@code Integer} 를 쓴다 — 실제 값은 목록 순번이라 int 범위를 넘지 않는다.
     */
    @Column(name = "SORT_SEQ", nullable = false)
    private Integer sortSeq;

    /** 등록자아이디. */
    @Column(name = "REG_ID", length = 30)
    private String regId;

    /** 등록일시. */
    @Column(name = "REG_DT", nullable = false, updatable = false)
    private LocalDateTime regDt;

    /** 수정자아이디. */
    @Column(name = "MDFR_ID", length = 30)
    private String mdfrId;

    /** 수정일시. */
    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;
}
