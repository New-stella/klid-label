// 키포인트 배치 가이드(인체 다이어그램) 컴포넌트 단위 테스트.
// 좌측 '라벨' 패널 내부에 렌더되는 in-flow 표시 컴포넌트 — konva·providers 불필요, 기본 RTL render 사용.
// (과거 캔버스 절대위치 오버레이는 좁은 폭에서 잘려 폐기됨 — 위치 assert 대신 표시/미표시·내용 중심)

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { KeypointGuide } from '../KeypointGuide';

describe('KeypointGuide', () => {
  it('KeypointGuide_좌측라벨패널_배치중_표시', () => {
    // given / when — 배치 진행 중(placingIndex 유효)
    const { container } = render(<KeypointGuide placingIndex={0} />);

    // then — 다이어그램(17원·19선) + 한글명 + N/17 + 인물기준 캡션이 패널 내 블록으로 표시
    expect(screen.getByRole('group', { name: '스켈레톤 배치 가이드' })).toBeInTheDocument();
    expect(container.querySelectorAll('[data-testid^="kpt-node-"]')).toHaveLength(17);
    expect(container.querySelectorAll('[data-testid^="kpt-edge-"]')).toHaveLength(19);
    expect(screen.getByTestId('kpt-guide-caption')).toHaveTextContent('1/17');
    expect(screen.getByTestId('kpt-guide-orientation')).toHaveTextContent('인물 기준');
  });

  it('placingIndex_N일때_해당관절_하이라이트_및_한글명_표시', () => {
    // given / when — 5번(왼쪽 어깨) 배치 중
    render(<KeypointGuide placingIndex={5} />);

    // then — 5번 노드가 활성(하이라이트) 표시
    const activeNode = screen.getByTestId('kpt-node-5');
    expect(activeNode).toHaveAttribute('data-active', 'true');
    // 다른 노드는 활성 아님
    expect(screen.getByTestId('kpt-node-0')).toHaveAttribute('data-active', 'false');
    // 한글 관절명 표시
    expect(screen.getByTestId('kpt-guide-caption')).toHaveTextContent('왼쪽 어깨');
  });

  it('진행률_N슬래시17_표시', () => {
    // given / when — 첫 관절(index 0) 배치 중 → 1/17
    const { rerender } = render(<KeypointGuide placingIndex={0} />);
    expect(screen.getByTestId('kpt-guide-caption')).toHaveTextContent('1/17');

    // 마지막 관절(index 16) 배치 중 → 17/17
    rerender(<KeypointGuide placingIndex={16} />);
    expect(screen.getByTestId('kpt-guide-caption')).toHaveTextContent('17/17');
  });

  it('관절_17원_스켈레톤_19선_렌더', () => {
    // given / when
    const { container } = render(<KeypointGuide placingIndex={0} />);

    // then — 17개 관절 원 + 19개 스켈레톤 선
    expect(container.querySelectorAll('[data-testid^="kpt-node-"]')).toHaveLength(17);
    expect(container.querySelectorAll('[data-testid^="kpt-edge-"]')).toHaveLength(19);
  });

  it('KeypointGuide_placingIndex_null이면_미표시', () => {
    // given / when — 배치 미진행
    const { container } = render(<KeypointGuide placingIndex={null} />);

    // then — 아무것도 렌더하지 않음
    expect(container.firstChild).toBeNull();
  });

  it('17점완료_범위초과_placingIndex이면_미표시', () => {
    // given / when — 17점 완료(범위 밖)
    const { container } = render(<KeypointGuide placingIndex={17} />);

    // then — 숨김
    expect(container.firstChild).toBeNull();
  });

  it('한글명_텍스트바인딩_XSS안전_스크립트요소_미생성', () => {
    // given / when — 하드코딩 상수만 표시(사용자 입력 없음)
    render(<KeypointGuide placingIndex={0} />);

    // then — script 요소가 생성되지 않음(dangerouslySetInnerHTML 미사용)
    expect(document.querySelector('script')).toBeNull();
  });

  it('인물기준_좌우_캡션_표시', () => {
    // given / when — 배치 중 인물 기준 안내 캡션 노출
    render(<KeypointGuide placingIndex={0} />);

    // then — '인물 기준' 안내 문구가 표시된다(COCO 미러 유지 하 좌우 오해 방지)
    expect(screen.getByTestId('kpt-guide-orientation')).toHaveTextContent('인물 기준');
  });

  it('인패널_블록_오버레이잔재_제거', () => {
    // given / when — 좌측 패널 내부 in-flow 블록으로 렌더
    render(<KeypointGuide placingIndex={0} />);

    // then — 절대위치·오버레이·pointer-events 잔재 없음, 패널 폭(w-full) 사용
    const group = screen.getByRole('group', { name: '스켈레톤 배치 가이드' });
    expect(group.className).toContain('w-full');
    expect(group.className).not.toContain('absolute');
    expect(group.className).not.toContain('pointer-events-none');
    expect(group.className).not.toMatch(/max-w-\[/);
  });
});
