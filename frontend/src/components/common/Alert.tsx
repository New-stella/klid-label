// 인라인 안내 배너 — 아이콘 + 제목 + 본문.
//
// <h3>왜 별개 컴포넌트인가 (ErrorState 와의 경계)</h3>
// 이 저장소에는 이미 두 개의 "경고처럼 보이는" 것이 있는데 둘 다 이 자리를 대신하지 못한다.
// <ul>
//   <li><b>{@code ErrorState}</b> — <i>영역 전체</i>를 대체하는 빈 상태다(세로 중앙정렬 + py-12 +
//       원형 아이콘 + 재시도 버튼). "내용을 못 불러왔다"를 그 자리에 대신 그리는 용도라, 폼 위에
//       한 줄로 끼어야 하는 인증 화면 오류 안내로 쓰면 카드 레이아웃이 무너지고 없는 재시도 동선을
//       암시한다.</li>
//   <li><b>{@code DiscardSaveNotice}</b> — 라벨링 폐기 저장 요약 전용이라 props 가
//       {@code DiscardSaveSummary} 에 묶여 있다. 임의 문구를 담을 수 없다.</li>
// </ul>
// 시안이 규정한 {@code .alert}(아이콘 + {@code .alert-title} + {@code .alert-text})는 이 둘과
// 역할이 다른 <b>인라인 배너</b>이며, 인증 3화면(SCREEN-001 · SCREEN-002 · SCREEN-004)이 각자
// {@code <p role="alert">} 한 줄로 때우고 있던 것을 여기로 모은다.
//
// <h3>제목과 본문은 층이 다르다</h3>
// 제목은 <b>분류</b>("무엇이 일어났나")이고 본문은 <b>상세</b>("무엇을 하면 되나" 또는 서버가
// 내려준 원인)다. 서버 메시지를 본문으로 실을 수 있게 {@code children} 을 열어 둔 이유이며,
// 제목만 남기고 서버 메시지를 지우면 사용자가 실제 원인을 볼 수 없게 된다.
//
// @design SCREEN-001 SCREEN-002 SCREEN-004

import { AlertTriangle, Info } from 'lucide-react';
import { type ReactNode } from 'react';

import { cn } from '@/lib/cn';

export type AlertVariant = 'error' | 'info';

export interface AlertProps {
  /** 시안 {@code .alert-error} / {@code .alert-info}. 기본값 error. */
  variant?: AlertVariant;
  /** 분류 한 줄 — 시안 {@code .alert-title}. */
  title: ReactNode;
  /** 상세 — 시안 {@code .alert-text}. 서버가 내려준 메시지를 그대로 실을 수 있다. */
  children?: ReactNode;
  /**
   * 접근성 역할. 기본은 variant 에서 도출한다(error → alert, info → status).
   * 시안의 참고 패널처럼 "문구 예시"를 보여주는 자리에서는 {@code region} 으로 낮춘다.
   */
  role?: 'alert' | 'status' | 'region';
  /** {@code role="region"} 일 때의 접근성 이름. */
  'aria-label'?: string;
  'aria-live'?: 'assertive' | 'polite';
  className?: string;
  'data-testid'?: string;
}

// 시안 매핑 — 배경 --e-0/--i-0(=50) · 테두리 --e-2/--i-2(=200) · 아이콘 --e-6/--i-6(=600) ·
// 제목 --e-7/--i-7(=700) · 본문 --n-8(=gray-800). 색 단계를 임의로 낮추지 말 것(대비 가드가 잡는다).
const VARIANT_STYLE: Record<
  AlertVariant,
  { box: string; icon: string; title: string; Icon: typeof AlertTriangle }
> = {
  error: {
    box: 'border-danger-200 bg-danger-50',
    icon: 'text-danger-600',
    title: 'text-danger-700',
    Icon: AlertTriangle,
  },
  info: {
    box: 'border-info-200 bg-info-50',
    icon: 'text-info-600',
    title: 'text-info-700',
    Icon: Info,
  },
};

export function Alert({
  variant = 'error',
  title,
  children,
  role,
  'aria-label': ariaLabel,
  'aria-live': ariaLive,
  className,
  'data-testid': testId,
}: AlertProps) {
  const style = VARIANT_STYLE[variant];
  const Icon = style.Icon;
  const resolvedRole = role ?? (variant === 'error' ? 'alert' : 'status');

  return (
    <div
      role={resolvedRole}
      aria-label={ariaLabel}
      aria-live={ariaLive}
      data-testid={testId}
      className={cn('flex items-start gap-2 rounded-md border p-4', style.box, className)}
    >
      <Icon className={cn('mt-0.5 h-5 w-5 shrink-0', style.icon)} aria-hidden="true" />
      {/* min-w-0 — 긴 서버 메시지가 배너 밖으로 넘치지 않게 한다(시안 .alert-body 와 동일). */}
      <div className="flex min-w-0 flex-col gap-1 text-left">
        {/* 시안 .alert-title = t-title-sm(17/600), .alert-text = t-body-sm(15/400). */}
        <p className={cn('text-title-sm', style.title)}>{title}</p>
        {/* 본문 색은 시안이 제목보다 낮은 대비(중립 8단)를 쓴다 — 분류가 먼저 읽히게. */}
        {children !== undefined && children !== null && (
          <p className="text-body-sm text-gray-800">{children}</p>
        )}
      </div>
    </div>
  );
}
