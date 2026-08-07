import { useState, type FormEvent } from 'react';
import { RotateCcw, Search } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { Input } from '@/components/common/Input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { useEventTypes } from '@/features/eventType/hooks';

import type { VideoListParams } from '../types';

export interface VideoFiltersProps {
  initial: VideoListParams;
  onApply: (next: VideoListParams) => void;
}

/**
 * 배치 단계 상태 필터 옵션 — BE `LsDataRaw.DATA_STTS_*` 상수와 1:1 이어야 한다.
 *
 * 값 집합: PENDING / MARKING_READY / PROCESSING / COMPLETED / FAILED.
 * MARKING_READY(마킹 대기)가 빠져 있으면 적재~마킹 구간의 영상을 상태로 좁힐 수 없다
 * (그 상태 영상이 목록에 실제로 존재하는데 드롭다운에만 없던 누락).
 */
const STATUS_OPTIONS = [
  { value: '', label: '전체 상태' },
  { value: 'COMPLETED', label: '완료' },
  { value: 'PROCESSING', label: '처리중' },
  { value: 'MARKING_READY', label: '마킹 대기' },
  { value: 'PENDING', label: '대기' },
  { value: 'FAILED', label: '실패' },
] as const;

/**
 * mock 정합 — 한 줄 그리드 형태(검색 + 상태 + 이벤트 + 시작/종료 + 조회/초기화).
 * 보안: 모든 입력은 controlled state — XSS 방지를 위해 텍스트 노드만 렌더.
 */
export function VideoFilters({ initial, onApply }: VideoFiltersProps) {
  // 이벤트 드롭다운 옵션 — 관제 마스터 기반 9 카테고리 (value=categoryKey, 표시=label).
  const { data: eventTypes, isLoading: eventTypesLoading } = useEventTypes();
  const [keyword, setKeyword] = useState(initial.cctvNameKeyword ?? '');
  const [status, setStatus] = useState(initial.dataSttsCd ?? '');
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
      dataSttsCd: status || undefined,
    });
  };

  const handleReset = () => {
    setKeyword('');
    setStatus('');
    setEventTypeCd('');
    setFrom('');
    setTo('');
    onApply({ page: 0, size: initial.size ?? 20, dataSttsCd: undefined });
  };

  return (
    <form
      onSubmit={handleSubmit}
      aria-label="영상 검색·필터"
      className="bg-white border border-gray-200 rounded-lg px-4 py-3 flex flex-wrap items-end gap-3 shadow-sm"
    >
      {/* 검색 — 아이콘은 기존과 동일하게 입력칸 좌측에 겹쳐 배치한다. */}
      <div className="flex flex-col gap-1 min-w-[180px] flex-1">
        <label className="text-label font-medium text-gray-500" htmlFor="video-keyword">
          CCTV명 / 영상ID
        </label>
        <div className="relative">
          <Search
            size={14}
            aria-hidden
            className="absolute left-2.5 top-1/2 z-10 -translate-y-1/2 text-gray-400"
          />
          <Input
            id="video-keyword"
            type="text"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="검색어 입력"
            maxLength={100}
            className="pl-8"
          />
        </div>
      </div>

      {/* 상태 */}
      <div className="flex flex-col gap-1">
        <label className="text-label font-medium text-gray-500" htmlFor="video-status">
          상태
        </label>
        <Select value={status} onValueChange={setStatus}>
          <SelectTrigger id="video-status">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {STATUS_OPTIONS.map((opt) => (
              <SelectItem key={opt.value} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* 이벤트 */}
      <div className="flex flex-col gap-1">
        <label className="text-label font-medium text-gray-500" htmlFor="video-event">
          이벤트 유형
        </label>
        <Select value={eventTypeCd} onValueChange={setEventTypeCd} disabled={eventTypesLoading}>
          <SelectTrigger id="video-event" aria-busy={eventTypesLoading}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {/* 전체 + (로딩 중 안내) + 서버 카테고리 — 기존 옵션 구성·순서를 그대로 유지한다. */}
            <SelectItem value="">전체 이벤트</SelectItem>
            {eventTypesLoading && (
              <SelectItem value="__loading__" disabled>
                로딩 중…
              </SelectItem>
            )}
            {(eventTypes ?? []).map((et) => (
              <SelectItem key={et.categoryKey} value={et.categoryKey}>
                {et.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* 날짜 범위 */}
      <div className="flex flex-col gap-1">
        <label className="text-label font-medium text-gray-500" htmlFor="video-from">
          시작일
        </label>
        <Input
          id="video-from"
          type="date"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
        />
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-label font-medium text-gray-500" htmlFor="video-to">
          종료일
        </label>
        <Input
          id="video-to"
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
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
