// @design SCREEN-005 · @design SCREEN-019 — 프레임 이미지 로드 실패 사유 힌트의 **단일 판정 지점**.
//
// 라벨링 화면(LabelingPage)과 검수 상세 화면(review/LabelCanvas)이 같은 실패를 같은 문구로
// 알려야 한다. 두 화면에 조건을 복제하면 상태코드가 늘거나 문구가 바뀔 때 한쪽만 갱신돼
// 어긋난다(이 저장소가 반복적으로 겪은 결함 유형이라 판정을 여기 한 곳에만 둔다).
//
// 보안(CWE-209): 힌트는 **상태코드로만** 만든다. 서버가 준 메시지·내부 경로·스택을 그대로
// 화면에 싣지 않는다. 미지의 상태코드는 힌트를 만들지 않고 undefined 를 돌려주며, 화면은
// "불러오지 못했습니다"라는 사실만 알린다.

/**
 * 프레임 이미지 로드 실패 원인 힌트.
 *
 * @param error useImageBlob 이 돌려준 error (ApiError 면 status 를 가진다). null/undefined 안전.
 * @returns 사용자에게 보여줄 정형 문구. 매핑되지 않은 상태코드면 undefined.
 */
export function resolveFrameImageErrorHint(error: unknown): string | undefined {
  const status = (error as { status?: number } | null | undefined)?.status;
  if (status === 412) return '비식별 재처리 대기 중인 영상입니다.';
  if (status === 403) return '이 프레임에 접근할 권한이 없습니다.';
  if (status === 404) return '이미지 파일을 찾을 수 없습니다.';
  return undefined;
}
