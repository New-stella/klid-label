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

/**
 * 라벨 마스터 — 프리셋 라벨의 단일 진실원.
 *
 * `dtctTypeCd` 는 <b>AI 검출 클래스 매핑</b>이다(null=미매핑). 출하 시드의 폴리곤 라벨
 * (화재·연기·침수)이 실제로 미매핑이라 — 대응 COCO 클래스가 없어 의도적으로 비웠다 —
 * '차량' 을 그 자리에 둔다.
 */
const MASTERS = [
  { labelId: 10, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y', dtctTypeCd: 'person' },
  { labelId: 20, name: '차량', color: '#3B82F6', type: 'POLYGON', sortNo: 2, useYn: 'Y', dtctTypeCd: null },
  { labelId: 30, name: '비활성', color: '#999999', type: 'BBOX', sortNo: 3, useYn: 'N', dtctTypeCd: null },
];

const presetOf = (over: Partial<Preset> = {}): Preset => ({
  id: 1,
  eventTypeCd: 'EV02000101',
  eventTypeNm: '화재',
  codes: [],
  effective: true,
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

  it('★라벨_미선택이어도_저장_버튼은_활성이다_구_차단_폐기', async () => {
    // 라벨을 비우는 것은 그 이벤트유형을 오토라벨링 대상에서 빼겠다는 <선언>이 됐다(CO-014).
    // 구 구현은 이 상태를 disabled 로 막아 그 선언을 표현할 수단이 아예 없었다.
    const user = userEvent.setup();
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />);
    await screen.findByRole('checkbox', { name: /사람/ });

    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '화재 (EV02000101)');
    expect((screen.getByText('만들기') as HTMLButtonElement).disabled).toBe(false);
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

/**
 * CO-014 — 프리셋 미보유·무효 이벤트유형의 오토라벨링이 fail-open 에서 <b>보류</b>로 반전되면서
 * 이 모달이 짊어진 두 가지.
 *
 *  ① 어느 라벨이 오토라벨 대상이 아닌지 짚어 준다(AI 검출 클래스 미매핑 표시).
 *     ⚠ 표시일 뿐 <b>선택을 막지 않는다</b> — 나중에 라벨 관리에서 매핑을 지정하면 그때부터
 *       적용되는 동선을 닫지 않기 위해서다. 막으면 그 동선이 화면에서 사라진다.
 *  ② 라벨을 <b>비운 채 저장</b>할 수 있게 하되, 실수와 선언을 사람이 가르도록 확인을 한 번 받는다.
 *
 * ★두 상태는 서로 다르고 <b>동시에 나오지 않는다</b> — 「전부 미매핑」은 라벨을 하나 이상 고른
 *   프리셋(사고)의 것이고, 「라벨 없이 저장 확인」은 라벨이 빈 프리셋(선언)의 것이다.
 *
 * @design SCREEN-026, API-038, API-039, AC-114
 */
describe('PresetEditModal — AI 매핑 표시 · 라벨 없이 저장 (CO-014)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/event-types').reply(200, ok(ADMIN_EVENT_TYPES));
    mock.onGet('/manage/labels').reply(200, ok(MASTERS));
  });

  afterEach(() => mock.restore());

  const openModal = (onSubmit = vi.fn()) => {
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />);
    return onSubmit;
  };

  // ── ① AI 검출 클래스 매핑 표시 ──────────────────────────────────────

  it('★AI_검출_클래스가_없는_라벨에만_미매핑_표시가_붙는다', async () => {
    openModal();
    await screen.findByRole('checkbox', { name: /사람/ });

    // 차량(dtctTypeCd=null)만 미매핑이다 — 판정은 서버가 라벨마다 내려준 값 그대로다.
    expect(screen.getByTestId('preset-label-unmapped-20')).toHaveTextContent('AI 미매핑');
    expect(screen.queryByTestId('preset-label-unmapped-10')).toBeNull();
  });

  it('★미매핑_라벨도_고를_수_있다_선택을_막지_않는다', async () => {
    const user = userEvent.setup();
    const onSubmit = openModal();

    const car = (await screen.findByRole('checkbox', { name: /차량/ })) as HTMLInputElement;
    // 막혀 있으면 나중에 매핑을 지정해도 그 라벨을 담을 방법이 없다.
    expect(car.disabled).toBe(false);

    fireEvent.click(car);
    await waitFor(() => expect(car.checked).toBe(true));
    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '화재 (EV02000101)');
    fireEvent.click(screen.getByText('만들기'));

    // 저장까지 그대로 나간다(경고만 하고 막지 않는다).
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0]![0]).toMatchObject({ labelIds: [20] });
  });

  it('★고른_라벨이_전부_미매핑이면_경고가_뜨지만_저장은_활성이다', async () => {
    const user = userEvent.setup();
    openModal();

    fireEvent.click(await screen.findByRole('checkbox', { name: /차량/ }));
    // 이벤트유형은 여전히 필수라, 저장 활성 여부를 보려면 먼저 골라야 한다.
    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '화재 (EV02000101)');

    const warning = await screen.findByTestId('preset-unmapped-warning');
    // 문구는 사양 확정값이라 앞뒤를 잘라 일부만 보지 않고 두 문장 모두 확인한다.
    expect(warning).toHaveTextContent(
      '담긴 라벨이 모두 AI 검출 클래스에 매핑되어 있지 않아 이 프리셋은 오토라벨링에 적용되지 않습니다.',
    );
    expect(warning).toHaveTextContent(
      '라벨 관리 화면에서 검출 클래스를 지정하면 그때부터 적용됩니다.',
    );
    // ★저장을 막지 않는다 — 막으면 "나중에 매핑을 지정하면 적용된다"는 동선이 닫힌다.
    expect((screen.getByText('만들기') as HTMLButtonElement).disabled).toBe(false);
  });

  it('매핑된_라벨이_하나라도_있으면_경고가_뜨지_않는다', async () => {
    openModal();

    fireEvent.click(await screen.findByRole('checkbox', { name: /차량/ }));
    await screen.findByTestId('preset-unmapped-warning');
    // 사람(person 매핑)을 더하면 이 프리셋은 실효한다.
    fireEvent.click(screen.getByRole('checkbox', { name: /사람/ }));

    await waitFor(() => expect(screen.queryByTestId('preset-unmapped-warning')).toBeNull());
  });

  it('★라벨을_하나도_고르지_않으면_전부_미매핑_경고가_뜨지_않는다_상태_배타', async () => {
    // 빈 배열의 every 는 true 라, 로딩 중이거나 미선택일 때 경고가 잘못 뜨기 쉬운 지점이다.
    openModal();
    await screen.findByRole('checkbox', { name: /사람/ });

    expect(screen.queryByTestId('preset-unmapped-warning')).toBeNull();
  });

  // ── ② 라벨 없이 저장 확인 ───────────────────────────────────────────

  it('★라벨_0건_저장은_확인을_먼저_받고_확인해야_요청이_나간다', async () => {
    const user = userEvent.setup();
    const onSubmit = openModal();
    await screen.findByRole('checkbox', { name: /사람/ });

    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '화재 (EV02000101)');
    // ⚠ 확인 창 열림은 <상태 변경>이라 act 로 감싸는 user.click 을 쓴다(fireEvent 면 act 경고).
    await user.click(screen.getByText('만들기'));

    // then ①: 저장 전에 확인이 뜬다 — 문구는 사양 확정값이다.
    //   ⚠ 확인 버튼이 뜰 때까지 먼저 기다린다. `findByRole('dialog')` 는 <b>아직 열려 있는 편집
    //     모달</b>을 즉시 집어 오므로, 그것만으로는 확인 창을 기다리는 것이 되지 않는다.
    await screen.findByRole('button', { name: '라벨 없이 저장' });
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('라벨을 하나도 고르지 않았습니다');
    expect(dialog).toHaveTextContent(
      '이대로 저장하면 이 이벤트유형은 오토라벨링 대상에서 빠집니다.',
    );
    // ★"보류되지 않는다"는 사실을 반드시 함께 알린다 — 라벨을 담았는데 적용되지 않는 상태(보류)와
    //   달리 이쪽은 배치가 멈추지 않는다는 것이 두 상태를 가르는 핵심이다.
    expect(dialog).toHaveTextContent(
      '해당 유형의 영상은 보류되지 않고 배치가 그대로 완료되며 오토라벨링만 건너뜁니다.',
    );
    expect(onSubmit).not.toHaveBeenCalled();

    // when: 확인
    await user.click(screen.getByRole('button', { name: '라벨 없이 저장' }));

    // then ②: 라벨 목록을 <비워서> 보낸다(키 자체는 남는다 — 전체 교체 계약).
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const payload = onSubmit.mock.calls[0]![0] as Record<string, unknown>;
    expect(Object.keys(payload).sort()).toEqual(['eventTypeCd', 'labelIds']);
    expect(payload).toMatchObject({ eventTypeCd: 'EV02000101', labelIds: [] });
  });

  it('★돌아가서_라벨_고르기는_저장하지_않고_고른_이벤트유형을_유지한다', async () => {
    const user = userEvent.setup();
    const onSubmit = openModal();
    await screen.findByRole('checkbox', { name: /사람/ });

    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '배회 (EV08000101)');
    await user.click(screen.getByText('만들기'));
    await screen.findByRole('button', { name: '라벨 없이 저장' });

    // when: 되돌아간다
    await user.click(screen.getByRole('button', { name: '돌아가서 라벨 고르기' }));

    // then: 저장은 나가지 않고, 편집 모달이 고른 이벤트유형을 그대로 들고 다시 뜬다
    expect(onSubmit).not.toHaveBeenCalled();
    const trigger = await screen.findByLabelText(/이벤트유형/);
    await waitFor(() => expect(trigger).toHaveTextContent('배회 (EV08000101)'));
  });

  it('★라벨을_하나_이상_고르면_확인_창이_뜨지_않고_바로_저장된다', async () => {
    const user = userEvent.setup();
    const onSubmit = openModal();

    fireEvent.click(await screen.findByRole('checkbox', { name: /사람/ }));
    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '화재 (EV02000101)');
    fireEvent.click(screen.getByText('만들기'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('라벨을 하나도 고르지 않았습니다')).toBeNull();
  });
});
