/**
 * 포털 전용 UI 계층 2차분 — **배지 · 빈 상태 · 구역 머리 · 목록 자리표시자**의 계약 가드.
 *
 * <h3>이 파일이 고정하는 것</h3>
 * <ul>
 *   <li>이 넷은 **포털 화면만** 쓴다 — 관제 공통 부품을 건드리지 않고 같은 일을 하기 위해 있다.
 *       (사용자 확정 구속: 관제향 화면·컴포넌트 불변)</li>
 *   <li><b>빈 상태는 사실만 말하지 않는다</b> — 다음에 무엇을 하면 되는지가 함께 나온다.
 *       이 단언이 없으면 「제목만 있는 빈 상태」로 조용히 되돌아간다.</li>
 *   <li><b>자리표시자는 보조기술에서 빠진다</b> — 읽을 것이 없는 회색 상자가 낭독되면 안 된다.</li>
 *   <li><b>구역 머리는 `h1` 을 만들지 않는다</b> — Host 가 화면 제목을 이미 갖는다(SHELL-002).</li>
 * </ul>
 *
 * 모양(색·여백)은 단언하지 않는다 — 채널이 산출 시점에 값을 정하므로 클래스로 고정하면
 * 디자인 시스템을 바꿀 때마다 이 파일이 함께 깨진다.
 *
 * @design DS-002
 * @design SCREEN-028
 * @design SCREEN-044
 */

import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { Inbox } from 'lucide-react';

import { PortalBadge } from '../PortalBadge';
import { PortalEmptyState } from '../PortalEmptyState';
import { PortalListSkeleton } from '../PortalListSkeleton';
import { PortalSectionHead } from '../PortalSectionHead';

describe('PortalBadge', () => {
  it('라벨_글자를_그대로_보인다', () => {
    render(<PortalBadge>내 업로드</PortalBadge>);
    expect(screen.getByText('내 업로드')).toBeInTheDocument();
  });

  /*
   * ★색이 뜻을 혼자 나르지 않는다(KRDS). 톤이 무엇이든 **글자는 그대로 남아야** 하고,
   *   장식 아이콘을 배지 안에 넣지 않는다 — 라벨이 이미 말한 것을 되풀이하는 자리였다.
   */
  it('★톤이_달라져도_글자가_사라지지_않는다', () => {
    const { rerender } = render(<PortalBadge tone="danger">실패</PortalBadge>);
    expect(screen.getByText('실패')).toBeInTheDocument();
    rerender(<PortalBadge tone="success">결과 도착</PortalBadge>);
    expect(screen.getByText('결과 도착')).toBeInTheDocument();
  });

  it('행_안에서_집을_수_있게_시험_식별자를_받는다', () => {
    render(<PortalBadge data-testid="src-1">데이터마트</PortalBadge>);
    expect(screen.getByTestId('src-1')).toHaveTextContent('데이터마트');
  });
});

describe('PortalEmptyState', () => {
  /*
   * ★★ 이 부품의 존재 이유가 이 단언이다. 사실 한 줄만 있는 빈 상태는 이용자를 그 자리에
   *    세워 둔다 — 특히 포털 채널은 영상을 고르는 목록이 **이 배포본 바깥**에 있어서, 설명이
   *    없으면 목록을 채울 방법이 화면 어디에도 드러나지 않는다.
   */
  it('★사실만_말하지_않고_다음에_할_일을_함께_보인다', () => {
    render(
      <PortalEmptyState
        icon={Inbox}
        title="저장한 작업이 없습니다."
        description="포털에서 영상을 골라 라벨이나 메타를 저장하면 여기에 모입니다."
      />,
    );
    expect(screen.getByText('저장한 작업이 없습니다.')).toBeInTheDocument();
    expect(
      screen.getByText('포털에서 영상을 골라 라벨이나 메타를 저장하면 여기에 모입니다.'),
    ).toBeInTheDocument();
  });

  /* 조회가 끝나고 0건이라는 것은 방금 일어난 일이다 — 다만 끼어들 만큼 급하지는 않다. */
  it('빈_상태를_status_로_알린다_alert_가_아니다', () => {
    render(<PortalEmptyState icon={Inbox} title="비었습니다" />);
    const box = screen.getByRole('status');
    expect(box).toHaveTextContent('비었습니다');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  /*
   * 갈 곳이 이 배포본 안에 있을 때만 조작을 둔다. 부품은 그 판정을 하지 않고 **받은 대로만**
   * 그린다 — 판정은 화면이 한다(내 작업: 갈 곳이 바깥이라 없음 / 증강: 내 업로드로 감).
   */
  it('조작은_넘겼을_때만_그린다', () => {
    const { rerender } = render(<PortalEmptyState icon={Inbox} title="비었습니다" />);
    expect(within(screen.getByRole('status')).queryByRole('button')).toBeNull();

    rerender(
      <PortalEmptyState icon={Inbox} title="비었습니다" action={<button type="button">가기</button>} />,
    );
    expect(screen.getByRole('button', { name: '가기' })).toBeInTheDocument();
  });
});

describe('PortalListSkeleton', () => {
  /*
   * ★자리표시자는 **읽을 내용이 없다.** 보조기술에 노출하면 빈 항목이 N 개 낭독된다.
   *   「불러오는 중」이라는 사실은 호출부가 `role="status"` 문구로 따로 알린다.
   */
  it('★보조기술에서_빠진다', () => {
    const { container } = render(<PortalListSkeleton rows={3} />);
    const root = container.firstElementChild;
    expect(root).toHaveAttribute('aria-hidden');
    expect(root?.children).toHaveLength(3);
  });

  it('줄_수를_지정하지_않으면_세_줄이다', () => {
    const { container } = render(<PortalListSkeleton />);
    expect(container.firstElementChild?.children).toHaveLength(3);
  });
});

describe('PortalSectionHead', () => {
  /*
   * ★★ **`h1` 을 만들지 않는다.** 이 화면들은 Host 화면 안에서 실행되고, 서비스 이름은 Host
   *    머리 영역이·화면 이름은 이동 탭의 활성 항목이 이미 말한다. 여기서 `h1` 을 만들면 한
   *    페이지에 제목이 둘이 되고 이동 탭과 제목이 서로를 흉내 내는 것으로 읽힌다.
   */
  it('★구역_제목은_h2_이고_h1_을_만들지_않는다', () => {
    render(<PortalSectionHead id="sec" title="내 저장 작업" />);
    expect(screen.getByRole('heading', { level: 2, name: '내 저장 작업' })).toHaveAttribute(
      'id',
      'sec',
    );
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull();
  });

  it('설명과_건수와_조작을_각자_자리에_그린다', () => {
    render(
      <PortalSectionHead
        id="sec"
        title="증강 요청 현황"
        lead="증강은 시간이 걸립니다."
        count="12건"
        action={<button type="button">영상 고르기</button>}
      />,
    );
    expect(screen.getByText('증강은 시간이 걸립니다.')).toBeInTheDocument();
    expect(screen.getByText('12건')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '영상 고르기' })).toBeInTheDocument();
  });

  /* 선택 항목을 넘기지 않으면 그 자리 자체가 없다 — 빈 칸이 남아 제목이 떠 보이지 않게. */
  it('설명과_건수는_넘기지_않으면_그리지_않는다', () => {
    const { container } = render(<PortalSectionHead id="sec" title="제목" />);
    expect(container.querySelectorAll('p')).toHaveLength(0);
    expect(container.querySelectorAll('span')).toHaveLength(0);
  });
});
