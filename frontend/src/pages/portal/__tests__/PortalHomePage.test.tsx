import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '@/test/renderWithProviders';

import { PortalHomePage } from '../PortalHomePage';

// GET /portal/uploads 엔드포인트는 BE 에서 삭제됨(ADR-013) — 홈은 더 이상 호출하지 않는다.
// 데이터마트 영상 목록 전용 엔드포인트 도입 전까지 홈은 최소 상태(KPI 0·시작하기 비활성)다.
describe('PortalHomePage', () => {
  it('포털_홈_라벨링_카드_표시_업로드_오토라벨_미노출', () => {
    renderWithProviders(<PortalHomePage />);

    // ADR-013: 라벨링 카드만 노출, 업로드·오토라벨 UI 미제공
    expect(screen.getByRole('heading', { name: '라벨링' })).toBeInTheDocument();
    expect(screen.queryByText(/업로드/)).toBeNull();
    expect(screen.queryByRole('button', { name: /오토라벨/ })).toBeNull();
    expect(screen.queryByTestId('upload-dropzone')).toBeNull();
  });

  it('목록_엔드포인트_미도입_시작하기_비활성', () => {
    renderWithProviders(<PortalHomePage />);

    // 데이터마트 목록 조회 엔드포인트가 없어 진입 대상 영상이 없으므로 비활성
    expect(screen.getByRole('button', { name: /시작하기/ })).toBeDisabled();
  });

  it('다운로드_UI_미제공_V1_5_포털_자체_책임', () => {
    const { container } = renderWithProviders(<PortalHomePage />);

    expect(screen.getByRole('heading', { name: '라벨링' })).toBeInTheDocument();
    // 다운로드 버튼/카드/배지 미렌더
    expect(container.querySelector('[aria-label*="다운로드"]')).toBeNull();
    expect(container.querySelector('[data-testid*="download"]')).toBeNull();
  });

  it('KPI_2_표시_영상수_라벨링수', () => {
    renderWithProviders(<PortalHomePage />);

    expect(screen.getByText(/영상 수/)).toBeInTheDocument();
    expect(screen.getByText(/라벨링 완료/)).toBeInTheDocument();
  });
});
