import dayjs from 'dayjs';
import type { BatchStage, PrivacyType } from '../api/types';

export function formatDate(iso: string, fmt = 'YYYY-MM-DD HH:mm'): string {
  return dayjs(iso).format(fmt);
}

export function formatDuration(seconds: number): string {
  if (seconds >= 3600) {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    return `${h}시간 ${m}분`;
  }
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

export function formatPercent(num: number, total: number): string {
  if (total === 0) return '0%';
  return `${((num / total) * 100).toFixed(1)}%`;
}

export function formatNumber(n: number): string {
  return n.toLocaleString('ko-KR');
}

export function eventTypeLabel(code: string): string {
  const map: Record<string, string> = {
    쓰러짐: '쓰러짐',
    폭력: '폭력',
    교통사고: '교통사고',
    '이상행동(유괴)': '이상행동(유괴)',
    침수: '침수',
    산불: '산불',
  };
  return map[code] ?? code;
}

export function stageLabel(stage: BatchStage): string {
  const map: Record<BatchStage, string> = {
    FRAME_EXTRACT: '프레임추출',
    DEIDENTIFY: '비식별',
    YOLO: 'YOLO',
    SAM2: 'SAM2',
    VLM: 'VLM',
  };
  return map[stage] ?? stage;
}

export function privacyTypeLabel(t: PrivacyType): string {
  const map: Record<PrivacyType, string> = {
    PRVC: '개인정보',
    PSDO: '가명처리',
    ANONY: '익명',
  };
  return map[t] ?? t;
}

const AVATAR_COLORS = [
  'bg-blue-500',
  'bg-green-500',
  'bg-purple-500',
  'bg-orange-500',
  'bg-pink-500',
  'bg-teal-500',
  'bg-indigo-500',
  'bg-red-500',
  'bg-yellow-500',
  'bg-cyan-500',
] as const;

/**
 * 사용자 ID 해시 기반 아바타 배경색 결정.
 * DB 컬럼 없이 FE에서 결정적으로 색상을 부여한다.
 */
export function avatarColorFromId(userId: string): string {
  let hash = 0;
  for (let i = 0; i < userId.length; i++) {
    hash = (hash + userId.charCodeAt(i)) | 0;
  }
  const idx = Math.abs(hash) % AVATAR_COLORS.length;
  return AVATAR_COLORS[idx]!;
}
