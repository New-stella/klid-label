package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.MngExLocalGov;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 관제 공유 지자체 마스터(MNG_EX_LOCAL_GOV) 조회 전용 리포지토리.
 *
 * <p>관제 소유 공유 테이블이므로 <b>READ 전용</b>이다(엔티티도 {@code @Immutable}).
 * 관제 완료 통지의 {@code lclgv_nm}(지자체명) 조달처.
 */
@ControlRepo
public interface MngExLocalGovRepository extends JpaRepository<MngExLocalGov, String> {
}
