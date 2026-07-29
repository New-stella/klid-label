// DeidentReportButton — 라벨러 비식별 누락 신고 UI 컴포넌트 테스트.
//
// 검증 범위:
//  - 버튼 렌더 및 클릭 시 모달 오픈
//  - 사유 입력 후 제출 → POST /labels/{srcSn}/deident-report 호출
//  - 빈 사유 / 1000자 초과 시 검증 에러
//  - 409 응답 시 잠금 안내 표시
//  - 403 응답 시 권한 없음 안내 표시
//  - 성공 시 모달 닫고 onSuccess 콜백 호출
//  - disabled prop 시 버튼 비활성

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { DeidentReportButton } from '../components/DeidentReportButton';

describe('DeidentReportButton', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('버튼_렌더_확인', () => {
    renderWithProviders(<DeidentReportButton srcSn={123} />);
    expect(
      screen.getByRole('button', { name: /비식별 누락 신고/ }),
    ).toBeInTheDocument();
  });

  it('disabled_prop_true_시_버튼_비활성', () => {
    renderWithProviders(<DeidentReportButton srcSn={123} disabled />);
    const btn = screen.getByRole('button', { name: /비식별 누락 신고/ });
    expect(btn).toBeDisabled();
  });

  it('버튼_클릭시_모달_오픈', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeidentReportButton srcSn={123} />);

    await user.click(screen.getByRole('button', { name: /비식별 누락 신고/ }));
    expect(
      screen.getByRole('dialog', { name: /비식별 누락 신고/ }),
    ).toBeInTheDocument();
  });

  it('사유_입력_후_제출시_API_호출_+_onSuccess_콜백', async () => {
    const user = userEvent.setup();
    const onSuccess = vi.fn();
    mock.onPost('/labels/123/deident-report').reply((config) => {
      const body = JSON.parse(config.data ?? '{}');
      expect(body).toEqual({ reason: '얼굴 미블러' });
      return [201, { success: true, data: 123, message: null, errorCode: null }];
    });

    renderWithProviders(<DeidentReportButton srcSn={123} onSuccess={onSuccess} />);

    await user.click(screen.getByRole('button', { name: /비식별 누락 신고/ }));
    const textarea = await screen.findByLabelText(/신고 사유/);
    await user.type(textarea, '얼굴 미블러');

    const submitBtn = screen.getByRole('button', { name: /신고하기/ });
    await user.click(submitBtn);

    await waitFor(() => expect(onSuccess).toHaveBeenCalledTimes(1));
    // 모달 닫힘
    await waitFor(() =>
      expect(
        screen.queryByRole('dialog', { name: /비식별 누락 신고/ }),
      ).not.toBeInTheDocument(),
    );
  });

  it('빈_사유_제출_불가_검증_에러_표시', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeidentReportButton srcSn={123} />);

    await user.click(screen.getByRole('button', { name: /비식별 누락 신고/ }));
    const submitBtn = screen.getByRole('button', { name: /신고하기/ });
    // 빈 상태 — disabled 또는 클릭 후 에러 메시지 표출
    if (submitBtn.hasAttribute('disabled')) {
      expect(submitBtn).toBeDisabled();
    } else {
      await user.click(submitBtn);
      await waitFor(() =>
        expect(screen.getByRole('alert')).toHaveTextContent(/입력|필수/),
      );
    }
    // API 호출 없음
    expect(mock.history.post).toHaveLength(0);
  });

  it('사유_1000자_초과시_검증_에러', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DeidentReportButton srcSn={123} />);

    await user.click(screen.getByRole('button', { name: /비식별 누락 신고/ }));
    const textarea = await screen.findByLabelText(/신고 사유/);
    // 1001자 입력 — userEvent.type 은 매우 느림 → fireEvent 로 직접 세팅
    const longText = 'a'.repeat(1001);
    // textarea 에 직접 value 세팅
    await user.click(textarea);
    // paste 로 한 번에 입력 (느린 typing 방지)
    await user.paste(longText);

    const submitBtn = screen.getByRole('button', { name: /신고하기/ });
    if (!submitBtn.hasAttribute('disabled')) {
      await user.click(submitBtn);
    }
    await waitFor(() =>
      expect(screen.queryByRole('alert')).toBeTruthy(),
    );
    expect(mock.history.post).toHaveLength(0);
  });

  it('409_응답시_이미_잠금_안내_표시', async () => {
    const user = userEvent.setup();
    mock.onPost('/labels/123/deident-report').reply(409, {
      success: false,
      data: null,
      message: '이미 잠금',
      errorCode: 'LOCKED_FOR_REDEIDENT',
    });

    renderWithProviders(<DeidentReportButton srcSn={123} />);

    await user.click(screen.getByRole('button', { name: /비식별 누락 신고/ }));
    const textarea = await screen.findByLabelText(/신고 사유/);
    await user.type(textarea, '사유');
    await user.click(screen.getByRole('button', { name: /신고하기/ }));

    await waitFor(() =>
      expect(
        screen.getByText(/이미 비식별 재처리 중/),
      ).toBeInTheDocument(),
    );
    // 모달은 열린 상태 유지
    expect(
      screen.getByRole('dialog', { name: /비식별 누락 신고/ }),
    ).toBeInTheDocument();
  });

  it('403_응답시_권한_없음_안내_표시', async () => {
    const user = userEvent.setup();
    mock.onPost('/labels/123/deident-report').reply(403, {
      success: false,
      data: null,
      message: '권한 없음',
      errorCode: 'FORBIDDEN',
    });

    renderWithProviders(<DeidentReportButton srcSn={123} />);

    await user.click(screen.getByRole('button', { name: /비식별 누락 신고/ }));
    const textarea = await screen.findByLabelText(/신고 사유/);
    await user.type(textarea, '사유');
    await user.click(screen.getByRole('button', { name: /신고하기/ }));

    await waitFor(() =>
      expect(screen.getByText(/배정된 영상이 아닙니다/)).toBeInTheDocument(),
    );
  });

  it('파생영상이면_버튼이_비활성화되고_사유가_툴팁으로_보인다', async () => {
    // 사유를 다 적고 제출한 뒤에야 412 를 보는 동선을 없앤다 — 알 수 있는 시점에 미리 막는다.
    // 원본으로 유도하지 않는다: 원본을 신고해도 이 파생본은 달라지지 않기 때문(자기 행 판정).
    const reason =
      '증강·해상도 변환으로 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다.';
    renderWithProviders(<DeidentReportButton srcSn={123} unsupportedReason={reason} />);

    const button = screen.getByRole('button', { name: /비식별 누락 신고/ });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', reason);
  });

  it('412_응답시_서버_안내문이_그대로_노출된다 — 화면이 파생 여부를 모를 때의 안전망', async () => {
    const user = userEvent.setup();
    mock.onPost('/labels/123/deident-report').reply(412, {
      success: false,
      data: null,
      message:
        '이 영상은 원본 영상의 비식별 결과를 복사해 만든 파생영상(증강·해상도 변환본)이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다.',
      errorCode: 'PRECONDITION_FAILED',
    });

    renderWithProviders(<DeidentReportButton srcSn={123} />);

    await user.click(screen.getByRole('button', { name: /비식별 누락 신고/ }));
    const textarea = await screen.findByLabelText(/신고 사유/);
    await user.type(textarea, '사유');
    await user.click(screen.getByRole('button', { name: /신고하기/ }));

    await waitFor(() =>
      expect(
        screen.getByText(/이 화면에서는 비식별 재처리를 요청할 수 없습니다/),
      ).toBeInTheDocument(),
    );
    // 모달은 열린 상태로 남아 안내가 보인다(무반응 실패 금지).
    expect(
      screen.getByRole('dialog', { name: /비식별 누락 신고/ }),
    ).toBeInTheDocument();
  });
});
