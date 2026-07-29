package kr.co.cudo.authoring.label.event;

/**
 * 비식별 누락 신고가 해소되어 <b>그 영상의 신고 게이트가 다시 열렸음</b>을 알리는 도메인 이벤트
 * (2026-07-29 — 신고 구간에 <b>보류</b>됐던 파이프라인 작업의 재개용).
 *
 * <h3>{@link DeidentReportResolvedEvent} 와 무엇이 다른가</h3>
 * <ul>
 *   <li>{@code DeidentReportResolvedEvent} — <b>검수 승인(APPROVED) 노드에만</b> 발행한다. 소비자는
 *       학습데이터 export 재산출·관제 재통지({@code DatasetExportBridge})라 미승인 영상에는 발행할
 *       이유가 없다(불필요한 v1 생성 방지).</li>
 *   <li>이 이벤트 — <b>게이트가 열린 모든 노드</b>에 발행한다. 신고 구간에 보류되는 작업은 export 만이
 *       아니다. 특히 <b>VLM 시계열 위탁</b>({@code VlmTimeseriesStep})은 파이프라인 진행 중(=대개
 *       미승인) 영상에서 보류되므로, 승인 노드에만 발행하는 이벤트로는 재개 신호가 <b>영원히 도달하지
 *       않는다</b>(시계열 메타 영구 결손).</li>
 * </ul>
 *
 * <h3>발행 범위 = 차단 범위와 대칭 — <b>해제된 영상 하나</b></h3>
 * 차단 게이트({@code DeidentReportGate})가 자기 rawSn 행만 보므로, 신고가 막던 영상도 그 하나뿐이다.
 * 파생영상은 원본 신고에 영향받지 않으므로(2026-07-29 확정 정책) 발행 대상도 아니다.
 *
 * <p>소비자는 {@code AFTER_COMMIT} 리스너여야 한다 — {@code DE_IDNTF_YN 'F'→'Y'} 복원이 커밋되기 전에
 * 실행하면 재개된 작업이 진입부 게이트에서 스스로 다시 보류된다.
 *
 * @param rawSn 게이트가 다시 열린 영상 PK (LS_DATA_RAW.RAW_SN)
 */
public record DeidentGateReopenedEvent(Long rawSn) {
}
