import { PrvcType } from '@/features/dev/types';

/**
 * dev 업로드 화면의 **상태 모델 + 선택지 + 직렬화 변환** — 화면(JSX)과 분리한다.
 *
 * `component.md` 의 "한 파일 400줄 초과 시 분리 필수" 를 지키기 위한 추출이며, TUS 패널이 이미
 * 같은 이유로 `upload/components/tusUploadForm.ts` 를 분리한 것과 동일한 구조다. 부수적으로
 * 폼 변환 규칙(로컬시각 → ISO)을 화면 렌더와 독립적으로 테스트할 수 있게 한다.
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

export interface DevUploadFormState {
  vmsClipId: string;
  cctvId: string;
  /**
   * 관제 이벤트 카테고리 키(EVNT_CLS_CD+EVNT_CTGRY_CD, 예 "020002"). select 의 value 다.
   * 제출 시 이 카테고리의 대표 EV-코드(memberCodes[0])로 변환해 eventTypeCd 로 전송한다.
   * 옵션 로드 전에는 빈 문자열이며 로드 완료 시 첫 카테고리로 초기화된다.
   */
  categoryKey: string;
  localGovCd: string;
  prvcTypeCd: PrvcType;
  /** datetime-local 형식 (`YYYY-MM-DDTHH:mm`) — 제출 시 ISO 로 변환. */
  capturedAtLocal: string;
}

/** ISO-8601 (Instant) — capturedAt 직렬화. `datetime-local` 값은 timezone 미포함이므로 보정. */
export function toIsoInstant(localDateTime: string): string {
  if (!localDateTime) return '';
  const d = new Date(localDateTime);
  if (Number.isNaN(d.getTime())) return '';
  return d.toISOString();
}

/** datetime-local input 의 초기값 — `YYYY-MM-DDTHH:mm` (브라우저 로컬). */
function nowLocalDateTime(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** vmsClipId 기본값 — 중복 방지를 위해 timestamp 사용. */
function defaultVmsClipId(): string {
  return `test-${Date.now()}`;
}

export function initialDevUploadForm(): DevUploadFormState {
  return {
    vmsClipId: defaultVmsClipId(),
    cctvId: 'CCTV-001',
    categoryKey: '',
    localGovCd: '11680',
    prvcTypeCd: PrvcType.ANONY,
    capturedAtLocal: nowLocalDateTime(),
  };
}
