import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi, beforeEach } from 'vitest';

import { EventTypeManagePage } from '@/pages/manage/EventTypeManagePage';

import * as adminApi from '../adminApi';

vi.mock('../adminApi');

/**
 * 이벤트유형 관리 화면 — 관제 인입 자동등록분의 표시명·수집여부 정정.
 *
 * 고정하는 계약:
 *  - 표시명은 BE 가 내려준 dsplNm 을 그대로 쓴다(FE 가 폴백을 재계산하지 않는다).
 *  - 수정 요청 본문에는 허용 필드만 담는다(관제 칸 evntNm 을 보내면 다음 인입이 덮어쓴다).
 *  - 표시명을 비워 저장하면 해제(빈 문자열)로 전송된다 — 관제값 복귀 경로.
 */
describe('이벤트유형 관리 화면', () => {
  const rows: adminApi.EventTypeAdminItem[] = [
    {
      evntTypeCd: 'EV01000101',
      dsplNm: '침수(범람)',
      optrIndctNm: null,
      evntNm: null,
      evntCtgryNm: '침수(범람)',
      evntClsfCd: '01',
      evntCtgryCd: '0001',
      clctYn: 'Y',
    },
  ];

  function renderPage() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <QueryClientProvider client={client}>
        <EventTypeManagePage />
      </QueryClientProvider>,
    );
  }

  beforeEach(() => {
    vi.mocked(adminApi.getEventTypeAdminList).mockResolvedValue(rows);
    vi.mocked(adminApi.updateEventTypeAdmin).mockResolvedValue({ ...rows[0] });
  });

  it('유형별_고유_이름이_없으면_카테고리명이_표시명으로_보인다', async () => {
    renderPage();

    // BE 가 폴백을 해석해 dsplNm 으로 내려준다 — 중복 이름은 결함이 아니라 정상 상태다.
    //   표시명 칸과 카테고리 칸에 같은 값이 보이는 것이 <폴백이 동작했다>는 증거다.
    expect(await screen.findAllByText('침수(범람)')).toHaveLength(2);
    expect(screen.getByText('EV01000101')).toBeInTheDocument();
    // 관제 원본은 아직 없다(관제 마스터에 유형별 이름이 없었다)
    expect(screen.getByText('-')).toBeInTheDocument();
  });

  it('표시명_저장은_허용필드만_전송한다', async () => {
    renderPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: '표시명 수정' }));
    await user.type(screen.getByLabelText('EV01000101 표시명'), '수위상승');
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(adminApi.updateEventTypeAdmin).toHaveBeenCalledWith('EV01000101', {
        optrIndctNm: '수위상승',
      }),
    );
  });

  it('수집여부_토글은_반대값을_전송한다', async () => {
    renderPage();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'EV01000101 수집여부 토글' }));

    await waitFor(() =>
      expect(adminApi.updateEventTypeAdmin).toHaveBeenCalledWith('EV01000101', { clctYn: 'N' }),
    );
  });
});
