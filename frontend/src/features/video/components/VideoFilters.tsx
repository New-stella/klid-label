import { useState, type FormEvent } from 'react';
import { RotateCcw, Search } from 'lucide-react';

import { bundleLabel } from '@/components/common/BatchStageIndicator';
import { Button } from '@/components/common/Button';
import { DateRangePicker } from '@/components/common/DateRangePicker';
import { Input } from '@/components/common/Input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { useEventTypes } from '@/features/eventType/hooks';

import {
  BULK_STAGE_BUNDLE,
  STAGE_BUNDLES,
  isStageBundle,
  type VideoListParams,
} from '../types';

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
 * 시계열 건너뜀 필터 옵션 — **두 값뿐**이다. [@design SCREEN-008] [@design ADR-050]
 *
 * ★ 벤더 연동이 확정된 뒤 건너뛴 영상을 모아 되살리려면 그 대상을 목록에서 골라낼 수 있어야
 * 한다 — 일괄 요청 건수에 상한이 있어 필터가 없으면 회수 자체가 성립하지 않는다.
 *
 * ⚠ **오토라벨 건너뜀은 옵션에 두지 않는다** — 일괄 축이 시계열 하나인 것과 같은 이유다(산출물이
 * 라벨이라 대량으로 다루는 길을 열지 않았다). 그래서 값도 묶음 상수 하나만 쓴다.
 */
const SKIPPED_STAGE_OPTIONS = [
  { value: '', label: '전체' },
  { value: BULK_STAGE_BUNDLE, label: '시계열 건너뜀' },
] as const;

/**
 * 작업 묶음 **실패** 필터 옵션 — 건너뜀 필터와 **다른 축**이다. [@design SCREEN-008] [@design ADR-050]
 *
 * ★ 왜 필요한가 — 「실패 후 판단」 입구(ADR-050)의 대상을 목록에서 모으는 유일한 수단이다. 시계열
 * 위탁 실패는 파이프라인을 멈추지 않아 **배치 상태 필터(실패)로는 한 건도 잡히지 않는다** — 그 영상은
 * 완주 상태로 남는다. 서버가 별도로 판정해 주는 이 축만이 그 영상을 집는다.
 *
 * ⚠ 건너뜀 필터와 달리 **두 묶음을 모두 둔다**. 그쪽이 시계열 하나인 것은 일괄 조작의 대상 축을
 * 따라간 것이고, 이쪽은 조회 축이라 오토라벨 실패를 감출 이유가 없다(감추면 그 영상이 목록에서
 * 도달 불가능해진다).
 *
 * ⚠ 표시명은 {@link bundleLabel} 에서 **파생**한다 — 묶음 이름을 여기서 따로 적으면 표가 둘이 되어
 * 같은 묶음이 화면마다 다른 이름으로 불린다(이 저장소의 반복 결함 패턴).
 */
const FAILED_STAGE_OPTIONS = [
  { value: '', label: '전체' },
  ...STAGE_BUNDLES.map((bundle) => ({ value: bundle, label: `${bundleLabel(bundle)} 실패` })),
] as const;

/**
 * mock 정합 — 한 줄 그리드 형태(검색 + 상태 + 이벤트 + 건너뜀 + 시작/종료 + 조회/초기화).
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
  const [skippedStage, setSkippedStage] = useState<string>(initial.skippedStage ?? '');
  const [failedStage, setFailedStage] = useState<string>(initial.failedStage ?? '');

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
      // 화이트리스트를 한 번 더 통과시킨다 — 이 값이 그대로 조회 파라미터가 되므로 화면 상태를
      // 그대로 믿지 않는다(초기값이 URL 에서 흘러온다).
      skippedStage: isStageBundle(skippedStage) ? skippedStage : undefined,
      // 같은 이유로 화이트리스트를 한 번 더 통과시킨다(초기값이 URL 에서 흘러온다).
      failedStage: isStageBundle(failedStage) ? failedStage : undefined,
    });
  };

  const handleReset = () => {
    setKeyword('');
    setStatus('');
    setEventTypeCd('');
    setFrom('');
    setTo('');
    setSkippedStage('');
    setFailedStage('');
    onApply({
      page: 0,
      size: initial.size ?? 20,
      dataSttsCd: undefined,
      skippedStage: undefined,
      failedStage: undefined,
    });
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

      {/* 시계열 건너뜀 — 지금 그 묶음이 건너뛴 상태인 영상만 남긴다(이미 되살린 영상은 남지 않는다).
          [@design SCREEN-008] [@design ADR-050] */}
      <div className="flex flex-col gap-1">
        <label className="text-label font-medium text-gray-500" htmlFor="video-skipped-stage">
          시계열 건너뜀
        </label>
        <Select value={skippedStage} onValueChange={setSkippedStage}>
          <SelectTrigger id="video-skipped-stage">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {SKIPPED_STAGE_OPTIONS.map((opt) => (
              <SelectItem key={opt.value} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* 작업 묶음 실패 — 지금 그 묶음이 실패한 상태인 영상만 남긴다.
          ★ 배치 상태 필터(실패)와 **다른 축**이다: 시계열 위탁 실패는 파이프라인을 멈추지 않아
            그 영상의 배치 상태는 완료로 남는다. 「실패 후 판단」 입구의 대상은 이 필터로만 모인다.
          [@design SCREEN-008] [@design ADR-050] */}
      <div className="flex flex-col gap-1">
        <label className="text-label font-medium text-gray-500" htmlFor="video-failed-stage">
          작업 묶음 실패
        </label>
        <Select value={failedStage} onValueChange={setFailedStage}>
          <SelectTrigger id="video-failed-stage">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {FAILED_STAGE_OPTIONS.map((opt) => (
              <SelectItem key={opt.value} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {/* 날짜 범위 — 사양 컴포넌트 `DateRangePicker`(UI-029) 로 배선한다.
          네이티브 date input 을 라벨과 함께 두 벌 직접 재구현하던 것을 걷어낸 것이며, 화면
          사양(SCREEN-008)이 규정한 구성(시작일·종료일 두 칸 + 상호 min/max 제약)은 그대로다.
          상호 제약과 `role="group"` 묶음은 이제 그 컴포넌트가 소유한다 — 빈 쪽은 undefined 로
          넘겨 제약 속성 자체를 붙이지 않는다(빈 문자열을 주면 브라우저가 제약으로 해석할 수 있다).
          상위로 올리는 값 형식(`yyyy-MM-dd`)은 URL 왕복 계약이라 종전과 동일하다.

          - 라벨 크기·색은 같은 줄의 다른 필터 라벨에 맞춘다(이 줄 안에서 라벨만 달라 보이지 않게).
          - 한국어 병기는 끈다 — 값이 들어올 때 블록이 자라 조회·초기화 버튼과 밑선이 어긋난다. */}
      <DateRangePicker
        className="[&_label]:text-label [&_label]:text-gray-500"
        showLocalizedDisplay={false}
        value={{ from: from || undefined, to: to || undefined }}
        onChange={(next) => {
          setFrom(next.from ?? '');
          setTo(next.to ?? '');
        }}
      />

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
