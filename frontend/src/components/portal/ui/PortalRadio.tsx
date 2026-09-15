/**
 * 포털 채널 라디오 — **보이는 동그라미를 네이티브 입력에 맡기지 않는다.**
 *
 * <h3>왜 따로 있나 (2026-09-15 포털 개발망 실측)</h3>
 * 포털 Host 의 디자인시스템 스타일은 네이티브 `input[type=radio]`·`input[type=checkbox]` 를
 * 시각적으로 숨긴다(1px · 절대 위치 · clip — 레이어 안 `!important`). 그 디자인시스템은 라벨의
 * 가상 요소로 동그라미를 그리는 전제라서다. 그 규칙은 뒤에 로드되는 우리 스타일이 이길 수 없어,
 * 입력 자체를 그리는 관제 공통 라디오를 포털에서 쓰면 **선택지 글자만 남고 동그라미가 사라진다.**
 *
 * 관제 공통 라디오는 고치지 않는다(사용자 확정 구속: 관제향 화면·컴포넌트 불변). 포털이 자기 부품을 갖는다.
 *
 * <h3>구조</h3>
 * - 네이티브 입력은 **남긴다** — 이름 연결·같은 이름끼리의 화살표 이동·Space 선택·폼 동작을 그대로 쓴다.
 *   Host 가 어차피 숨기지만 우리도 `sr-only` 로 **명시해서** Host 스타일 유무와 무관하게 같은 모양이 된다.
 * - 동그라미는 입력의 **다음 형제** `span` 이 그린다. 선택·초점·비활성은 `peer-*` 로 입력 상태를 따른다
 *   — 비제어 사용(`defaultChecked`)에서도 React 상태 없이 따라온다.
 * - 크기는 px 로 적는다(Host 루트 글자 크기가 10px 이라 rem 은 줄어든다).
 *
 * ⚠ Host 가 `input + label` 형태의 선택자로 가상 요소를 그리더라도 여기에는 걸리지 않는다 —
 *   입력의 다음 형제는 `label` 이 아니라 `span` 이다.
 *
 * props 는 관제 공통 라디오와 같은 모양이다(호출부 교체만으로 바뀐다).
 *
 * @design DS-002
 * @design SCREEN-029
 */

import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

export interface PortalRadioProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label?: ReactNode;
}

/** 보이는 동그라미 — 입력의 다음 형제라 `peer-*` 로 상태를 받는다. */
const DOT = cn(
  'inline-block size-[16px] shrink-0 rounded-full border border-gray-500 bg-white',
  'transition-colors duration-fast',
  // 선택 — 채운 원 + 안쪽 흰 틈으로 가운데 점을 만든다(요소 하나로 끝나 형제 선택자가 한 번만 필요하다).
  'peer-checked:border-primary-500 peer-checked:bg-primary-500 peer-checked:shadow-[inset_0_0_0_3px_white]',
  // 키보드 초점 — 입력은 숨어 있으므로 링을 동그라미에 옮긴다.
  'peer-focus-visible:ring-[3px] peer-focus-visible:ring-primary-500 peer-focus-visible:ring-offset-2',
  // 비활성 — 흐림(opacity)이 아니라 채도를 뺀 면으로 표시한다(포털 조작 관례).
  'peer-disabled:border-gray-300 peer-disabled:bg-gray-100',
  'peer-checked:peer-disabled:border-gray-400 peer-checked:peer-disabled:bg-gray-400',
);

export const PortalRadio = forwardRef<HTMLInputElement, PortalRadioProps>(function PortalRadio(
  { label, id, className, ...rest },
  ref,
) {
  const autoId = useId();
  const fieldId = id ?? autoId;
  return (
    // 44px 행 전체가 클릭 영역이다. `relative` — 숨긴 입력(절대 위치)이 스크롤 상자 밖으로 새지 않게 가둔다.
    <label
      data-portal-radio
      className={cn(
        'relative inline-flex min-h-11 cursor-pointer select-none items-center gap-2',
        'has-[:disabled]:cursor-not-allowed',
        !label && 'min-w-11 justify-center',
      )}
    >
      <input
        ref={ref}
        id={fieldId}
        type="radio"
        className={cn('peer sr-only', className)}
        {...rest}
      />
      <span aria-hidden data-portal-radio-dot className={DOT} />
      {label && (
        <span className="text-body text-gray-700 peer-disabled:text-gray-500">{label}</span>
      )}
    </label>
  );
});
