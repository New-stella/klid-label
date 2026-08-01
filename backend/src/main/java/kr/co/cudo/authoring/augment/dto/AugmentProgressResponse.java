package kr.co.cudo.authoring.augment.dto;

/**
 * 증강 진행상태 응답 — {@code GET /v1/augments/{id}/progress}.
 *
 * <h3>{@code id} 는 {@code LS_DATA_AUG.DATA_AUG_SN} 이다</h3>
 * <p>FE 계약에 별도 job PK 개념이 없고, accept/reject({@code /v1/augments/{id}/accept}) 가 이미
 * 같은 식별자를 쓴다. 위탁 청크({@code LS_DATA_AUG_JOB.AUG_JOB_SN})나 외부 {@code job_id} 는
 * 내부 식별자라 응답에 싣지 않는다(CWE-209 — 외부 시스템 구조 노출 금지).
 *
 * <h3>{@code progress} 산식 — <b>청크 job 의 파일 수 가중 평균</b></h3>
 * <pre>
 *   progress = round( Σ(weight_i × p_i) / Σ(weight_i) )
 *     weight_i = max(1, LS_DATA_AUG_JOB.TOT_NOCS)   // 그 청크가 위탁한 입력 파일 수
 *     p_i      = 종결 job → 100
 *                비종결 job → 외부 상태조회(§4.4)의 progress (미제공이면 0)
 * </pre>
 * <p><b>최솟값(min) 이 아니다.</b> min 을 쓰면 청크 3개 중 2개가 100% 여도 전체가 0% 로 보여
 * 진행이 멈춘 것처럼 표시된다. 반대로 단순 평균(가중 없음)은 1장짜리 꼬리 청크가 100장짜리 청크와
 * 같은 비중을 가져 실제 처리량과 어긋난다. 그래서 <b>파일 수 가중</b>이다.
 *
 * <p>{@code weight} 에 {@code max(1, ...)} 를 쓰는 이유: 위탁 거부 기록({@code createRejected})은
 * {@code TOT_NOCS=0} 이라 가중치가 0 이 되면 분모에서 사라져 "실패한 청크가 없는 것처럼" 보인다.
 *
 * @param id                 증강 결과 PK({@code LS_DATA_AUG.DATA_AUG_SN})
 * @param augTypeCd          증강 종류(WINTER/NIGHT/RAIN)
 * @param status             {@link AugmentProgressStatus} 이름
 * @param progress           0~100. <b>{@code null} 이면 산출 불가</b>이며 사유는 {@code unavailableReason}
 * @param unavailableReason  {@link AugmentProgressUnavailableReason} 이름 또는 null
 * @param totalJobCount      위탁 청크 총 수
 * @param terminalJobCount   그중 종결된 청크 수
 * @param cancelable         취소 가능 여부(증강 행이 PENDING 일 때만 true)
 * @param nextPollAfterMs    <b>권고</b> 폴링 간격(ms). 0 = 더 폴링할 필요 없음(종결).
 *                           서버가 강제하지 않는 힌트다 — 속도 제한(RateLimiter)은 도입하지 않기로
 *                           확정됐으므로, 폴링 N+1 을 줄이는 수단은 이 힌트뿐이다(S13).
 */
public record AugmentProgressResponse(
        Long id,
        String augTypeCd,
        String status,
        Integer progress,
        String unavailableReason,
        int totalJobCount,
        int terminalJobCount,
        boolean cancelable,
        long nextPollAfterMs
) {
}
