/**
 * 포털 내 작업 화면 — **말투와 안내 구성**의 회귀 가드(DS-002 리디자인으로 새로 생긴 계약).
 *
 * <h3>왜 문구를 시험으로 고정하나</h3>
 * 이 화면의 안내 두 곳이 **이용자가 아니라 서버 사정을 말하고 있었다** — 보존기간 안내가
 * *"저장 **행**과 파일이 함께 삭제됩니다 … 조회 시점 설정으로 계산되어 **응답에 실려 옵니다**"*
 * 였다. 이용자가 그 문장으로 할 수 있는 일이 없다. 이런 말투는 한 번 걷어내도 다음 사람이
 * 사양 문장을 그대로 옮겨 적으며 되돌아오므로, **금지어를 시험으로 못박는다.**
 * 사양(SCREEN-028)이 이 규정을 그 컴포넌트 note 로 갖고 있다.
 *
 * <h3>고정하는 것 / 고정하지 않는 것</h3>
 * 고정: 「구현 말투가 없다」·「빈 상태가 다음 걸음을 알린다」·「구역에 설명 한 줄이 있다」.
 * 고정하지 않음: 색·여백·글자 크기(채널이 산출 시점에 정한다).
 *
 * @design SCREEN-028
 * @design DS-002
 */

import { readFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, screen, within } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { PortalUserWork } from '@/features/portal/api';

import { PortalHomePage } from '../PortalHomePage';

const useUserWorksMock = vi.fn();
vi.mock('@/features/portal/hooks/useUserWorks', () => ({
  useUserWorks: (params: { page?: number; size?: number }) => useUserWorksMock(params),
}));

function work(over: Partial<PortalUserWork> = {}): PortalUserWork {
  return {
    rawSn: 10,
    assetSource: 'DATAMART',
    videoName: 'CLIP-10',
    labelCount: 3,
    lastSavedAt: '2026-06-01T10:00:00',
    entrySrcSn: 100,
    expiresOn: '2026-06-08',
    ...over,
  };
}

function mount(content: PortalUserWork[], isLoading = false) {
  useUserWorksMock.mockReturnValue({
    data: isLoading
      ? undefined
      : { content, totalElements: content.length, totalPages: 1, number: 0, size: 20 },
    isLoading,
    isError: false,
  });
  renderWithProviders(<PortalHomePage />);
}

afterEach(() => {
  useUserWorksMock.mockReset();
  cleanup();
});

describe('포털 내 작업 — 안내 말투', () => {
  /**
   * ★★ **구현 말투 금지.** 아래 낱말들은 전부 서버·저장소 사정이라 이용자가 할 일을 알려 주지
   *    않는다. 「저장 행」은 DB 행, 「응답에 실려」는 API 응답, 「조회 시점 설정」은 서버 설정이다.
   *    ⚠ 이 단언을 「문구가 바뀌었으니」로 지우지 말 것 — 지우는 순간 다음 사양 문장이 그대로
   *      화면에 옮겨 붙는다(그렇게 들어온 문구였다).
   */
  it('★안내에_서버_사정을_드러내는_말이_없다', () => {
    mount([work()]);
    const body = document.body.textContent ?? '';
    for (const banned of ['저장 행', '응답에 실려', '조회 시점 설정', 'API', 'null']) {
      expect(body, `구현 말투 «${banned}» 가 화면에 남아 있다`).not.toContain(banned);
    }
  });

  /** 알려야 할 것은 둘이다 — 「기한이 지나면 사라진다」와 「그 전에 내려받아 두라」. */
  it('보존기간_안내가_사라진다는_사실과_지금_할_일을_함께_말한다', () => {
    mount([work()]);
    const body = document.body.textContent ?? '';
    expect(body).toContain('보존기간이 지나면');
    // ⚠ 2026-09-16 — 부모 포털 문구에 맞춰 「두세요」 → 「주세요」. 뜻은 같고 말투만 통일했다.
    expect(body).toContain('내려받아 주세요');
  });

  /**
   * 목록만 놓인 화면은 «여기 실리는 것이 무엇인지» 를 스스로 말하지 못한다. 이 목록은 특히
   * **두 출처가 섞여** 있어(내가 올린 자산 · 내가 손댄 데이터마트 영상) 그 사실이 한 줄로
   * 나와 있지 않으면 이용자가 «왜 이 영상이 여기 있지» 를 행마다 되묻는다.
   */
  it('구역_머리에_무엇이_실리는_목록인지_한_줄이_있다', () => {
    mount([work()]);
    expect(
      screen.getByText('내가 올린 자산과, 내가 라벨이나 메타를 더한 영상이 여기에 모입니다.'),
    ).toBeInTheDocument();
  });
});

describe('포털 내 작업 — 표 짜임', () => {
  /**
   * ★★ 형제 화면(증강)에서 **실제로 깨졌던 자리다** — 열 폭을 브라우저 자동 배분에 맡겼더니
   *    가장 잘 접히는 칸이 최소 폭까지 눌려 한 글자씩 세로로 흘러내렸다. 이 화면도 같은 짜임이라
   *    같은 방식으로 막고, 그 수단을 시험으로 고정한다.
   */
  it('★열_폭을_표에_맡기지_않는다_고정_배분과_열_수가_맞는다', () => {
    mount([work()]);
    const table = screen.getByRole('table');
    // ⚠ 2026-09-16 — 고정 배분을 **Tailwind `table-fixed` 가 아니라 킷 CSS 가** 건다
    //   (`krds-react/dist/index.css` 의 `table{table-layout:fixed}`). 그래서 클래스 이름으로는
    //   확인할 수 없고, **우리가 책임지는 몫인 「폭을 명시했는가」**를 본다.
    const cols = table.querySelectorAll('colgroup > col');
    // 열 수와 칸 수가 맞는다 — 어긋나면 폭 배분이 통째로 밀린다
    expect(cols).toHaveLength(within(table).getAllByRole('columnheader').length);
    // 값이 짧은 칸은 폭을 못 박는다(남는 폭은 이름 칸이 먹으므로 첫 칸만 비운다)
    const widths = [...cols].map((c) => (c as HTMLElement).style.width);
    expect(widths[0]).toBe('');
    expect(widths.slice(1).every((w) => w !== '')).toBe(true);
  });

  /** 이름은 길이가 제각각이다 — 자르되 전문은 말풍선에 남긴다(둘은 짝이다). */
  it('★긴_영상_이름을_한_줄로_자르고_전문을_말풍선에_담는다', () => {
    const longName = '아주-긴-영상이름-'.repeat(6) + '.mp4';
    mount([work({ videoName: longName })]);
    const cell = within(screen.getByTestId('portal-work-row-10')).getByTitle(longName);
    // ⚠ 2026-09-16 — 자르는 일을 Tailwind `truncate` 가 아니라 **우리 포털 CSS 의 이름 칸 규칙**이
    //   맡는다. jsdom 은 CSS 를 적용하지 않으므로 ①칸이 그 클래스를 달았는지와 ②그 클래스가
    //   실제로 말줄임을 선언하는지를 **함께** 본다 — 한쪽만 보면 클래스만 남고 규칙이 사라져도 통과한다.
    expect(cell.className).toContain('klid-authoring-cell-name');
    const css = readFileSync(
      path.resolve(__dirname, '../../../styles/portal/authoring-layout.css'),
      'utf-8',
    );
    const rule = css.slice(css.indexOf('.klid-authoring-cell-name {'));
    const body = rule.slice(0, rule.indexOf('}'));
    expect(body).toContain('text-overflow: ellipsis');
    expect(body).toContain('white-space: nowrap');
  });

  /**
   * 목록의 일시는 행끼리 비교하는 값이라 자리폭이 흔들리면 세로로 훑을 수 없다. 로케일 표기
   * (`2026. 9. 1. 오후 2:22:00`)는 오전/오후·한 자리 월이 섞여 그 성질을 잃는다.
   * 포털 목록 셋(내 작업 · 내 업로드 · 증강)이 같은 모양을 쓴다.
   */
  it('저장_시각을_분까지_자리폭_고정으로_보인다', () => {
    mount([work({ lastSavedAt: '2026-09-01T14:22:33' })]);
    expect(screen.getByTestId('portal-work-saved-10')).toHaveTextContent('2026-09-01 14:22');
  });
});

describe('포털 내 작업 — 빈 상태', () => {
  /**
   * ★ 사양이 정한 문구(`저장한 작업이 없습니다.`)는 **그대로 둔다** — 구역 설명이 그 문장을
   *   인용하고 있어 여기서 바꾸면 사양과 화면이 갈린다. 대신 **다음 걸음 한 줄을 더한다**.
   */
  it('★사실_한_줄에서_끝내지_않고_목록이_채워지는_길을_알린다', () => {
    mount([]);
    const empty = screen.getByTestId('portal-work-empty');
    expect(empty).toHaveTextContent('저장한 작업이 없습니다.');
    expect(empty).toHaveTextContent(
      '포털에서 영상을 골라 라벨이나 메타를 저장하면 여기에 모입니다.',
    );
  });

  /**
   * ★★ **빈 상태에 조작을 두지 않는다.** 영상을 고르는 자리는 **이 배포본 바깥**(Host 화면)이라
   *    여기서 갈 수 있는 곳이 없다. 누를 수 없는 버튼을 두면 막다른 길이 하나 더 는다.
   *    ⚠ 형제 화면(증강)은 갈 곳이 안에 있어 조작을 둔다 — 두 화면의 차이는 의도다.
   */
  it('★빈_상태에_갈_수_없는_조작을_두지_않는다', () => {
    mount([]);
    const empty = screen.getByTestId('portal-work-empty');
    expect(within(empty).queryByRole('button')).toBeNull();
    expect(within(empty).queryByRole('link')).toBeNull();
  });
});

describe('포털 내 작업 — 로딩', () => {
  /**
   * 로딩과 빈 상태를 가른다 — 자리표시자만 두면 «비었다» 와 모양이 같고, 글만 두면 결과가
   * 붙는 순간 화면이 튄다. 둘을 함께 둔다.
   */
  it('불러오는_중임을_글로_알리고_자리표시자를_함께_그린다', () => {
    mount([], true);
    expect(screen.getByRole('status')).toHaveTextContent('저장한 작업을 불러오고 있습니다.');
    // 자리표시자 자체는 보조기술에서 빠져 있어야 한다(빈 항목이 낭독되지 않게).
    expect(document.querySelector('[aria-hidden]')).not.toBeNull();
  });
});
