import { useState, type FormEvent } from 'react';

import { Button } from '@/components/common/Button';
import { DateRangePicker } from '@/components/common/DateRangePicker';
import { Input } from '@/components/common/Input';
import { Select } from '@/components/common/Select';

import type { VideoListParams } from '../types';

export interface VideoFiltersProps {
  initial: VideoListParams;
  onApply: (next: VideoListParams) => void;
}

const eventTypes = [
  { value: '', label: '전체 이벤트' },
  { value: 'FIRE', label: '화재' },
  { value: 'FALL', label: '쓰러짐' },
  { value: 'INVASION', label: '침입' },
  { value: 'CROWD', label: '군집' },
  { value: 'VIOLENCE', label: '폭력' },
  { value: 'ABANDON', label: '유기/방치' },
];

/**
 * 영상 목록 필터 폼.
 * 보안: 모든 입력은 controlled state — XSS 방지를 위해 텍스트 노드만 렌더.
 */
export function VideoFilters({ initial, onApply }: VideoFiltersProps) {
  const [keyword, setKeyword] = useState(initial.cctvNameKeyword ?? '');
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
    setEventTypeCd('');
    setFrom('');
    setTo('');
    onApply({ page: 0, size: initial.size ?? 20 });
  };

  return (
    <form
      onSubmit={handleSubmit}
      aria-label="영상 검색·필터"
      className="flex flex-wrap items-end gap-3 rounded border border-border bg-white p-3"
    >
      <div className="min-w-[220px] flex-1">
        <Input
          label="CCTV명/이벤트 검색"
          placeholder="예: 강남대로, 화재"
          value={keyword}
          maxLength={100}
          onChange={(e) => setKeyword(e.target.value)}
        />
      </div>
      <div className="min-w-[160px]">
        <Select
          label="이벤트 유형"
          options={eventTypes}
          value={eventTypeCd}
          onChange={(e) => setEventTypeCd(e.target.value)}
        />
      </div>
      <DateRangePicker
        label="기간"
        value={{ from, to }}
        onChange={(v) => {
          setFrom(v.from ?? '');
          setTo(v.to ?? '');
        }}
      />
      <div className="flex gap-2">
        <Button type="submit" variant="primary" size="md">
          검색
        </Button>
        <Button type="button" variant="outline" size="md" onClick={handleReset}>
          초기화
        </Button>
      </div>
    </form>
  );
}
