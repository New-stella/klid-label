// 프리셋 도메인 타입 — BE: {name, description?, labelCodes: string[]} 정합

export interface Preset {
  id: number;
  name: string;
  description: string | null;
  labelCodes: string[];
  createdAt: string;
  updatedAt: string;
}

export interface PresetForm {
  name: string;
  description: string;
  labelCodes: string[];
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
