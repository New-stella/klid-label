// 창 안 입력 한 칸 — 라벨 + 도움말 + 컨트롤. [@design UI-107]
//
// 도움말은 <b>라벨 아래 회색 글씨로 항상 표시</b>한다(툴팁·물음표 아이콘이 아니다). 처음 쓰는
// 사람이 무엇을 적는 칸인지 알아야 하고, 툴팁은 키보드·터치에서 열기 어렵기 때문이다.
// 「도움말 숨기기」로 끄면 라벨과 컨트롤만 남는다.

import { type ReactNode } from 'react';

import { cn } from '@/lib/cn';

export interface AnnotationFieldProps {
  label: string;
  /** 라벨 아래 회색 도움말. helpVisible 이 false 면 그리지 않는다. */
  help?: string;
  helpVisible?: boolean;
  /** 필수 표시(빨간 「필수」) — 이벤트 분류에만 쓴다. */
  required?: boolean;
  htmlFor?: string;
  /** 컨트롤 아래 덧붙이는 조각(주석·글자 수 등). */
  after?: ReactNode;
  /** 바깥 배치용 클래스(2열 그리드에서 전폭을 차지해야 하는 칸 등). */
  className?: string;
  children: ReactNode;
}

export function AnnotationField({
  label,
  help,
  helpVisible = true,
  required = false,
  htmlFor,
  after,
  className,
  children,
}: AnnotationFieldProps) {
  const labelNode = (
    <>
      {label}
      {required && <span className="ml-1 font-bold text-danger">필수</span>}
    </>
  );
  return (
    <div className={cn('mb-3', className)}>
      {htmlFor !== undefined ? (
        <label
          htmlFor={htmlFor}
          className="block text-caption font-semibold text-gray-600"
        >
          {labelNode}
        </label>
      ) : (
        <span className="block text-caption font-semibold text-gray-600">{labelNode}</span>
      )}
      {help !== undefined && helpVisible && (
        <p className="mb-1 mt-0.5 text-caption text-gray-500">{help}</p>
      )}
      <div className={help !== undefined && helpVisible ? '' : 'mt-1'}>{children}</div>
      {after}
    </div>
  );
}
