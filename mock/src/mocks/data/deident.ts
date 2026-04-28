import type { DeidentDto } from '../../api/types';
import { id, daysAgo, rangeInt, range } from './_helpers';
import { videos } from './videos';

export let deidents: DeidentDto[] = range(20).map((i) => {
  const video = videos[i];
  const privacyType = video.privacyType;
  const status: DeidentDto['status'] = privacyType === 'ANONY' ? 'N/A'
    : i < 14 ? 'SUCCESS'
    : i < 17 ? 'PENDING'
    : 'FAIL';

  return {
    id: id('deident', i),
    videoId: video.id,
    videoName: video.cctvName,
    status,
    privacyType,
    processedAt: status === 'SUCCESS' ? daysAgo(rangeInt(0, 10, i * 7)) : undefined,
    originalUrl: `https://picsum.photos/seed/${video.id}/640/360`,
    deidentifiedUrl: status === 'SUCCESS' ? `https://picsum.photos/seed/${video.id}-di/640/360` : undefined,
  };
});

export function updateDeident(deidentId: string, patch: Partial<DeidentDto>): DeidentDto | undefined {
  const idx = deidents.findIndex((d) => d.id === deidentId);
  if (idx === -1) return undefined;
  deidents[idx] = { ...deidents[idx], ...patch };
  return deidents[idx];
}
