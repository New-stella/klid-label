// dev 업로드 토글이 꺼진 산출물의 LNB — 「관리자」 그룹이 통째로 사라지지 않는다. [@design NAV-001]
//
// 왜 별도 파일인가: `isDevUploadEnabled()` 는 Lnb 모듈이 **평가될 때 한 번** 읽히고 그 결과가
//   모듈 스코프 MENU 에 반영된다. 같은 파일에서 토글을 바꿔 재평가하면 다른 케이스가 보는 MENU 가
//   회차에 따라 달라져 서로를 오염시킨다. 모듈 레지스트리를 초기화해 격리한다.
//
// 못 박는 것: 토글로 빠지는 것은 「파일 업로드」 하나뿐이고, 나머지 관리자 항목 네 개는 MENU
//   배열에 직접 들어 있어 그룹과 함께 남는다. 그룹이 사라지면 관리자가 사용자 관리·연동 주소·
//   산출물 가져오기·패스워드 교체로 갈 진입점을 통째로 잃는다.
//   「산출물 가져오기」는 이 토글과 무관하므로 함께 확인한다 — 두 화면이 같은 그룹에 있지만
//   노출 조건이 다르다는 것이 요점이다(하나는 토글, 하나는 역할).
//   ⚠ 구 단언 폐기 — *"업로드 그룹은 이 토글과 무관하게 그대로다"*. 그 그룹 자체가 없어졌다.
//
// ⚠ 「관리자」 그룹은 **관리자 역할에게만** 보이므로 이 파일의 배우는 관리자다. 검수자로 두면
//   그룹이 애초에 없어 「토글로 사라지지 않는다」를 확인할 수 없다(축이 뒤바뀐 초록이 된다).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, screen, within } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';

vi.mock('@/lib/devUpload', () => ({ isDevUploadEnabled: () => false }));

/**
 * ★인증 상태를 **동적으로 가져온 스토어**에 심는다.
 *
 * `vi.resetModules()` 가 모듈 레지스트리를 비우므로, 파일 상단에서 정적 import 한 스토어와
 * 아래에서 동적 import 하는 `Lnb` 가 보는 스토어는 **서로 다른 인스턴스**가 된다. 정적 쪽에
 * 역할을 심으면 `Lnb` 는 「역할 없음」으로 렌더한다 — 그 상태가 전체 메뉴를 보여주던 동안에는
 * 이 오배선이 초록에 가려져 있었다(메뉴 노출이 fail-closed 로 바뀌자 드러났다).
 */
async function renderLnbAs(role: 'ADMIN' | 'REVIEWER' | 'WORKER') {
  const { useAuthStore } = await import('@/stores/useAuthStore');
  useAuthStore.setState({
    token: 'tok',
    claims: { sub: '1', role, channel: 'INTERNAL', exp: 9999999999 },
  });
  const { Lnb } = await import('@/components/layout/Lnb');
  return renderWithProviders(<Lnb />);
}

describe('LNB — dev 업로드 토글 OFF', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    cleanup();
  });

  it('관리자_그룹에서_파일_업로드만_빠지고_그룹은_사라지지_않는다', async () => {
    await renderLnbAs('ADMIN');

    const adminGroup = screen.getByText('관리자').parentElement?.parentElement;
    expect(adminGroup).toBeTruthy();

    const labels = within(adminGroup as HTMLElement)
      .getAllByRole('link')
      .map((a) => a.textContent);

    expect(labels).toEqual([
      '사용자 관리',
      '연동 서버 주소',
      '산출물 가져오기',
      '패스워드 교체',
      '위험 액션',
    ]);
    expect(screen.queryByRole('link', { name: '파일 업로드' })).toBeNull();
  });

  it('산출물_가져오기는_이_토글과_무관하게_관리자_그룹에_남는다', async () => {
    // MENU 배열에 직접 들어 있어 dev 업로드 토글의 영향을 받지 않는다. 같은 그룹에 있어도
    // 노출 조건이 다르다 — 파일 업로드만 토글로 빠진다.
    await renderLnbAs('ADMIN');

    expect(screen.getByRole('link', { name: '산출물 가져오기' })).toHaveAttribute(
      'href',
      '/admin/imports',
    );
    // 「업로드」 그룹은 없어졌다 — 토글과 무관하게 어느 쪽 산출물에서도 나타나지 않는다.
    expect(screen.queryByText('업로드')).toBeNull();
  });
});
