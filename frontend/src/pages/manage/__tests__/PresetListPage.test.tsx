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
