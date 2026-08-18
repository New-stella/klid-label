// 표시명 출처 칩 (UI-126).
//
// 화면에 보이는 이름이 <어느 단계에서 온 값인지> 알리는 칩이다. 서버가 여러 단계를 거쳐
// 해석해 내려준 이름은 값만 봐서는 출처를 알 수 없어, 같은 이름이 여러 줄에 보이는 까닭이
// 드러나지 않는다.
//
// ★ 이 컴포넌트는 <서버가 함께 내려준 출처 표기를 그대로 보여줄 뿐> 해석을 다시 계산하지
//   않는다. 그래서 props 가 `source` 하나다 — 원본 이름 필드(운영자 지정명·연동 수신명·
//   카테고리명)를 받지 않는 것이 계약이며, 받으면 그 순간 판정이 두 곳으로 갈린다.
//   정적 가드: features/eventType/__tests__/eventTypeAdminSource.test.tsx
//
// 색상 대비: 배경은 각 스케일 50(코드 폴백만 gray-100), 글자는 700(코드 폴백만 gray-800) —
// 칩 글자가 곧 뜻이라 색상 단독으로 의미를 전달하지 않는다(UI-126 accessibility_notes).
//
// ⚠ 시안(SD-023)은 칩 앞에 글리프를 하나씩 두지만 여기서는 두지 않는다 — 라벨 텍스트가 이미
//   단계를 완전히 서술해 아이콘이 정보를 더하지 않는 <장식>이고, 이 저장소는 그런 장식 아이콘을
//   2026-08-10 에 걷어냈다(KpiCard 의 지표 아이콘 폐지와 같은 축). 4 종을 새로 들이면 아이콘
//   종수 가드(src/test/iconInventoryGuard.test.ts)의 상한도 함께 올려야 한다.
//
// @design UI-126, SCREEN-038

import { cn } from '@/lib/cn';

/** 표시명이 채택된 단계 — 서버 응답값(소문자)과 1:1. */
export type DisplayNameSource = 'operator' | 'control' | 'category' | 'code';

/** 서버가 보낼 수 있는 값 집합 — 미지 값은 칩을 렌더하지 않는 판정에 쓴다. */
export const DISPLAY_NAME_SOURCES: readonly DisplayNameSource[] = [
  'operator',
  'control',
  'category',
  'code',
];

/**
 * 응답 문자열이 알려진 출처인지 확인한다.
 *
 * ⚠ 이것은 폴백 <b>재판정</b>이 아니다 — 원본 이름 필드를 보지 않고, 서버가 준 토큰이 우리가
 * 아는 값인지만 본다. 모르는 값이면 아무 단계로도 <b>추측하지 않고</b> 칩을 생략한다.
 */
export function isDisplayNameSource(value: unknown): value is DisplayNameSource {
  return typeof value === 'string' && (DISPLAY_NAME_SOURCES as readonly string[]).includes(value);
}

interface ChipStyle {
  label: string;
  className: string;
}

const STYLE: Record<DisplayNameSource, ChipStyle> = {
  operator: { label: '운영자 지정', className: 'bg-primary-50 text-primary-700' },
  control: { label: '관제 수신명', className: 'bg-secondary-50 text-secondary-700' },
  category: { label: '카테고리명', className: 'bg-warning-50 text-warning-700' },
  code: { label: '유형코드 그대로', className: 'bg-gray-100 text-gray-800' },
};

export interface DisplayNameSourceChipProps {
  /** 서버가 내려준 출처(`dsplNmSource`). */
  source: DisplayNameSource;
  className?: string;
}

export function DisplayNameSourceChip({ source, className }: DisplayNameSourceChipProps) {
  const style = STYLE[source];

  return (
    <span
      data-source={source}
      className={cn(
        'inline-flex shrink-0 items-center rounded px-2 py-0.5 text-label font-medium',
        style.className,
        className,
      )}
    >
      {style.label}
    </span>
  );
}

export default DisplayNameSourceChip;
