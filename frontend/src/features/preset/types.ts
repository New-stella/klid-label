// 프리셋 도메인 타입 — 라벨 마스터(LS_LABEL) 단일 진실원 참조.
//
// Phase 4 (마스터 연동):
// - 프리셋 코드는 라벨 마스터 PK(labelId)로 연결된다. 라벨명·형태는 스냅샷 없이
//   BE 가 조회 시 마스터를 실시간 join 하여 파생한다(FE 하드코딩 금지).
// - 요청은 labelIds(number[]) 만 전송한다. 형태(BBOX/POLYGON 등)는 마스터가 소유하므로
//   FE 에서는 읽기 전용으로 표시만 한다.
// - 미연결(linked=false) 코드는 legacy 코드 문자열을 라벨명으로 표시하고 '미연결' 배지로 구분한다.

import type { LabelMasterType } from '@/features/label/api/labelMaster';

/**
 * 프리셋 코드 1건 — BE `LabelCodeOptionResponse` 정합(마스터 실시간 join 결과).
 * - `labelId`   : 마스터 PK. 미연결 레거시 행이면 null.
 * - `code`      : 미연결 레거시 코드 문자열. 연결 행이면 null.
 * - `labelName` : 연결 시 마스터 라벨명(실시간), 미연결 시 legacy 코드.
 * - `labelType` : 마스터 형태 코드. 미연결이면 null.
 * - `linked`    : 활성 마스터 연결 여부.
 * - `bboxEnabled`/`polygonEnabled` : 마스터 형태 파생(읽기 전용).
 */
export interface PresetCode {
  labelId: number | null;
  code: string | null;
  labelName: string;
  labelType: LabelMasterType | null;
  linked: boolean;
  bboxEnabled: boolean;
  polygonEnabled: boolean;
}

export interface Preset {
  id: number;
  name: string;
  description: string | null;
  /** 프리셋 라벨 코드 목록 — 마스터 실시간 join 결과. */
  codes: PresetCode[];
  /** 매핑 이벤트 타입 코드. null/undefined = 미매핑. (V15 — DB UNIQUE 제약, 이벤트 1:1 매핑) */
  eventTypeCd?: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * 프리셋 생성/수정 폼 — BE `PresetRequest` 정합.
 * 형태는 마스터가 소유하므로 요청에서 받지 않고 labelIds 만 전송한다.
 */
export interface PresetForm {
  name: string;
  description: string;
  /** 선택한 라벨 마스터 PK 배열. BE 요청 body 의 labelIds. */
  labelIds: number[];
  /** 매핑 이벤트 타입. 빈 문자열/undefined = 미매핑. */
  eventTypeCd?: string;
}
