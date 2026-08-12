package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    /**
     * (영상 × 단계) 의 <b>마지막 수동 스킵 표식</b> 행 — 현재 스킵 상태 판정용. [@design API-198]
     *
     * <p>스킵/해제는 표식 행을 <b>덧붙여</b> 기록하므로(append-only 감사) "지금 스킵인가"는 마지막 행의
     * {@code ERR_CD} 로 정해진다. 정렬 키는 {@code REG_DT} 가 아니라 <b>PK({@code IDENTITY} 증가)</b> 다 —
     * 같은 밀리초에 스킵→해제가 연달아 들어오면 시각 정렬은 순서가 흔들려 판정이 뒤집힌다.
     *
     * <p>파라미터 바인딩 파생 쿼리만 사용한다(CWE-89). {@code errorCds} 는 호출부 상수라 크기가 고정이다.
     */
    Optional<LsBatchProcLog> findTopByDataRawSnAndProcStepCdAndProcSttsCdAndErrorCdInOrderByBatchProcLogSnDesc(
            Long dataRawSn, String procStepCd, String procSttsCd, Collection<String> errorCds);

    /**
     * 한 영상의 <b>단계별 마지막 수동 스킵 표식</b>을 한 번에 조회한다 — 영상 상세용. [@design API-043]
     *
     * <p><b>왜 단계별 개별 조회(3회)가 아닌가</b>: 영상 상세는 화면이 배치 진행을 폴링하며 반복 호출하는
     * 경로다({@code useVideoDetail} 의 배치 폴링). 단계 수만큼 왕복을 늘리지 않는다.
     *
     * <p><b>왜 "최근 N건"으로 자르지 않는가</b>: 표식은 append-only 라 한 단계에 여러 번 쌓일 수 있고,
     * 상한을 두면 스킵을 자주 토글한 단계의 행이 다른 단계의 마지막 표식을 <b>목록 밖으로 밀어내</b>
     * 판정이 조용히 틀린다. 상관 서브쿼리로 단계별 최대 PK 행만 고르면 결과가 정확히 단계 수 이하로
     * 제한되면서도 절단 위험이 없다.
     *
     * <p>정렬 키가 {@code REG_DT} 가 아니라 <b>PK(IDENTITY 증가)</b> 인 이유는 위
     * {@code findTopBy...OrderByBatchProcLogSnDesc} 와 같다 — 같은 밀리초에 스킵→해제가 연달으면
     * 시각 정렬은 판정을 뒤집는다.
     *
     * <p>파라미터 바인딩만 사용한다(CWE-89).
     */
    @Query("""
            select l from LsBatchProcLog l
             where l.dataRawSn = :dataRawSn
               and l.procSttsCd = :procSttsCd
               and l.errorCd in :errorCds
               and l.batchProcLogSn = (
                     select max(x.batchProcLogSn) from LsBatchProcLog x
                      where x.dataRawSn = l.dataRawSn
                        and x.procStepCd = l.procStepCd
                        and x.procSttsCd = :procSttsCd
                        and x.errorCd in :errorCds)
            """)
    List<LsBatchProcLog> findLatestManualSkipMarkers(
            @Param("dataRawSn") Long dataRawSn,
            @Param("procSttsCd") String procSttsCd,
            @Param("errorCds") Collection<String> errorCds);
}
