package kr.co.cudo.authoring.augment.dto;

import java.util.List;

/**
 * 증강 취소 응답 — {@code POST /v1/augments/{id}/cancel}.
 *
 * <h3>왜 {@code AugmentSummaryResponse} 가 아닌 전용 타입인가</h3>
 * <p>취소는 <b>부분 성공이 실재하는</b> 연산이다(S2). 증강 1건은 입력 100장 상한 때문에 여러 청크
 * job 으로 나뉘고, 청크마다 외부 취소 호출이 따로 나간다 — chunk1 취소 성공 · chunk2 이미 SUCCEEDED ·
 * chunk3 네트워크 실패 같은 혼재가 정상 시나리오다. 요약 DTO 로는 "일부만 취소됐다" 를 표현할 수
 * 없어 사용자가 <b>전부 취소된 줄 안다</b>.
 *
 * <h3>★ 이 응답의 shape 은 accept/reject 와 다르다 (FE 계약 주의)</h3>
 * <p>accept/reject 는 {@code AugmentSummaryResponse}(증강 1건 요약)를 돌려주는데 취소는 이 전용 타입을
 * 돌려준다 — 위 이유(부분 성공 표현)로 의도된 차이이며, 같은 화면에서 두 응답을 <b>같은 파서로
 * 다루면 안 된다</b>. Swagger 설명({@code AugmentController#cancel})에도 명시돼 있다.
 *
 * <h3>부분 취소일 때 화면이 해야 할 일 (Phase 5 인계)</h3>
 * <ul>
 *   <li>{@code fullyCanceled=false} → "일부 작업은 외부 시스템에 취소가 전달되지 않아 외부에서 계속
 *       처리될 수 있습니다. 그 결과는 반영되지 않습니다." <b>재시도 버튼을 두지 않는다</b>(DEV_FIX
 *       MED-7) — 증강은 이미 {@code CANCELED} 로 종결돼 재요청하면 "이미 종결된 증강이라 취소할 수
 *       없습니다" 만 나온다. 수행 불가능한 동선을 안내하지 않는다.</li>
 *   <li>남은 청크는 만료 스윕이 회수해 실패로 종결되므로 <b>영구 대기는 없다</b>. 뒤늦게 도착한 성공
 *       결과도 증강이 non-PENDING 이라 폐기된다(WARN 로그).</li>
 *   <li>{@code canceled=false} 이고 {@code status} 가 이미 종결이면 <b>오류가 아니다</b>(멱등 200).
 *       "이미 종결된 증강입니다" 안내만 띄운다.</li>
 * </ul>
 *
 * @param id               증강 결과 PK({@code LS_DATA_AUG.DATA_AUG_SN})
 * @param augTypeCd        증강 종류
 * @param status           취소 후 증강 상태({@code CANCELED} 또는 이미 종결이던 기존 상태)
 * @param canceled         <b>이번 요청이</b> 취소를 확정했는가. 멱등 재요청·이미 종결이면 false
 * @param fullyCanceled    취소 대상 청크 전부의 외부 취소가 성립했는가(부분 실패 판정).
 *                         벤더 404({@code JOB_NOT_FOUND})·409({@code STATE_CONFLICT})는 "외부에 취소할
 *                         대상이 없다" 는 뜻이라 <b>성립으로 센다</b>(DEV_FIX MED-7)
 * @param targetJobCount   취소 대상(비종결) 청크 수
 * @param canceledJobCount 실제 취소 확정된 청크 수
 * @param failedJobSeqs    외부 취소가 <b>전달되지 않은</b> 청크 순번({@code JOB_SEQ}) — 타임아웃·서킷
 *                         open·5xx·요청 예산 소진. 외부 오류 원문·job_id 는 싣지 않는다(CWE-209)
 * @param message          사용자 안내 문구
 */
public record AugmentCancelResponse(
        Long id,
        String augTypeCd,
        String status,
        boolean canceled,
        boolean fullyCanceled,
        int targetJobCount,
        int canceledJobCount,
        List<Integer> failedJobSeqs,
        String message
) {

    public AugmentCancelResponse {
        failedJobSeqs = failedJobSeqs == null ? List.of() : List.copyOf(failedJobSeqs);
    }
}
