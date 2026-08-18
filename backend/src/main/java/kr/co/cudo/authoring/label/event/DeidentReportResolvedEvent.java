package kr.co.cudo.authoring.label.event;

/**
 * M1 — 비식별 누락 신고가 해소({@code OPEN→RESOLVED}, {@code DE_IDNTF_YN 'F'→'Y'})되어
 * <b>신고 구간에 보류됐던 학습데이터 산출·통지를 복구</b>해야 할 때 발행하도록 정의된 도메인 이벤트.
 *
 * <h3>★현재 이 이벤트를 발행하는 곳은 없다 — 휴면(dormant) 확장점이다</h3>
 * 산출·통지의 트리거가 <b>검수 승인 한 곳</b>으로 일원화되면서, 해소 시점에 하던 일은
 * {@code TaskModifiedEvent}(META_UPDATED · exportRegenerated=true · needsRecheck=true)가 맡아
 * <b>재검토 표시만</b> 세운다. 실제 재산출·재통지는 <b>재승인 시점</b>에 디바운스 flush 로 나간다.
 * 발행 지점의 단일 진실원은 {@code DeidentReportService#publishResolvedForExportRecovery} 이며,
 * 그 메서드는 이 이벤트를 발행하지 않는다.
 *
 * <p>정의와 수신 배선({@code DatasetExportBridge#onDeidentReportResolved})을 지우지 않는 이유는
 * 그 자리가 다시 필요해질 수 있어서이며, 이 저장소는 같은 성격의 확장점을 이미 그렇게 유지한다.
 * <b>아래 두 절은 그때를 위해 보존한 근거</b>이지 현행 동작 서술이 아니다.
 *
 * <h3>왜 필요했나 (통지·동기화 영구 유실)</h3>
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
 * @param rawSn 신고가 해소된 영상 PK (LS_DATA_RAW.RAW_SN)
 */
public record DeidentReportResolvedEvent(Long rawSn) {
}
