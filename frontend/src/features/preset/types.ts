// 프리셋 도메인 타입 — UI/UX §4-4 라벨링 프리셋

import type { EventTypeCd } from '@/features/dashboard/types';

export type LabelShape = 'BBOX' | 'POLYGON' | 'SEGMENT' | 'TRACK';

export interface LabelItem {
  id?: number;
  name: string;
  shape: LabelShape;
  color: string; // #rrggbb
  attributes?: Record<string, string>;
}

export interface Preset {
  id: number;
  name: string;
  eventTypeCd: EventTypeCd;
  subType?: string;
  items: LabelItem[];
  createdAt?: string;
  updatedAt?: string;
}
