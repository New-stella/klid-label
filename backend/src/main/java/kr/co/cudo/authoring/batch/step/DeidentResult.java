package kr.co.cudo.authoring.batch.step;

/**
 * 비식별 단계({@link DeidentifyStep#run}) 결과 — 호출자가 "동기 완료 vs 외부 위탁 지연"을 구분하게 한다.
 *
 * <p>배경(상태머신 단일화): KPST 위탁 경로는 제출(submit)만 하고 즉시 반환하므로, 호출자가 무조건
 * {@code markRawDataMarkingReady} 를 호출하면 비식별 미완료(DE_IDNTF_YN='N') 상태에서 dataSttsCd 가
 * MARKING_READY 로 조기 전이된다(영상 스트림 NOT_FOUND). 따라서 run() 은 완료 여부를 반환값으로 알려주고,
 * 호출자는 <b>동기 완료(completed)일 때만</b> 전이한다. 위탁(지연)일 때 MARKING_READY 전이는 KPST 폴링
 * ({@code KpstDeidentTxService.applyBatchCompletion})이 단일 지점에서 수행한다.
 *
 * @param completed    {@code true}=동기 완료(mock, DE_IDNTF_YN='Y' 까지 끝남). {@code false}=외부 위탁으로 지연.
 * @param deidFilePath 동기 완료 시 비식별 산출물 경로(완료일 때만 non-null). 지연이면 {@code null}.
 */
public record DeidentResult(boolean completed, String deidFilePath) {

    /** 동기 완료(mock) — 비식별 산출물 경로를 보유한다. 호출자가 즉시 MARKING_READY 로 전이한다. */
    public static DeidentResult completed(String deidFilePath) {
        return new DeidentResult(true, deidFilePath);
    }

    /** 외부 위탁으로 지연(KPST) — 완료(MARKING_READY 전이)는 폴링 잡이 담당. 호출자는 전이하지 않는다. */
    public static DeidentResult deferred() {
        return new DeidentResult(false, null);
    }
}
