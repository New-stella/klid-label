/**
 * 포털 채널 목록 **행 카드** — 그림 없이 글자만 남는 목록의 한 줄.
 *
 * <h3>왜 표가 아니라 이 형태인가</h3>
 * Host 포털이 같은 물음에 이미 답해 두었다(`ResultRow`):
 *
 * > *"카드는 대표 이미지가 먼저 읽혀야 하는 목록의 것이고, 로우는 **그림이 없어 글자만 남는
 * > 목록**의 것이다. 그림 없는 카드를 3열로 세우면 칸 안에 텍스트만 남아 카드일 이유가
 * > 사라지는데, 로우로 눕히면 제목이 왼쪽 한 줄로 줄맞춰 서서 훑는 속도가 붙는다."*
 *
 * ★★ **표를 쓰지 않는 진짜 이유는 폭이다.** 증강 목록 한 행이 요구하는 최소 폭은
 *    `일시 144 + 파일명 400 + 생성 조건 444 + 상태 534 + 조작 114 ≈ 1,636px` 인데 본문 최대
 *    폭은 **1,200px** 이다. 436px 이 구조적으로 모자라므로 어느 칸이든 반드시 접히거나 잘린다.
 *    실제로 생성 조건 칸이 최소 폭까지 눌려 **한 글자씩 세로로 흘러내렸다.** 말줄임은 그
 *    부족분을 이용자에게 떠넘기는 것이고, 행 카드는 **줄바꿈이 손해가 아닌 구조**라 부족분
 *    자체가 사라진다.
 *
 * <h3>말줄임을 기본으로 두지 않는다</h3>
 * 시안(SD-026)이 이 화면에서 그것을 이미 거부했다 — *"잘리는 꼬리는 확장자다 … 왜 거부됐는지
 * 화면에서 사라진다"*, 그리고 `title` 보완도 **쓰지 않는다**(*"이 폭은 사실상 터치 환경이라
 * hover 툴팁이 뜨지 않고, 게시본 sanitizer 가 지우는 속성 계열이기도 하다"*). 그래서 이 부품은
 * 어느 자리에도 `truncate` 를 걸지 않고, 긴 값은 **줄바꿈으로 전문을 보인다.**
 *
 * <h3>치수는 Host 원본 실측값이다</h3>
 * <ul>
 *   <li>짜임 <code>[본문 남는폭] [사실값·조작]</code> — 본문이 남는 폭을 먹는다</li>
 *   <li>표면: 흰 면 · 경계 gray-200 · 라운드 16 · <b>그림자 없음</b> · 패딩 20(밀집 목록)</li>
 *   <li>안쪽 간격 세로 12 / 가로 20 · 목록 항목 사이 16</li>
 *   <li>hover 는 <b>주색 최옅단</b>(Host: *"목록 표의 줄은 마우스가 얹히면 primary-5 로 짚는다"*)</li>
 *   <li>제목 17/600 gray-900 · 사실값 13 gray-500 자리폭 고정</li>
 * </ul>
 *
 * <h3>왼쪽 장식을 두지 않는다 (2026-09-08 사용자 지적)</h3>
 * Host `ResultRow` 는 왼쪽에 56 짜리 **갈래 타일**을 두지만 그것은 **여러 갈래가 섞이는 목록**의
 * 장치다. 우리 두 목록은 갈래가 한 종류뿐이라(업로드 자산 · 증강 요청) 같은 그림이 모든 줄에
 * 반복될 뿐이다 — 아무것도 구별해 주지 않는 장식이다.
 * 상태를 알리는 **좌측 색 띠**도 두지 않는다. 그 정보는 이미 상태 배지와 실패 사유가 나르고,
 * 띠는 줄마다 색이 켜졌다 꺼졌다 해서 목록을 훑는 눈을 뺏는다.
 *
 * ⚠ 관제 공통 부품을 쓰지 않는다 — 그 부품들은 관제 화면 여럿이 함께 써서 포털 모양을 넣으면
 *   관제 화면이 같이 바뀐다(사용자 확정 구속: **관제향 화면·컴포넌트 불변**).
 *
 * @design DS-002
 * @design SCREEN-033
 * @design SCREEN-044
 */

import type { ReactNode } from 'react';

import { cn } from '@/lib/cn';

interface PortalRecordRowProps {
  /** 이 줄이 무엇인지. 길면 **자르지 않고 줄바꿈한다.** */
  title: ReactNode;
  /** 제목 오른쪽에 붙는 표식(상태 배지 등). 제목과 같은 줄에서 흐른다. */
  titleAside?: ReactNode;
  /** 제목 아래 본문 — 조건 칩·부제·실패 사유처럼 폭을 먹는 것. */
  body?: ReactNode;
  /** 오른쪽 사실값(일시·크기 등). 13 · 자리폭 고정이라 줄끼리 세로로 선다. */
  meta?: ReactNode;
  /** 오른쪽 조작. 사실값 아래에 선다. */
  actions?: ReactNode;
  /** 고른 줄 — 색만으로 말하지 않는다(호출부가 `aria-pressed` 등을 함께 둔다). */
  selected?: boolean;
  className?: string;
  'data-testid'?: string;
}

/** 행 카드 목록 — 항목 사이 16(Host `.klid-row-list`). */
export function PortalRecordList({
  children,
  className,
  'aria-label': ariaLabel,
  'data-testid': testId,
}: {
  children: ReactNode;
  className?: string;
  'aria-label'?: string;
  'data-testid'?: string;
}) {
  return (
    <ul
      aria-label={ariaLabel}
      data-testid={testId}
      className={cn('flex list-none flex-col gap-card-gap p-0', className)}
    >
      {children}
    </ul>
  );
}

export function PortalRecordRow({
  title,
  titleAside,
  body,
  meta,
  actions,
  selected = false,
  className,
  'data-testid': testId,
}: PortalRecordRowProps) {
  return (
    <div
      data-testid={testId}
      className={cn(
        // 짜임 — 왼쪽(본문)이 남는 폭을 먹는다. `minmax(0,1fr)` 이라야 긴 값이 칸을 밀지 않는다.
        'grid grid-cols-[minmax(0,1fr)_auto] items-start gap-x-block gap-y-in-component',
        'rounded-surface border border-gray-200 bg-white p-block',
        'transition-colors duration-fast hover:bg-primary-50',
        // 좁은 폭에서는 사실값·조작을 본문 아래로 내린다(Host 767 이하).
        'max-md:grid-cols-1',
        selected && 'bg-primary-50 ring-2 ring-primary-500',
        className,
      )}
    >
      <div className="flex min-w-0 flex-col gap-label-gap">
        <div className="flex flex-wrap items-center gap-inline">
          {/*
            ★**자르지 않는다.** 긴 파일명은 줄바꿈으로 전문을 보인다 — 잘리는 꼬리가 확장자라
              「왜 거부됐는지」가 화면에서 사라진다(시안 SD-026). 공백 없는 긴 토큰
              (`YTDown_YouTube_Media_...`)도 끊기도록 `break-all` 을 쓴다.
          */}
          <span className="min-w-0 break-all text-title-sm text-gray-900">{title}</span>
          {titleAside}
        </div>
        {body}
      </div>

      {(meta !== undefined || actions !== undefined) && (
        <div className="flex flex-col items-end gap-in-component max-md:items-start">
          {/*
            사실값 — 13 · 자리폭 고정. 좁은 폭에서는 가로로 흐른다.
            ⚠ **글자색이 600 이다 — Host 원본(gray-50 = 우리 500)보다 한 단 진하다.**
              그 값은 흰 면 위에서는 통과하지만 이 줄의 hover 면(주색 최옅단) 위에서
              **4.01:1 로 AA 미달**이다(대비 가드 실측). 사본이라 해서 미달을 그대로 옮기지
              않는다 — 상대에 제기할 대상이다.
          */}
          {meta !== undefined && (
            <div className="flex flex-wrap items-baseline justify-end gap-x-column gap-y-tight text-xs tabular-nums whitespace-nowrap text-gray-600 max-md:justify-start">
              {meta}
            </div>
          )}
          {actions !== undefined && (
            <div className="flex flex-wrap items-center justify-end gap-inline max-md:justify-start">
              {actions}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * 사실 한 조각을 담는 칩 — `이름 값` 두 도막.
 *
 * ★**서술을 한 줄로 이어 붙이지 않는다.** 생성 조건 다섯을 `시간대: 밤 · 계절: 겨울 · …` 로
 *  이으면 한 덩이 문자열이 되어 폭이 모자랄 때 통째로 잘린다. 칩으로 흩으면 **줄바꿈이
 *  자연스럽고 항목 하나하나가 독립적으로 읽힌다.**
 *
 * 이름은 뒤로 물러나고(gray-500) 값이 앞선다(gray-800/500 굵기) — 훑을 때 눈에 들어와야 하는
 * 것은 값이다.
 */
export function PortalFactChip({ label, value }: { label: string; value: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-tag border border-gray-200 bg-gray-50 px-2 py-0.5 text-xs">
      {/* 이름은 600, 값은 800 — 이름이 한 단 물러나되 옅은 면(gray-50) 위에서 AA 를 지킨다
          (500 은 4.13:1 로 미달이다 — 대비 가드 실측). */}
      <span className="text-gray-600">{label}</span>
      <span className="font-medium text-gray-800">{value}</span>
    </span>
  );
}
