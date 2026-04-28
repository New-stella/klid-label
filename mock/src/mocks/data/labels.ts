import type { LabelObject, FrameLabels } from '../../api/types';
import { rangeInt, id, range } from './_helpers';

const LABEL_CODES = [
  { code: 'PERSON', name: '사람', color: '#FF6B6B' },
  { code: 'VEHICLE', name: '차량', color: '#4ECDC4' },
  { code: 'BICYCLE', name: '자전거', color: '#45B7D1' },
  { code: 'MOTORCYCLE', name: '오토바이', color: '#96CEB4' },
  { code: 'TRUCK', name: '트럭', color: '#FFEAA7' },
  { code: 'BUS', name: '버스', color: '#DDA0DD' },
  { code: 'FIRE', name: '화재', color: '#FF4500' },
  { code: 'SMOKE', name: '연기', color: '#808080' },
] as const;

const TARGET_VIDEO_IDS = [
  'video-0001',
  'video-0002',
  'video-0003',
  'video-0004',
  'video-0005',
];

function makeLabelObject(videoId: string, frameNo: number, objIdx: number): LabelObject {
  const seed = videoId.charCodeAt(6) * 1000 + frameNo * 20 + objIdx;
  const labelInfo = LABEL_CODES[rangeInt(0, LABEL_CODES.length - 1, seed)];
  const isPolygon = rangeInt(0, 9, seed + 1) < 2; // 20% polygon
  const createdBy: 'auto' | 'manual' = rangeInt(0, 9, seed + 2) < 7 ? 'auto' : 'manual';

  const x = rangeInt(10, 550, seed + 3);
  const y = rangeInt(10, 290, seed + 4);
  const w = rangeInt(30, 150, seed + 5);
  const h = rangeInt(20, 120, seed + 6);

  const obj: LabelObject = {
    id: id(`lbl-${videoId}-f${frameNo}`, objIdx),
    type: isPolygon ? 'polygon' : 'bbox',
    labelCode: labelInfo.code,
    labelName: labelInfo.name,
    color: labelInfo.color,
    confidence: (rangeInt(60, 99, seed + 7)) / 100,
    createdBy,
    trackId: `track-${rangeInt(1, 10, seed + 8)}`,
    attributes: {
      occluded: rangeInt(0, 1, seed + 9),
      truncated: rangeInt(0, 1, seed + 10),
    },
  };

  if (isPolygon) {
    obj.points = [x, y, x + w, y, x + w, y + h, x, y + h];
  } else {
    obj.bbox = { x, y, w, h };
  }

  return obj;
}

export const labelsByVideoFrame: Record<string, Record<number, FrameLabels>> = Object.fromEntries(
  TARGET_VIDEO_IDS.map((videoId) => [
    videoId,
    Object.fromEntries(
      range(60).map((frameNo) => {
        const count = rangeInt(5, 15, videoId.charCodeAt(6) * 100 + frameNo);
        return [
          frameNo,
          {
            videoId,
            frameNo,
            objects: range(count).map((objIdx) => makeLabelObject(videoId, frameNo, objIdx)),
          } satisfies FrameLabels,
        ];
      }),
    ),
  ]),
);

// in-memory mutable store for PUT operations
export const mutableLabels: Record<string, Record<number, FrameLabels>> = JSON.parse(
  JSON.stringify(labelsByVideoFrame),
) as Record<string, Record<number, FrameLabels>>;

export function getFrameLabels(videoId: string, frameNo: number): FrameLabels {
  return (
    mutableLabels[videoId]?.[frameNo] ?? {
      videoId,
      frameNo,
      objects: [],
    }
  );
}

export function setFrameLabels(videoId: string, frameNo: number, objects: LabelObject[]): FrameLabels {
  if (!mutableLabels[videoId]) {
    mutableLabels[videoId] = {};
  }
  mutableLabels[videoId][frameNo] = { videoId, frameNo, objects };
  return mutableLabels[videoId][frameNo];
}

export function generateAutoLabels(videoId: string, frameNo: number): LabelObject[] {
  const count = rangeInt(3, 5, videoId.charCodeAt(6) * 100 + frameNo + 999);
  return range(count).map((i) => makeLabelObject(videoId, frameNo, i + 100));
}
