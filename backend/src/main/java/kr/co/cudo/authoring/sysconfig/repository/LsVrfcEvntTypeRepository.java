package kr.co.cudo.authoring.sysconfig.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.sysconfig.entity.LsVrfcEvntType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 검증 이벤트 유형 카탈로그 리포지토리 (LS_VRFC_EVNT_TYPE). [design: ERD-033]
 *
 * <p>파생 쿼리 + 파라미터 바인딩만 쓴다(SQL 문자열 조립 없음 — CWE-89 표면 없음).
 *
 * <p><b>등록·삭제 통로를 두지 않는다</b> — 시드는 연동 규격서의 지원 이벤트 목록에서 왔고, 유형
 * 자체의 생성·수정·삭제는 이번 범위가 아니다. 상속받은 {@code delete*}·{@code save} 를 호출하지 않는다.
 */
@ControlRepo
public interface LsVrfcEvntTypeRepository extends JpaRepository<LsVrfcEvntType, String> {

    /**
     * 전체 유형을 <b>정렬순서 오름차순</b>으로 조회한다.
     *
     * <p>2차 정렬키로 유형코드를 고정한다 — 정렬순서가 같은 유형이 생기면(이 표엔 유일 제약이 없다)
     * 목록 순서가 조회마다 흔들려 화면이 이유 없이 뒤바뀐다.
     *
     * <p>페이징을 두지 않는 이유: 이 표는 <b>코드 체계</b>라 행수가 한 자릿수 규모이고 조회는
     * REVIEWER 전용 관리 화면 1곳뿐이다(이벤트유형 관리 조회와 같은 판단).
     */
    List<LsVrfcEvntType> findAllByOrderBySortSeqAscVrfcEvntTypeCdAsc();
}
