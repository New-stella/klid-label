/**
 * 포털 채널 아이콘 버튼 — 글자 없이 아이콘만 두는 조작 + **마우스를 올리면 뜨는 짧은 설명**.
 *
 * <h3>왜 있나</h3>
 * 한 줄에 조작이 여럿 모이는 자리(업로드 자산 행)는 전부 글자로 두면 조작 덩어리가 본문을 밀어내서
 * 아이콘만 둔다. 그런데 아이콘만으로는 **무슨 버튼인지 읽히지 않는다**(내려받기 둘이 같은 모양이라
 * 구분이 안 된다는 사용자 지적). 그래서 모양을 가르고, 짧은 설명을 붙인다.
 *
 * <h3>Host 와 같은 사용법</h3>
 * 포털 Host 가 같은 자리에 쓰는 `PortalIconButton` 과 **props 모양을 맞췄다**(`aria-label` 필수 ·
 * `tooltip` · `tone`). 다만 Host 는 Radix 툴팁을 쓰고 여기는 **CSS 호버**로 띄운다 —
 * 이 저장소에 Radix 툴팁 의존성이 없고, 같은 화면의 삭제 사유 안내가 이미 이 방식이었다.
 *
 * <h3>접근 이름과 설명은 다른 문자열이어도 된다</h3>
 * `aria-label` 은 낭독용이라 대상까지 밝히고(「{파일명} 삭제」), `tooltip` 은 눈으로 스치는 말이라
 * 짧다(「삭제」). 잠긴 버튼에는 **왜 잠겼는지**를 넣는다.
 *
 * <h3>잠긴 버튼에서도 설명이 뜬다</h3>
 * native `disabled` 버튼은 포인터 이벤트를 받지 않는다. 그래서 잠기면 버튼의 포인터를 끊어
 * **감싸는 요소가 hover 를 받게** 하고, 설명은 감싸는 요소 기준으로 띄운다. 설명 문구는 마크업에
 * 늘 실려 있어(`role="tooltip"` + `aria-describedby`) 보조기술도 읽는다.
 *
 * @design DS-002
 * @design SCREEN-033
 */

import { useId, type ComponentProps, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

import { portalButtonSm } from './portalControl';

export type PortalIconButtonTone = 'default' | 'danger';

type PortalIconButtonProps = Omit<ComponentProps<'button'>, 'children'> & {
  /** 아이콘만 있으므로 필수. */
  'aria-label': string;
  /** 마우스를 올리거나 초점을 옮기면 뜨는 짧은 설명. */
  tooltip: ReactNode;
  /** `danger` 는 되돌릴 수 없는 조작 — 평소엔 조용하고 올렸을 때만 위험색이 드러난다. */
  tone?: PortalIconButtonTone;
  children: ReactNode;
};

const TONE: Record<PortalIconButtonTone, string> = {
  default: '',
  danger: 'hover:bg-danger-50 hover:text-danger-600 active:bg-danger-100',
};

export function PortalIconButton({
  tooltip,
  tone = 'default',
  className,
  type = 'button',
  children,
  ...props
}: PortalIconButtonProps) {
  const tipId = useId();

  return (
    <span className="group/tip relative inline-flex">
      <button
        type={type}
        aria-describedby={tipId}
        className={cn(
          portalButtonSm('ghost', 'px-2'),
          TONE[tone],
          // 잠기면 포인터를 감싸는 요소로 넘겨 설명이 계속 뜨게 한다.
          'disabled:pointer-events-none',
          className,
        )}
        {...props}
      >
        {children}
      </button>
      <span
        id={tipId}
        role="tooltip"
        className={cn(
          'pointer-events-none absolute bottom-full right-0 z-20 mb-2',
          'w-max max-w-64 rounded-md bg-gray-900 px-3 py-1.5',
          // 한글이 음절 중간에서 끊기지 않게 어절 단위로 줄바꿈한다.
          'text-caption font-medium text-white text-pretty break-keep',
          'opacity-0 transition-opacity duration-fast',
          'group-hover/tip:opacity-100 group-focus-within/tip:opacity-100',
        )}
      >
        {tooltip}
        {/* 꼬리 — 버튼 가운데를 가리키도록 오른쪽에서 버튼 반폭만큼 들인다. */}
        <span
          aria-hidden
          className="absolute -bottom-1 right-3.5 size-2 rotate-45 bg-gray-900"
        />
      </span>
    </span>
  );
}
