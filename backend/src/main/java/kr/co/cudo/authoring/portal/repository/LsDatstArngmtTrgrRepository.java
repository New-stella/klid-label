package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.portal.entity.LsDatstArngmtTrgr;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 포털 데이터셋 정리 트리거 접수 원장 저장소.
 *
 * <h3>★★ {@link Repository} 마커를 직접 상속하는 것은 의도다 — 삭제 창구를 열지 않기 위해서다</h3>
 * <p>{@code JpaRepository} 를 상속하면 {@code deleteById(ID)} · {@code deleteAll()} 이 <b>공짜로
 * 딸려 온다.</b> 이 원장이 보관하는 것은 비가역 삭제의 <b>기산점</b>이라, 「식별자만 받아 지우는
 * 창구를 두지 않는다」가 이 저장소의 불변식이다(파생영상 폐기 삭제에서 세운 원칙과 같은 축).
 * 마커 인터페이스를 상속하면 <b>여기 선언한 메서드만</b> 존재하므로 그 불변식이 상속이 아니라
 * 구조로 지켜진다.
 *
 * <p>⚠ 편의를 이유로 {@code JpaRepository} 로 바꾸지 말 것. 바꾸는 순간 삭제 메서드가 되살아나고,
 * 그것을 막는 것이 이 선택의 전부다.
 *
 * <h3>★ 재수신 멱등은 조회가 아니라 실행문이 판정한다</h3>
 * <p>{@code ON CONFLICT DO NOTHING} 하나로 끝낸다. 조회한 뒤 삽입하면 두 트리거가 같은 순간에
 * 도착할 때 둘 다 조회를 통과해 행이 둘 생기고 기산점이 갈린다. 그 창을 닫는 유일한 수단이
 * 단일 실행문이다.
 *
 * @design ERD-035
 * @design API-244
 * @design AC-1102
 */
@ControlRepo
public interface LsDatstArngmtTrgrRepository extends Repository<LsDatstArngmtTrgr, Long> {

    /**
     * 트리거를 접수한다 — <b>이미 있으면 아무것도 하지 않는다.</b>
     *
     * <p>★ 수신일시({@code rcptn_dt})를 명시로 싣지 않는 것은 의도다. 컬럼 기본값
     * ({@code CURRENT_TIMESTAMP})이 채우므로 <b>행이 생기는 시각과 트리거를 받은 시각이 같은
     * 사실</b>로 유지된다. 여기서 값을 계산해 넣으면 그 둘이 갈릴 수 있다.
     *
     * <p>★★ {@code DO UPDATE SET rcptn_dt = ...} 로 바꾸지 말 것. 재수신마다 기산점이 뒤로 밀려
     * 중복 수신이 반복되는 동안 정리가 <b>영영 일어나지 않는다.</b> 기준은 처음 받은 시각 하나다.
     *
     * <p>값은 전부 파라미터 바인딩이다(문자열 결합 없음 — CWE-89).
     *
     * @return 새로 접수했으면 {@code 1}, 이미 접수돼 있어 아무것도 하지 않았으면 {@code 0}.
     *         ⚠ 이 구분은 <b>진단용</b>이다 — 창구 응답을 가르는 데 쓰지 않는다(첫 수신과 재수신은
     *         같은 응답이어야 한다).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO ls_datst_arngmt_trgr (datst_cd, ver_no)
            VALUES (:datstCd, :verNo)
            ON CONFLICT (datst_cd, ver_no) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("datstCd") String datstCd, @Param("verNo") String verNo);

    /** 접수 기록 조회 — 기산점 확인용. 소비자는 정리 규칙(DFEAT-055)의 소유자다. */
    Optional<LsDatstArngmtTrgr> findByDatstCdAndVerNo(String datstCd, String verNo);

    /** 같은 짝의 접수 기록 수 — 유일 제약이 실제로 지켜지는지 확인하는 자리. */
    long countByDatstCdAndVerNo(String datstCd, String verNo);
}
