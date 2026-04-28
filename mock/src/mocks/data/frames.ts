import type { FrameMeta } from '../../api/types';
import { rangeInt, range } from './_helpers';

/** 5개 영상에 대해 각 60프레임 생성 */
const TARGET_VIDEO_IDS = [
  'video-0001',
  'video-0002',
  'video-0003',
  'video-0004',
  'video-0005',
];

export const framesByVideo: Record<string, FrameMeta[]> = Object.fromEntries(
  TARGET_VIDEO_IDS.map((videoId, vi) => [
    videoId,
    range(60).map((fi) => ({
      frameNo: fi,
      thumbnailUrl: `https://picsum.photos/seed/${videoId}-f${fi}/160/90`,
      hasIssue: rangeInt(0, 9, vi * 60 + fi) < 2, // ~20% 이슈
      timestampMs: fi * 500,
    })),
  ]),
);

export function getFrames(videoId: string): FrameMeta[] {
  return framesByVideo[videoId] ?? [];
}
