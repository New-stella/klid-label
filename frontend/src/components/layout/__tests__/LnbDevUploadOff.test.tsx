// dev 업로드 토글이 꺼진 산출물의 LNB — 「업로드」 그룹이 통째로 사라지지 않는다. [@design NAV-001]
//
// 왜 별도 파일인가: `isDevUploadEnabled()` 는 Lnb 모듈이 **평가될 때 한 번** 읽히고 그 결과가
//   모듈 스코프 MENU 에 반영된다. 같은 파일에서 토글을 바꿔 재평가하면 다른 케이스가 보는 MENU 가
//   회차에 따라 달라져 서로를 오염시킨다. 모듈 레지스트리를 초기화해 격리한다.
//
// 못 박는 것: 토글로 빠지는 것은 「파일 업로드」 하나뿐이고, 「산출물 가져오기」는 MENU 배열에
//   직접 들어 있어 그룹과 함께 남는다. 그룹이 사라지면 검수자가 산출물을 가져올 진입점을 잃는다.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, screen, within } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

vi.mock('@/lib/devUpload', () => ({ isDevUploadEnabled: () => false }));

describe('LNB — dev 업로드 토글 OFF', () => {
  beforeEach(() => {
    vi.resetModules();
    useAuthStore.setState({
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    useAuthStore.getState().clear();
    cleanup();
  });

  it('업로드_그룹에_산출물_가져오기만_남고_그룹은_사라지지_않는다', async () => {
    const { Lnb } = await import('@/components/layout/Lnb');
    renderWithProviders(<Lnb />);

    const uploadGroup = screen.getByText('업로드').parentElement?.parentElement;
    expect(uploadGroup).toBeTruthy();

    const labels = within(uploadGroup as HTMLElement)
      .getAllByRole('link')
      .map((a) => a.textContent);

    expect(labels).toEqual(['산출물 가져오기']);
    expect(screen.queryByRole('link', { name: '파일 업로드' })).toBeNull();
  });
});
