package kr.co.cudo.authoring.dataset.export.event;

/**
 * 이미 검수 승인(APPROVED)된 영상의 동결 스냅샷이 <b>재동결</b>되어 학습데이터 export 재생성이 필요할 때
 * 발행하는 도메인 이벤트.
 *
 * <p>사용처: event_annotation <b>지연 승인</b>(영상 검수 승인이 먼저 일어나 동결 시점에 event_annotation 이
 * 아직 PENDING 이었던 경우) 처리 — 뒤늦은 event_annotation 승인이 동결본(EVNT_ANNO_CN)을 갱신하므로
 * export JSON 을 재생성해 반영한다.
 *
 * <p>{@code ReviewApprovedEvent} 와 달리 <b>TASK_COMPLETED 통지를 재발행하지 않는다</b> — 완료된 작업의
 * 후속 수정이므로 통지는 별도로 {@code TaskModifiedEvent(META_UPDATED)} 로 발행한다(CLAUDE.md 작업 단위
 * 통지 정책). 본 이벤트는 오직 {@code DatasetExportBridge} 의 export 재생성만 트리거한다(단일 소비자).
 *
 * @param rawSn 재동결·재산출 대상 영상 PK (LS_DATA_RAW.RAW_SN)
 */
public record DatasetReExportEvent(Long rawSn) {
}
