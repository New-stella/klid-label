import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { PresetListPage } from '@/pages/manage/PresetListPage';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

// BE 카테고리 옵션 — categoryKey→label 변환 소스 (useEventTypes).
const CATEGORIES = [
  { categoryKey: '010001', label: '침수(범람)', memberCodes: ['EV01000101'] },
  { categoryKey: '020002', label: '쓰러짐', memberCodes: ['EV02000201'] },
];

// Phase 4 — 프리셋 코드는 마스터 실시간 join 결과(labelId/labelName/labelType/linked)를 담는다.
// 프리셋은 categoryKey 를 eventTypeCd 로 저장한다 (Phase 4a/4b 확정).
const PRESETS = [
  {
    id: 1,
    name: '프리셋A',
    description: null,
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
    eventTypeCd: '020002',
    createdAt: '2026-06-01T00:00:00',
    updatedAt: '2026-06-01T00:00:00',
  },
  {
    id: 2,
    name: '프리셋B',
    description: null,
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
    eventTypeCd: null,
    createdAt: '2026-06-01T00:00:00',
    updatedAt: '2026-06-01T00:00:00',
  },
];

describe('PresetListPage', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/presets').reply(200, ok(PRESETS));
    mock.onGet('/event-types').reply(200, ok(CATEGORIES));
    // 회귀 가드 — 라벨 맵 엔드포인트는 더 이상 프리셋 표시에 쓰이지 않아야 한다.
    mock.onGet('/event-types/labels').reply(200, ok({ EV02000201: '쓰러짐(EVcode)' }));
  });

  afterEach(() => mock.restore());

  it('프리셋목록_categoryKey가_카테고리라벨로_표시된다', async () => {
    // given / when
    renderWithProviders(<PresetListPage />);

    // then — eventTypeCd="020002"(categoryKey) → "쓰러짐"(카테고리 label)
    const badge = await screen.findByTestId('preset-event-1');
    expect(badge).toHaveTextContent('쓰러짐');
    // EV-코드 맵 폴백 원문("020002")이 노출되면 안 된다.
    expect(badge).not.toHaveTextContent('020002');
  });

  it('미매핑_프리셋은_미매핑으로_표시된다', async () => {
    // given / when
    renderWithProviders(<PresetListPage />);

    // then
    const badge = await screen.findByTestId('preset-event-2');
    expect(badge).toHaveTextContent('미매핑');
  });

  it('연결된_라벨은_마스터_라벨명과_형태로_표시된다', async () => {
    // given / when
    renderWithProviders(<PresetListPage />);

    // then — labelName='사람' + 형태 배지 '바운딩박스'
    expect(await screen.findByText('사람')).toBeInTheDocument();
    expect(screen.getByText('바운딩박스')).toBeInTheDocument();
  });

  it('미연결_라벨은_미연결_배지로_구분된다', async () => {
    // given / when
    renderWithProviders(<PresetListPage />);

    // then — legacy 코드명 + '미연결' 배지
    expect(await screen.findByText('LEGACY_CAR')).toBeInTheDocument();
    expect(screen.getByText('미연결')).toBeInTheDocument();
  });

  // ── 사양 SCREEN-026 정합 회귀 가드 (REVIEWER 동선) ───────────────────

  describe('REVIEWER 카드 액션', () => {
    beforeEach(() => {
      useAuthStore.setState({
        claims: { sub: 'u', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
      });
    });
    afterEach(() => useAuthStore.getState().clear());

    it('복제_버튼이_clone_API를_호출한다', async () => {
      // given: API·훅(usePresetActions().clone)은 이전부터 있었으나 화면에 버튼이 없어
      // 기능 자체가 도달 불가였다 — 그 배선을 고정한다.
      mock.onPost('/manage/presets/1/clone').reply(200, ok(PRESETS[0]));
      renderWithProviders(<PresetListPage />);
      await screen.findByTestId('preset-event-1');

      // when
      await userEvent.click(screen.getByRole('button', { name: '프리셋A 복제' }));

      // then: 확인 단계 없이 즉시 서버 복제 요청이 나간다(사양: 클릭 즉시 요청)
      await waitFor(() => {
        expect(
          mock.history.post.some((c) => c.url === '/manage/presets/1/clone'),
        ).toBe(true);
      });
    });

    it('★카드_액션_3종에는_아이콘이_붙지_않는다_제목_폭_잠식_회귀_가드', async () => {
      // given: 확정 디자인(SCREEN-026 `.psm-card__actions`)은 **텍스트 전용 ghost 버튼 3개**다.
      //   아이콘을 붙이면 버튼마다 20px(아이콘 14 + gap 6)씩 액션 열이 넓어지고 그 폭은 전부
      //   제목이 든 1fr 열에서 빠져나가 제목이 `교통사고 기...` 로 잘렸다(실제 발생한 회귀).
      //   레이아웃 자체는 jsdom 이 계산하지 않으므로, **원인인 아이콘 유무**를 고정한다.
      renderWithProviders(<PresetListPage />);
      await screen.findByTestId('preset-event-1');

      // when / then
      for (const name of ['수정', '프리셋A 복제', '삭제']) {
        const btn = screen.getAllByRole('button', { name })[0];
        expect(btn.querySelector('svg'), `"${name}" 버튼에 아이콘이 붙었다`).toBeNull();
        // 텍스트 라벨 자체는 남아 있어야 한다(아이콘 전용 버튼으로 바꾸는 것도 사양 위반).
        expect(btn.textContent?.trim()).not.toBe('');
      }
    });

    it('라벨_칩은_앞_6개만_보이고_나머지는_펼칠_수_있다', async () => {
      // given: 라벨 8종짜리 프리셋
      const many = {
        ...PRESETS[0],
        id: 3,
        name: '프리셋C',
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
      // given: 기본 목록(프리셋A 라벨 1종)
      renderWithProviders(<PresetListPage />);
      await screen.findByTestId('preset-event-1');

      // then
      expect(screen.queryByTestId('preset-chip-toggle-1')).toBeNull();
    });
  });

  // ── 사양 SCREEN-026 그리드 임계 회귀 가드 ───────────────────────────────
  //
  // ★위 '아이콘 제거' 가드만으로는 제목 절단이 해소되지 않았다(브라우저 실측: 1440 에서
  //   9/9 전건 말줄임). 남은 원인은 **3열 임계를 뷰포트로 잰 것**이다 — 확정 디자인의
  //   `@media (max-width: 1280px) { 2열 }` 은 셸이 없는 캔버스 기준이라 뷰포트=카드 영역인데,
  //   실제 화면은 고정 LNB(240) + 좌우 패딩(48)을 뺀 나머지가 카드 영역이다. 그래서 구
  //   `xl:`(뷰포트 1280)은 카드 영역이 992px 인데도 3열을 만들어 카드가 368px 로 눌렸다.
  //
  // ⚠ jsdom 은 미디어쿼리를 적용하지 않아 실제 열 수는 여기서 판정할 수 없다(브라우저 실측이
  //   짝이다). 이 가드가 고정하는 것은 **임계값을 카드 영역 기준으로 환산했다는 사실**이다.
  it('★3열_임계는_뷰포트가_아니라_카드_영역_기준이다_구_xl_임계_폐기', async () => {
    renderWithProviders(<PresetListPage />);
    await screen.findByTestId('preset-event-1');

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
   * 구 구현은 10건이었다. 그리드가 xl 에서 3열이라 9면 마지막 줄이 정확히 차지만 10이면 카드
   * 하나만 남은 줄이 생긴다. 경계(10번째 카드)를 직접 본다 — 총 페이지 수만 세면 9든 10이든
   * 같은 값이 나오는 모수가 있어 가드가 헐거워진다.
   */
  it('한_페이지에_9건까지만_보이고_10번째는_다음_페이지에_있다', async () => {
    // given: 프리셋 10개 (경계 + 1)
    const ten = Array.from({ length: 10 }, (_, i) => ({
      ...PRESETS[0],
      id: 100 + i,
      name: `프리셋${String(i).padStart(2, '0')}`,
    }));
    mock.onGet('/manage/presets').reply(200, ok(ten));

    // when
    renderWithProviders(<PresetListPage />);
    await screen.findByText('프리셋00');

    // then ① 1페이지에는 앞 9건만 — 10번째(프리셋09)는 없다
    expect(screen.getByText('프리셋08')).toBeInTheDocument();
    expect(screen.queryByText('프리셋09')).toBeNull();
    expect(screen.getAllByTestId(/^preset-event-/)).toHaveLength(9);

    // then ② 나머지 1건은 2페이지에 있다
    await userEvent.click(screen.getByRole('button', { name: '2페이지' }));
    expect(await screen.findByText('프리셋09')).toBeInTheDocument();
    expect(screen.queryByText('프리셋00')).toBeNull();
  });
});
