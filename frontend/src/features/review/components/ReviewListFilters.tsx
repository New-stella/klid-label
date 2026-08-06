import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';

import { Button } from '@/components/common/Button';
import { Input } from '@/components/common/Input';
import { Select } from '@/components/common/Select';

import {
  DEFAULT_REVIEW_FILTERS,
  MAX_SEARCH_KEYWORD_LENGTH,
  REVIEW_STATUS_LABEL,
  UI_REVIEW_STATUSES,
  type ReviewFilterValues,
} from '../reviewListParams';
import type { ReviewStatus } from '../types';

/** 검색 입력 debounce — 한글 IME 조합 중 매 keystroke 로 요청이 나가는 것을 막는다. */
export const SEARCH_DEBOUNCE_MS = 300;

interface ReviewListFiltersProps {
  /** ★ URL 에서 파생된 값이다 — 이 컴포넌트는 검색 입력 외에 자체 필터 state 를 갖지 않는다. */
  values: ReviewFilterValues;
  onSearchChange: (q: string) => void;
  onStatusChange: (status: '' | ReviewStatus) => void;
  /** 진입 기본값(검수요청·오래된순)으로 되돌린다 — 전체가 아니다. */
  onReset: () => void;
  /**
   * 이미 진입 기본값이라 초기화가 아무 일도 하지 않는 상태인지.
   *
   * ★ 판정은 **부모(URL)** 가 한다 — 필터뿐 아니라 **정렬**도 봐야 하기 때문이다. 필터만 보면
   * 정렬만 바꾼 사용자에게 버튼이 비활성으로 보여 기본 정렬로 되돌릴 경로가 사라진다.
   */
  resetDisabled: boolean;
}

/**
 * 검수목록(SCR-REVIEW-001) 검색·상태 필터.
 *
 * - 필터의 **단일 진실원은 URL** 이다. 여기서 유지하는 로컬 state 는 debounce 대상인 검색 입력 하나뿐이며
 *   그것도 외부(초기화·뒤로가기) 변경 시 URL 값으로 되돌아간다.
 * - 검색어 길이는 BE `@Size(max=100)` 와 같게 제한해 400 왕복을 막는다.
 * - 보안: 검색어는 axios params 로만 전달하고 텍스트 노드로만 렌더한다(XSS 방어 —
 *   `dangerouslySetInnerHTML` 미사용).
 */
export function ReviewListFilters({
  values,
  onSearchChange,
  onStatusChange,
  onReset,
  resetDisabled,
}: ReviewListFiltersProps) {
  const [keyword, setKeyword] = useState(values.q);

  // '' = 전체(필터 해제) — 구 `<option value="">전체</option>` 와 동일 값·순서를 유지한다.
  const statusOptions = useMemo(
    () => [
      { value: '', label: '전체' },
      ...UI_REVIEW_STATUSES.map((status) => ({
        value: status,
        label: REVIEW_STATUS_LABEL[status],
      })),
    ],
    [],
  );

  // 콜백은 매 렌더 새 참조일 수 있어 deps 에 넣으면 debounce 타이머가 계속 재시작된다.
  const onSearchChangeRef = useRef(onSearchChange);
  useEffect(() => {
    onSearchChangeRef.current = onSearchChange;
  });

  // 외부(초기화·URL 복원)에서 검색어가 바뀌면 입력값을 맞춘다.
  useEffect(() => {
    setKeyword(values.q);
  }, [values.q]);

  /**
   * 대기 중인 debounce 타이머.
   *
   * ★ effect cleanup 에만 의존할 수 없다 — cleanup 은 deps(`keyword`/`values.q`)가 **바뀔 때만**
   * 돈다. URL 에 `q` 가 없는 상태(`values.q === ''`)에서 입력한 뒤 300ms 안에 초기화하면
   * `values.q` 가 `'' → ''` 라 아무 deps 도 변하지 않아 타이머가 살아남고, 초기화 직후
   * `q=강남` 이 되살아난다("초기화 = 진입 기본값 복귀" 계약 위반). 그래서 취소를 명시한다.
   */
  const pendingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const cancelPendingSearch = () => {
    if (pendingTimerRef.current !== null) {
      clearTimeout(pendingTimerRef.current);
      pendingTimerRef.current = null;
    }
  };

  // debounce — 입력이 멈춘 뒤에만 URL·요청을 갱신한다.
  useEffect(() => {
    if (keyword === values.q) return undefined;
    const timer = setTimeout(() => {
      pendingTimerRef.current = null;
      onSearchChangeRef.current(keyword);
    }, SEARCH_DEBOUNCE_MS);
    pendingTimerRef.current = timer;
    return () => {
      clearTimeout(timer);
      if (pendingTimerRef.current === timer) pendingTimerRef.current = null;
    };
  }, [keyword, values.q]);

  /** "조회"(또는 Enter) — 대기 중인 debounce 를 기다리지 않고 즉시 적용한다. */
  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    cancelPendingSearch();
    onSearchChangeRef.current(keyword);
  };

  /**
   * 초기화 — 부모에 위임하기 **전에** 대기 중인 debounce 를 끊고 입력값도 기본값으로 되돌린다.
   * 부모는 URL 만 바꾸므로, `values.q` 가 이미 빈 값이면 이 컴포넌트에는 아무 신호도 오지 않는다.
   */
  const handleReset = () => {
    cancelPendingSearch();
    setKeyword(DEFAULT_REVIEW_FILTERS.q);
    onReset();
  };

  return (
    <form
      onSubmit={handleSubmit}
      data-testid="review-filters"
      className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-200 bg-white p-3"
    >
      <div className="min-w-[180px] flex-1">
        <Input
          id="review-search"
          label="영상명 / 작업자명"
          type="text"
          value={keyword}
          maxLength={MAX_SEARCH_KEYWORD_LENGTH}
          onChange={(e) => setKeyword(e.target.value)}
          placeholder="검색어를 입력하세요"
        />
      </div>
      <div className="min-w-[140px]">
        <Select
          id="review-status-filter"
          label="상태"
          value={values.status}
          onChange={(e) => onStatusChange(e.target.value as '' | ReviewStatus)}
          options={statusOptions}
        />
      </div>
      <div className="flex items-end gap-2">
        <Button type="submit" variant="primary" size="sm">
          조회
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={handleReset}
          disabled={resetDisabled}
          title="검수요청·제출일 오래된 순(기본 화면)으로 되돌립니다"
        >
          초기화
        </Button>
      </div>

      {/* 진입 기본값이 '필터가 걸린 상태' 임을 알린다 — 0건일 때 "전체 중 0건" 으로 오인하지 않게. */}
      <p
        data-testid="review-active-filter"
        className="w-full text-sub text-gray-500"
      >
        {values.status === ''
          ? '전체 상태'
          : `${REVIEW_STATUS_LABEL[values.status]} 상태만 표시 중`}
        {values.q ? ` · 검색어 "${values.q}"` : ''}
      </p>
    </form>
  );
}

export default ReviewListFilters;
