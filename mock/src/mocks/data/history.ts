import type { HistoryCommit } from '../../api/types';
import { daysAgo, rangeInt, range } from './_helpers';

const COMMIT_MESSAGES = [
  'PERSON 라벨 3개 수정',
  'VEHICLE 좌표 보정',
  '신규 SMOKE 라벨 추가',
  'BICYCLE 클래스 변경',
  'FIRE 영역 확대 조정',
  'BUS 라벨 누락 추가',
  'TRUCK 바운딩박스 크기 수정',
  '프레임 40~50 전체 재라벨링',
  'MOTORCYCLE 어트리뷰트 수정',
  '오토라벨 결과 수동 검수 완료',
  'PERSON occluded 속성 업데이트',
  'VLM 객체 검증 결과 반영',
];

const AUTHOR_NAMES = ['최라벨', '정작업', '강라벨링', '윤어노테', '임태그'];

const TARGET_VIDEO_IDS = ['video-0001', 'video-0002', 'video-0003', 'video-0004', 'video-0005'];

export const historyByVideo: Record<string, HistoryCommit[]> = Object.fromEntries(
  TARGET_VIDEO_IDS.map((videoId, vi) => {
    const count = rangeInt(7, 10, vi * 13);
    return [
      videoId,
      range(count).map((ci) => {
        const seed = vi * 100 + ci;
        return {
          hash: `git-${(seed * 99999 + 12345).toString(16).substring(0, 8).toUpperCase()}`,
          videoId,
          message: COMMIT_MESSAGES[(seed) % COMMIT_MESSAGES.length],
          authorName: AUTHOR_NAMES[rangeInt(0, AUTHOR_NAMES.length - 1, seed * 7)],
          committedAt: daysAgo(rangeInt(0, 30, seed * 3)),
        };
      }),
    ];
  }),
);
