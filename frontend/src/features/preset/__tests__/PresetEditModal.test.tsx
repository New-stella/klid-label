import type { QueryClient } from '@tanstack/react-query';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { EVENT_TYPE_ADMIN_KEY } from '@/features/eventType/adminHooks';
import { PresetEditModal } from '@/features/preset/components/PresetEditModal';
import { LABEL_IDS_MAX_COUNT } from '@/features/preset/schemas';
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

/**
 * CO-20260908 — 「수정」으로 편집 모달을 열면 저장돼 있던 이벤트유형이 복원되지 않던 결함.
 *
 * <h3>왜 기존 가드가 이 결함을 통과시켰나 (진입 순서 미재현)</h3>
 * 기존 가드는 `<PresetEditModal open initial={...} />` 로 <b>처음부터 열린 채</b> 마운트했다.
 * 실사용은 그렇지 않다 — 목록 화면이 이 컴포넌트를 <b>닫힌 채 계속 마운트해 두고</b>(그래서
 * 이벤트유형·라벨 마스터 조회가 <b>모달을 열기 전에 이미 끝나 있다</b>) 「수정」 클릭으로
 * `open=false·initial=undefined` → `open=true·initial=preset` 로 <b>전이</b>한다.
 * 그 전이에서만 값 복원이 옵션 등록보다 앞서고, 그때 Radix 의 숨은 native select 가
 * <b>빈 값 change 를 되쏘아</b> 방금 복원한 값을 지우고 필수 오류까지 세웠다.
 *
 * ⇒ 아래 가드는 전부 <b>그 전이</b>를 실제로 지난다. 옵션 조회를 먼저 끝내 두는 것이
 *   `warmUp()` 이며, 이 준비가 빠지면 같은 결함이 다시 통과한다.
 *
 * <h3>「걸리는 쪽」도 함께 단언한다</h3>
 * 「오류가 없다 / 저장이 활성이다」만 보면 <b>애초에 그 상태가 된 적이 없어도</b> 통과한다.
 * 그래서 신규 모드에서 <b>잠기는</b> 것과 <b>고르는 순간 풀리는</b> 것을 짝으로 둔다.
 */
describe('PresetEditModal — 진입 모드별 초기 상태 (CO-20260908)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/event-types').reply(200, ok(ADMIN_EVENT_TYPES));
    mock.onGet('/manage/labels').reply(200, ok(MASTERS));
  });

  afterEach(() => mock.restore());

  const closedModal = (onSubmit = vi.fn()) =>
    renderWithProviders(
      <PresetEditModal open={false} onClose={() => undefined} onSubmit={onSubmit} />,
    );

  /**
   * 실사용 재현의 핵심 — 모달이 <b>닫혀 있는 동안</b> 옵션·마스터 조회가 끝난 상태를 만든다.
   * 이 컴포넌트는 목록 화면에 상시 마운트돼 있어 두 조회가 모달을 열기 전에 이미 완료된다.
   */
  const warmUp = async (queryClient: QueryClient) => {
    await waitFor(() => {
      expect(mock.history.get.some((c) => c.url === '/manage/event-types')).toBe(true);
      expect(mock.history.get.some((c) => c.url === '/manage/labels')).toBe(true);
    });
    // 이 시점에 <b>진행 중인 조회가 없다</b>는 것을 확인한다 — 실사용 진입 순서(모달을 열기
    // 전에 옵션·마스터가 이미 있다)가 실제로 만들어졌다는 뜻이다.
    //
    // ⚠ 구 단언(`mock.history.get.length >= 2`)은 <b>위 waitFor 가 통과한 시점에 이미 참</b>이라
    //   아무것도 확인하지 않았다 — 「요청이 나갔다」와 「응답이 반영됐다」는 다른 축인데 둘 다
    //   요청 이력만 보고 있었다. 아래는 응답 축을 본다.
    // ⚠ 다만 <b>기다리는 장치로 읽지 말 것</b> — 실측상 첫 평가에서 이미 0이라 대기 시간이 0이다.
    //   이 줄의 값은 「전제가 성립한다」를 못박는 자기검사이지 동기화가 아니다.
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));
  };

  /** 편집 진입 직후의 세 축을 함께 본다 — 값·오류·저장 수단. */
  const expectRestored = async (label: string) => {
    const trigger = await screen.findByLabelText(/이벤트유형/);
    await waitFor(() => expect(trigger).toHaveTextContent(label));
    // 오류가 서 있으면 `Field` 가 트리거에 aria-invalid 를 건다. 문구로 판정하지 않는 이유는
    // 필수 오류 문구와 placeholder 문구가 같은 문장이라 서로 구분되지 않기 때문이다.
    expect(trigger).not.toHaveAttribute('aria-invalid');
    expect((screen.getByRole('button', { name: '저장' }) as HTMLButtonElement).disabled).toBe(false);
  };

  it('★수정_진입시_저장된_이벤트가_선택된_채로_열린다_실사용_전이_순서', async () => {
    // 제외 대분류(배회)라 필터 옵션 축이었다면 옵션에 없어 표시조차 되지 않았다.
    const initial = presetOf({ id: 3, eventTypeCd: 'EV08000101', eventTypeNm: '배회' });
    const { rerender, queryClient } = closedModal();
    await warmUp(queryClient);

    // when: 「수정」 클릭 — 닫힘·미지정에서 열림·대상지정으로 전이한다.
    rerender(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    await expectRestored('배회 (EV08000101)');
  });

  it('★수정_진입_직후에는_필수_입력_오류가_서_있지_않는다', async () => {
    // ⚠ 대기를 「오류가 없다」와 같은 축으로 걸면, 회귀했을 때 대기가 먼저 만료돼 정작 이 단언이
    //   실행되지 않는다. 그래서 여기서는 <b>렌더 정착</b>(옵션 등록 완료)을 대기 축으로 쓰고
    //   오류 유무는 단언으로만 본다.
    const initial = presetOf({ id: 3, eventTypeCd: 'EV08000101', eventTypeNm: '배회' });
    const { rerender, queryClient } = closedModal();
    await warmUp(queryClient);
    rerender(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    const trigger = await screen.findByLabelText(/이벤트유형/);
    await screen.findByRole('checkbox', { name: /사람/ });
    await waitFor(() =>
      expect(document.querySelector('select')?.options.length ?? 0).toBeGreaterThan(1),
    );

    // 이 픽스처에는 미연결·전부 미매핑 배너가 없으므로 alert 이 있다면 그것은 필수 입력 오류다.
    expect(screen.queryByRole('alert')).toBeNull();
    expect(trigger).not.toHaveAttribute('aria-invalid');
  });

  it('★수정_진입_복원은_모달을_닫았다_다시_열어도_같다', async () => {
    const initial = presetOf({ id: 3, eventTypeCd: 'EV08000101', eventTypeNm: '배회' });
    const { rerender, queryClient } = closedModal();
    await warmUp(queryClient);

    const openIt = () =>
      rerender(
        <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
      );

    openIt();
    await expectRestored('배회 (EV08000101)');

    // when: 닫았다 다시 연다(2회차) — 실측 결함이 2회차에서도 그대로 재현됐다.
    rerender(
      <PresetEditModal open={false} onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );
    await waitFor(() => expect(screen.queryByLabelText(/이벤트유형/)).toBeNull());
    openIt();

    await expectRestored('배회 (EV08000101)');
  });

  it('★수정_진입시_저장된_라벨_체크가_함께_복원된다', async () => {
    // 값 복원은 이벤트유형과 라벨 <두 축>이다 — 한 축만 보면 나머지가 조용히 빠져도 통과한다.
    const initial = presetOf({
      id: 3,
      eventTypeCd: 'EV08000101',
      eventTypeNm: '배회',
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
    const { rerender, queryClient } = closedModal();
    await warmUp(queryClient);
    rerender(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    await expectRestored('배회 (EV08000101)');
    const person = (await screen.findByRole('checkbox', { name: /사람/ })) as HTMLInputElement;
    await waitFor(() => expect(person.checked).toBe(true));
    expect((screen.getByRole('checkbox', { name: /차량/ }) as HTMLInputElement).checked).toBe(false);
  });

  it('★옵션이_아직_도착하지_않은_채로_수정_진입해도_복원된다', async () => {
    // warmUp 없이 곧바로 연다 — 값 복원이 옵션 도착보다 <앞서는> 순서다.
    // ⚠ 이 축을 「옵션 도착 후 값 재반영」 효과로 메우지 말 것. 그 효과는 옵션 목록이 재조회될
    //   때마다 다시 돌아 사용자가 고른 값을 되돌린다(아래 가드가 그것을 잡는다).
    const initial = presetOf({ id: 3, eventTypeCd: 'EV08000101', eventTypeNm: '배회' });
    const { rerender } = closedModal();
    rerender(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );

    await expectRestored('배회 (EV08000101)');
  });

  it('★옵션_목록이_다시_조회돼도_사용자가_고른_이벤트가_되돌아가지_않는다', async () => {
    const user = userEvent.setup();
    const initial = presetOf({ id: 3, eventTypeCd: 'EV08000101', eventTypeNm: '배회' });
    const { rerender, queryClient } = closedModal();
    await warmUp(queryClient);
    rerender(
      <PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} initial={initial} />,
    );
    await expectRestored('배회 (EV08000101)');

    // given: 편집 중에 다른 이벤트유형으로 바꾼다
    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '화재 (EV02000101)');
    await waitFor(() =>
      expect(screen.getByLabelText(/이벤트유형/)).toHaveTextContent('화재 (EV02000101)'),
    );

    // when: 옵션 목록이 다시 조회된다(창 복귀·무효화·프리셋 저장 후 등).
    // ⚠ 응답이 <b>이전과 완전히 같으면</b> 캐시가 참조를 그대로 유지해(구조 공유) 목록을 의존성으로
    //   삼는 효과가 아예 돌지 않는다 — 그 상태로는 이 축이 검증되지 않는다. 그래서 목록이 실제로
    //   달라진 응답(다른 화면에서 유형이 하나 늘어난 상황)을 돌려준다.
    mock.onGet('/manage/event-types').reply(
      200,
      ok([
        ...ADMIN_EVENT_TYPES,
        { evntTypeCd: 'EV09000101', dsplNm: '연기', dsplNmSource: 'category', clctYn: 'Y' },
      ]),
    );
    const before = mock.history.get.filter((c) => c.url === '/manage/event-types').length;
    await queryClient.invalidateQueries({ queryKey: EVENT_TYPE_ADMIN_KEY });
    await waitFor(() =>
      expect(mock.history.get.filter((c) => c.url === '/manage/event-types').length).toBeGreaterThan(
        before,
      ),
    );

    // then: 방금 고른 값이 저장돼 있던 값으로 되돌아가지 않는다
    await expectRestored('화재 (EV02000101)');
  });

  // ── 신규 모드 — 「걸리는 쪽」 ────────────────────────────────────────

  it('★신규_진입은_두_축이_모두_미선택이고_저장이_잠긴다', async () => {
    const { rerender, queryClient } = closedModal();
    await warmUp(queryClient);
    rerender(<PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />);

    const trigger = await screen.findByLabelText(/이벤트유형/);
    expect(trigger).toHaveTextContent('이벤트유형을 선택하세요');
    expect((await screen.findByRole('checkbox', { name: /사람/ })) as HTMLInputElement).not.toBeChecked();
    // ★잠기는 쪽 — 이것이 없으면 「활성이다」 단언이 애초에 잠긴 적 없어도 통과한다.
    expect((screen.getByRole('button', { name: '만들기' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('★신규_모드에서_이벤트유형을_고르는_순간_저장이_활성된다_라벨_0건은_막지_않는다', async () => {
    const user = userEvent.setup();
    const { rerender, queryClient } = closedModal();
    await warmUp(queryClient);
    rerender(<PresetEditModal open onClose={() => undefined} onSubmit={vi.fn()} />);
    await screen.findByRole('checkbox', { name: /사람/ });

    const save = () => screen.getByRole('button', { name: '만들기' }) as HTMLButtonElement;
    expect(save().disabled).toBe(true);

    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '화재 (EV02000101)');

    // 라벨을 하나도 고르지 않았지만 저장 수단은 활성이다 — 라벨 개수는 이 조건에 들어가지 않는다.
    await waitFor(() => expect(save().disabled).toBe(false));
    expect((screen.getByRole('checkbox', { name: /사람/ }) as HTMLInputElement).checked).toBe(false);
  });
});

/**
 * CO-20260916 — 라벨을 상한보다 많이 고른 채 저장을 눌러도 <b>저장되지도 않고 아무 반응이 없던</b>
 * 발주처 오류 증적.
 *
 * <h3>고친 자리는 검증이 아니라 「도달」이다</h3>
 * 상한 판정은 원래부터 스키마에 있었고(`labelIds.max`) 라벨 목록 쪽에는 개수 표기와 오류 문구까지
 * 떠 있었다. 그런데 <b>저장 가능 조건에 개수가 없어</b> 버튼은 눌리고, 검증은 실패하고, 그 실패가
 * 사용자 눈에 닿지 않았다. 그래서 이 가드는 「판정이 있다」가 아니라 <b>도달</b>을 결박한다.
 *
 * ⚠ 그래서 「안내가 화면 어딘가에 있다」로는 부족하다 — 그 상태는 증적 당시에도 참이었다.
 *   <b>저장 수단 바로 곁</b>에 있는지를 구조로 단언한다.
 *
 * @design SCREEN-026, UC-032, AC-1128
 */
describe('PresetEditModal — 라벨 선택 개수 상한 (CO-20260916)', () => {
  let mock: MockAdapter;

  /** 상한을 넘겨 고를 수 있어야 하므로 상한보다 넉넉히 많은 활성 마스터를 둔다. */
  const MANY_MASTERS = Array.from({ length: LABEL_IDS_MAX_COUNT + 2 }, (_, i) => ({
    labelId: 100 + i,
    name: `라벨${String(i + 1).padStart(2, '0')}`,
    color: '#3B82F6',
    type: 'BBOX',
    sortNo: i + 1,
    useYn: 'Y',
    // 전부 매핑해 둔다 — 「전부 미매핑」 경고가 함께 뜨면 이 가드가 보는 축이 흐려진다.
    dtctTypeCd: 'person',
  }));

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/event-types').reply(200, ok(ADMIN_EVENT_TYPES));
    mock.onGet('/manage/labels').reply(200, ok(MANY_MASTERS));
  });

  afterEach(() => mock.restore());

  const boxes = () => screen.getAllByRole('checkbox') as HTMLInputElement[];
  const save = () => screen.getByRole('button', { name: '만들기' }) as HTMLButtonElement;
  const countText = () => screen.getByTestId('preset-labels-count');

  /** 앞에서부터 `n` 개를 고른다. 실제로 그만큼 골라졌는지 개수 표기로 확인한다. */
  const selectFirstLabels = (n: number) => {
    const all = boxes();
    for (let i = 0; i < n; i += 1) fireEvent.click(all[i]!);
    expect(countText()).toHaveTextContent(`${n} / ${LABEL_IDS_MAX_COUNT}개 선택`);
  };

  /** 이벤트유형까지 골라 「개수만 남은」 상태로 만든다 — 저장이 막히는 사유를 개수 하나로 좁힌다. */
  const openWithEventType = async (onSubmit = vi.fn()) => {
    const user = userEvent.setup();
    renderWithProviders(<PresetEditModal open onClose={() => undefined} onSubmit={onSubmit} />);
    await screen.findByRole('checkbox', { name: /라벨01/ });
    await selectRadixOption(user, screen.getByLabelText(/이벤트유형/), '화재 (EV02000101)');
    await waitFor(() => expect(save().disabled).toBe(false));
    return onSubmit;
  };

  it('★상한을_넘겨_고르면_저장이_막히고_사유가_저장_수단_곁에서_보인다', async () => {
    await openWithEventType();

    selectFirstLabels(LABEL_IDS_MAX_COUNT + 1);

    // ① 막힌다
    expect(save().disabled).toBe(true);

    // ② 사유와 지금 고른 개수가 함께 드러난다
    const notice = screen.getByTestId('preset-label-limit-notice');
    expect(notice).toHaveTextContent(`최대 ${LABEL_IDS_MAX_COUNT}개까지 선택할 수 있습니다`);
    expect(notice).toHaveTextContent(`지금 ${LABEL_IDS_MAX_COUNT + 1}개를 골랐습니다`);

    // ③ ★그 사유가 <b>저장 수단 바로 곁</b>에 있다.
    //   「화면 어딘가에 안내가 있다」로는 부족하다 — 라벨 목록 쪽 표기는 증적 당시에도 이미 있었고
    //   그래도 「아무 반응이 없다」가 됐다. 안내를 목록 쪽으로 되돌리면 이 단언이 깨진다.
    expect(within(save().parentElement!).getByTestId('preset-label-limit-notice')).toBe(notice);
  });

  it('★상한을_넘겨도_라벨_선택_자체는_막지_않는다', async () => {
    // 넘긴 상태에서 <b>무엇을 뺄지</b> 고르려면 그 상태에 머무를 수 있어야 한다.
    await openWithEventType();

    selectFirstLabels(LABEL_IDS_MAX_COUNT + 1);

    // 넘긴 그 선택이 실제로 반영돼 있고(표시만 되고 버려지는 것이 아니다)
    expect(boxes()[LABEL_IDS_MAX_COUNT]!.checked).toBe(true);
    // 어느 체크박스도 잠기지 않으며
    expect(boxes().every((b) => !b.disabled)).toBe(true);
    // 한 개 더 고르는 것도 막히지 않는다.
    fireEvent.click(boxes()[LABEL_IDS_MAX_COUNT + 1]!);
    expect(countText()).toHaveTextContent(`${LABEL_IDS_MAX_COUNT + 2} / ${LABEL_IDS_MAX_COUNT}개 선택`);
  });

  it('★상한_이하로_줄이면_안내가_사라지고_다시_저장할_수_있다', async () => {
    const onSubmit = await openWithEventType();
    selectFirstLabels(LABEL_IDS_MAX_COUNT + 1);
    expect(screen.getByTestId('preset-label-limit-notice')).toBeInTheDocument();

    // when: 하나를 해제해 상한 이하로 되돌린다
    fireEvent.click(boxes()[LABEL_IDS_MAX_COUNT]!);

    // then: 막힘이 영구 상태로 남지 않는다
    await waitFor(() => expect(screen.queryByTestId('preset-label-limit-notice')).toBeNull());
    expect(save().disabled).toBe(false);

    fireEvent.click(save());
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const payload = onSubmit.mock.calls[0]![0] as { labelIds: number[] };
    expect(payload.labelIds).toHaveLength(LABEL_IDS_MAX_COUNT);
  });

  it('★상한_이하에서는_안내가_없고_종전과_똑같이_저장된다', async () => {
    // ★「걸리는 쪽」의 짝 — 이것이 없으면 경계를 한 칸 좁게 막아 정당한 선택까지 막아도 통과한다.
    const onSubmit = await openWithEventType();

    selectFirstLabels(LABEL_IDS_MAX_COUNT);

    expect(screen.queryByTestId('preset-label-limit-notice')).toBeNull();
    expect(save().disabled).toBe(false);

    fireEvent.click(save());
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect((onSubmit.mock.calls[0]![0] as { labelIds: number[] }).labelIds).toHaveLength(
      LABEL_IDS_MAX_COUNT,
    );
  });
});
