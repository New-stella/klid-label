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
});
