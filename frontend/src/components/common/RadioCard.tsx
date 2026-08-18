import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

/**
 * 설명을 곁들인 선택 카드형 라디오.
 *
 * @design SCREEN-002 (역할 클레임 — 작업자/검수자) · @design SCREEN-004 (개발용 로그인 — 역할 프리셋)
 *
 * 두 화면의 확정 시안이 같은 `.radio-card` 골격을 공유한다: 44px 이상 높이의 카드 안에
 * 라디오 + (제목 · 설명) + 선택적 후행 슬롯(채널 칩)을 두고, **선택된 카드만** 테두리·배경으로
 * 강조한다. 구 구현은 두 화면 모두 테두리 상자 안의 라디오 한 줄이라 ①옵션별 설명이 없고
 * ②무엇이 선택됐는지 라디오 점 하나로만 드러났다.
 *
 * ⚠ 두 화면에 각자 복제하지 않는다 — 시안이 같은 골격을 쓰므로 한쪽만 손대면 갈라진다.
 *
 * 접근성:
 * - `<label>` 이 input 을 감싸 카드 전체가 클릭·터치 영역이다(KRDS 44px 터치 타깃).
 * - **이름은 제목만, 설명은 설명으로** 갈라 준다 — `aria-labelledby`(제목) + `aria-describedby`
 *   (설명). label 이 감싸는 것에만 맡기면 접근가능한 이름이 "작업자 영상에 라벨을 만들고…"
 *   처럼 설명까지 붙어버리고, `aria-describedby` 와 겹쳐 같은 문장을 두 번 읽는다.
 * - 선택 강조는 색 단독이 아니다 — 라디오 자체의 체크 상태가 항상 함께 보인다(a11y: 색상만으로
 *   정보 전달 금지).
 * - 포커스 링은 카드에 준다(시안). `:has()` 미지원 브라우저에서도 input 의 **브라우저 기본
 *   포커스 링이 남도록** input 에서 outline 을 제거하지 않는다 — 지시자가 통째로 사라지지 않게.
 */
export interface RadioCardProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'title'> {
  /** 카드의 제목 줄 — 역할 호칭 등. */
  title: ReactNode;
  /** 제목 아래 한 줄 설명. 생략하면 제목만 렌더한다. */
  description?: ReactNode;
  /** 카드 오른쪽 끝 슬롯 — 채널 칩처럼 값에 딸린 보조 표기. */
  trailing?: ReactNode;
  /** 카드(label) 에 붙는 클래스. input 에는 `className` 이 그대로 간다. */
  cardClassName?: string;
}

export const RadioCard = forwardRef<HTMLInputElement, RadioCardProps>(function RadioCard(
  { title, description, trailing, cardClassName, className, id, disabled, ...rest },
  ref,
) {
  // 호출부 id 유무와 무관하게 안정적인 내부 id 를 쓴다 — 이름·설명 배선이 id 를 넘겨줬는지에
  // 따라 조용히 끊기지 않게.
  const autoId = useId();
  const base = id ?? autoId;
  const titleId = `${base}-title`;
  const descriptionId = description != null ? `${base}-desc` : undefined;

  return (
    <label
      className={cn(
        'flex min-h-11 cursor-pointer select-none items-center gap-4 rounded-md border border-gray-400 bg-white p-4 transition-colors',
        'hover:bg-gray-50',
        // 선택 강조 — 시안 `.radio-card:has(> input:checked)`: 테두리·배경 전환 + 1px inset 링으로
        // 테두리를 두껍게 보이게 한다(레이아웃을 밀지 않으려고 border-width 대신 ring 을 쓴다).
        'has-[:checked]:border-primary-500 has-[:checked]:bg-primary-50 has-[:checked]:ring-1 has-[:checked]:ring-inset has-[:checked]:ring-primary-500',
        'has-[:focus-visible]:outline has-[:focus-visible]:outline-[3px] has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-primary-500',
        disabled && 'cursor-not-allowed opacity-60',
        cardClassName,
      )}
    >
      <input
        ref={ref}
        id={id}
        type="radio"
        disabled={disabled}
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        className={cn('h-5 w-5 shrink-0 accent-primary-500', className)}
        {...rest}
      />
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span id={titleId} className="text-body-md text-gray-900">
          {title}
        </span>
        {description != null && (
          // gray-600 — 선택 시 배경이 primary-50 으로 바뀌므로 그 위에서도 AA(4.5:1)를 넘는
          // 단계를 쓴다(gray-500 은 primary-50 위에서 4.01:1 로 미달, contrastGuard 참조).
          <span id={descriptionId} className="text-caption text-gray-600">
            {description}
          </span>
        )}
      </span>
      {trailing != null && <span className="shrink-0">{trailing}</span>}
    </label>
  );
});

export default RadioCard;
