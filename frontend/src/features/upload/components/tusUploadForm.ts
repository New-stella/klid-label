import type { InternalUploadCreatePayload } from '@/features/upload/api/tusClient';

/**
 * TUS 업로드 폼의 **상태 모델 + 전송 페이로드 변환** — 화면(JSX)과 분리한다.
 *
 * `component.md` 의 "한 파일 400줄 초과 시 분리 필수" 를 지키기 위한 추출이며, 부수적으로 폼
 * 변환 규칙(빈 값 처리)을 화면 렌더와 독립적으로 테스트할 수 있게 한다.
 */

/**
 * 출처유형 — BE `LsDataIngest.UPLOAD_SRC_TYPES`(**입력면 4종**)와 동일 목록.
 *
 * `AUGMENTED` 는 **적재면에만** 있는 값이라 여기 두면 안 된다 — 증강 파생본은 저작도구가 직접
 * 만들고 원본 영상을 `ORGNL_RAW_SN` 으로 가리키므로, 인입으로 받으면 부모 없는 "파생 출처" 행이
 * 생겨 파생 판별 축이 어긋난다(BE 가 400 으로 거부한다).
 */
export const SRC_TYPES: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'USER_ULD', label: 'USER_ULD (사용자 업로드)' },
  { value: 'ORIGINAL', label: 'ORIGINAL (원본 수집)' },
  { value: 'RELAY', label: 'RELAY (중계 수집)' },
  { value: 'GENERATED', label: 'GENERATED (생성형 AI)' },
];

/** 화면 입력은 전부 문자열로 들고 있다가 제출 시점에 타입 변환한다(부분 입력 중 NaN 방지). */
export interface TusFormState {
  // 식별
  vmsClipId: string;
  cctvId: string;
  srcType: string;
  lclgvCd: string;
  shtDtLocal: string;
  // 위치·CCTV
  rgnNm: string;
  wgs84Lat: string;
  wgs84Lot: string;
  ogCd: string;
  cctvNm: string;
  cctvHgt: string;
  mainSurvPanAng: string;
  // 이벤트
  evntId: string;
  evntNm: string;
  mntrCn: string;
  // 기술메타(선택 — 비우면 서버가 파일에서 자동 추출)
  vdoLenSec: string;
  fps: string;
  frmCnt: string;
  wdth: string;
  vrtc: string;
  resl: string;
  asprtRt: string;
  vdoCdc: string;
  fileFmt: string;
  fileSz: string;
  bit: string;
  pxl: string;
}

function nowLocalDateTime(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function initialForm(): TusFormState {
  return {
    vmsClipId: `tus-${Date.now()}`,
    cctvId: 'CCTV-001',
    srcType: 'USER_ULD',
    lclgvCd: '11680',
    shtDtLocal: nowLocalDateTime(),
    rgnNm: '',
    wgs84Lat: '',
    wgs84Lot: '',
    ogCd: '',
    cctvNm: '',
    cctvHgt: '',
    mainSurvPanAng: '',
    evntId: '',
    evntNm: '',
    mntrCn: '',
    vdoLenSec: '',
    fps: '',
    frmCnt: '',
    wdth: '',
    vrtc: '',
    resl: '',
    asprtRt: '',
    vdoCdc: '',
    fileFmt: '',
    fileSz: '',
    bit: '',
    pxl: '',
  };
}

/** 빈 문자열은 키 자체를 보내지 않는다 — BE 는 "값 없음(null)"과 "0"을 구분한다. */
function text(value: string): string | undefined {
  const v = value.trim();
  return v === '' ? undefined : v;
}

/** 숫자 필드 — 비었거나 숫자가 아니면 보내지 않는다(BE @Valid 가 형식을 최종 판정). */
function num(value: string): number | undefined {
  const v = value.trim();
  if (v === '') return undefined;
  const n = Number(v);
  return Number.isFinite(n) ? n : undefined;
}

export function toPayload(form: TusFormState, fileName: string): InternalUploadCreatePayload {
  return {
    fileName,
    vmsClipId: form.vmsClipId.trim(),
    cctvId: form.cctvId.trim(),
    lclgvCd: form.lclgvCd.trim(),
    srcType: form.srcType,
    shtDt: text(form.shtDtLocal),
    rgnNm: text(form.rgnNm),
    wgs84Lat: num(form.wgs84Lat),
    wgs84Lot: num(form.wgs84Lot),
    ogCd: text(form.ogCd),
    cctvNm: text(form.cctvNm),
    cctvHgt: num(form.cctvHgt),
    mainSurvPanAng: num(form.mainSurvPanAng),
    evntId: text(form.evntId),
    evntNm: text(form.evntNm),
    mntrCn: text(form.mntrCn),
    vdoLenSec: num(form.vdoLenSec),
    fps: text(form.fps),
    frmCnt: num(form.frmCnt),
    wdth: num(form.wdth),
    vrtc: num(form.vrtc),
    resl: text(form.resl),
    asprtRt: text(form.asprtRt),
    vdoCdc: text(form.vdoCdc),
    fileFmt: text(form.fileFmt),
    fileSz: num(form.fileSz),
    bit: text(form.bit),
    pxl: text(form.pxl),
  };
}
