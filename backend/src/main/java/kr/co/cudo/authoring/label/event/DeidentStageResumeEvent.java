package kr.co.cudo.authoring.label.event;

/**
 * 비식별 누락 신고가 해소되어 <b>그 신고가 접수된 단계부터 작업을 재개</b>해야 함을 알리는 도메인 이벤트
 * (V171 — 단계 구분 + 재개 지점 분기).
 *
 * <h3>기존 2종과 무엇이 다른가 — 셋 다 필요하다</h3>
 * <ul>
 *   <li>{@link DeidentGateReopenedEvent} — <b>항상</b> 발행. 소비자는 보류됐던 <b>VLM 위탁 재개</b>
 *       ({@code VlmResumeBridge}). 단계와 무관하게 필요하므로 <b>무변경</b>이다.</li>
 *   <li>{@link DeidentReportResolvedEvent} — <b>검수 승인(APPROVED) 노드에만</b> 발행. 소비자는
 *       <b>export 재산출·관제 재통지</b>({@code DatasetExportBridge}). 역시 <b>무변경</b>이다.</li>
 *   <li>이 이벤트 — <b>신고 단계를 아는 신고에만</b> 발행. 소비자는 작업 <b>재개 지점 분기</b>
 *       ({@code DeidentStageResumeBridge}).</li>
 * </ul>
 *
 * <h3>재개 지점 (사용자 확정, 구속)</h3>
 * <ul>
 *   <li>{@code MARKING} — 비식별 재수행 결과 위에서 <b>마킹부터 다시</b>. 배치 단계 상태를
 *       {@code MARKING_READY} 로 되감고 활성 마킹을 종결해 재마킹 진입을 연다. 실제 재실행은
 *       사람이 다시 마킹하면 기존 {@code MarkingCompletedEvent → MarkingBatchBridge} 가 그대로 탄다
 *       (파이프라인을 여기서 재구현하지 않는다).</li>
 *   <li>{@code LABELING} — <b>프레임 이미지만 재추출</b>하고 라벨링을 이어간다. 마킹은 유지되고
 *       라벨 좌표도 보존된다({@code DeidentFrameAttacher} 가 기존 {@code LS_DATA_SRC} 행을
 *       dirty-update 하므로 {@code SRC_SN} 이 유지되어 라벨 FK 가 끊기지 않는다).</li>
 * </ul>
 *
 * <p><b>단계 미상(NULL) 은 발행하지 않는다</b> — 컬럼 신설 이전 레거시 신고는 어디서 접수됐는지
 * 알 수 없고, 지어내면 <b>마킹 단계로 오판정 시 라벨이 있는 영상을 재마킹 대기로 되감는다</b>.
 * 발행하지 않으면 기존 2종만 도는 현행 동작이 그대로 유지된다.
 *
 * <p>소비자는 반드시 {@code AFTER_COMMIT} 리스너여야 한다 — {@code DE_IDNTF_YN 'F'→'Y'} 복원이
 * 커밋되기 전에 실행하면 재개 작업이 자기 신고 게이트에 스스로 막힌다.
 *
 * @param rawSn 신고가 해소된 영상 PK (LS_DATA_RAW.RAW_SN)
 * @param stage {@code MARKING} | {@code LABELING} (null 이면 발행하지 않는다)
 */
public record DeidentStageResumeEvent(Long rawSn, String stage) {
}
