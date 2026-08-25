import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PresetEditModal } from '@/features/preset/components/PresetEditModal';
import type { Preset } from '@/features/preset/types';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

/**
 * 이벤트유형 옵션 원천 — <b>등록된 전체 유형</b>(GET /manage/event-types).
 *
 * ★필터 옵션(GET /event-types)이 아니다. 그 목록은 제외 대분류(EV08 배회)와 비수집 유형을
 *   감추므로, 그 축으로 옵션을 채우면 해당 유형의 프리셋을 만들거나 고칠 수 없다.
 */
const ADMIN_EVENT_TYPES = [
  { evntTypeCd: 'EV01000101', dsplNm: '침수(범람)', dsplNmSource: 'category', clctYn: 'Y' },
  { evntTypeCd: 'EV02000101', dsplNm: '화재', dsplNmSource: 'category', clctYn: 'Y' },
  // 같은 표시명 계열 — 코드로만 구분된다.
  { evntTypeCd: 'EV02000102', dsplNm: '화재', dsplNmSource: 'category', clctYn: 'Y' },
  // 제외 대분류(배회) — 필터 옵션에는 없지만 여기에는 반드시 있어야 한다.
  { evntTypeCd: 'EV08000101', dsplNm: '배회', dsplNmSource: 'category', clctYn: 'Y' },
];

// 라벨 마스터 — 프리셋 라벨의 단일 진실원.
const MASTERS = [
  { labelId: 10, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y' },
  { labelId: 20, name: '차량', color: '#3B82F6', type: 'POLYGON', sortNo: 2, useYn: 'Y' },
  { labelId: 30, name: '비활성', color: '#999999', type: 'BBOX', sortNo: 3, useYn: 'N' },
];

const presetOf = (over: Partial<Preset> = {}): Preset => ({
  id: 1,
  eventTypeCd: 'EV02000101',
  eventTypeNm: '화재',
  codes: [],
  createdAt: '2026-05-01T00:00:00Z',
  updatedAt: '2026-05-10T00:00:00Z',
  ...over,
});

describe('PresetEditModal (이벤트 + 라벨)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/event-types').reply(200, ok(ADMIN_EVENT_TYPES));
    mock.onGet('/manage/labels').reply(200, ok(MASTERS));
    // 회귀 가드 — 필터 옵션 엔드포인트는 이 모달에서 더 이상 쓰이지 않아야 한다.
    mock.onGet('/event-types').reply(200, ok([{ categoryKey: 'EV01000101', label: '침수(범람)', memberCodes: ['EV01000101'] }]));
  });

  afterEach(() => mock.restore());

  // ── 이름·설명 폐기 ──────────────────────────────────────────────────

  it('★프리셋_이름과_설명_입력란이_없다', async () => {
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />);
    await screen.findByRole('checkbox', { name: /사람/ });

    // 이벤트 1건에 프리셋 1건이라 이름은 이벤트명의 중복이었고, 설명은 읽는 화면이 없었다.
    expect(screen.queryByLabelText(/프리셋 이름/)).toBeNull();
    expect(screen.queryByLabelText(/^설명$/)).toBeNull();
    expect(screen.queryByPlaceholderText(/프리셋에 대한 설명/)).toBeNull();
  });

  it('★이름_없이_이벤트와_라벨만으로_제출된다', async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />);

    fireEvent.click(await screen.findByRole('checkbox', { name: /사람/ }));
    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '배회 (EV08000101)');
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const payload = onSubmit.mock.calls[0]![0] as Record<string, unknown>;
    expect(Object.keys(payload).sort()).toEqual(['eventTypeCd', 'labelIds']);
    expect(payload).toMatchObject({ eventTypeCd: 'EV08000101', labelIds: [10] });
  });

  // ── 이벤트유형 옵션 원천 ────────────────────────────────────────────

  it('★이벤트_옵션은_등록된_전체_유형에서_온다_필터_옵션_축_폐기', async () => {
    const user = userEvent.setup();
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />);
    await screen.findByRole('checkbox', { name: /사람/ });

    await user.click(screen.getByLabelText(/이벤트유형/));

    // 제외 대분류(배회)가 옵션에 있어야 한다 — 서버는 등록 여부로 검증하므로 받아 주는데
    // 화면만 못 고르면 비대칭이 된다.
    expect(await screen.findByRole('option', { name: '배회 (EV08000101)' })).toBeInTheDocument();
    // 필터 옵션 엔드포인트는 호출되지 않는다.
    expect(mock.history.get.some((c) => c.url === '/event-types')).toBe(false);
    expect(mock.history.get.some((c) => c.url === '/manage/event-types')).toBe(true);
  });

  it('★옵션은_이벤트명과_유형코드를_함께_보인다_동명_유형_구분', async () => {
    const user = userEvent.setup();
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />);
    await screen.findByRole('checkbox', { name: /사람/ });

    await user.click(screen.getByLabelText(/이벤트유형/));

    // 표시명이 같은 두 유형이 코드로 구분된다.
    expect(await screen.findByRole('option', { name: '화재 (EV02000101)' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '화재 (EV02000102)' })).toBeInTheDocument();
  });

  it('★선택_안_함_미매핑_옵션이_없다', async () => {
    const user = userEvent.setup();
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />);
    await screen.findByRole('checkbox', { name: /사람/ });

    await user.click(screen.getByLabelText(/이벤트유형/));
    await screen.findByRole('option', { name: '배회 (EV08000101)' });

    // 이벤트는 필수다 — 걸리지 않은 프리셋은 어느 영상에도 매칭되지 않는 죽은 행이다.
    expect(screen.queryByRole('option', { name: /선택 안 함/ })).toBeNull();
  });

  // ── 저장 가능 조건 ─────────────────────────────────────────────────

  it('라벨_미선택시_저장_disabled', async () => {
    const user = userEvent.setup();
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />);
    await screen.findByRole('checkbox', { name: /사람/ });

    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '화재 (EV02000101)');
    expect((screen.getByText('만들기') as HTMLButtonElement).disabled).toBe(true);
  });

  it('★이벤트_미선택시_저장_disabled', async () => {
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />);
    fireEvent.click(await screen.findByRole('checkbox', { name: /사람/ }));

    // 라벨만 골라도 저장할 수 없다.
    expect((screen.getByText('만들기') as HTMLButtonElement).disabled).toBe(true);
  });

  // ── 라벨 선택(불변) ────────────────────────────────────────────────

  it('활성_마스터만_형태와_함께_렌더링', async () => {
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />);

    expect(await screen.findByRole('checkbox', { name: /사람/ })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /차량/ })).toBeInTheDocument();
    expect(screen.getByText('바운딩박스')).toBeInTheDocument();
    expect(screen.getByText('폴리곤')).toBeInTheDocument();
    // 비활성(useYn='N') 마스터는 노출되지 않는다.
    expect(screen.queryByRole('checkbox', { name: /비활성/ })).toBeNull();
  });

  it('마스터_선택시_labelIds로_제출', async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />);

    fireEvent.click(await screen.findByRole('checkbox', { name: /사람/ }));
    fireEvent.click(screen.getByRole('checkbox', { name: /차량/ }));
    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '화재 (EV02000101)');
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({
      labelIds: [10, 20],
      eventTypeCd: 'EV02000101',
    });
  });

  it('선택_토글시_labelIds_에서_제거', async () => {
    const onSubmit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />);

    const person = await screen.findByRole('checkbox', { name: /사람/ });
    fireEvent.click(person); // 선택
    fireEvent.click(person); // 해제
    fireEvent.click(screen.getByRole('checkbox', { name: /차량/ }));
    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '화재 (EV02000101)');
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({ labelIds: [20] });
  });

  it('초기값_연결_코드가_체크박스에_반영', async () => {
    const initial = presetOf({
      codes: [
        {
          labelId: 10,
          code: null,
          labelName: '사람',
          labelType: 'BBOX',
          linked: true,
          bboxEnabled: true,
          polygonEnabled: false,
        },
      ],
    });
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    const person = (await screen.findByRole('checkbox', { name: /사람/ })) as HTMLInputElement;
    await waitFor(() => expect(person.checked).toBe(true));
    const car = screen.getByRole('checkbox', { name: /차량/ }) as HTMLInputElement;
    expect(car.checked).toBe(false);
  });

  it('★편집_진입시_기존_이벤트가_선택된_채로_열린다', async () => {
    const initial = presetOf({ id: 3, eventTypeCd: 'EV08000101', eventTypeNm: '배회' });
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    // 제외 대분류라 필터 옵션 축이었다면 옵션에 없어 표시조차 되지 않았다.
    const trigger = await screen.findByLabelText(/이벤트유형/);
    await waitFor(() => expect(trigger).toHaveTextContent('배회 (EV08000101)'));
  });

  it('미연결_코드_있으면_경고_배너_표시', async () => {
    const initial = presetOf({
      id: 2,
      codes: [
        {
          labelId: null,
          code: 'OLD_CODE',
          labelName: 'OLD_CODE',
          labelType: null,
          linked: false,
          bboxEnabled: false,
          polygonEnabled: false,
        },
      ],
    });
    renderWithProviders(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    const warning = await screen.findByTestId('preset-unlinked-warning');
    expect(warning).toHaveTextContent('OLD_CODE');
  });
});
