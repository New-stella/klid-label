import { useState, type FormEvent } from 'react';
import { RotateCcw, Search } from 'lucide-react';

import { Button } from '@/components/common/Button';

import type { VideoListParams } from '../types';

export interface VideoFiltersProps {
  initial: VideoListParams;
  onApply: (next: VideoListParams) => void;
}

const STATUS_OPTIONS = [
  { value: '', label: '전체 상태' },
  { value: 'BATCH_COMPLETED', label: '완료' },
  { value: 'BATCH_PROCESSING', label: '처리중' },
  { value: 'PENDING', label: '대기' },
  { value: 'BATCH_FAILED', label: '실패' },
];

const EVENT_OPTIONS = [
  { value: '', label: '전체 이벤트' },
  { value: 'FALL', label: '쓰러짐' },
  { value: 'VIOLENCE', label: '폭력' },
  { value: 'TRAFFIC_ACCIDENT', label: '교통사고' },
  { value: 'ABNORMAL_BEHAVIOR', label: '이상행동(유괴)' },
  { value: 'FLOOD', label: '침수' },
  { value: 'WILDFIRE', label: '산불' },
];

/**
 * mock 정합 — 한 줄 그리드 형태(검색 + 상태 + 이벤트 + 시작/종료 + 조회/초기화).
 * 보안: 모든 입력은 controlled state — XSS 방지를 위해 텍스트 노드만 렌더.
 */
export function VideoFilters({ initial, onApply }: VideoFiltersProps) {
  const [keyword, setKeyword] = useState(initial.cctvNameKeyword ?? '');
  const [status, setStatus] = useState('');
  const [eventTypeCd, setEventTypeCd] = useState(initial.eventTypeCd ?? '');
  const [from, setFrom] = useState(initial.from ?? '');
  const [to, setTo] = useState(initial.to ?? '');

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    onApply({
      ...initial,
      page: 0,
      cctvNameKeyword: keyword.trim() || undefined,
      eventTypeCd: eventTypeCd || undefined,
      from: from || undefined,
      to: to || undefined,
    });
  };

  const handleReset = () => {
    setKeyword('');
    setStatus('');
    setEventTypeCd('');
    setFrom('');
    setTo('');
    onApply({ page: 0, size: initial.size ?? 20 });
  };

  return (
    <form
      onSubmit={handleSubmit}
      aria-label="영상 검색·필터"
      className="bg-white border border-gray-200 rounded-lg px-4 py-3 flex flex-wrap items-end gap-3 shadow-sm"
    >
      {/* 검색 */}
      <div className="flex flex-col gap-1 min-w-[180px] flex-1">
        <label className="text-xs font-medium text-gray-500" htmlFor="video-keyword">
          CCTV명 / 영상ID
        </label>
        <div className="relative">
          <Search
            size={14}
            aria-hidden
            className="absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400"
          />
          <input
            id="video-keyword"
            type="text"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="검색어 입력"
            maxLength={100}
            className="w-full pl-8 pr-3 py-1.5 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
          />
        </div>
      </div>

      {/* 상태 */}
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-gray-500" htmlFor="video-status">
          상태
        </label>
        <select
          id="video-status"
          value={status}
          onChange={(e) => setStatus(e.target.value)}
          className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          {STATUS_OPTIONS.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </div>

      {/* 이벤트 */}
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-gray-500" htmlFor="video-event">
          이벤트 유형
        </label>
        <select
          id="video-event"
          value={eventTypeCd}
          onChange={(e) => setEventTypeCd(e.target.value)}
          className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
        >
          {EVENT_OPTIONS.map((et) => (
            <option key={et.value} value={et.value}>
              {et.label}
            </option>
          ))}
        </select>
      </div>

      {/* 날짜 범위 */}
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-gray-500" htmlFor="video-from">
          시작일
        </label>
        <input
          id="video-from"
          type="date"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
          className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
        />
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-xs font-medium text-gray-500" htmlFor="video-to">
          종료일
        </label>
        <input
          id="video-to"
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          className="py-1.5 px-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-primary-500"
        />
      </div>

      {/* 버튼 */}
      <div className="flex gap-2 items-end">
        <Button type="submit" variant="primary" size="sm">
          <Search size={14} aria-hidden />
          조회
        </Button>
        <Button type="button" variant="secondary" size="sm" onClick={handleReset}>
          <RotateCcw size={14} aria-hidden />
          초기화
        </Button>
      </div>
    </form>
  );
}
