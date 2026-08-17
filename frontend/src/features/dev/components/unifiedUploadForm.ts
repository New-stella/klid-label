import { PrvcType, type AutolabelTestMeta } from '@/features/dev/types';
import type { InternalUploadCreatePayload } from '@/features/upload/api/tusClient';
import {
  initialForm as initialTusForm,
  toPayload as toTusPayload,
  type TusFormState,
} from '@/features/upload/components/tusUploadForm';

/**
 * 영상 업로드 화면(`/dev/upload`)의 **단일 폼 상태 + 경로별 전송 변환**. [@design SCREEN-027]
 *
 * <p>화면은 입력 폼 한 벌만 갖고, 최상단 라디오로 고른 **적재 경로**가 「보내는 곳과 그 뒤 흐름」만
 * 바꾼다. 그래서 폼 상태는 두 경로의 **합집합**이고, 전송 payload 변환기만 경로별로 갈린다 —
 * 두 경로의 전송 계층(통째 multipart vs 청크 재개)과 payload 계약이 실제로 다르므로 전송을
 * 하나로 합치면 계약이 깨진다.
 *
 * <h3>★필드 ↔ 경로 매핑 (조용한 손실 차단용 정본)</h3>
 * <pre>
 * 필드                         즉시 실행(dev 업로드)      인입 재현(TUS)
 * ─────────────────────────── ───────────────────────── ─────────────────────
 * 영상 파일                    multipart `file`          청크 전송 + `fileName`
 * 영상 클립 ID                 `vmsClipId`               `vmsClipId`
 * CCTV ID                      `cctvId`                  `cctvId`
 * 지자체코드                   `localGovCd`              `lclgvCd`
 * 이벤트유형(코드)             `eventTypeCd` (필수)      `evntTypeCd` (선택)
 * 촬영일시                     `capturedAt` (ISO, 필수)  `shtDt` (LocalDateTime, 선택)
 * 개인정보 유형                `prvcTypeCd` (필수)       ✗ 계약에 없음
 * 출처유형                     ✗ 계약에 없음             `srcType`
 * 위치·CCTV 제원 6종           ✗ 계약에 없음             전량
 * 이벤트 ID/명·관제일지·
 *   시계열 이벤트유형          ✗ 계약에 없음             전량
 * 영상 기술메타 12종           ✗ 계약에 없음 (※)         전량
 * </pre>
 *
 * <p>즉시 실행 경로가 버리는 항목이 많은 것은 BE `AutolabelTestRequest` 가 6필드짜리 좁은 계약이기
 * 때문이다. 계약을 넓히는 것은 FE 범위가 아니므로, 화면은 **그 경로에서 전송되지 않는다는 사실을
 * 묶음별로 알린다**({@link UNSENT_GROUPS}) — 입력을 받아 놓고 조용히 버리지 않는다.
 *
 * <p>※ **기술메타만 예외적으로 «버려진다» 로 끝나지 않는다** — 입력값은 전송되지 않지만, 서버가 올린
 * 영상 파일을 조사해 읽을 수 있는 항목(해상도·코덱·fps·비트레이트·파일크기·길이)을 직접 채운다.
 * 그래서 이 묶음의 안내 문구만 갈라진다({@link SERVER_FILLED_GROUPS}).
 */

/** 적재 경로 — 라디오 값. 폼 필드가 아니라 «어디로 보낼지»의 축이다. */
export const UploadRoute = {
  /** 올린 즉시 파이프라인(비식별 → 마킹 대기)까지 이어진다. */
  IMMEDIATE: 'IMMEDIATE',
  /** 인입 원장에 적재하고 주기 배치가 훑을 때 진행된다. */
  INGEST: 'INGEST',
} as const;
export type UploadRoute = (typeof UploadRoute)[keyof typeof UploadRoute];

export const UPLOAD_ROUTE_OPTIONS: ReadonlyArray<{
  value: UploadRoute;
  label: string;
  hint: string;
}> = [
  {
    value: UploadRoute.IMMEDIATE,
    label: '파이프라인 즉시 실행',
    hint: '올리는 즉시 비식별을 거쳐 마킹 대기까지 진행됩니다',
  },
  {
    value: UploadRoute.INGEST,
    label: '관제 인입 재현',
    hint: '인입 원장에 적재하고 주기 배치가 훑을 때 진행됩니다 · 끊긴 지점부터 이어 올리기 지원',
  },
];

/**
 * 통째 전송(multipart) 한도 — BE `spring.servlet.multipart.max-file-size`(500MB)와 같은 값.
 *
 * ⚠ 이것은 **영상 크기 제한이 아니라 전송 방식의 한도**다. 청크로 나눠 보내는 인입 재현 경로는 이
 * 한도를 받지 않는다(그 경로의 전체 크기 상한 `authoring.upload.tus.max-file-size` 는 별개 축이며
 * 서버가 413 으로 판정한다 — 화면이 그 값을 사본으로 들지 않는다).
 */
export const MULTIPART_MAX_BYTES = 500 * 1024 * 1024;

/**
 * 이벤트유형 select 의 "직접 입력" 센티넬 — **전송값이 아니라 화면 모드 표식**이다.
 *
 * 시계열 이벤트유형(`VRFC_MANUAL_OPTION`)과 같은 이유로 둔다: 관제 이벤트 코드 체계는 우리 소유가
 * 아니고 미등록 코드도 실제로 들어오며 적재가 처음 보는 코드를 마스터에 자동 등록한다. 마스터
 * 조회 목록으로만 좁히면 **관제가 코드를 넓힐 때 우리가 먼저 막는다**.
 */
export const EVENT_TYPE_MANUAL_OPTION = '__manual__';

/** 선택한 경로에서 전송되지 않는 입력 묶음 — 화면 안내(조용한 손실 차단)의 단일 원천. */
export const UNSENT_GROUPS: Readonly<Record<UploadRoute, ReadonlyArray<string>>> = {
  // 즉시 실행 경로의 BE 계약(6필드)에 없는 묶음.
  [UploadRoute.IMMEDIATE]: ['출처유형', '위치 · CCTV 제원', '이벤트 · 관제일지', '영상 기술메타'],
  // 인입 계약에 개인정보 유형 컬럼이 없다(적재 후 비식별 축이 별도로 채운다).
  [UploadRoute.INGEST]: ['개인정보 유형'],
};

/**
 * {@link UNSENT_GROUPS} 중 **입력값은 전송되지 않지만 서버가 올린 파일에서 읽어 채우는** 묶음.
 *
 * <p>«전송되지 않는다» 와 «채워지지 않는다» 는 다른 축이다. 영상 기술메타(해상도·코덱·fps·
 * 비트레이트·파일크기·길이)는 입력값이 계약에 없어 버려지지만, 서버가 올린 영상 파일을 조사해
 * 읽을 수 있는 항목을 직접 채운다. 그래서 이 묶음에는 «전송되지 않습니다» 만 알리면 **거짓**이 된다.
 *
 * <p>다른 묶음(위치·CCTV 제원·이벤트 ID/명·관제일지·출처유형)은 파일에서 읽을 수 없는 값이라
 * 진짜로 버려진다 — 전부 같은 문구로 통일하면 그쪽이 거짓이 된다.
 *
 * <p>경로별로 나누지 않는 이유: 안내 자체가 «그 경로에서 전송되지 않는 묶음» 에만 뜨고
 * (`UNSENT_GROUPS[route]` 로 걸러진다), 기술메타는 즉시 실행 경로에서만 그 목록에 든다.
 */
export const SERVER_FILLED_GROUPS: ReadonlyArray<string> = ['영상 기술메타'];

/**
 * 단일 폼 상태 — 인입 재현 폼(`TusFormState`)의 합집합에 즉시 실행 전용 2필드를 더한 것.
 *
 * `TusFormState` 를 **확장**하는 형태라 기존 fieldset 컴포넌트(`TusMetaFieldsets`)를 그대로
 * 재사용한다(구조적 타이핑). 두 폼의 입력 UI 를 복제하면 한쪽만 갱신돼 갈라진다.
 */
export interface UnifiedUploadFormState extends TusFormState {
  /**
   * 이벤트유형 select 값 — 표시명 그룹의 대표코드(`EventTypeResponse.categoryKey`) 또는
   * {@link EVENT_TYPE_MANUAL_OPTION}. 옵션 로드 전에는 빈 문자열이다.
   *
   * 실제 전송값은 {@link resolveEventTypeCd} 가 산출한다 — 화면이 재유도하지 않는다.
   */
  categoryKey: string;
  /** 개인정보 유형 — 즉시 실행 경로 전용(표시용 메타. 비식별 수행 여부를 바꾸지 않는다). */
  prvcTypeCd: PrvcType;
}

export function initialUnifiedUploadForm(): UnifiedUploadFormState {
  return {
    ...initialTusForm(),
    categoryKey: '',
    prvcTypeCd: PrvcType.ANONY,
  };
}

/** 이벤트유형 옵션의 최소 형태 — `EventTypeResponse` 중 이 변환이 쓰는 필드만. */
export interface EventTypeOptionLike {
  categoryKey: string;
  memberCodes: string[];
}

/**
 * 전송할 이벤트유형코드를 확정한다 — 두 경로가 **같은 값**을 쓴다.
 *
 * - 직접 입력 모드면 자유 입력값(`evntTypeCd`)을 대문자로 올려 쓴다. BE 형식 검증이 대문자·숫자·`_`
 *   라 소문자 입력이 400 이 되는데, 관제 코드 체계가 대문자 표기이므로 소문자는 **표기 실수이지
 *   다른 값이 아니다**.
 * - 그룹 선택이면 그 그룹의 대표코드(`memberCodes[0]`)를 쓴다. 그룹을 못 찾으면 빈 문자열이며,
 *   추측해 채우지 않는다(엉뚱한 유형으로 적재되는 것보다 서버가 거부하는 편이 안전하다).
 */
export function resolveEventTypeCd(
  form: UnifiedUploadFormState,
  options: ReadonlyArray<EventTypeOptionLike>,
): string {
  if (form.categoryKey === EVENT_TYPE_MANUAL_OPTION) {
    return form.evntTypeCd.trim().toUpperCase();
  }
  if (!form.categoryKey) return '';
  const matched = options.find((o) => o.categoryKey === form.categoryKey);
  return matched?.memberCodes[0] ?? '';
}

/** ISO-8601 (Instant) — `datetime-local` 값은 timezone 미포함이므로 보정해 직렬화한다. */
export function toIsoInstant(localDateTime: string): string {
  if (!localDateTime) return '';
  const d = new Date(localDateTime);
  if (Number.isNaN(d.getTime())) return '';
  return d.toISOString();
}

/**
 * 즉시 실행 경로 payload — BE `AutolabelTestRequest`(6필드) 1:1.
 *
 * 이 계약에 없는 입력(출처유형·위치·이벤트 부가·기술메타)은 **여기서 사라진다**. 그 사실은
 * {@link UNSENT_GROUPS} 를 통해 화면이 사용자에게 알린다.
 */
export function toImmediatePayload(
  form: UnifiedUploadFormState,
  eventTypeCd: string,
): AutolabelTestMeta {
  return {
    vmsClipId: form.vmsClipId.trim(),
    cctvId: form.cctvId.trim(),
    eventTypeCd: eventTypeCd.trim().toUpperCase(),
    localGovCd: form.lclgvCd.trim(),
    prvcTypeCd: form.prvcTypeCd,
    capturedAt: toIsoInstant(form.shtDtLocal),
  };
}

/**
 * 인입 재현 경로 payload — BE `InternalUploadCreateRequest`.
 *
 * 변환 규칙(빈 값 = 키 부재, 숫자 파싱)은 기존 `tusUploadForm.toPayload` 를 **재사용**한다. 규칙을
 * 복제하면 한쪽만 갱신돼 전송값이 갈라진다. 이벤트유형만 확정값으로 덮어써 두 경로가 같은 코드를
 * 보내게 한다.
 */
export function toIngestPayload(
  form: UnifiedUploadFormState,
  args: { fileName: string; eventTypeCd: string },
): InternalUploadCreatePayload {
  return toTusPayload({ ...form, evntTypeCd: args.eventTypeCd }, args.fileName);
}

/**
 * 업로드 시작 가능 여부.
 *
 * 화면이 `*` 로 표시하는 필수는 **식별 정보 4항목**뿐이고 나머지 묶음은 전부 선택이다. 다만 즉시
 * 실행 경로의 BE 계약은 이벤트유형·촬영일시를 `@NotNull` 로 요구하므로 그 경로에서만 두 값을
 * 추가로 요구한다 — 둘 다 기본값이 미리 채워져 있어 사용자가 손댈 일은 없고, 비운 채 제출해
 * 서버 400 을 받는 것보다 버튼을 잠그는 편이 낫다.
 */
export function canStartUpload(args: {
  file: File | null;
  form: UnifiedUploadFormState;
  route: UploadRoute;
  eventTypeCd: string;
}): boolean {
  const { file, form, route, eventTypeCd } = args;
  if (!file) return false;
  if (!form.vmsClipId.trim()) return false;
  if (!form.cctvId.trim()) return false;
  if (!form.srcType.trim()) return false;
  if (!form.lclgvCd.trim()) return false;
  if (route === UploadRoute.IMMEDIATE) {
    if (!eventTypeCd) return false;
    if (!form.shtDtLocal) return false;
    if (file.size > MULTIPART_MAX_BYTES) return false;
  }
  return true;
}

/**
 * 즉시 실행 경로의 통째 전송 한도 초과 안내 — 초과가 아니면 `null`.
 *
 * 서버가 413 으로 거부하기 전에 화면에서 사유를 먼저 알린다(500MB 파일을 다 올린 뒤 거부되는
 * 동선 제거). 인입 재현 경로에서는 이 한도가 적용되지 않으므로 항상 `null` 이다.
 */
export function multipartLimitMessage(file: File | null, route: UploadRoute): string | null {
  if (!file || route !== UploadRoute.IMMEDIATE) return null;
  if (file.size <= MULTIPART_MAX_BYTES) return null;
  return '파이프라인 즉시 실행은 파일을 한 번에 통째로 보내므로 500MB 를 넘을 수 없습니다. 관제 인입 재현을 고르면 청크로 나눠 올릴 수 있습니다.';
}
