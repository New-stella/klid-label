// 프리셋 도메인 타입 — BE: {name, description?, labelCodes: string[], eventTypeCd?} 정합

export interface Preset {
  id: number;
  name: string;
  description: string | null;
  labelCodes: string[];
  /** 매핑 이벤트 타입 코드. null/undefined = 미매핑. (V15 — DB UNIQUE 제약, 이벤트 1:1 매핑) */
  eventTypeCd?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PresetForm {
  name: string;
  description: string;
  labelCodes: string[];
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
 * BE V1.8 SFR-08 / EventTypeBadge 라벨과 정합.
 */
export const EVENT_TYPE_OPTIONS = [
  { code: 'EVT_FALL', name: '낙상' },
  { code: 'EVT_VIOLENCE', name: '폭력' },
  { code: 'EVT_ACCIDENT', name: '사고' },
  { code: 'EVT_FIRE', name: '화재' },
  { code: 'EVT_TRASH', name: '쓰레기 무단투기' },
] as const;

/** 이벤트 코드 → 한글 라벨 매핑 (목록/카드 표시용). */
export const EVENT_TYPE_LABELS: Readonly<Record<string, string>> = Object.freeze(
  EVENT_TYPE_OPTIONS.reduce<Record<string, string>>((acc, e) => {
    acc[e.code] = e.name;
    return acc;
  }, {}),
);
