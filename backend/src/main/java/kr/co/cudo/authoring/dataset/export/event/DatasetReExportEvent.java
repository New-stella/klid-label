package kr.co.cudo.authoring.dataset.export.event;

/**
 * 이미 검수 승인(APPROVED)된 영상의 동결 스냅샷이 <b>재동결</b>되어 학습데이터 export 재생성이 필요할 때
 * 발행하는 도메인 이벤트.
 *
 * <h3>현재 발행처 없음 — 휴면 확장점(존치 결정, Phase 5C LOW-3)</h3>
 * MED-F 로 event_annotation 지연 승인 경로가 재산출을 {@code TaskModifiedEvent(regen=true)} 단일 축으로
 * 통일하면서(이중 export/이중 통지 제거) 이 이벤트의 <b>발행처가 0건</b>이 됐다. 그럼에도 제거하지 않고
 * 존치하는 이유:
 * <ul>
 *   <li>단일 소비자 {@link kr.co.cudo.authoring.dataset.export.listener.DatasetExportBridge#onReExport}
 *       가 "통지 재발행 없이 export 만 멱등 재산출"({@code force=false})하는 <b>테스트된 재동결 재산출 배선</b>
 *       을 이미 갖추고 있다 — 향후 재동결형 경로(예: 통지 없는 순수 재산출)가 생기면 발행만 붙이면 된다.</li>
 *   <li>여러 서비스 테스트가 {@code verify(...never()).publishEvent(DatasetReExportEvent.class)} 로 "재산출은
 *       {@code TaskModifiedEvent(regen=true)} 단일 축뿐"이라는 MED-F 결정을 회귀 방어한다. 이벤트를 지우면
 *       이 가드가 함께 사라진다.</li>
 * </ul>
 * 발행처가 다시 생기기 전까지는 소비 경로만 존재하는 <b>휴면 상태</b>임을 명시한다.
 *
 * <p>{@code ReviewApprovedEvent} 와 달리 <b>TASK_COMPLETED 통지를 재발행하지 않는다</b> — 완료된 작업의
 * 후속 수정이므로 통지는 별도로 {@code TaskModifiedEvent(META_UPDATED)} 로 발행한다(CLAUDE.md 작업 단위
 * 통지 정책). 본 이벤트는 오직 {@code DatasetExportBridge} 의 export 재생성만 트리거한다(단일 소비자).
 *
 * @param rawSn 재동결·재산출 대상 영상 PK (LS_DATA_RAW.RAW_SN)
 */
public record DatasetReExportEvent(Long rawSn) {
}
