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
 * 검증이벤트유형 — 외부 시계열 분석 사업자가 지원하는 `event_type` **프리셋 7종**.
 * [req: R7] dev 업로드에서 이 값을 직접 지정한다.
 *
 * 원래 이 값은 관제가 인입(`LS_DATA_INGEST.VRFC_EVNT_TYPE_CD`)으로 보내주기로 확정됐으나 관제
 * 반영 전까지 위탁 연동을 돌려볼 수단이 없어, dev 업로드 입력으로 열어 둔다.
 *
 * **전송값은 벤더 규격 그대로 소문자 원문**이다 — 라벨(한글 병기)은 화면 표시용이며 전송하면
 * 벤더 검증에서 거부된다.
 *
 * <h3>★ 이 배열은 BE 상수의 <b>손 복제본</b>이다 — 자동으로 따라오지 않는다</h3>
 * 값의 원천은 BE `LsDataIngest.VRFC_EVNT_TYPES` 이지만 이 배열이 그것을 **소비하지 않는다**.
 * 화면이 그 상수를 읽을 통로가 없어 **사람이 옮겨 적은 사본**이며, 그래서 **BE 가 값을 늘리면
 * 여기도 함께 고쳐야 한다**(고치지 않으면 화면만 조용히 옛 목록에 갇힌다 — 실제로 `smoke` 가
 * BE 에 더해진 뒤 화면은 6종에 머물러 있었다).
 * 함께 고칠 자리는 이 배열과 그 값을 단언하는 시험(`tusUpload.test.tsx`) 둘이다.
 * ⚠ 구 주석은 *"목록의 단일 진실원은 BE"* 라고 적어 마치 배선이 있는 것처럼 읽혔다. 사본이라는
 * 사실을 적는 것이 이 문단의 목적이며, **여기에 없는 자동 동기화를 만들라는 뜻이 아니다.**
 *
 * <h3>FE 안에서는 여기가 단일 지점이다</h3>
 * 리터럴을 화면 여러 곳에 흩지 말 것(`SRC_TYPES` 가 겪은 실사고 동형) — 화면·시험 모두 이
 * 배열을 참조한다.
 *
 * ★ **이 배열은 프리셋일 뿐 허용목록이 아니다 (2026-08-06)** — 화면에 **직접 입력**이 함께 열려
 * 있어 여기 없는 값도 보낼 수 있다(`TusMetaFieldsets.VrfcEvntTypeField`). BE 도 allowlist 사전
 * 차단을 **폐기**하고 **형식 검사**(소문자·숫자·밑줄 20자)로 좁혔다.
 * **이 값으로 입력을 막는 코드를 새로 만들지 말 것.**
 *
 * 최종 판정은 서버가 한다 — 이 select 는 자주 쓰는 값을 빠르게 고르게 하는 **UX 보조**일 뿐이고
 * 신뢰 경계가 아니다(BE `LsDataIngest.isVrfcEvntTypeFormatValid` 가 형식 위반이면 400).
 *
 * 순서·한글 표기는 질문 카탈로그 시드(`LS_VRFC_EVNT_TYPE`, 정렬순서)와 같은 축으로 맞춘다.
 */
export const VRFC_EVNT_TYPES: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'fire', label: '화재 (fire)' },
  { value: 'smoke', label: '연기 (smoke)' },
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
  cctvNm: string;
  cctvHgt: string;
  mainSurvPanAng: string;
  // 이벤트
  evntId: string;
  evntNm: string;
  /**
   * 이벤트유형코드(선택) — 빈 문자열 = 미지정.
   *
   * 검증이벤트유형(`vrfcEvntTypeCd`)과 **축이 다른 값**이다. 이쪽은 관제 코드 체계의 유형
   * 식별자(예 `EV01000101`)이고 마킹 진입 조건이며, 저쪽은 외부 VLM 검증 API 의 `event_type` 이다.
   */
  evntTypeCd: string;
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
    // 프로토콜명(TUS)은 사용자에게 보이는 문구에 쓰지 않는다 — 이 값은 입력칸에 그대로
    // 노출되고 저장 파일명이 된다. 중복 방지를 위해 시각을 붙인다.
    vmsClipId: `upload-${Date.now()}`,
    cctvId: 'CCTV-001',
    srcType: 'USER_ULD',
    lclgvCd: '11680',
    shtDtLocal: nowLocalDateTime(),
    lclgvNm: '',
    wgs84Lat: '',
    wgs84Lot: '',
    cctvNm: '',
    cctvHgt: '',
    mainSurvPanAng: '',
    evntId: '',
    evntNm: '',
    evntTypeCd: '',
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
    cctvNm: text(form.cctvNm),
    cctvHgt: num(form.cctvHgt),
    mainSurvPanAng: num(form.mainSurvPanAng),
    evntId: text(form.evntId),
    evntNm: text(form.evntNm),
    // 미지정이면 키 부재(위 관례와 동일). 값이 있으면 대문자로 올려 보낸다 — BE 형식 검증이
    // 대문자·숫자·'_' 라 소문자 입력이 400 이 되는데, 관제 코드 체계가 대문자 표기라
    // 사용자가 소문자로 친 것은 표기 실수이지 다른 값이 아니다.
    evntTypeCd: text(form.evntTypeCd)?.toUpperCase(),
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
