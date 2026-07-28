package kr.co.cudo.authoring.dataset.export.event;

/**
 * Phase 5C — 검수 승인 export(전량 재생성)가 <b>종결된 뒤</b> 발행하는 완료 신호.
 *
 * <p><b>왜 필요한가 (C-2 통지 순서 보장)</b>: 승인 통지({@code TASK_COMPLETED})가 export 보다 먼저
 * 나가면, 관제가 통지를 받고 {@code V_COMPLETED_VIDEO.EXPORT_PATH_NM}(최신 SUCCEEDED export) 를 조회할
 * 때 <b>이번 승인의 새 버전이 아직 없어 구 버전 폴더</b>를 픽업한다(적대검증 N-3). 이 이벤트는
 * {@link kr.co.cudo.authoring.dataset.export.AsyncDatasetExportRunner#runApprovalAsync} 가 export 를
 * 동기로 마친 <b>직후</b> 같은 스레드에서 발행하므로, 이를 소비하는 통지 리스너
 * ({@code ControlNotifyEventListener#onExportCompleted})가 <b>항상 export 종결 이후에</b> 통지를 낸다.
 *
 * <p>일반 {@code ApplicationEvent}(트랜잭션 phase 없음)라 발행 스레드에서 리스너가 <b>동기 실행</b>된다 —
 * export → 통지 순서가 이벤트 전달 계층에서 보장된다.
 *
 * @param rawSn 승인·재산출된 영상 PK (LS_DATA_RAW.RAW_SN)
 */
public record DatasetExportCompletedEvent(Long rawSn) {
}
