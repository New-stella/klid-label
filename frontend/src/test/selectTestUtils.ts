import { screen } from '@testing-library/react';
import type { UserEvent } from '@testing-library/user-event';

/**
 * Radix `Select`(UI-003)는 포털 드롭다운이라 `userEvent.selectOptions` (네이티브 `<select>` 전용
 * API)가 통하지 않는다 — 트리거를 열고 옵션을 클릭하는 절차로 대체한다.
 *
 * 옵션은 **값이 아니라 접근성 이름(라벨 텍스트)** 으로 찾는다 — Radix 옵션은 `role="option"`
 * DOM 요소일 뿐 `value` 속성을 노출하지 않는다.
 *
 * @param user `userEvent.setup()` 인스턴스
 * @param trigger `role="combobox"` 트리거 엘리먼트(`getByLabelText`/`getByRole('combobox', {name})` 등으로 조회)
 * @param optionName 선택할 옵션의 접근성 이름
 */
export async function selectRadixOption(
  user: UserEvent,
  trigger: HTMLElement,
  optionName: string | RegExp,
): Promise<void> {
  await user.click(trigger);
  const option = await screen.findByRole('option', { name: optionName });
  await user.click(option);
}
