import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { PresetListPage } from '@/pages/manage/PresetListPage';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

/**
 * 프리셋은 이름·설명을 갖지 않는다(V17) — 카드 제목은 서버가 준 이벤트 표시명과 유형코드다.
 *
 * ★`eventTypeNm` 은 서버가 4단 폴백으로 채운다. 필터 옵션 목록으로 역해석하면 제외 대분류
 *   (EV08 배회)가 코드로만 노출된다 — 실제 발생했던 결함이고 이 파일이 그 회귀를 고정한다.
 */
const PRESETS = [
  {
    id: 1,
    labelCodes: ['사람'],
    labelCodeOptions: [
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
    eventTypeCd: 'EV08000101',
    eventTypeNm: '배회',
    createdAt: '2026-06-01T00:00:00',
    updatedAt: '2026-06-01T00:00:00',
  },
  {
    id: 2,
    labelCodes: ['LEGACY_CAR'],
    labelCodeOptions: [
      {
        labelId: null,
        code: 'LEGACY_CAR',
        labelName: 'LEGACY_CAR',
        labelType: null,
        linked: false,
        bboxEnabled: false,
        polygonEnabled: false,
      },
    ],
    eventTypeCd: 'EV04000101',
    eventTypeNm: '교통사고',
    createdAt: '2026-06-01T00:00:00',
    updatedAt: '2026-06-01T00:00:00',
  },
];

describe('PresetListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/presets').reply(200, ok(PRESETS));
    mock.onGet('/manage/labels').reply(200, ok([]));
    mock.onGet('/manage/event-types').reply(200, ok([]));
    // 회귀 가드 — 이벤트 목록·라벨맵 엔드포인트는 프리셋 <표시>에 쓰이지 않아야 한다.
    mock.onGet('/event-types').reply(200, ok([]));
    mock.onGet('/event-types/labels').reply(200, ok({ EV08000101: '역해석된 이름' }));
  });

  afterEach(() => mock.restore());

  it('★카드_제목은_서버가_준_이벤트명과_유형코드다', async () => {
    // given / when
    renderWithProviders(<PresetListPage />);

    // then
    const title = await screen.findByTestId('preset-title-1');
    expect(title).toHaveTextContent('배회 (EV08000101)');
  });

  it('★제외_대분류_유형도_이름으로_표시된다_역해석_폐기_회귀_가드', async () => {
    // given: 배회(EV08)는 필터 옵션 목록에 없다. 구 구현은 그 목록으로 코드를 역해석해
    //   이 프리셋을 코드(EV08000101)로만 표시했다.
    renderWithProviders(<PresetListPage />);

    // then ① 이름이 보인다
    const title = await screen.findByTestId('preset-title-1');
    expect(title).toHaveTextContent('배회');

    // then ② 표시를 위해 이벤트 목록·라벨맵을 조회하지 않는다(판정은 서버 한 곳)
    await waitFor(() => {
      expect(mock.history.get.some((c) => c.url === '/event-types')).toBe(false);
      expect(mock.history.get.some((c) => c.url === '/event-types/labels')).toBe(false);
    });
  });

  it('연결된_라벨은_마스터_라벨명과_형태로_표시된다', async () => {
    renderWithProviders(<PresetListPage />);

    // labelName='사람' + 형태 배지 '바운딩박스'
    expect(await screen.findByText('사람')).toBeInTheDocument();
    expect(screen.getByText('바운딩박스')).toBeInTheDocument();
  });

  it('미연결_라벨은_미연결_배지로_구분된다', async () => {
    renderWithProviders(<PresetListPage />);

    expect(await screen.findByText('LEGACY_CAR')).toBeInTheDocument();
    expect(screen.getByText('미연결')).toBeInTheDocument();
  });

  // ── 사양 SCREEN-026 정합 회귀 가드 (REVIEWER 동선) ───────────────────

  describe('REVIEWER 카드 액션', () => {
    beforeEach(() => {
      useAuthStore.setState({
        claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
      });
      // 토스트는 전역 스토어라 테스트 간에 새지 않게 비우고 시작한다.
      useUiStore.setState({ toasts: [] });
    });
    afterEach(() => {
      useAuthStore.getState().clear();
      useUiStore.setState({ toasts: [] });
    });

    it('★복제_버튼이_없다', async () => {
      // 프리셋은 이벤트유형 1건에 1건만 대응하므로 복제 대상이 없고, 복제 결과(이벤트 미연결)는
      // 어느 영상에도 매칭되지 않는 죽은 행이 된다. 서버 엔드포인트도 함께 제거됐다.
      renderWithProviders(<PresetListPage />);
      await screen.findByTestId('preset-title-1');

      expect(screen.queryByRole('button', { name: /복제/ })).toBeNull();
      expect(mock.history.post.some((c) => c.url?.includes('/clone'))).toBe(false);
    });

    it('★삭제_확인은_어느_이벤트의_프리셋인지_밝힌다', async () => {
      // 프리셋에 이름이 없으므로 이벤트로 밝히지 않으면 무엇을 지우는지 알 수 없다.
      renderWithProviders(<PresetListPage />);
      await screen.findByTestId('preset-title-1');

      await userEvent.click(
        screen.getByRole('button', { name: '배회 (EV08000101) 프리셋 삭제' }),
      );

      const dialog = await screen.findByRole('dialog');
      expect(dialog).toHaveTextContent('배회 (EV08000101)');
      expect(dialog).toHaveTextContent('되돌릴 수 없습니다');
    });

    it('★카드_액션에는_아이콘이_붙지_않는다_제목_폭_잠식_회귀_가드', async () => {
      // given: 확정 디자인(SCREEN-026 `.psm-card__actions`)은 **텍스트 전용 ghost 버튼**이다.
      //   아이콘을 붙이면 버튼마다 20px(아이콘 14 + gap 6)씩 액션 열이 넓어지고 그 폭은 전부
      //   제목이 든 1fr 열에서 빠져나가 제목이 `교통사고 기...` 로 잘렸다(실제 발생한 회귀).
      //   레이아웃 자체는 jsdom 이 계산하지 않으므로, **원인인 아이콘 유무**를 고정한다.
      renderWithProviders(<PresetListPage />);
      await screen.findByTestId('preset-title-1');

      for (const name of ['배회 (EV08000101) 프리셋 수정', '배회 (EV08000101) 프리셋 삭제']) {
        const btn = screen.getAllByRole('button', { name })[0];
        expect(btn.querySelector('svg'), `"${name}" 버튼에 아이콘이 붙었다`).toBeNull();
        // 텍스트 라벨 자체는 남아 있어야 한다(아이콘 전용 버튼으로 바꾸는 것도 사양 위반).
        expect(btn.textContent?.trim()).not.toBe('');
      }
    });

    it('★이미_프리셋이_있는_이벤트면_서버_409_메시지를_그대로_보여준다', async () => {
      // given: 서버는 어느 이벤트가 이미 쓰이고 있는지 표시명+코드로 밝혀 준다.
      //   화면에서 문구를 새로 만들면 그 구체성이 사라진다.
      mock.onGet('/manage/labels').reply(
        200,
        ok([{ labelId: 10, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y' }]),
      );
      mock.onGet('/manage/event-types').reply(
        200,
        ok([{ evntTypeCd: 'EV08000101', dsplNm: '배회', dsplNmSource: 'category', clctYn: 'Y' }]),
      );
      mock.onPost('/manage/presets').reply(409, {
        success: false,
        data: null,
        message: '이미 프리셋이 등록된 이벤트입니다: 배회(EV08000101)',
        errorCode: 'CONFLICT',
      });

      renderWithProviders(<PresetListPage />);
      await screen.findByTestId('preset-title-1');

      // when
      await userEvent.click(screen.getByRole('button', { name: '프리셋 추가' }));
      await userEvent.click(await screen.findByRole('checkbox', { name: /사람/ }));
      await userEvent.click(screen.getByLabelText(/이벤트유형/));
      await userEvent.click(await screen.findByRole('option', { name: '배회 (EV08000101)' }));
      await userEvent.click(screen.getByText('만들기'));

      // then: 토스트 호스트는 이 렌더 트리에 없으므로 발행된 안내 문구를 직접 본다.
      await waitFor(() => expect(useUiStore.getState().toasts).toHaveLength(1));
      const toast = useUiStore.getState().toasts[0]!;
      expect(toast.variant).toBe('error');
      // ★화면이 만든 일반 문구('프리셋 추가에 실패했습니다')가 아니라 서버 문구여야 한다.
      expect(toast.message).toBe('이미 프리셋이 등록된 이벤트입니다: 배회(EV08000101)');
    });

    it('라벨_칩은_앞_6개만_보이고_나머지는_펼칠_수_있다', async () => {
      // given: 라벨 8종짜리 프리셋
      const many = {
        ...PRESETS[0],
        id: 3,
        labelCodeOptions: Array.from({ length: 8 }, (_, i) => ({
          labelId: 100 + i,
          code: null,
          labelName: `라벨${i}`,
          labelType: 'BBOX',
          linked: true,
          bboxEnabled: true,
          polygonEnabled: false,
        })),
      };
      mock.onGet('/manage/presets').reply(200, ok([many]));

      renderWithProviders(<PresetListPage />);
      await screen.findByText('라벨0');

      // then: 앞 6개만 보이고 초과분은 '+2' 로 접힌다(사양: 앞 6개 + '+N')
      expect(screen.getByText('라벨5')).toBeInTheDocument();
      expect(screen.queryByText('라벨6')).toBeNull();
      const toggle = screen.getByTestId('preset-chip-toggle-3');
      expect(toggle).toHaveTextContent('+2');

      // when: 펼친다 — '+N' 이 정보 배지일 뿐이면 나머지 라벨은 도달 불가능해진다
      await userEvent.click(toggle);

      // then
      expect(screen.getByText('라벨7')).toBeInTheDocument();
      expect(toggle).toHaveTextContent('접기');
    });

    it('라벨이_6개_이하면_접기_토글을_노출하지_않는다', async () => {
      renderWithProviders(<PresetListPage />);
      await screen.findByTestId('preset-title-1');

      expect(screen.queryByTestId('preset-chip-toggle-1')).toBeNull();
    });
  });

  // ── 사양 SCREEN-026 그리드 임계 회귀 가드 ───────────────────────────────
  //
  // ★'아이콘 제거' 가드만으로는 제목 절단이 해소되지 않았다(브라우저 실측: 1440 에서
  //   9/9 전건 말줄임). 남은 원인은 **3열 임계를 뷰포트로 잰 것**이다 — 확정 디자인의
  //   `@media (max-width: 1280px) { 2열 }` 은 셸이 없는 캔버스 기준이라 뷰포트=카드 영역인데,
  //   실제 화면은 고정 LNB(240) + 좌우 패딩(48)을 뺀 나머지가 카드 영역이다.
  //
  // ⚠ jsdom 은 미디어쿼리를 적용하지 않아 실제 열 수는 여기서 판정할 수 없다(브라우저 실측이
  //   짝이다). 이 가드가 고정하는 것은 **임계값을 카드 영역 기준으로 환산했다는 사실**이다.
  it('★3열_임계는_뷰포트가_아니라_카드_영역_기준이다_구_xl_임계_폐기', async () => {
    renderWithProviders(<PresetListPage />);
    await screen.findByTestId('preset-title-1');

    const grid = document.querySelector('[class*="grid-cols-1"]');
    expect(grid).not.toBeNull();
    const cls = grid!.className;

    // 1280(디자인 임계) + 240(LNB) + 48(좌우 패딩) = 1520
    expect(cls).toContain('min-[1520px]:grid-cols-3');
    // 구 임계로 되돌아가면 카드가 368px 로 눌려 제목이 다시 전건 잘린다.
    expect(cls).not.toContain('xl:grid-cols-3');
    // 2열 축은 이번 변경 범위가 아니다 — 함께 지워지지 않았는지 확인한다.
    expect(cls).toContain('md:grid-cols-2');
  });

  // ── 사양 SCREEN-026 페이지 크기 회귀 가드 ───────────────────────────

  /**
   * ★한 페이지에 **9건**이다(사양 SCREEN-026 — 카드 그리드 절·페이지네이션 절 양쪽에 명시).
   *
   * 구 구현은 10건이었다. 그리드가 3열이라 9면 마지막 줄이 정확히 차지만 10이면 카드 하나만
   * 남은 줄이 생긴다. 경계(10번째 카드)를 직접 본다 — 총 페이지 수만 세면 9든 10이든 같은
   * 값이 나오는 모수가 있어 가드가 헐거워진다.
   */
  it('한_페이지에_9건까지만_보이고_10번째는_다음_페이지에_있다', async () => {
    // given: 프리셋 10개 (경계 + 1)
    const ten = Array.from({ length: 10 }, (_, i) => ({
      ...PRESETS[0],
      id: 100 + i,
      eventTypeCd: `EV0400010${i}`,
      eventTypeNm: `이벤트${String(i).padStart(2, '0')}`,
    }));
    mock.onGet('/manage/presets').reply(200, ok(ten));

    // when
    renderWithProviders(<PresetListPage />);
    await screen.findByTestId('preset-title-100');

    // then ① 1페이지에는 앞 9건만 — 10번째(id=109)는 없다
    expect(screen.getByTestId('preset-title-108')).toBeInTheDocument();
    expect(screen.queryByTestId('preset-title-109')).toBeNull();
    expect(screen.getAllByTestId(/^preset-title-/)).toHaveLength(9);

    // then ② 나머지 1건은 2페이지에 있다
    await userEvent.click(screen.getByRole('button', { name: '2페이지' }));
    expect(await screen.findByTestId('preset-title-109')).toBeInTheDocument();
    expect(screen.queryByTestId('preset-title-100')).toBeNull();
  });
});

/**
 * CO-014 — 프리셋이 없거나 무효인 이벤트유형의 영상은 오토라벨링이 <b>보류</b>된다.
 * 그래서 목록이 두 가지를 갈라 보여줘야 한다.
 *
 *  - 「오토라벨 미적용」 : 라벨은 담았는데 전부 AI 검출 클래스 미매핑이라 <b>적용되지 않는</b> 상태.
 *    운영자는 기준을 걸었다고 믿는데 실제로는 걸리지 않은 <b>사고</b>다.
 *  - 「오토라벨 제외」   : 라벨을 하나도 담지 않아 그 유형을 오토라벨 대상에서 <b>뺀</b> 선언.
 *
 * ★두 배지는 <b>한 카드에 함께 붙지 않는다</b>. 판정 축이 다르기 때문이다 — 앞은 서버가 준 실효
 *   여부, 뒤는 라벨 건수다. 라벨 0건 프리셋도 서버는 실효하지 않는다고 내려주므로, 실효 여부만으로
 *   가르면 둘이 같은 카드에 붙는다.
 *
 * @design SCREEN-026, API-037, API-038, AC-114, AC-119
 */
describe('PresetListPage — 오토라벨 배지·저장 안내 (CO-014)', () => {
  let mock: MockAdapter;

  const MASTERS = [
    { labelId: 10, name: '사람', color: '#EF4444', type: 'BBOX', sortNo: 1, useYn: 'Y', dtctTypeCd: 'person' },
    { labelId: 20, name: '차량', color: '#3B82F6', type: 'POLYGON', sortNo: 2, useYn: 'Y', dtctTypeCd: null },
  ];
  const ADMIN_EVENT_TYPES = [
    { evntTypeCd: 'EV02000101', dsplNm: '화재', dsplNmSource: 'category', clctYn: 'Y' },
  ];

  const presetJson = (over: Record<string, unknown> = {}) => ({
    id: 1,
    labelCodes: ['사람'],
    labelCodeOptions: [
      {
        labelId: 10,
        code: null,
        labelName: '사람',
        labelType: 'BBOX',
        linked: true,
        bboxEnabled: true,
        polygonEnabled: false,
        dtctTypeCd: 'person',
      },
    ],
    eventTypeCd: 'EV02000101',
    eventTypeNm: '화재',
    createdAt: '2026-06-01T00:00:00',
    updatedAt: '2026-06-01T00:00:00',
    effective: true,
    ...over,
  });

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/labels').reply(200, ok(MASTERS));
    mock.onGet('/manage/event-types').reply(200, ok(ADMIN_EVENT_TYPES));
    useAuthStore.setState({
      claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    useUiStore.setState({ toasts: [] });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useUiStore.setState({ toasts: [] });
  });

  // ── 카드 배지 ───────────────────────────────────────────────────────

  it('★라벨은_담았는데_실효하지_않으면_오토라벨_미적용_배지가_붙는다', async () => {
    mock.onGet('/manage/presets').reply(200, ok([presetJson({ effective: false })]));

    renderWithProviders(<PresetListPage />);

    const badge = await screen.findByTestId('preset-badge-1');
    expect(badge).toHaveTextContent('오토라벨 미적용');
    // 보조 안내 문구는 사양 확정값이다 — 배지 글자만으로는 "그래서 어떻게 되는가"가 안 드러난다.
    expect(badge).toHaveAttribute('title', '이 프리셋은 오토라벨링에 적용되지 않습니다');
  });

  it('★라벨이_0건이면_오토라벨_제외_배지가_붙고_미적용_배지는_붙지_않는다', async () => {
    // 서버는 라벨 0건 프리셋도 실효하지 않는다(effective=false)고 내려준다 — 그래서 실효 여부만
    // 보면 두 배지가 함께 붙는다. 라벨 건수를 먼저 보는 것이 그 배타를 만든다.
    mock.onGet('/manage/presets').reply(
      200,
      ok([presetJson({ labelCodes: [], labelCodeOptions: [], effective: false })]),
    );

    renderWithProviders(<PresetListPage />);

    const badge = await screen.findByTestId('preset-badge-1');
    expect(badge).toHaveTextContent('오토라벨 제외');
    expect(badge).toHaveAttribute(
      'title',
      '이 이벤트유형은 오토라벨링을 하지 않도록 지정되어 있습니다',
    );
    expect(screen.queryByText('오토라벨 미적용')).toBeNull();
  });

  it('실효하는_프리셋에는_배지가_붙지_않는다', async () => {
    mock.onGet('/manage/presets').reply(200, ok([presetJson({ effective: true })]));

    renderWithProviders(<PresetListPage />);
    await screen.findByTestId('preset-title-1');

    expect(screen.queryByTestId('preset-badge-1')).toBeNull();
  });

  it('★실효_여부가_응답에_없으면_경고를_지어내지_않는다', async () => {
    // 구 서버·부분 응답. 모르는 상태를 "적용되지 않는다"고 단정하면 멀쩡한 프리셋 전건에 경고가 붙는다.
    const withoutEffective: Record<string, unknown> = { ...presetJson() };
    delete withoutEffective.effective;
    mock.onGet('/manage/presets').reply(200, ok([withoutEffective]));

    renderWithProviders(<PresetListPage />);
    await screen.findByTestId('preset-title-1');

    expect(screen.queryByTestId('preset-badge-1')).toBeNull();
  });

  // ── 저장 성공 안내 ──────────────────────────────────────────────────

  it('★라벨_없이_저장하면_오토라벨에서_빠진다는_사실을_안내한다', async () => {
    mock.onGet('/manage/presets').reply(200, ok([]));
    let sentBody: Record<string, unknown> = {};
    mock.onPost('/manage/presets').reply((config) => {
      sentBody = JSON.parse(config.data as string) as Record<string, unknown>;
      return [
        201,
        ok(presetJson({ labelCodes: [], labelCodeOptions: [], effective: false })),
      ];
    });

    const user = userEvent.setup();
    renderWithProviders(<PresetListPage />);
    await user.click(await screen.findByRole('button', { name: '프리셋 추가' }));
    await user.click(await screen.findByLabelText(/이벤트유형/));
    await user.click(await screen.findByRole('option', { name: '화재 (EV02000101)' }));
    await user.click(screen.getByText('만들기'));
    // 라벨이 비었으므로 저장 전에 확인을 받는다.
    await user.click(await screen.findByRole('button', { name: '라벨 없이 저장' }));

    // then ①: 라벨 목록을 비워서 보낸다(키 자체는 남는다 — 전체 교체 계약)
    await waitFor(() => expect(mock.history.post).toHaveLength(1));
    expect(sentBody).toMatchObject({ eventTypeCd: 'EV02000101', labelIds: [] });

    // then ②: 안내 문구는 사양 확정값이다
    await waitFor(() => expect(useUiStore.getState().toasts).toHaveLength(1));
    const toast = useUiStore.getState().toasts[0]!;
    expect(toast.variant).toBe('success');
    expect(toast.message).toBe(
      '프리셋을 저장했습니다. 이 이벤트유형은 오토라벨링 대상에서 빠집니다.',
    );
  });

  it('★담긴_라벨이_전부_미매핑이면_저장은_성공하고_적용되지_않는다는_사실을_함께_알린다', async () => {
    mock.onGet('/manage/presets').reply(200, ok([]));
    mock.onPost('/manage/presets').reply(
      201,
      ok(
        presetJson({
          labelCodes: ['차량'],
          labelCodeOptions: [
            {
              labelId: 20,
              code: null,
              labelName: '차량',
              labelType: 'POLYGON',
              linked: true,
              bboxEnabled: false,
              polygonEnabled: true,
              dtctTypeCd: null,
            },
          ],
          effective: false,
        }),
      ),
    );

    const user = userEvent.setup();
    renderWithProviders(<PresetListPage />);
    await user.click(await screen.findByRole('button', { name: '프리셋 추가' }));
    await user.click(await screen.findByRole('checkbox', { name: /차량/ }));
    await user.click(screen.getByLabelText(/이벤트유형/));
    await user.click(await screen.findByRole('option', { name: '화재 (EV02000101)' }));
    await user.click(screen.getByText('만들기'));

    // 라벨을 하나 골랐으므로 확인 창은 뜨지 않고 곧바로 저장된다.
    await waitFor(() => expect(useUiStore.getState().toasts).toHaveLength(1));
    const toast = useUiStore.getState().toasts[0]!;
    expect(toast.variant).toBe('success');
    // 저장은 <성공>이다 — 막지 않는다. 다만 적용되지 않는다는 사실을 함께 알린다.
    expect(toast.message).toContain('프리셋을 추가했습니다');
    expect(toast.message).toContain('오토라벨링에 적용되지 않습니다');
  });
});
