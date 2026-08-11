// 비식별 누락 신고가 <이 영상에서> 애초에 불가능한 사유를 판정한다 — <b>단일 판정 지점</b>.
//
// 왜 한 곳인가: 사유가 늘 때마다 화면(라벨링·마킹)이 각자 조건을 이어붙이면 한쪽만 갱신돼 값이
// 어긋난다(이 저장소의 반복 결함). 화면은 <b>어떤 영상인지</b>만 넘기고 판정은 여기 위임한다.
//
// 서버 계약과의 관계: 여기서 막는 것은 <b>동선</b>이고, 실제 차단은 BE 프리컨디션(412)이 한다.
// 화면이 조건을 모르는 경우(구 응답 등)를 위해 컴포넌트의 412 안내는 안전망으로 유지된다.

/** 판정에 필요한 최소 영상 속성 — VideoDetail 의 부분집합(전체 타입에 결합하지 않는다). */
export interface DeidentReportEligibilityInput {
  /** 증강·해상도 변환으로 만든 파생영상인가 (BE: LS_DATA_RAW.ORGNL_RAW_SN 유무). */
  derivative?: boolean;
  /** 검수 상태 (BE: LS_RAW_DATA_STATUS.DATA_STTS_CD). 'APPROVED' = 검수 완료. */
  reviewSttsCd?: string;
  /**
   * 한번이라도 검수 완료된 적이 있는가 (BE: VideoDetailResponse.everApproved).
   *
   * ★ `reviewSttsCd` 와 <b>다른 축</b>이다. 검수 완료 뒤 작업자가 다시 제출하면 상태는 PENDING 으로
   * 내려가는데(ReviewStateMachine 이 APPROVED → PENDING 을 허용한다), 그 구간에도 신고는 받지 않는다.
   * 현재 상태만 보면 그 구간에서 버튼이 열려 사용자가 사유를 다 적고 제출한 뒤에야 거부를 알게 된다.
   */
  everApproved?: boolean;
}

/** 검수 완료 코드 — BE LsRawDataStatus.STTS_APPROVED 와 동일 값. */
const REVIEW_APPROVED = 'APPROVED';

/**
 * 신고가 불가능한 사유(툴팁 문구)를 돌려준다. 가능하면 `undefined`.
 *
 * 사유는 둘 다 <b>영상의 영구 속성</b>이라 시간이 지나도 풀리지 않는다:
 *  ① 파생영상 — 원본의 비식별 결과를 복사한 사본이라 파생본을 다시 비식별할 수단이 없다.
 *  ② <b>한번이라도</b> 검수 완료된 영상 — 승인된 학습데이터 위에 신고를 새로 받지 않는다(역할 무관).
 *     판정은 <b>지금 상태가 아니라 이력</b>이다(재제출로 상태가 내려간 구간에도 막는다).
 *     근거는 데이터마트 롤백 정합성이다: 영상 파일은 회차별로 분리되지 않아, 승인 후 재비식별이
 *     일어나면 v1 이미지(옛 마스킹)와 뷰의 영상(새 마스킹)이 어긋나고 v1 으로 되돌리면 신고로 걷어낸
 *     개인정보가 되살아난다.
 *
 * 영상 정보를 아직 모르면(`undefined`) 막지 않는다 — 모른다는 이유로 버튼을 잠그면 정상 동선이
 * 로딩 구간마다 끊긴다. 그 창은 BE 412 + 컴포넌트 안내가 받는다.
 */
export function resolveDeidentReportUnsupportedReason(
  video: DeidentReportEligibilityInput | undefined | null,
): string | undefined {
  if (!video) return undefined;
  if (video.derivative) {
    return '증강·해상도 변환으로 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다.';
  }
  // ★ 이력 축이 우선이다. 현재 상태(reviewSttsCd)도 함께 보는 이유는 <b>fail-closed</b> 다 — 이 필드를
  //   못 내리는 구 응답에서도 지금 승인 상태라면 막아야 한다(둘 중 하나라도 참이면 차단).
  if (video.everApproved === true || video.reviewSttsCd === REVIEW_APPROVED) {
    return '한번이라도 검수가 완료된 영상은 비식별 누락을 신고할 수 없습니다';
  }
  return undefined;
}
