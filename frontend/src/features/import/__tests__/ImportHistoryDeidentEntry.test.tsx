import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ImportHistorySection } from '@/features/import/components/ImportHistorySection';
import { canRecordDeidentComplete } from '@/features/import/approvalHold';
import type { ImportHistoryItem } from '@/features/import/types';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';

function pageBody(content: ImportHistoryItem[]) {
  return {
    success: true,
    data: {
      content,
      totalElements: content.length,
      totalPages: content.length === 0 ? 0 : 1,
      number: 0,
      size: 20,
    },
    message: null,
    errorCode: null,
  };
}

function row(overrides: Partial<ImportHistoryItem>): ImportHistoryItem {
  return {
    trnsfSn: 1,
    folderName: '00000073',
    rawSn: 42,
    status: 'SUCCESS',
    frameCount: 120,
    labelCount: 480,
    approvalHeld: true,
    regId: 'reviewer1',
    regDt: '2026-08-20T10:00:00',
    ...overrides,
  };
}

describe('이관 이력 — 승인 보류 축과 비식별 완료 기록 진입점', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('보류가_선_행에서_비식별_완료_기록을_연다', async () => {
    mock.onGet('/imports').reply(200, pageBody([row({ approvalHeld: true })]));
    renderWithProviders(<ImportHistorySection />);

    expect(await screen.findByTestId('import-deident-record-1')).toBeInTheDocument();
    expect(screen.getByTestId('import-hold-1')).toHaveTextContent('보류');
  });

  it('보류가_없다고_서버가_말한_행에는_진입점이_없다', async () => {
    mock.onGet('/imports').reply(200, pageBody([row({ approvalHeld: false })]));
    renderWithProviders(<ImportHistorySection />);

    await screen.findByTestId('import-history-row-1');
    expect(screen.queryByTestId('import-deident-record-1')).not.toBeInTheDocument();
    expect(screen.getByTestId('import-hold-1')).toHaveTextContent('없음');
  });

  /**
   * ★핵심 회귀 가드 — 보류 여부가 **없을 때** 진입점을 감추지 않는다.
   *
   * 서버는 영상의 작업 상태 행을 찾지 못하면 값을 비워 돌려준다. 그것은 "보류가 없다"가 아니라
   * "알 수 없다"는 뜻이다. 없음을 「보류 아님」으로 단정하면 그 영상은 보류를 풀 자리가 화면에서
   * 사라져 영영 승인되지 못한다.
   *
   * ⚠ mutation 확인 절차: `canRecordDeidentComplete` 의 `item.approvalHeld !== false` 를
   *   `item.approvalHeld === true` 로 바꾸면 이 케이스가 실패해야 한다(실제로 확인함).
   */
  it('★보류_여부가_없음일_때도_진입점을_감추지_않는다_없음을_아님으로_단정하지_않는다', async () => {
    mock.onGet('/imports').reply(200, pageBody([row({ approvalHeld: null })]));
    renderWithProviders(<ImportHistorySection />);

    expect(await screen.findByTestId('import-deident-record-1')).toBeInTheDocument();
    // 표시도 「없음」이 아니라 「미상」이다 — 값이 없다는 사실을 그대로 알린다
    expect(screen.getByTestId('import-hold-1')).toHaveTextContent('미상');
  });

  it('영상이_없는_이력에는_기록할_대상이_없어_진입점이_없다', async () => {
    mock
      .onGet('/imports')
      .reply(200, pageBody([row({ rawSn: null, approvalHeld: null, status: 'FAILED' })]));
    renderWithProviders(<ImportHistorySection />);

    await screen.findByTestId('import-history-row-1');
    expect(screen.queryByTestId('import-deident-record-1')).not.toBeInTheDocument();
  });

  it('실패한_이력은_상태_자리에서_사유를_펼쳐본다_사양이_선언한_열_구성을_유지한다', async () => {
    mock.onGet('/imports').reply(200, pageBody([row({ status: 'FAILED', rawSn: null })]));
    mock.onGet('/imports/1').reply(200, {
      success: true,
      data: {
        trnsfSn: 1,
        folderPath: null,
        folderName: '00000073',
        externalDatasetId: null,
        rawSn: null,
        status: 'FAILED',
        frameCount: null,
        labelCount: null,
        failReason: '산출물 문서를 읽을 수 없습니다.',
        regId: 'reviewer1',
        regDt: '2026-08-20T10:00:00',
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<ImportHistorySection />);

    const toggle = await screen.findByTestId('import-fail-toggle-1');
    // 사유 펼침은 별도 열이 아니라 상태 셀 안에 있다 — 열 구성은 사양이 선언한 8열 그대로다.
    expect(screen.queryByRole('columnheader', { name: '사유' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('columnheader')).toHaveLength(8);

    await user.click(toggle);
    expect(await screen.findByTestId('import-fail-reason-1')).toHaveTextContent(
      '산출물 문서를 읽을 수 없습니다.',
    );
  });
});

describe('비식별 완료 기록 — 비워 둔 프레임 수를 사람이 본다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: 'tok',
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
  });

  it('★비워_둔_프레임이_남으면_산출물에서_빠진다는_사실을_알린다', async () => {
    mock.onGet('/imports').reply(200, pageBody([row({ approvalHeld: true })]));
    mock.onPost('/videos/42/deident-complete').reply(200, {
      success: true,
      data: {
        rawSn: 42,
        procLogSn: 9,
        approvalHoldReleased: true,
        deidentFrameMatchedCount: 118,
        deidentFrameUnmatchedCount: 2,
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<ImportHistorySection />);

    await user.click(await screen.findByTestId('import-deident-record-1'));
    await user.type(
      screen.getByLabelText(/비식별 산출물 폴더 경로/),
      '/nas-storage/handover/00000073-deid',
    );
    await user.click(screen.getByTestId('deident-complete-submit'));

    const alert = await screen.findByTestId('deident-complete-unmatched');
    expect(alert).toHaveTextContent('2건');
    expect(alert).toHaveTextContent('빠진 채로 나갑니다');
  });

  it('비워_둔_프레임이_없으면_그_경고는_뜨지_않는다', async () => {
    mock.onGet('/imports').reply(200, pageBody([row({ approvalHeld: true })]));
    mock.onPost('/videos/42/deident-complete').reply(200, {
      success: true,
      data: {
        rawSn: 42,
        procLogSn: 9,
        approvalHoldReleased: true,
        deidentFrameMatchedCount: 120,
        deidentFrameUnmatchedCount: 0,
      },
      message: null,
      errorCode: null,
    });

    const user = userEvent.setup();
    renderWithProviders(<ImportHistorySection />);

    await user.click(await screen.findByTestId('import-deident-record-1'));
    await user.type(
      screen.getByLabelText(/비식별 산출물 폴더 경로/),
      '/nas-storage/handover/00000073-deid',
    );
    await user.click(screen.getByTestId('deident-complete-submit'));

    await screen.findByTestId('deident-complete-result');
    expect(screen.queryByTestId('deident-complete-unmatched')).not.toBeInTheDocument();
  });

  it('경로를_비운_채로는_기록_요청이_나가지_않는다', async () => {
    mock.onGet('/imports').reply(200, pageBody([row({ approvalHeld: true })]));
    const user = userEvent.setup();
    renderWithProviders(<ImportHistorySection />);

    await user.click(await screen.findByTestId('import-deident-record-1'));
    expect(screen.getByTestId('deident-complete-submit')).toBeDisabled();
    await waitFor(() =>
      expect(mock.history.post.filter((r) => r.url?.includes('deident-complete'))).toHaveLength(0),
    );
  });
});

describe('승인 보류 판정 — 단일 판정 지점', () => {
  it('상태값으로_보류를_대신_판단하지_않는다', () => {
    // 같은 성공 이관인데 보류 값이 서로 다르다 — 상태로는 가릴 수 없다
    expect(canRecordDeidentComplete(row({ status: 'SUCCESS', approvalHeld: true }))).toBe(true);
    expect(canRecordDeidentComplete(row({ status: 'SUCCESS', approvalHeld: false }))).toBe(false);
    // 값이 없으면 열어 둔다(단정하지 않는다)
    expect(canRecordDeidentComplete(row({ status: 'SUCCESS', approvalHeld: null }))).toBe(true);
    // 영상이 없으면 기록할 대상 자체가 없다
    expect(canRecordDeidentComplete(row({ rawSn: null, approvalHeld: null }))).toBe(false);
  });
});
