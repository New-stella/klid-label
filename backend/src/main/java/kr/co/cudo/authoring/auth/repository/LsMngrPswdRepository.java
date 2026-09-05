package kr.co.cudo.authoring.auth.repository;

import kr.co.cudo.authoring.auth.entity.LsMngrPswd;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 관리자 공유 자격 저장소 (Control 데이터소스). [@design ADR-046]
 *
 * <p>비즈니스 로직 금지 — 조회/저장만. 행은 최대 하나이므로 목록 조회 메서드를 두지 않는다.
 * "여러 행 중 하나를 고르는" 메서드가 생기는 순간 그 선택 규칙이 두 번째 진실원이 된다.
 */
@ControlRepo
public interface LsMngrPswdRepository extends JpaRepository<LsMngrPswd, Long> {
}
