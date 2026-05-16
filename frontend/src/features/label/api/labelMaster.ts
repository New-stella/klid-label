// Phase 7 — 라벨 마스터 API 클라이언트.
//
// BE: GET /v1/manage/labels — 활성 라벨 마스터 목록 조회 (REVIEWER + WORKER + PORTAL_USER).
// 응답은 ApiResponse<T> 래퍼에서 client interceptor 가 data 만 꺼내 반환.
//
// 보안: 본 엔드포인트는 사용자 입력을 받지 않음 (단순 GET). IDOR 위험 없음.

import { apiClient } from '@/lib/api/client';

export interface LabelMaster {
  labelId: number;
  name: string;
  /** #RRGGBB 형태 */
  color: string;
  /** BBOX | POLYGON | ... — BE 검증 */
  type: string;
  sortNo: number;
  useYn: 'Y' | 'N';
}

/**
 * 활성 라벨 마스터 목록 조회.
 *
 * BE 응답 예:
 *   [
 *     { "labelId": 1, "name": "사람", "color": "#EF4444", "type": "BBOX", "sortNo": 1, "useYn": "Y" },
 *     ...
 *   ]
 */
export async function fetchLabelMasters(): Promise<LabelMaster[]> {
  const res = await apiClient.get<LabelMaster[]>('/manage/labels');
  const data = res.data;
  if (!Array.isArray(data)) return [];
  // 응답 정규화 — useYn 미정의는 'Y' 로 간주, sortNo NaN 은 최대값으로 후순위 처리.
  return data.map((m) => ({
    labelId: Number(m.labelId),
    name: String(m.name ?? ''),
    color: String(m.color ?? '#94A3B8'),
    type: String(m.type ?? 'BBOX'),
    sortNo: Number.isFinite(Number(m.sortNo)) ? Number(m.sortNo) : Number.MAX_SAFE_INTEGER,
    useYn: m.useYn === 'N' ? 'N' : 'Y',
  }));
}
