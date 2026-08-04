package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 관제 공유 이벤트 리스트({@code MNG_CLIP_EVNT_LST}) <b>읽기 전용</b> 조회 리포지토리.
 *
 * <p>관제 인입({@code LS_DATA_INGEST})은 이벤트<b>식별자</b>({@code EVNT_ID}, 예 {@code ABA_0001})만
 * 보내고 이벤트<b>유형코드</b>는 보내지 않는다. 유형코드는 이 테이블에서 {@code EVNT_ID} 로 조회해
 * 도출한다 — 적재 시점에 {@code LS_DATA_RAW.EVNT_TYPE_CD} 를 채우는 유일한 경로다
 * ({@code TrainingVideoIngestTx}).
 *
 * <h3>쓰기 금지 (공유 스키마)</h3>
 * <p>{@code MNG_*} 는 <b>관제팀 소유</b>다. 이 인터페이스는 조회만 선언하며 상속받은
 * {@code JpaRepository} 의 {@code save*}/{@code delete*} 도 호출하지 않는다. 스키마 변경도 하지 않는다
 * (로컬/테스트 stub 은 {@code V63} 이 {@code CREATE TABLE IF NOT EXISTS} 로 만들 뿐, 실 테이블이 있으면
 * no-op 이다).
 *
 * <p>매핑 엔티티가 없으므로(구 {@code MngClipEvntLst} 엔티티는 제거됨) Spring Data 계약상 필요한 도메인
 * 타입으로 {@link LsDataRaw} 를 재사용한다 — {@code DatasetMetaSourceRepository} 와 동일 관례다.
 * 엔티티를 되살리지 않는 이유: {@code ddl-auto=validate} 대상이 늘어나 stub 스키마와 결합도가 커지는데,
 * 여기서 필요한 것은 컬럼 하나의 조회뿐이다.
 *
 * <p>보안: {@code EVNT_ID} 는 관제 수신 자유값이므로 <b>{@code :evntId} 파라미터 바인딩만</b> 사용한다
 * (문자열 결합 없음 — CWE-89).
 */
@ControlRepo
public interface MngClipEvntLstRepository extends JpaRepository<LsDataRaw, Long> {

    /**
     * {@code EVNT_ID} 에 매달린 <b>서로 다른</b> 이벤트유형코드를 최대 2건 조회한다.
     *
     * <h3>왜 {@code LIMIT 2} 인가 — "하나로 확정되는가"만 알면 된다</h3>
     * <p>{@code MNG_CLIP_EVNT_LST} 의 PK 는 {@code (EVNT_ID, EVNT_TYPE_CD)} 복합키라 <b>한 이벤트에
     * 유형이 여러 개 달릴 수 있다</b>. 그때 아무거나 하나를 고르면 관제 완료통지 계약 필드·필터·통계가
     * <b>틀린 값으로 확정</b>된다(null 보다 나쁘다). 호출부는 결과가 정확히 1건일 때만 채택하므로,
     * 2건째의 존재 여부만 알면 판정이 끝난다 — 데이터가 많아도 조회 비용이 유계다.
     *
     * <p>{@code ORDER BY} 는 결과의 결정성을 위한 것이다(같은 입력에 같은 출력 — 해시 비결정성 배제).
     * {@code EVNT_TYPE_CD IS NOT NULL} 은 stub/실테이블 어느 쪽에서도 빈 유형이 "매칭 1건"으로
     * 오인되지 않게 한다.
     *
     * @param evntId 관제 인입이 준 이벤트 식별자
     * @return 서로 다른 유형코드 0~2건(1건일 때만 확정 가능)
     */
    @Query(value = """
            SELECT DISTINCT e.EVNT_TYPE_CD
              FROM MNG_CLIP_EVNT_LST e
             WHERE e.EVNT_ID = :evntId
               AND e.EVNT_TYPE_CD IS NOT NULL
             ORDER BY e.EVNT_TYPE_CD
             LIMIT 2
            """, nativeQuery = true)
    List<String> findDistinctEvntTypeCdsByEvntId(@Param("evntId") String evntId);
}
