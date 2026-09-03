// 산출물 종류 — 화면 맨 위에서 고르는 갈래.
//
// ★두 갈래는 방향이 반대다 — 라벨링 완료는 이미 라벨이 끝난 산출물을 받아 검수 대기로 보내고,
//   이벤트 마킹은 마킹만 끝난 영상 묶음을 받아 비식별부터 앞 단계를 전부 밟는다. ADR-053 이
//   **계약을 합치지 않으며 화면에서만 갈래를 고른다**고 정했다.
//
// ★기본값은 라벨링 완료다 — 기존 사용자에게는 아무것도 달라지지 않아야 한다.
//
// @design SCREEN-039
// @design ADR-053

export const ImportKind = {
  /** 라벨링 완료 산출물 (API-205·API-206). */
  LABELED: 'labeled',
  /** 이벤트 마킹 산출물 (API-216·API-217·API-218). */
  MARKING: 'marking',
} as const;
export type ImportKind = (typeof ImportKind)[keyof typeof ImportKind];

/** 화면이 처음 열렸을 때의 갈래. */
export const DEFAULT_IMPORT_KIND: ImportKind = ImportKind.LABELED;

export const IMPORT_KIND_LABEL: Record<ImportKind, string> = {
  [ImportKind.LABELED]: '라벨링 완료',
  [ImportKind.MARKING]: '이벤트 마킹',
};
