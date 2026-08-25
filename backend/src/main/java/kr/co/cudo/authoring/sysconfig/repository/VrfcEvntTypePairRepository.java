package kr.co.cudo.authoring.sysconfig.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 관제 이벤트유형 코드 ↔ 검증 이벤트 유형 코드의 <b>수신된 짝</b> 조회 리포지토리. [design: API-219]
 *
 * <h3>★ 매핑표를 만들지 않는다 (되돌리지 말 것)</h3>
 * <p>관제는 인입 행 하나({@code LS_DATA_INGEST})에 {@code EVNT_TYPE_CD}(관제 채번)와
 * {@code VRFC_EVNT_TYPE_CD}(검증)를 <b>함께 실어 보낸다</b>. 두 코드의 대응은 이미 수신 원장 안에
 * 있으므로 저작도구가 사본 테이블을 두지 않는다 — 사본은 원장과 어긋나는 <b>두 번째 진실원</b>이 된다.
 * 확정 정책도 <i>"검증 이벤트 유형은 저작도구가 매핑표를 만들지 않고 관제 인입에서 수신한다"</i> 이다.
 *
 * <p>한 검증 유형에 관제 코드가 <b>여러 개</b> 붙을 수 있고 <b>하나도 없을 수도</b> 있다(빈 목록).
 *
 * <p>조회 전용이라 매핑 엔티티가 따로 없지만 Spring Data 계약상 도메인 타입이 필요하여
 * {@link LsVrfcEvntType} 을 재사용한다({@code IngestSourceRepository} 와 동일 관례). 이 인터페이스로
 * 인입 원장에 <b>쓰지 않는다</b> — 인입은 관제 소유 수신 원장이다.
 */
@ControlRepo
public interface VrfcEvntTypePairRepository extends JpaRepository<LsVrfcEvntType, String> {

    /**
     * 인입 원장에 실제로 <b>짝지어 수신된</b> (검증 유형, 관제 유형) 조합 전체.
     *
     * <p>{@code DISTINCT} 로 조합 단위까지 접으므로 결과 행수는 인입 행수가 아니라 <b>코드 조합
     * 개수</b>다(코드 체계 규모). 값은 인입 <b>원문</b>으로 돌려주고 표기 정규화는 자바 쪽에서
     * {@code LsDataIngest.normalizeVrfcEvntType} <b>한 함수</b>로 한다 — SQL 에 {@code lower(trim(...))}
     * 를 박으면 그 정규화 규칙이 두 곳으로 갈라진다.
     *
     * <p>정렬을 고정하는 이유: 응답의 관제 코드 목록 순서가 조회마다 흔들리면 화면이 이유 없이 뒤바뀐다.
     *
     * <p>파라미터가 없어 문자열 조립도 없다(CWE-89 표면 없음).
     */
    @Query(value = """
            SELECT DISTINCT
                   i.VRFC_EVNT_TYPE_CD AS "vrfcEvntTypeCd",
                   i.EVNT_TYPE_CD      AS "evntTypeCd"
              FROM LS_DATA_INGEST i
             WHERE i.VRFC_EVNT_TYPE_CD IS NOT NULL
               AND i.EVNT_TYPE_CD IS NOT NULL
             ORDER BY 1, 2
            """, nativeQuery = true)
    List<VrfcEvntTypePairRow> findReceivedPairs();
}
