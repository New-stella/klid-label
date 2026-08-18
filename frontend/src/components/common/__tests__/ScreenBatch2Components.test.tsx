import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { AttachmentList } from '../AttachmentList';
import { Avatar } from '../Avatar';
import { Badge } from '../Badge';
import { FieldCounter } from '../FieldCounter';
import { PresetLabelOverflowChip } from '../PresetLabelOverflowChip';
import { RoleBadge } from '../RoleBadge';

/**
 * 화면 설계 2차 배치(SCREEN-024/026/030/031/036/037)를 위해 신설한 공용 컴포넌트
 * 6종(UI-109~113·115)의 계약 가드.
 *
 * 화면 구현 전 단계라 레이아웃까지 검증하지 않는다 — 여기서 못박는 것은 카탈로그가 명시한
 * **접근성·의미 계약**이다(색상 단독 구분 금지, 아이콘 전용 버튼의 라벨, 읽기 전용성 등).
 * 그 계약이 화면 구현 중 조용히 사라지는 것을 막는 것이 목적이다.
 *
 * ⚠ **UI-114(라벨 마스터 체크박스 목록)는 여기에 없다** — 그 계약의 구현체는 유일한 호출부인
 *   `PresetEditModal`(UI-091) 안에 있고, 가드도 `features/preset` 쪽 모달 테스트가 담당한다.
 *   공용 컴포넌트로 따로 뽑아 두면 아무도 쓰지 않는 두 번째 구현이 되므로 여기서 되살리지 말 것.
 */

describe('Avatar (UI-109)', () => {
  it('이니셜_1자만_렌더한다', () => {
    render(<Avatar initial="홍길동" />);
    expect(screen.getByText('홍')).toBeInTheDocument();
    expect(screen.queryByText('홍길동')).not.toBeInTheDocument();
  });

  it('스크린리더에_노출되지_않는다_이름텍스트가_식별을_담당한다', () => {
    // 이름 텍스트를 항상 병기하는 것이 전제이므로(UI-109) 아바타가 이니셜을 또 읽으면 중복이다.
    const { container } = render(<Avatar initial="김" />);
    expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('RoleBadge (UI-110)', () => {
  it('역할별_한글_라벨을_표시한다', () => {
    const { unmount: u1 } = render(<RoleBadge role="REVIEWER" />);
    expect(screen.getByText('검수자')).toBeInTheDocument();
    u1();

    const { unmount: u2 } = render(<RoleBadge role="WORKER" />);
    expect(screen.getByText('작업자')).toBeInTheDocument();
    u2();

    render(<RoleBadge role="PORTAL_USER" />);
    expect(screen.getByText('포털')).toBeInTheDocument();
  });

  /*
   * 회귀 가드 — 포털 역할 표기는 화면 사양(SCREEN-024 역할 select 허용값 · SCREEN-003 역할 배지
   * 매핑)이 일관되게 쓰는 **'포털'** 이다. 구 기대값 `'포털 사용자'` → **폐기**(2026-08-18).
   *
   * 같은 코드베이스의 ForbiddenPage 는 이미 '포털' 이라 이 배지만 갈려 있었다 — 같은 역할이
   * 화면마다 다른 이름으로 읽히던 상태다. 부분 문자열이 아니라 **정확 일치**로 못박아
   * '포털 사용자'가 되살아나도 통과하지 않게 한다.
   */
  it('포털_역할_라벨은_포털이다_포털사용자로_되돌리지_않는다', () => {
    render(<RoleBadge role="PORTAL_USER" />);
    const badge = screen.getByText('포털');
    expect(badge.textContent).toBe('포털');
    expect(screen.queryByText('포털 사용자')).not.toBeInTheDocument();
  });

  it('role_이_null_이면_미배정으로_표시한다', () => {
    render(<RoleBadge role={null} />);
    expect(screen.getByText('미배정')).toBeInTheDocument();
  });

  it('미배정만_경고_아이콘을_병기한다_색상단독_구분_금지', () => {
    const { container, unmount } = render(<RoleBadge role={null} />);
    expect(container.querySelector('svg')).toBeInTheDocument();
    unmount();

    const { container: reviewer } = render(<RoleBadge role="REVIEWER" />);
    expect(reviewer.querySelector('svg')).toBeNull();
  });

  it('작업자는_secondary_700_텍스트를_쓴다_3단_스케일로는_생성되지_않던_클래스다', () => {
    render(<RoleBadge role="WORKER" />);
    expect(screen.getByText('작업자').className).toMatch(/text-secondary-700/);
  });
});

describe('Badge (UI-111)', () => {
  it('variant별_라벨을_항상_표시한다', () => {
    const { unmount } = render(<Badge variant="pinned" label="중요" />);
    expect(screen.getByText('중요')).toBeInTheDocument();
    unmount();

    render(<Badge variant="success" label="발행" />);
    expect(screen.getByText('발행')).toBeInTheDocument();
  });

  it('pinned_는_핀_아이콘을_기본_병기한다', () => {
    const { container, unmount } = render(<Badge variant="pinned" label="중요" />);
    expect(container.querySelector('svg')).toBeInTheDocument();
    unmount();

    const { container: neutral } = render(<Badge variant="neutral" label="작성중" />);
    expect(neutral.querySelector('svg')).toBeNull();
  });
});

describe('AttachmentList (UI-112)', () => {
  const items = [
    { id: 1, name: '라벨링가이드.pdf', size: '340.0 KB' },
    { id: 2, name: '체크리스트.xlsx', size: '12.5 KB' },
  ];

  it('행마다_파일명_크기_액션버튼을_렌더한다', () => {
    render(<AttachmentList items={items} action="download" onAction={() => {}} />);
    expect(screen.getByText('라벨링가이드.pdf')).toBeInTheDocument();
    expect(screen.getByText('340.0 KB')).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(2);
  });

  it('아이콘_전용_버튼의_aria_label_에_파일명이_들어간다', () => {
    // 목록에 버튼이 여러 개라 "다운로드"만으로는 어느 파일인지 구분되지 않는다.
    render(<AttachmentList items={items} action="download" onAction={() => {}} />);
    expect(screen.getByRole('button', { name: '라벨링가이드.pdf 다운로드' })).toBeInTheDocument();
  });

  it('action_delete_면_버튼_라벨이_삭제다', () => {
    render(<AttachmentList items={items} action="delete" onAction={() => {}} />);
    expect(screen.getByRole('button', { name: '체크리스트.xlsx 삭제' })).toBeInTheDocument();
  });

  it('downloading_행은_aria_busy_disabled_로_재클릭을_막는다', async () => {
    const onAction = vi.fn();
    render(
      <AttachmentList
        items={[{ id: 1, name: 'a.pdf', size: '1.0 KB', status: 'downloading' }]}
        action="download"
        onAction={onAction}
      />,
    );
    const button = screen.getByRole('button', { name: 'a.pdf 다운로드' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    await userEvent.click(button).catch(() => undefined);
    expect(onAction).not.toHaveBeenCalled();
  });

  it('첨부_0건이면_목록대신_안내를_렌더한다_배타적_분기', () => {
    render(<AttachmentList items={[]} action="download" />);
    expect(screen.getByText('첨부파일이 없습니다.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).toBeNull();
  });

  it('파일명이_잘려도_전체이름을_알_수_있게_title_을_단다', () => {
    render(<AttachmentList items={items} action="download" onAction={() => {}} />);
    expect(screen.getByText('라벨링가이드.pdf')).toHaveAttribute('title', '라벨링가이드.pdf');
  });

  it('onAction_클릭이_해당_항목을_전달한다', async () => {
    const onAction = vi.fn();
    render(<AttachmentList items={items} action="download" onAction={onAction} />);
    await userEvent.click(screen.getByRole('button', { name: '체크리스트.xlsx 다운로드' }));
    expect(onAction).toHaveBeenCalledWith(items[1], 1);
  });
});

describe('PresetLabelOverflowChip (UI-113)', () => {
  it('초과_개수를_플러스N_으로_표시한다', () => {
    render(<PresetLabelOverflowChip count={2} />);
    expect(screen.getByText('+2')).toBeInTheDocument();
  });

  it('0건이면_렌더하지_않는다_접힌게_없다는_사실을_잘못_알리지_않는다', () => {
    const { container } = render(<PresetLabelOverflowChip count={0} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('FieldCounter (UI-115)', () => {
  it('현재_최대_글자수를_표시한다', () => {
    render(<FieldCounter current={18} max={200} />);
    expect(screen.getByText('18/200')).toBeInTheDocument();
  });

  it('스크린리더에_노출되지_않는다_타이핑마다_읽히면_방해가_된다', () => {
    const { container } = render(<FieldCounter current={0} max={200} />);
    expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true');
  });

  it('한도_초과시_경고색으로_바꾸지_않는다_사양_미정_임계값을_지어내지_않는다', () => {
    render(<FieldCounter current={201} max={200} />);
    expect(screen.getByText('201/200').className).not.toMatch(/warning|danger/);
  });
});
