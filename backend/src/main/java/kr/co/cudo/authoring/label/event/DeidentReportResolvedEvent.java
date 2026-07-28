package kr.co.cudo.authoring.label.event;

/**
 * M1 — 비식별 누락 신고가 해소({@code OPEN→RESOLVED}, {@code DE_IDNTF_YN 'F'→'Y'})되어
 * <b>신고 구간에 보류됐던 학습데이터 산출·통지를 복구</b>해야 할 때 발행하는 도메인 이벤트.
 *
 * <h3>왜 필요한가 (통지·동기화 영구 유실)</h3>
 * 신고 구간에는 export 가 게이트에 차단되며, 차단은 "실패"가 아니라 정책적 보류이므로
 * {@code LS_DATASET_EXPORT} 행을 남기지 않는다(FAILED 행을 남기면 장애로 오분류되고 회수기가 반드시
 * 다시 막힐 재시도로 시도 상한만 소진한다). 그런데 검수 승인({@code ReviewService.approve})에는 신고
 * 게이트가 없어 신고 상태에서도 승인이 성립하므로, 그 승인의 export 는 차단되고 <b>행이 0건</b>이라
 * {@code DatasetExportFailureRecoverer}(FAILED 행만 스캔)의 회수 대상도 아니게 된다. 결과적으로 그
 * 승인분의 export 와 관제 통지가 <b>영원히 나가지 않고</b> 관제는 구 버전 폴더에 고착된다.
 *
 * <p>그래서 <b>차단 상태를 저장하는 대신 해제 시점에 재트리거</b>한다(옵션 (a)). 발행 조건은
 * <b>검수 승인(APPROVED) 영상</b> 뿐이다 — 미승인 영상은 애초에 산출 대상이 아니라 재트리거가 불필요한
 * v1 을 만든다.
 *
 * <h3>왜 강제 재생성(force=true)인가</h3>
 * 소비자({@code DatasetExportBridge#onDeidentReportResolved})는 승인 경로 러너
 * ({@code AsyncDatasetExportRunner#runApprovalAsync}, force=true)로 위임한다. 멱등 skip 의 기준인
 * 콘텐츠 해시는 라벨·프레임 행·메타만 반영하고 <b>이미지 픽셀은 반영하지 않는데</b>, resolve 는 외부
 * 솔루션이 비식별본을 제자리 교체한 뒤에만 통과한다({@code verifyDeidentArtifact}). 즉 해시가 같아도
 * 디스크의 비식별 이미지는 바뀌어 있으므로, 멱등 skip 하면 export 폴더에 <b>옛 PII 이미지</b>가 남는다.
 * 재트리거 빈도는 "APPROVED 영상의 신고 해소" 로 한정돼 낭비가 아니다.
 *
 * <h3>Phase 8 DEV_FIX MEDIUM-1 — 게이트는 <b>소비자별</b>이다 (발행 자체는 무조건)</h3>
 * 구 구현은 발행 측이 APPROVED 가 아니면 <b>이벤트를 아예 발행하지 않았다</b>. 그런데 같은 이벤트가
 * export 복구 외에 <b>증강 보류 재개</b>({@code AugmentRequestBridge})의 유일한 복구 경로이기도 하다.
 * 증강 요청은 요청 시점에만 APPROVED 를 검증하고({@code AugmentRequestService}) 이후 상태 유지를
 * 보장하지 않는데, {@code ReviewStateMachine} 은 <b>APPROVED → PENDING(재검수 재제출)</b> 전이를
 * 실제로 허용한다. 즉 "증강 요청 → 재검수 재제출 → 신고 해소" 순서면 보류가 <b>영구화</b>됐다.
 *
 * <p>그래서 발행은 무조건 하고, "APPROVED 전용" 은 그 제약이 실제로 필요한 소비자
 * ({@code DatasetExportBridge} — 미승인 영상은 산출 대상 자체가 아니라 무의미한 v1 을 만든다)만
 * {@link #reviewApproved()} 로 판단한다. 증강 재개 소비자는 검수 상태와 무관하므로 게이트가 없다.
 *
 * @param rawSn          신고가 해소된 영상 PK (LS_DATA_RAW.RAW_SN)
 * @param reviewApproved 해소 시점의 검수 상태가 APPROVED 인가 (export 복구 소비자 전용 판단 재료)
 */
public record DeidentReportResolvedEvent(Long rawSn, boolean reviewApproved) {
}
