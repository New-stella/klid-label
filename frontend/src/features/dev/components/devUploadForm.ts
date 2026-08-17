import { PrvcType } from '@/features/dev/types';

/**
 * dev 업로드 화면의 **개인정보 유형 선택지**.
 *
 * ★ 폼 상태 모델·직렬화 변환은 `unifiedUploadForm.ts` 로 옮겨졌다 — 화면이 적재 경로 2종을 한
 * 폼으로 다루게 되면서 상태가 두 경로의 합집합이 되었고, 여기 남아 있던 `DevUploadFormState`·
 * `initialDevUploadForm`·`toIsoInstant` 는 참조가 0 이 되어 제거했다(두 번째 진실원 방지).
 */

/**
 * 개인정보 유형 선택지 — 값은 BE `PRVC_TYPE_CD` 원문이다.
 *
 * 셋 다 **표시용**이다: 비식별은 파이프라인 선두에서 무조건 실행되므로 이 선택이 비식별 수행
 * 여부를 바꾸지 않는다(구 'PRVC/PSDO 만 비식별' 게이팅은 폐지됨).
 */
export const PRVC_OPTIONS: ReadonlyArray<{
  value: PrvcType;
  label: string;
  hint: string;
}> = [
  {
    value: PrvcType.ANONY,
    label: 'ANONY (비식별 미적용)',
    hint: '표시용 — 비식별은 선두 무조건 실행, 원본도 별도 보존',
  },
  {
    value: PrvcType.PRVC,
    label: 'PRVC (개인정보 포함)',
    hint: '표시용 — 비식별은 선두 무조건 실행',
  },
  {
    value: PrvcType.PSDO,
    label: 'PSDO (가명 정보)',
    hint: '표시용 — 비식별은 선두 무조건 실행',
  },
];
