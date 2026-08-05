package kr.co.cudo.authoring.eventtype.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.eventtype.entity.LsEvntType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 이벤트유형 마스터({@code LS_EVNT_TYPE}, V168) 리포지토리 — <b>저작도구 소유</b>.
 *
 * <p>조회는 {@code findAll()} 1회 로드로 충분하다(코드 체계라 행수가 수십 규모이고, 소비측
 * {@code EventTypeService} 가 장수명 캐시로 감싼다). 조건 조회 메서드를 늘리면 캐시 키가 갈라져
 * 필터·라벨·검증이 서로 다른 스냅샷을 보게 된다.
 */
@ControlRepo
public interface LsEvntTypeRepository extends JpaRepository<LsEvntType, String> {

    /**
     * <b>자동등록 — 원자 upsert(등록 + 조건부 갱신)</b>.
     *
     * <h3>왜 조회 후 INSERT 가 아닌가 (CWE-362)</h3>
     * <p>2노드 Active-Active 라 같은 신규 유형이 두 노드에서 동시에 인입될 수 있다. "없으면 넣는다"를
     * 조회 → INSERT 두 문장으로 쓰면 두 노드가 모두 "없음"을 관측한 뒤 각각 INSERT 해 <b>PK 위반</b>이
     * 난다. PostgreSQL 은 제약 위반 시 <b>트랜잭션 전체를 abort</b> 하므로 그 예외를 잡아도 진행 중이던
     * 트랜잭션이 죽는다. {@code ON CONFLICT} 는 충돌을 <b>예외 없이</b> 흡수한다.
     *
     * <h3>★ 갱신 대상은 <b>관제 칸</b>뿐이다</h3>
     * <ul>
     *   <li><b>갱신</b> — {@code EVNT_NM}(관제 수신 유형명) · {@code EVNT_CLSF_CD} ·
     *       {@code EVNT_CTGRY_CD}. 관제 마스터에는 유형별 이름이 없었고 앞으로 관제가 보내주므로,
     *       {@code DO NOTHING} 이면 그 값이 <b>영원히 반영되지 않는다</b>.</li>
     *   <li><b>미갱신</b> — {@code OPTR_INDCT_NM}(운영자 표시명). 관제가 쓰지 않는 칸이라
     *       <b>표식 컬럼 없이도</b> 운영자 정정이 보호된다.</li>
     *   <li><b>미갱신</b> — {@code CLCT_YN}. 관제는 이 값을 보내지 않는다({@code LS_DATA_INGEST} 에
     *       컬럼 자체가 없다). 인입 기본값 {@code 'Y'} 로 되돌리면 <b>운영자가 숨긴 유형이 매 인입마다
     *       되살아난다</b>.</li>
     *   <li><b>미송신 보존</b> — 수신값이 null 이면 기존 값을 유지한다({@code COALESCE}).
     *       관제가 안 보낸 것이 기존 사실을 <b>지우면</b> 안 된다.</li>
     *   <li><b>no-op 방지</b> — 실제로 값이 바뀔 때만 UPDATE 한다({@code IS DISTINCT FROM}).
     *       매 인입마다 행을 건드리면 불필요한 행 잠금·WAL·캐시 evict 가 생긴다.</li>
     * </ul>
     *
     * <p>보안: 네 값 모두 파라미터 바인딩이다(CWE-89). 길이 초과분은 호출자가 자르며, DB 컬럼 길이가
     * 최종 방어선이다.
     *
     * @return 신규 등록되거나 <b>실제로 갱신</b>됐으면 1, 아무 변화가 없으면 0
     */
    @Modifying
    @Query(value = """
            INSERT INTO LS_EVNT_TYPE (EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, EVNT_CTGRY_CD, CLCT_YN)
            VALUES (:evntTypeCd, :evntNm, :evntClsfCd, :evntCtgryCd, 'Y')
            ON CONFLICT (EVNT_TYPE_CD) DO UPDATE
               SET EVNT_NM       = COALESCE(EXCLUDED.EVNT_NM, LS_EVNT_TYPE.EVNT_NM),
                   EVNT_CLSF_CD  = COALESCE(EXCLUDED.EVNT_CLSF_CD, LS_EVNT_TYPE.EVNT_CLSF_CD),
                   EVNT_CTGRY_CD = COALESCE(EXCLUDED.EVNT_CTGRY_CD, LS_EVNT_TYPE.EVNT_CTGRY_CD)
             WHERE LS_EVNT_TYPE.EVNT_NM
                       IS DISTINCT FROM COALESCE(EXCLUDED.EVNT_NM, LS_EVNT_TYPE.EVNT_NM)
                OR LS_EVNT_TYPE.EVNT_CLSF_CD
                       IS DISTINCT FROM COALESCE(EXCLUDED.EVNT_CLSF_CD, LS_EVNT_TYPE.EVNT_CLSF_CD)
                OR LS_EVNT_TYPE.EVNT_CTGRY_CD
                       IS DISTINCT FROM COALESCE(EXCLUDED.EVNT_CTGRY_CD, LS_EVNT_TYPE.EVNT_CTGRY_CD)
            """, nativeQuery = true)
    int registerOrRefresh(@Param("evntTypeCd") String evntTypeCd,
                          @Param("evntNm") String evntNm,
                          @Param("evntClsfCd") String evntClsfCd,
                          @Param("evntCtgryCd") String evntCtgryCd);

    /** 전체 마스터 1회 로드(소비측이 캐시한다). */
    @Override
    List<LsEvntType> findAll();
}
