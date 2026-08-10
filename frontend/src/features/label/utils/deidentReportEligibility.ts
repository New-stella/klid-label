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
}

/** 검수 완료 코드 — BE LsRawDataStatus.STTS_APPROVED 와 동일 값. */
const REVIEW_APPROVED = 'APPROVED';

/**
 * 신고가 불가능한 사유(툴팁 문구)를 돌려준다. 가능하면 `undefined`.
 *
 * 사유는 둘 다 <b>영상의 영구 속성</b>이라 시간이 지나도 풀리지 않는다:
 *  ① 파생영상 — 원본의 비식별 결과를 복사한 사본이라 파생본을 다시 비식별할 수단이 없다.
 *  ② 검수 승인(APPROVED) 영상 — 승인된 학습데이터 위에 신고를 새로 받지 않는다(역할 무관).
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
  if (video.reviewSttsCd === REVIEW_APPROVED) {
    return '검수가 완료된 영상은 비식별 누락을 신고할 수 없습니다';
  }
  return undefined;
}
