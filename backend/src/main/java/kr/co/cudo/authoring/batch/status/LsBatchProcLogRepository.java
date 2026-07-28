package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

@ControlRepo
public interface LsBatchProcLogRepository extends JpaRepository<LsBatchProcLog, Long> {

    Optional<LsBatchProcLog> findTopByDataRawSnOrderByRegDtDesc(Long dataRawSn);

    /**
     * 진행 상태 조회용 — 특정 처리상태(=SKIPPED 감사 행)를 제외한 최신 행.
     *
     * <p>{@code SKIPPED} 행은 "이 단계는 수행하지 않았다"는 <b>append-only 감사 기록</b>이라 파이프라인의
     * 현재 진행 상태가 아니다. 이를 제외하지 않으면 다음 단계 전이({@code markStage})가 감사 행을
     * 골라 <b>덮어써</b> 흔적이 사라진다(B-ISSUE-24 의 재발 경로).
     */
    Optional<LsBatchProcLog> findTopByDataRawSnAndProcSttsCdNotOrderByRegDtDesc(
            Long dataRawSn, String procSttsCd);
}
