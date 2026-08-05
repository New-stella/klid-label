import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';

import { VersionList } from '../components/VersionList';
import type { Version } from '../types';

const versions: Version[] = [
  {
    commitSha: 'aaa111',
    shortHash: 'aaa111',
    authorName: '홍길동',
    authorNo: '2001',
    message: '라벨 수정',
    committedAt: '2026-05-07T10:00:00Z',
    isCurrent: true,
  },
  {
    commitSha: 'bbb222',
    shortHash: 'bbb222',
    authorName: '김검수',
    authorNo: '1001',
    message: '초기 라벨',
    committedAt: '2026-05-06T10:00:00Z',
    isCurrent: false,
  },
];

describe('VersionList', () => {
  it('버전_목록_커밋_시간순_정렬', () => {
    renderWithProviders(<VersionList versions={versions} />);
    const items = screen.getAllByTestId('version-item');
    expect(items).toHaveLength(2);
    // 첫번째: aaa111 (최신)
    expect(items[0].textContent).toContain('aaa111');
    expect(items[1].textContent).toContain('bbb222');
  });

  it('현재_커밋에_현재_뱃지_노출', () => {
    renderWithProviders(<VersionList versions={versions} />);
    const items = screen.getAllByTestId('version-item');
    // 현재 커밋(aaa111)에는 "현재" 뱃지 노출
    expect(items[0].textContent).toContain('현재');
    // 이전 커밋에는 미노출
    expect(items[1].textContent).not.toContain('현재');
  });

  it('작성자는_사번이_아니라_이름으로_표시된다', () => {
    renderWithProviders(<VersionList versions={versions} />);
    const items = screen.getAllByTestId('version-item');
    expect(items[0].textContent).toContain('홍길동');
    // 이름 자리에 사번이 찍히던 결함의 회귀 가드
    expect(items[0].textContent).not.toContain('2001');
  });

  it('이름_해석에_실패하면_사번으로_폴백해_빈칸이_되지_않는다', () => {
    // BE 가 이름을 못 찾으면 authorName=null 로 내려온다(퇴사·비숫자 사번 등)
    const unresolved: Version[] = [{ ...versions[0], authorName: null }];
    renderWithProviders(<VersionList versions={unresolved} />);
    expect(screen.getAllByTestId('version-item')[0].textContent).toContain('2001');
  });

  it('빈_목록일때_EmptyState_표시', () => {
    renderWithProviders(<VersionList versions={[]} />);
    expect(screen.getByText('버전 이력이 없습니다')).toBeInTheDocument();
  });
});
