// 프리셋 도메인 타입 — BE: {name, description?, labelCodes: string[], labelCodeOptions: LabelCodeOption[], eventTypeCd?} 정합
//
// Phase 3 (V1.7) — 각 라벨 코드별 BBOX/POLYGON 옵션 지원.
// `labelCodes` 는 레거시 호환 필드로 유지 (PresetListPage 등 카드 표시용).

import { EVENT_TYPES } from '@/constants/eventTypes';

/**
 * 라벨 코드별 어노테이션 타입 옵션.
 * - BBOX 또는 POLYGON 중 최소 하나는 true 여야 함 (둘 다 false 는 schema/BE 에서 거부)
 */
export interface LabelCodeOption {
  code: string;
  bboxEnabled: boolean;
  polygonEnabled: boolean;
}

export interface Preset {
  id: number;
  name: string;
  description: string | null;
  /** 레거시 호환 — 카드 표시 등. labelCodeOptions 의 code 목록과 동일. */
  labelCodes: string[];
  /** Phase 3 — 각 라벨의 BBOX/POLYGON 옵션. */
  labelCodeOptions: LabelCodeOption[];
  /** 매핑 이벤트 타입 코드. null/undefined = 미매핑. (V15 — DB UNIQUE 제약, 이벤트 1:1 매핑) */
  eventTypeCd?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PresetForm {
  name: string;
  description: string;
  /** 레거시 호환 — labelCodeOptions 의 code 만 발췌한 배열. BE 송신 시 함께 전송하지 않아도 OK. */
  labelCodes: string[];
  /** Phase 3 — 각 라벨의 BBOX/POLYGON 옵션. */
  labelCodeOptions: LabelCodeOption[];
  /** 매핑 이벤트 타입. 빈 문자열/undefined = 미매핑. */
  eventTypeCd?: string;
}

/** 빠른 추가 chips (10종) — mock 정합. {code, name}. */
export const PRESET_LABEL_SUGGESTIONS = [
  { code: 'PERSON', name: '사람' },
  { code: 'VEHICLE', name: '차량' },
  { code: 'BICYCLE', name: '자전거' },
  { code: 'MOTORCYCLE', name: '오토바이' },
  { code: 'TRUCK', name: '트럭' },
  { code: 'BUS', name: '버스' },
  { code: 'FIRE', name: '화재' },
  { code: 'SMOKE', name: '연기' },
  { code: 'WATER', name: '침수물' },
  { code: 'FALLEN', name: '쓰러진 사람' },
] as const;

/**
 * 이벤트 타입 옵션 — 프리셋 1:1 매핑용.
 *
 * SoT: {@code @/constants/eventTypes}. EVT_TRASH 제거, ABNORMAL/FLOOD 포함 6 종.
 * 라벨은 SoT 한글 라벨을 그대로 노출 (UI 표시 통일).
 */
export const EVENT_TYPE_OPTIONS: ReadonlyArray<{ code: string; name: string }> =
  EVENT_TYPES.map((e) => ({ code: e.code, name: e.label }));

/** 이벤트 코드 → 한글 라벨 매핑 (목록/카드 표시용). SoT 기반. */
export const EVENT_TYPE_LABELS: Readonly<Record<string, string>> = Object.freeze(
  EVENT_TYPE_OPTIONS.reduce<Record<string, string>>((acc, e) => {
    acc[e.code] = e.name;
    return acc;
  }, {}),
);
