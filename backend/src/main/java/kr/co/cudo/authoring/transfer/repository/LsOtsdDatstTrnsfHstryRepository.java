package kr.co.cudo.authoring.transfer.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.transfer.entity.LsOtsdDatstTrnsfHstry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 외부데이터셋이관이력({@code LS_OTSD_DATST_TRNSF_HSTRY}) 저장·조회.
 *
 * <p>이 표는 <b>감사 기록</b>이다. 같은 산출물을 두 번 가져오는 것을 실제로 막는 것은
 * {@code LS_DATA_RAW.VMS_CLIP_ID} 의 유일 제약이며, 여기서 조회로 사전 판정만 하면
 * 두 노드가 같은 순간에 통과한다(check-then-act).
 *
 * @design DOMAIN-017
 * @design ERD-031
 */
@ControlRepo
public interface LsOtsdDatstTrnsfHstryRepository extends JpaRepository<LsOtsdDatstTrnsfHstry, Long> {

    /** 한 영상이 어느 이관으로 들어왔는가 — 사후 판독용. 최신순이 아니라 등록순이다. */
    List<LsOtsdDatstTrnsfHstry> findByRawSnOrderByTrnsfSnAsc(Long rawSn);
}
