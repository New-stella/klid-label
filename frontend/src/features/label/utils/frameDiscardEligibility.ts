// 프레임 폐기·복원이 <이 영상에서> 애초에 불가능한 사유를 판정한다 — <b>단일 판정 지점</b>. [req: P2b]
//
// 왜 한 곳인가: 사유가 늘 때마다 화면이 각자 조건을 이어붙이면 한쪽만 갱신돼 값이 어긋난다(이 저장소의
// 반복 결함). 화면은 <b>어떤 영상인지</b>만 넘기고 판정은 여기 위임한다. 신고 쪽의
// `deidentReportEligibility` 와 같은 골격이다.
//
// 서버 계약과의 관계: 여기서 막는 것은 <b>동선</b>이고 실제 차단은 BE 가 한다(400 —
// LabelService.requireDiscardAllowed). 화면이 조건을 모르는 경우(구 응답 등)는 저장 시 안내로 받는다.

/** 판정에 필요한 최소 영상 속성 — VideoDetail 의 부분집합(전체 타입에 결합하지 않는다). */
export interface FrameDiscardEligibilityInput {
  /**
   * 한번이라도 검수 완료된 적이 있는가 (BE: VideoDetailResponse.everApproved).
   *
   * ★ 현재 검수 상태와 <b>다른 축</b>이다 — 재검수 재제출로 상태가 내려간 구간에도 true 다.
   */
  everApproved?: boolean;
  /** 검수 상태 (BE: LS_RAW_DATA_STATUS.DATA_STTS_CD). 구 응답용 fail-closed 폴백. */
  reviewSttsCd?: string;
}

/** 검수 완료 코드 — BE LsRawDataStatus.STTS_APPROVED 와 동일 값. */
const REVIEW_APPROVED = 'APPROVED';

/**
 * 폐기·복원이 불가능한 사유(툴팁 문구)를 돌려준다. 가능하면 `undefined`.
 *
 * <h3>왜 막는가 — 데이터마트 롤백 정합성</h3>
 * 프레임 이미지·JSON 은 회차별로 물리 분리돼 v1 폴더가 불변인데 <b>영상 파일은 회차별로 분리되지
 * 않는다</b>. 이미 산출되어 외부로 나간 회차에서 프레임이 빠지거나 되살아나면 그 회차의 산출물과
 * 어긋난다.
 *
 * <h3>회차 적용은 이 판정과 무관하다</h3>
 * 과거 회차를 불러와 확정하는 경로는 <b>막지 않는다</b> — 그 회차의 폐기 상태는 이미 승인된 것이라
 * 새로 바꾸는 게 아니라 그 시점으로 되돌아가는 것이다. 이 판정이 가리는 것은 화면의 <b>토글 조작</b>
 * 뿐이며, 확정 저장이 회차 값을 싣는 경로는 서버가 예외로 허용한다.
 *
 * <p>영상 정보를 아직 모르면(`undefined`) 막지 않는다 — 모른다는 이유로 잠그면 정상 동선이 로딩
 * 구간마다 끊긴다.
 */
export function resolveFrameDiscardUnsupportedReason(
  video: FrameDiscardEligibilityInput | undefined | null,
): string | undefined {
  if (!video) return undefined;
  if (video.everApproved === true || video.reviewSttsCd === REVIEW_APPROVED) {
    return '한번이라도 검수가 완료된 영상은 프레임을 새로 폐기하거나 복원할 수 없습니다';
  }
  return undefined;
}
