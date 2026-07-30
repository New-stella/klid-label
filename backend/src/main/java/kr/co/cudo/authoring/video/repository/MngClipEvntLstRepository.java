package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.MngClipEvntLst;
import kr.co.cudo.authoring.video.entity.MngClipEvntLstId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 관제서버 클립 이벤트 리스트(MNG_CLIP_EVNT_LST) 조회 전용 리포지토리.
 *
 * <p>공유 DB(관제) READ 만 수행한다 — {@link ControlRepo} 로 controlEntityManager/controlTransactionManager
 * 에 바인딩된다. MNG_* 테이블은 관제팀 소유이므로 본 리포지토리는 조회만 노출하며 쓰기 메서드를 두지 않는다.
 * PK 는 복합키 {@link MngClipEvntLstId}(EVNT_ID, EVNT_TYPE_CD).
 */
@ControlRepo
public interface MngClipEvntLstRepository extends JpaRepository<MngClipEvntLst, MngClipEvntLstId> {

    /**
     * EVNT_ID 로 이벤트 행 1건 조회 — evntTypeCd/shtDt 적재 도출용.
     *
     * <p>실측상 EVNT_ID 당 1행이나(작업요청 Y 1221/1221 매칭), 방어적으로 first 1건만 취한다.
     * 파생 쿼리(메서드 이름) 이므로 SQL Injection 위험이 없고 파라미터는 자동 바인딩된다.
     */
    Optional<MngClipEvntLst> findFirstByEvntId(String evntId);

    /**
     * 후보 EVNT_ID 집합을 <b>IN 조회 1회</b>로 배치 조회 — 클립당 개별 조회(N+1) 제거(B-ISSUE-04).
     *
     * <p>구 구현은 클립마다 {@link #findFirstByEvntId} 를 호출해 후보 N 건이면 매 tick N 회의 point
     * lookup 이 <b>관제 공유 DB</b> 에 발생했다. 스캔 단계에서 후보 EVNT_ID 를 모아 1회만 조회한다.
     *
     * <p>실측상 EVNT_ID 당 1행이나 스키마상 복합 PK (EVNT_ID, EVNT_TYPE_CD) 라 다행 가능성이 있다.
     * 호출 측이 {@code findFirstBy...} 와 동일하게 "EVNT_ID 당 1건" 으로 축약하되, 어느 행이 뽑히는지가
     * 흔들리지 않도록 PK 오름차순으로 정렬해 반환한다.
     *
     * <p>파생 쿼리(메서드 이름)이므로 SQL Injection 위험이 없고 파라미터는 자동 바인딩된다.
     */
    List<MngClipEvntLst> findByEvntIdInOrderByEvntIdAscEvntTypeCdAsc(Collection<String> evntIds);
}
