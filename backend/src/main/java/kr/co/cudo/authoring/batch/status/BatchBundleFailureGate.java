package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <b>「그 작업 묶음이 실패한 상태인가」의 단일 판정 지점</b> — 건너뛰기 허용 게이트의 입력.
 * [@design ADR-050] [@design API-198] [@design API-212]
 *
 * <h3>왜 이 판정이 필요한가</h3>
 * <p>건너뛰기는 <b>그 묶음이 실패한 영상</b>에만 허용한다. 정상 진행 중인 영상을 미리 골라 건너뛸 수
 * 있으면 ①이미 들어와 있는 영상만 덮어 뒤에 들어오는 영상은 매번 다시 골라야 하고 ②근거 없이 정상
 * 영상이 시계열·오토라벨 없이 확정된다. 미연동 구간을 통째로 덮는 몫은 <b>전체 설정</b>
 * ({@link VlmDefaultSkipMarker})이 맡는다 — 입구가 둘로 갈린 이유가 그것이다.
 *
 * <h3>★판정 축이 묶음마다 다르다 (같은 신호로 통일할 수 없다)</h3>
 * <table>
 *   <tr><th>묶음</th><th>실패가 남는 자리</th></tr>
 *   <tr><td>오토라벨</td><td><b>진행 축</b> — 스텝이 예외를 던져 파이프라인이 서고
 *       {@code PROC_STTS_CD='FAILED'} 가 남는다({@link BatchStatusService#isBundleProgressFailed}).</td></tr>
 *   <tr><td>시계열</td><td><b>위탁 실패 감사 행</b> — 제출은 논블로킹이라 실패해도 <b>예외가 위로 올라가지
 *       않고</b> 프레임 추출·오토라벨이 그대로 완주한다. 그래서 진행 축은 절대 {@code FAILED} 가 되지
 *       않으며, 실패는 {@code VLM/SKIPPED} + 확정 실패 사유로만 남는다
 *       ({@code VlmSubmitOutcomeRecorder} 계열).</td></tr>
 * </table>
 * <p>시계열에도 진행 축을 함께 본다 — 위탁 이전 단계 준비에서 예외가 나는 경로가 열릴 수 있고, 그때는
 * 진행 축에 {@code VLM/FAILED} 가 남는다. 두 축은 <b>OR</b> 다(어느 쪽이든 실패는 실패다).
 *
 * <h3>★어떤 미수행 사유를 「실패」로 볼 것인가</h3>
 * <p>{@code VLM/SKIPPED} 감사 행은 실패만 담지 않는다 — 비식별 신고 구간 <b>보류</b>처럼 "지금 안 했을
 * 뿐 스스로 재개되는" 사유가 섞여 있다. 그런 행을 실패로 읽으면 <b>정상 영상을 건너뛰는 길</b>이 다시
 * 열려 이 게이트의 존재 이유가 사라진다. 그래서 <b>결과를 끝내 받지 못한 세 사유</b>만 고른다
 * ({@link #VLM_FAILURE_SKIP_REASONS}).
 *
 * <h3>알려진 한계 (인지·수용)</h3>
 * <ul>
 *   <li><b>벤더가 실패 콜백으로 답한 건은 이 판정에 잡히지 않는다</b> — 그 경로
 *       ({@code VlmResultService.handleFailed})는 배치 이력에 아무 행도 남기지 않고 마킹 상태
 *       ({@code VLM_FAILED})만 바꾼다. 마킹 축을 여기에 더하면 잡히지만, 그 축은 성공 재개 뒤에도
 *       종결 상태가 그대로 남아 되돌아오지 않으므로 <b>더 넓게 허용</b>하게 된다. 이 게이트의 목적이
 *       「정상 영상을 건너뛰지 못하게」 이므로 <b>덜 허용하는 쪽</b>을 골랐다.</li>
 *   <li>감사 행은 append-only 라, 실패 후 재개가 <b>성공</b>해도 그 행은 남는다 — 그 영상은 이후에도
 *       건너뛰기가 허용된다. 건너뛰기가 되돌릴 수 있는 조작(재수행)이라 감수한다.</li>
 * </ul>
 *
 * <p>보안: 판정 결과를 응답 문구로 되비추지 않는다 — 거부 문구는 고정 상수이며 어느 축에서 걸렸는지
 * 드러내지 않는다(CWE-209).
 */
@Service
@RequiredArgsConstructor
public class BatchBundleFailureGate {

    /**
     * 시계열 위탁이 <b>결과를 끝내 받지 못한</b> 미수행 사유 — 이 셋만 「실패」로 읽는다.
     *
     * <p>문자열의 단일 원천은 {@link VlmTimeseriesStep} 상수다(여기서 문자열을 복제하지 않는다).
     * <b>제외</b>: 비식별 신고 보류(해소되면 스스로 재개된다) · 검증이벤트유형 계열(신규 발생이 없는
     * 과거 행 판독용 상수) · 폐지된 토글 비활성.
     */
    static final List<String> VLM_FAILURE_SKIP_REASONS = List.of(
            VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED,
            VlmTimeseriesStep.SKIP_REASON_ACK_MISSING,
            VlmTimeseriesStep.SKIP_REASON_CALLBACK_MISSING);

    /**
     * 시계열 「확정 실패」 사유 목록의 <b>읽기 창구</b> — 목록 필터가 같은 축으로 DB 술어를 만들 때 쓴다.
     *
     * <p>상수 자체를 공개하지 않고 접근자를 두는 이유는 <b>단일 원천을 유지</b>하기 위해서다. 문자열을
     * 소비자 쪽에 복제하면 사유가 하나 늘 때 한쪽만 갱신돼 조용히 갈린다.
     *
     * @return 불변 목록(선언 순서 고정)
     */
    public static List<String> vlmFailureSkipReasons() {
        return VLM_FAILURE_SKIP_REASONS;
    }

    private final BatchStatusService batchStatusService;

    /**
     * <b>지금 실패한 상태인</b> 작업 묶음 목록 — 영상 상세용. [@design API-043] [@design ADR-050]
     *
     * <h3>왜 이 목록이 필요한가 (사람이 결정하는 입구가 닫혀 있었다)</h3>
     * <p>건너뛰기는 <b>그 묶음이 실패한 영상</b>에만 열린다. 그런데 시계열 위탁은 <b>논블로킹</b>이라
     * 실패해도 예외가 위로 올라가지 않아 <b>배치 상태가 완료로 남고 단계 실패 표시도 서지 않는다</b>.
     * 화면이 그 두 신호만 보면 위탁이 확정 실패한 영상에서도 건너뛰기 버튼이 <b>어디에도 뜨지 않는다</b>
     * — 서버는 허용하는데 사람이 누를 자리가 없는 상태였다.
     *
     * <p><b>판정은 {@link #hasFailed} 그대로다</b> — 건너뛰기를 허용할지 정하는 판정과 같은 것이어야
     * 화면에 뜬 버튼이 눌렀을 때 412 로 튕기지 않는다. 여기서 규칙을 재유도하지 않는다.
     *
     * <p>순서는 {@link BatchStageBundle} 선언 순서(VLM → AUTOLABEL) <b>고정</b>이다 —
     * {@code manuallySkippedBundles}·{@code clearedBundles} 와 같은 관례(실행마다 흔들리면 화면이 깜빡인다).
     *
     * @return 실패 상태인 묶음 코드 목록. 없으면 <b>빈 리스트</b>({@code null} 아님)
     */
    public List<String> failedBundles(Long rawSn) {
        if (rawSn == null) {
            return List.of();
        }
        return java.util.Arrays.stream(BatchStageBundle.values())
                .filter(bundle -> hasFailed(rawSn, bundle))
                .map(BatchStageBundle::name)
                .toList();
    }

    /**
     * 그 묶음이 지금 실패한 상태인가.
     *
     * @return 진행 축 실패이거나(모든 묶음) 시계열 위탁이 확정 실패로 기록됐으면 {@code true}
     */
    public boolean hasFailed(Long rawSn, BatchStageBundle bundle) {
        if (rawSn == null || bundle == null) {
            return false;
        }
        if (batchStatusService.isBundleProgressFailed(rawSn, bundle)) {
            return true;
        }
        return bundle == BatchStageBundle.VLM
                && batchStatusService.isStageSkippedWithAnyReason(
                        rawSn, BatchStage.VLM, VLM_FAILURE_SKIP_REASONS);
    }
}
