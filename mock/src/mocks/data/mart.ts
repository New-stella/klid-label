import type { MartDataset } from '../../api/types';
import { id, daysAgo, rangeInt, pick, range } from './_helpers';

const EVENT_TYPES = ['쓰러짐', '폭력', '교통사고', '이상행동(유괴)', '침수', '산불'] as const;
const WEATHERS = ['맑음', '흐림', '비', '눈', '안개', '강풍'] as const;
const SEASONS = ['봄', '여름', '가을', '겨울'] as const;

// SFR-13 — 자체개발 AI 모델 목록 (이벤트 카탈로그와 매칭)
export const AI_MODELS = [
  '침수탐지',
  '이상상황탐지',
  '쓰러짐탐지',
  '폭력감지',
  '교통사고감지',
  '산불감지',
] as const;

// 이벤트 → 우선 매칭 모델 (영상 eventType과 일관성 있는 모델 우선 부여)
const EVENT_TO_MODEL: Record<string, string> = {
  '쓰러짐': '쓰러짐탐지',
  '폭력': '폭력감지',
  '교통사고': '교통사고감지',
  '이상행동(유괴)': '이상상황탐지',
  '침수': '침수탐지',
  '산불': '산불감지',
};

/** 결정적 분배: i를 0~5로 나눠 0개/1개/2개의 linkedModels 부여 */
function buildLinkedModels(eventType: string, i: number): string[] {
  const slot = i % 6; // 0,1: 1개 / 2,3: 2개 / 4: 0개 / 5: 1개(보조)
  const primary = EVENT_TO_MODEL[eventType];
  if (slot === 4) return []; // 연동 없음
  if (slot === 0 || slot === 1) {
    return primary ? [primary] : [pick(AI_MODELS, i * 31)];
  }
  if (slot === 2 || slot === 3) {
    const secondary = pick(AI_MODELS, i * 37);
    if (primary && secondary !== primary) {
      return [primary, secondary];
    }
    // primary 없거나 중복이면 두 개 결정적으로 선택
    const a = pick(AI_MODELS, i * 41);
    const b = pick(AI_MODELS, i * 43);
    return a === b ? [a] : [a, b];
  }
  // slot === 5: 보조 모델 1개
  return [pick(AI_MODELS, i * 47)];
}

export const martDatasets: MartDataset[] = range(18).map((i) => {
  const eventType = pick(EVENT_TYPES, i * 11);
  return {
    id: id('mart', i),
    name: `${eventType}_v${Math.floor(i / 3) + 1}.${i % 3}`,
    eventType,
    weather: pick(WEATHERS, i * 13),
    season: pick(SEASONS, i * 5),
    count: rangeInt(500, 10000, i * 19),
    version: `v${Math.floor(i / 3) + 1}.${i % 3}.0`,
    sizeBytes: rangeInt(100, 5000, i * 23) * 1024 * 1024,
    createdAt: daysAgo(rangeInt(10, 180, i * 29)),
    linkedModels: buildLinkedModels(eventType, i),
  };
});

// SFR-08 — 영상 ID → 영향 받는 마트 데이터셋 ID 배열 매핑
// 일부 영상만 매핑 (모든 영상이 마트에 포함된 건 아님). 결정적 분배.
export const videoMartImpact: Record<string, string[]> = {
  'video-0001': ['mart-0001', 'mart-0005'],
  'video-0002': ['mart-0002'],
  'video-0003': ['mart-0003', 'mart-0007'],
  'video-0004': [],
  'video-0005': ['mart-0004'],
  'video-0006': ['mart-0006', 'mart-0010'],
  'video-0007': ['mart-0008'],
  'video-0008': [],
  'video-0009': ['mart-0009'],
  'video-0010': ['mart-0011', 'mart-0014'],
};
