// Phase 6 — 포털 업로드 프레임 라벨 조회 훅 + BE→FE Label 매핑.
//
// BE PortalUploadLabelResponse(points: [[x,y]...]) → 캔버스 FE Label 로 매핑한다.
// BBOX 는 [[l,t],[r,b]] 2점, POLYGON 은 [[x,y]...] 를 flat number[] 로 펼친다.
// 좌표가 규격 미달(BBOX≠2점 / POLYGON<3점)인 라벨은 캔버스 렌더 크래시를 막기 위해 스킵한다.

import { useQuery } from '@tanstack/react-query';

import { PORTAL_KEYS } from '@/lib/queryKeys';
import type { Label } from '@/features/label/types';

import { getUploadFrameLabels, type UploadFrameLabel } from '../api';

/** BE 라벨 1행 → FE Label. 규격 미달이면 null(스킵). */
function toLabel(raw: UploadFrameLabel, frameNo: number): Label | null {
  const pts = Array.isArray(raw.points) ? raw.points : [];
  const type = raw.lblTypeCd;
  const base = {
    id: `uld-${raw.uldLblSn}`,
    serverId: raw.uldLblSn,
    frameNo,
    classId: 0,
    className: raw.label ?? '',
    source: 'MANUAL' as const,
  };

  if (type === 'BBOX') {
    if (pts.length !== 2 || pts[0]?.length < 2 || pts[1]?.length < 2) return null;
    const [[left, top], [right, bottom]] = pts;
    return { ...base, shape: { type: 'BBOX', left, top, right, bottom } };
  }
  if (type === 'POLYGON') {
    if (pts.length < 3) return null;
    const flat: number[] = [];
    for (const p of pts) {
      if (Array.isArray(p) && p.length >= 2) flat.push(p[0], p[1]);
    }
    if (flat.length < 6) return null;
    return { ...base, shape: { type: 'POLYGON', points: flat } };
  }
  // BBOX|POLYGON 외(방어) 스킵 — 본 화면은 두 타입만 다룬다.
  return null;
}

/** BE 라벨 배열 → FE Label 배열(규격 미달 스킵). */
export function mapUploadLabels(raw: UploadFrameLabel[], frameNo: number): Label[] {
  return raw
    .map((r) => toLabel(r, frameNo))
    .filter((l): l is Label => l !== null);
}

/**
 * 업로드 프레임 라벨 조회. uldFrmeSn 이 undefined 면 비활성.
 * @param uldFrmeSn 업로드 프레임 PK
 * @param frameNo   FE Label.frameNo 로 부여할 프레임 번호(표시/일관성용)
 */
export function useUploadFrameLabels(uldFrmeSn: number | undefined, frameNo = 0) {
  return useQuery({
    queryKey: PORTAL_KEYS.uploadFrameLabels(uldFrmeSn ?? -1),
    queryFn: () => getUploadFrameLabels(uldFrmeSn as number),
    enabled: uldFrmeSn !== undefined && Number.isFinite(uldFrmeSn),
    select: (raw: UploadFrameLabel[]) => mapUploadLabels(raw, frameNo),
  });
}
