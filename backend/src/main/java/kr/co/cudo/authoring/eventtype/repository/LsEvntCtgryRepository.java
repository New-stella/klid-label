package kr.co.cudo.authoring.eventtype.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.eventtype.entity.LsEvntCtgry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 이벤트카테고리 마스터({@code LS_EVNT_CTGRY}, V168) 리포지토리 — 조회 전용.
 *
 * <p>행수가 10 규모(구 관제 카테고리명 이관분)라 {@code findAll()} 1회 로드로 충분하며, 소비측
 * ({@code EventTypeService})이 유형 목록과 <b>같은 캐시 키 안에서</b> 함께 사용한다. 조건 조회를
 * 늘리면 캐시가 갈라져 표시명 판정이 호출부마다 달라진다.
 */
@ControlRepo
public interface LsEvntCtgryRepository extends JpaRepository<LsEvntCtgry, LsEvntCtgry.Key> {

    @Override
    List<LsEvntCtgry> findAll();
}
