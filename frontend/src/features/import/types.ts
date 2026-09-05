// 외부 산출물 이관(DOMAIN-017) 도메인 타입 — BE `transfer` 패키지 DTO 의 1:1 미러.
//
// 진실원은 백엔드 응답 계약이다. 여기서 필드를 지어내거나 성공 조건을 다시 계산하지 않는다.
//
// @design SCREEN-039
// @design API-205 API-206 API-207 API-208 API-209 API-210 API-211 API-215

/** 대응 종류 — BE `LsOtsdCtgryMpng.MPNG_KND_*`. */
export const MappingKind = {
  LABEL: 'LABEL',
  EVNT_TYPE: 'EVNT_TYPE',
} as const;
export type MappingKind = (typeof MappingKind)[keyof typeof MappingKind];

/** 이관 진행 상태 — BE `LS_OTSD_DATST_TRNSF_HSTRY.TRNSF_STTS_CD`. */
export const ImportStatus = {
  PROCESSING: 'PROCESSING',
  SUCCESS: 'SUCCESS',
  FAILED: 'FAILED',
} as const;
export type ImportStatus = (typeof ImportStatus)[keyof typeof ImportStatus];

/* ------------------------------------------------------------------ *
 * API-205 검사(미리보기)
 * ------------------------------------------------------------------ */

/** 검사 요청 — 경로는 본문으로 보낸다(주소줄에 실으면 접근 기록에 남는다). */
export interface ImportScanRequest {
  folderPath: string;
  /** 원본 영상 파일 위치. 비우면 프레임과 라벨만 가져온다. */
  videoPath?: string;
}

/**
 * 검사 알림 1건.
 *
 * 이 목록에는 적재를 막는 사유와 막지 않는 사유가 **함께** 담긴다. 개수를 세어 적재 가능 여부를
 * 판정하지 말 것 — 판정은 {@link ImportScanResult.importable} 하나가 소유한다.
 */
export interface ImportWarning {
  code: string;
  message: string;
}

/** 추천 후보 1건 — `targetId` 는 라벨이면 라벨 아이디, 이벤트 유형이면 유형 코드다. */
export interface CategorySuggestion {
  targetId: string;
  targetName: string;
}

/**
 * 아직 대응이 정해지지 않은 분류 1건.
 *
 * `suggestions` 는 **비어 있을 수 있다**(후보 0건이거나 유일하지 않은 경우). 그것은 오류가 아니라
 * 사람이 직접 골라야 한다는 뜻이다.
 */
export interface UnmappedCategory {
  kind: MappingKind;
  externalCode: string;
  externalName: string | null;
  suggestions: CategorySuggestion[];
}

/** 이미 가져온 산출물이 가리키는 영상. */
export interface ImportDuplicate {
  rawSn: number;
}

/** 검사 결과 — 아무것도 저장하지 않는 미리보기다. */
export interface ImportScanResult {
  /** 실제로 발견된 프레임 수(파일 기준). */
  frameCount: number;
  /** 산출물 문서가 선언한 프레임 수. 선언이 없으면 0. */
  declaredFrameCount: number;
  labelCount: number;
  videoFileName: string | null;
  duplicate: ImportDuplicate | null;
  unmappedCategories: UnmappedCategory[];
  warnings: ImportWarning[];
  /** ★적재 가능 여부의 단일 판정값. 화면의 적재 버튼 활성 여부는 이 값 하나가 정한다. */
  importable: boolean;
}

/* ------------------------------------------------------------------ *
 * API-206 적재
 * ------------------------------------------------------------------ */

export interface ImportCreateRequest {
  folderPath: string;
  videoPath?: string;
  /** 이 산출물이 비식별이 끝난 것인지 여부. 기본은 원본(false). */
  deidentified: boolean;
  /** 확인한 알림 사유 코드 — 감사 기록일 뿐 차단 사유를 해제하지 않는다. */
  acknowledgedWarnings?: string[];
}

export interface ImportCreateResult {
  rawSn: number;
  trnsfSn: number;
  frameCount: number;
  labelCount: number;
}

/* ------------------------------------------------------------------ *
 * API-207 / API-208 이관 이력
 * ------------------------------------------------------------------ */

/**
 * 이관 이력 1건.
 *
 * ★`approvalHeld` 는 `status` 와 **다른 축**이다. `status` 는 가져오는 일이 어떻게 끝났는가이고
 * `approvalHeld` 는 그렇게 만들어진 영상이 지금 검수 승인을 받을 수 있는가이다. 성공한 이관
 * 가운데 **일부에만** 보류가 서므로 상태값으로 대신 판단할 수 없다.
 *
 * ★값이 `null` 일 수 있다(영상이 없거나 그 영상의 작업 상태 행이 없을 때). **없음을 「보류
 * 아님」으로 단정하지 않는다** — 단정하면 화면이 보류를 푸는 자리를 감춘다.
 */
export interface ImportHistoryItem {
  trnsfSn: number;
  folderName: string | null;
  rawSn: number | null;
  status: ImportStatus;
  frameCount: number | null;
  labelCount: number | null;
  approvalHeld: boolean | null;
  regId: string | null;
  regDt: string;
}

/** 이관 이력 상세 — 실패 사유·폴더 경로·데이터셋 식별자가 여기에만 있다. */
export interface ImportHistoryDetail {
  trnsfSn: number;
  folderPath: string | null;
  folderName: string | null;
  externalDatasetId: string | null;
  rawSn: number | null;
  status: ImportStatus;
  frameCount: number | null;
  labelCount: number | null;
  failReason: string | null;
  regId: string | null;
  regDt: string;
}

export interface ListImportHistoryParams {
  status?: ImportStatus;
  page?: number;
  size?: number;
}

/* ------------------------------------------------------------------ *
 * API-209 / API-210 / API-211 분류 대응
 * ------------------------------------------------------------------ */

/** 확정된 대응 1건. 연결이 비어 있어도 감추지 않는다(감추면 왜 계속 미확정인지 알 수 없다). */
export interface ImportMapping {
  mpngSn: number;
  kind: MappingKind;
  externalCode: string;
  externalName: string | null;
  labelId: number | null;
  /** 연결된 라벨의 이름. 라벨이 없거나 비활성이면 null. */
  labelName: string | null;
  evntTypeCd: string | null;
  useYn: 'Y' | 'N';
}

export interface ImportMappingListResult {
  items: ImportMapping[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ListImportMappingsParams {
  kind?: MappingKind;
  /** 쓰지 않게 표시한 대응까지 포함할지 여부. */
  includeUnused?: boolean;
  page?: number;
  size?: number;
}

/** 확정 요청 1건 — 라벨 축이면 `labelId`, 이벤트 축이면 `evntTypeCd` 를 채운다. */
export interface ImportMappingCreateItem {
  kind: MappingKind;
  externalCode: string;
  externalName?: string | null;
  labelId?: number | null;
  evntTypeCd?: string | null;
}

export interface ImportMappingCreateRequest {
  items: ImportMappingCreateItem[];
  /** 이미 있는 대응을 바꾸려는 의도인지 여부. 미지정이면 거짓(조용히 덮어쓰지 않는다). */
  overwrite?: boolean;
}

export interface ImportMappingSaveResult {
  created: number;
  updated: number;
}

/* ------------------------------------------------------------------ *
 * API-215 비식별 완료 기록
 * ------------------------------------------------------------------ */

export interface DeidentCompleteRequest {
  deidentifiedFolderPath: string;
}

/**
 * 비식별 완료 기록 결과.
 *
 * ★`deidentFrameUnmatchedCount` 가 0 이 아니면 그만큼의 프레임이 비식별 이미지 없이 남아
 * 그 영상의 학습데이터 산출물에 빠진 채로 나간다. 사람이 그 사실을 알아야 한다.
 */
export interface DeidentCompleteResult {
  rawSn: number;
  procLogSn: number;
  approvalHoldReleased: boolean;
  deidentFrameMatchedCount: number;
  deidentFrameUnmatchedCount: number;
}

/* ------------------------------------------------------------------ *
 * API-221 / API-222 이관 대상 위치 탐색
 * ------------------------------------------------------------------ */

/** 탐색 목록의 한 항목. `path` 를 그대로 다음 탐색·입력칸에 쓴다(문자열을 다시 조립하지 않는다). */
export interface ImportBrowseEntry {
  name: string;
  path: string;
}

/**
 * 위치 탐색 결과 — 폴더 탐색(API-221)·영상 파일 탐색(API-222)이 같은 모양을 돌려준다.
 *
 * ★`path` 는 요청이 지정한 표기가 아니라 **서버가 판정에 사용한 실제 위치**다. 입력칸에 넣을 때
 *   반드시 이 값을 쓴다 — 요청 표기를 그대로 되쓰면 검사 창구와 왕복이 어긋난다.
 * ★`parent` 가 null 인 것은 **지금 자리가 허용 저장소 루트**라는 뜻이다(그 위가 범위 밖이라
 *   비워서 돌아온다). 부모가 또 다른 허용 루트인 것은 범위 안이므로 비우지 않고 그 위치를 싣는다.
 *   ⚠ 「상위로」를 어떻게 다룰지는 여기 적지 않는다 — 그 판정의 단일 진실원은
 *     `components/ImportPathPickerModal` 이다. 두 곳에 적으면 한쪽만 갱신돼 어긋난다.
 * ★`nextCursor` 는 **이어받을 자리**다. 다음 요청에 그대로 실어 보내면 그 자리 뒤부터 이어 받는다.
 *   비어 있으면 그 폴더를 끝까지 본 것이다.
 *   ⚠ **끝났는지는 담긴 개수가 아니라 이 값이 비었는지로 판정한다.** 서버가 살펴보기 상한에 먼저
 *     걸리면 `entries` 가 비어 있으면서 `nextCursor` 가 있는 응답이 정상적으로 돌아온다 —
 *     개수로 끝을 판정하면 그 폴더의 나머지가 통째로 사라진다.
 */
export interface ImportBrowseResult {
  /** 허용 저장소 루트 목록을 돌려줄 때는 기준 위치가 하나로 정해지지 않아 null 이다. */
  path: string | null;
  parent: string | null;
  entries: ImportBrowseEntry[];
  nextCursor: string | null;
}
