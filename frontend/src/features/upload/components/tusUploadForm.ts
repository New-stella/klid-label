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

/**
 * 검증이벤트유형 — 외부 VLM 검증 API(`POST /v1/videovlm/verify`)의 `event_type` enum 6종.
 * [req: R7] dev 업로드에서 이 값을 직접 지정한다.
 *
 * 원래 이 값은 관제가 인입(`LS_DATA_INGEST.VRFC_EVNT_TYPE_CD`)으로 보내주기로 확정됐으나 관제
 * 반영 전까지 verify 연동을 돌려볼 수단이 없어, dev 업로드 입력으로 열어 둔다.
 *
 * **전송값은 벤더 규격 그대로 소문자 원문**이다 — 라벨(한글 병기)은 화면 표시용이며 전송하면
 * 벤더 검증에서 거부된다. 목록의 단일 진실원은 BE `LsDataIngest.VRFC_EVNT_TYPES` 이고 이 배열은
 * 그 **FE 단일 진실원**이다(리터럴을 화면 여러 곳에 흩지 말 것 — `SRC_TYPES` 가 겪은 실사고 동형).
 *
 * ★ **이 배열은 프리셋일 뿐 허용목록이 아니다 (2026-08-06)** — 화면에 **직접 입력**이 함께 열려
 * 있어 여기 없는 값도 보낼 수 있다(`TusMetaFieldsets.VrfcEvntTypeField`). BE 도 6종 allowlist 를
 * 폐기하고 **형식 검사**(소문자·숫자·밑줄 20자)로 좁혔다.
 *
 * 최종 판정은 서버가 한다 — 이 select 는 자주 쓰는 값을 빠르게 고르게 하는 **UX 보조**일 뿐이고
 * 신뢰 경계가 아니다(BE `LsDataIngest.isVrfcEvntTypeFormatValid` 가 형식 위반이면 400).
 */
export const VRFC_EVNT_TYPES: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'fire', label: '화재 (fire)' },
  { value: 'fall', label: '쓰러짐 (fall)' },
  { value: 'violence', label: '폭력 (violence)' },
  { value: 'flooding', label: '침수 (flooding)' },
  { value: 'car_accident', label: '교통사고 (car_accident)' },
  { value: 'kidnapping', label: '납치 (kidnapping)' },
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
  lclgvNm: string;
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
  /** 검증이벤트유형(선택) — 빈 문자열 = 미지정. [req: R7] */
  vrfcEvntTypeCd: string;
  // 기술메타(선택 — 비우면 서버가 파일에서 자동 추출)
  vdoLenSec: string;
  fps: string;
  frmeCnt: string;
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
    lclgvNm: '',
    wgs84Lat: '',
    wgs84Lot: '',
    ogCd: '',
    cctvNm: '',
    cctvHgt: '',
    mainSurvPanAng: '',
    evntId: '',
    evntNm: '',
    mntrCn: '',
    // 미지정이 기본 — 값 없는 업로드는 VLM 위탁 SKIPPED 경로를 검증하는 정당한 케이스다.
    vrfcEvntTypeCd: '',
    vdoLenSec: '',
    fps: '',
    frmeCnt: '',
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
    lclgvNm: text(form.lclgvNm),
    wgs84Lat: num(form.wgs84Lat),
    wgs84Lot: num(form.wgs84Lot),
    ogCd: text(form.ogCd),
    cctvNm: text(form.cctvNm),
    cctvHgt: num(form.cctvHgt),
    mainSurvPanAng: num(form.mainSurvPanAng),
    evntId: text(form.evntId),
    evntNm: text(form.evntNm),
    mntrCn: text(form.mntrCn),
    // 미지정이면 키 자체를 보내지 않는다(다른 선택 필드와 동일 관례). BE 는 공백도 미지정으로
    // 처리하지만, "값 없음"을 키 부재로 표현하는 이 폼의 기존 계약을 따른다. [req: R7]
    vrfcEvntTypeCd: text(form.vrfcEvntTypeCd),
    vdoLenSec: num(form.vdoLenSec),
    fps: text(form.fps),
    frmeCnt: num(form.frmeCnt),
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
