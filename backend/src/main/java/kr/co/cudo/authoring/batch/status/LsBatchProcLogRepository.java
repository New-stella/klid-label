package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
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

    /**
     * 특정 단계가 특정 사유로 <b>보류(SKIPPED)</b> 된 감사 행이 있는가 — 신고 해소 후 재개 대상 식별용.
     *
     * <p>{@code createSkipped} 는 사유를 별도 컬럼 없이 {@code ERR_MSG_CN}(errorMsg)에 적재하므로
     * (스키마 추가 없음), 재개 판정도 같은 축을 읽는다. 사유 문자열의 단일 원천은 각 스텝의 상수다
     * (예: {@code VlmTimeseriesStep.SKIP_REASON_DEIDENT_REPORT}) — 호출부가 그 상수를 넘긴다.
     *
     * <p>파라미터 바인딩 파생 쿼리만 사용한다(CWE-89).
     */
    boolean existsByDataRawSnAndProcStepCdAndProcSttsCdAndErrorMsg(
            Long dataRawSn, String procStepCd, String procSttsCd, String errorMsg);

    /**
     * 위 판정의 <b>다중 사유</b>판 (Phase C-1) — VLM 재개 대상 사유가 셋으로 늘어난 데 따른 확장.
     *
     * <p>파라미터 바인딩 파생 쿼리만 사용한다(CWE-89). 사유 집합은 호출부 상수라 크기가 고정이다.
     */
    boolean existsByDataRawSnAndProcStepCdAndProcSttsCdAndErrorMsgIn(
            Long dataRawSn, String procStepCd, String procSttsCd, Collection<String> errorMsgs);
}
