package kr.co.cudo.authoring.eventtype.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 이벤트유형 마스터 (LS_EVNT_TYPE, V168) — <b>저작도구 소유</b>.
 *
 * <p>구 조달처였던 관제 공유 마스터 2종({@code MNG_EX_EVNT_TYPE} + {@code MNG_EX_EVNT_TYPE_MAP})을
 * 대체한다. 관제 2차에서 적재 주체가 반전되어 이벤트유형코드·이벤트명·이벤트분류코드가 전부
 * <b>인입 평면값</b>({@code LS_DATA_INGEST})으로 오므로, 남의 스키마를 조회하지 않고 인입 소비
 * 시점에 <b>우리 테이블에 자동 등록</b>해 관리한다.
 *
 * <h3>축은 유형(type)이다 — 카테고리 축은 폐기됐다</h3>
 * <p>어노테이션 계약(export JSON {@code NiaVideo})의 {@code event_id}/{@code event_name} 이 영상당
 * <b>단일 유형 값</b>이기 때문이다. 구 구현은 (대분류+카테고리) 키로 dedup 해 라벨을 만들었는데,
 * 그러면 같은 카테고리의 상세 유형들이 <b>구분되지 않는 하나의 이름</b>으로 뭉개져 관제가 실어 보낸
 * 유형별 이름이 표시될 자리가 없었다.
 *
 * <h3>★ 관제 칸과 운영자 칸이 분리돼 있다 (핵심 설계)</h3>
 * <ul>
 *   <li>{@link #evntNm} — <b>관제 수신 유형명</b>. 인입 소비 시점 upsert 가 값이 바뀔 때만 갱신한다.</li>
 *   <li>{@link #optrIndctNm} — <b>운영자 표시명</b>. 관리 API 전용이며 관제가 절대 쓰지 않는다.</li>
 * </ul>
 * <p>각자 자기 칸만 쓰므로 "사람이 고쳤다"는 <b>표식 컬럼이 필요 없고</b>, 운영자가 표시명을 지우면
 * 관제값으로 <b>자연 복귀</b>하며 <b>관제 원본이 유실되지 않는다</b>.
 *
 * <h3>표시명은 4단 폴백이다</h3>
 * <p>{@code COALESCE(운영자 표시명, 관제 수신 유형명, 카테고리명, 유형코드)} —
 * 판정은 {@link kr.co.cudo.authoring.eventtype.policy.EventTypeDisplayNamePolicy} <b>한 곳</b>에만
 * 있다. 관제 마스터에는 유형별 이름이 없었으므로 이관 직후에는 대부분 <b>카테고리명</b>으로 표시되며,
 * 같은 카테고리의 유형들이 같은 이름으로 보이는 것은 <b>결함이 아니라 정상 상태</b>다.
 *
 * <p><b>신규 수동 생성 경로는 없다</b> — 등록의 유일한 출처는 인입이다.
 *
 * <h3>★ 카테고리에 FK 를 걸지 않는다 (의도된 설계 — 되돌리지 말 것)</h3>
 * <p>{@code (EVNT_CLSF_CD, EVNT_CTGRY_CD)} 는 {@link LsEvntCtgry} 의 복합 PK 와 같은 값이지만
 * <b>물리 FK 를 선언하지 않는다</b>. 이벤트유형은 관제 인입 소비 시점에 자동 등록되는데, FK 가
 * 있으면 관제가 <b>아직 등록되지 않은 카테고리코드</b>를 보내는 순간 그 INSERT 가 FK 위반으로
 * 실패하고 — 예외가 흡수되더라도 — <b>그 유형이 영구 미등록으로 남는다</b>. 결과적으로 그 영상은
 * 이벤트 필터에서 사라지고 라벨이 원문 코드로 떨어진다.
 * <p>정합성 문제도 생기지 않는다: 표시명 4단 폴백
 * ({@link kr.co.cudo.authoring.eventtype.policy.EventTypeDisplayNamePolicy})이 <b>카테고리명 부재를
 * 이미 정상 케이스로</b> 처리하기 때문이다(3순위가 없으면 4순위인 유형코드로 내려간다). 고아
 * 카테고리코드는 오류가 아니라 "아직 카테고리명을 모르는 상태"이며, 관제가 카테고리를 보내오면
 * 그때 이름이 붙는다. 즉 <b>참조 무결성을 지키려다 데이터가 아예 안 들어오는 교환</b>이 된다.
 *
 * <p><b>{@code @Immutable} 을 붙이지 않는다</b> — 관제 소유 스텁이던 구 엔티티와 달리 이 테이블은
 * 우리가 쓴다. 다만 임의 setter 는 두지 않고 의미 있는 상태 전이 메서드({@link #applyManagement})
 * 하나만 노출한다.
 */
@Entity
@Table(name = "LS_EVNT_TYPE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsEvntType {

    /** 수집대상 여부값 — 필터 드롭다운 노출 조건. */
    public static final String CLCT_YES = "Y";


    /** 이벤트유형코드 (PK) — {@code LS_DATA_RAW.EVNT_TYPE_CD} · export {@code event_id} 와 같은 값. */
    @Id
    @Column(name = "EVNT_TYPE_CD", length = 20)
    private String evntTypeCd;

    /**
     * <b>관제 수신</b> 이벤트유형명 — 인입({@code LS_DATA_INGEST.EVNT_NM})이 값이 바뀔 때만 갱신한다.
     *
     * <p>관제 마스터에는 <b>유형별 이름이 애초에 없었으므로</b> 이관 시점에는 비어 있다. 비어 있으면
     * 표시명이 <b>카테고리명</b>으로 폴백한다(정상 상태 — 클래스 javadoc 참조).
     * <p>⚠ 이 칸에 운영자 입력을 넣지 말 것 — 다음 인입이 덮어쓴다.
     */
    @Column(name = "EVNT_NM", length = 200)
    private String evntNm;

    /**
     * <b>운영자 표시명</b> — 관리 API({@code PATCH /v1/manage/event-types}) 전용 칸.
     *
     * <p>관제 인입은 <b>이 칸을 절대 쓰지 않는다</b>. 그래서 "사람이 고쳤다"는 별도 표식 컬럼이
     * 필요 없고, 운영자가 이 값을 <b>지우면 관제 수신명으로 자연 복귀</b>한다(관제 원본 유실 없음).
     */
    @Column(name = "OPTR_INDCT_NM", length = 200)
    private String optrIndctNm;

    /**
     * 이벤트분류코드(대분류) — 관제 인입값에서 온다. 제외 대분류 설정
     * ({@code eventtype.excluded-class-codes})의 판정축이다.
     *
     * <p><b>코드에서 유도하지 않는다</b>(사용자 확정 2026-08-04). 구 안이던
     * {@code SUBSTRING(EVNT_TYPE_CD, 3, 2)} 는 실측상 비규격 코드({@code INTRUSION} 등)가 존재해
     * <b>존재하지 않는 대분류</b>를 만들고, 그 값이 제외 목록과 우연히 겹치면 영상이 목록에서
     * 조용히 사라진다. 관제가 안 보내면 <b>null 이고 폴백하지 않는다</b>.
     */
    @Column(name = "EVNT_CLSF_CD", length = 20)
    private String evntClsfCd;

    /**
     * 이벤트카테고리코드 — 원래 3계층 구조(대분류 → 카테고리 → 유형)의 중간 레벨.
     * {@link LsEvntCtgry} 조인 키이며, 유형에 고유 이름이 없을 때 표시명이 카테고리명으로 폴백하는
     * 근거다. 관제 수신값이며 코드에서 유도하지 않는다.
     */
    @Column(name = "EVNT_CTGRY_CD", length = 20)
    private String evntCtgryCd;

    /** 수집여부 (Y/N) — 자동등록 기본값은 {@code Y}(인입으로 실제 들어온 유형이므로). */
    @Column(name = "CLCT_YN", length = 1, nullable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String clctYn;

    /** 등록일시. */
    @Column(name = "REG_DT", nullable = false, updatable = false)
    private java.time.LocalDateTime regDt;


    /** 수집대상인가 — 필터 드롭다운 노출 판정. */
    public boolean isCollected() {
        return CLCT_YES.equalsIgnoreCase(clctYn == null ? null : clctYn.trim());
    }

    /**
     * 관리 화면 정정 — <b>운영자 표시명·수집여부</b>를 고친다(REVIEWER 전용 경로에서만 호출).
     *
     * <p><b>부분 수정</b>이다: null 로 온 항목은 바꾸지 않는다(PATCH 시맨틱). 표시명을 <b>비우려면</b>
     * 빈 문자열을 보낸다 — 그러면 관제 수신명으로 자연 복귀한다(되돌리기 경로).
     *
     * <p><b>관제 칸은 건드리지 않는다</b> — {@code EVNT_NM}(관제 수신명)·{@code EVNT_CLSF_CD}·
     * {@code EVNT_CTGRY_CD} 는 관제가 보내는 사실이고, PK 는 영상이 참조하는 식별자다.
     *
     * @param newOptrIndctNm 운영자 표시명. {@code null}=미변경, 빈 문자열=해제(관제값 복귀)
     * @param newClctYn      수집여부. {@code null}=미변경
     * @return 실제로 값이 바뀌었으면 true (캐시 무효화 여부 판정에 쓴다)
     */
    public boolean applyManagement(String newOptrIndctNm, String newClctYn) {
        boolean changed = false;
        if (newOptrIndctNm != null) {
            String normalized = newOptrIndctNm.trim().isEmpty() ? null : newOptrIndctNm.trim();
            if (!java.util.Objects.equals(normalized, this.optrIndctNm)) {
                this.optrIndctNm = normalized;
                changed = true;
            }
        }
        if (newClctYn != null && !newClctYn.equals(this.clctYn)) {
            this.clctYn = newClctYn;
            changed = true;
        }
        return changed;
    }
}
