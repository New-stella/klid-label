// SaveCommitButton — 저장 후 React Query 캐시 무효화 회귀 테스트.
//
// 회귀 배경: 본 컴포넌트가 saveAndCommit() 직접 호출 시 React Query 캐시 invalidate 가 누락되어
// LabelingPage 메인 경로에서 발생한 "프레임 넘어가면 초기화" 버그와 동일한 stale 노출이
// 이 버튼을 사용하는 다른 화면(포털 채널 등)에서 재발할 수 있었다.
// 이를 막기 위해 useUpdateLabels 훅으로 전환하고 LABEL/VIDEO/ASSIGNMENT/REVIEW_KEYS 일괄
// invalidate 가 호출되는지 검증한다.
//
// BE 변경 사항: PUT /frames/{srcSn}/labels 가 저장 + 라벨 스냅샷 버전 커밋(DB)을 한 번에 처리하므로
// FE 는 commit 별도 호출이 필요 없음. (SaveCommitFlow.saveAndCommit 도 같은 단일 PUT 모델로 정리됨)

import { fireEvent, screen, waitFor } from '@testing-library/react';
import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { ASSIGNMENT_KEYS, LABEL_KEYS, REVIEW_KEYS, VIDEO_KEYS } from '@/lib/queryKeys';
import { renderWithProviders } from '@/test/renderWithProviders';

import { SaveCommitButton } from '../components/SaveCommitButton';
import type { Label } from '../types';

const sample: Label[] = [
  {
    id: 'tmp1',
    frameNo: 1,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 0, top: 0, right: 50, bottom: 30 },
  },
];

function newQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
}

describe('SaveCommitButton', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('내부_채널_저장_시_PUT_단일_호출_BE_가_저장_커밋_일괄_처리', async () => {
    // given — BE 가 PUT 단일 엔드포인트로 저장 + 커밋을 처리하는 현재 계약
    const calls: string[] = [];
    mock.onPut('/frames/123/labels').reply(() => {
      calls.push('PUT');
      return [
        200,
        {
          success: true,
          data: { frameNo: 1, srcSn: 123, labels: sample },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    renderWithProviders(<SaveCommitButton srcSn={123} labels={sample} />);
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    // then — PUT 한 번만 호출 (POST /commit 별도 호출 없음)
    await waitFor(() => expect(calls).toEqual(['PUT']));
  });

  it('portalMode_저장시_PUT_단일_호출', async () => {
    // given
    const calls: string[] = [];
    mock.onPut('/frames/123/labels').reply(() => {
      calls.push('PUT');
      return [
        200,
        {
          success: true,
          data: { frameNo: 1, srcSn: 123, labels: sample },
          message: null,
          errorCode: null,
        },
      ];
    });

    // when
    renderWithProviders(<SaveCommitButton srcSn={123} labels={sample} portalMode />);
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    // then
    await waitFor(() => expect(calls).toEqual(['PUT']));
  });

  it('저장_성공_시_LABEL_VIDEO_ASSIGNMENT_REVIEW_캐시를_모두_invalidate', async () => {
    // given
    mock.onPut('/frames/123/labels').reply(200, {
      success: true,
      data: { frameNo: 1, srcSn: 123, labels: sample },
      message: null,
      errorCode: null,
    });

    const qc = newQueryClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');

    // when
    renderWithProviders(<SaveCommitButton srcSn={123} labels={sample} />, {
      queryClient: qc,
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    // then — 4개 도메인 invalidate (회귀 가드). LABEL 은 저장한 프레임의 internal 키로
    // 좁힌다 (FE-5 — 포털 캐시 churn 방지).
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: [...LABEL_KEYS.byFrame(123, 0), 'internal'],
      });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: VIDEO_KEYS.all });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ASSIGNMENT_KEYS.all });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: REVIEW_KEYS.all });
    });
  });

  it('onSaved_콜백은_저장_성공_후_호출', async () => {
    // given
    mock.onPut('/frames/123/labels').reply(200, {
      success: true,
      data: { frameNo: 1, srcSn: 123, labels: sample },
      message: null,
      errorCode: null,
    });
    const onSaved = vi.fn();

    // when
    renderWithProviders(
      <SaveCommitButton srcSn={123} labels={sample} onSaved={onSaved} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    // then
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
  });

  it('저장_실패_시_alert_로_에러_메시지_노출', async () => {
    // given
    mock.onPut('/frames/123/labels').reply(500, {
      success: false,
      data: null,
      message: '저장 실패: 서버 오류',
      errorCode: 'INTERNAL_ERROR',
    });

    // when
    renderWithProviders(<SaveCommitButton srcSn={123} labels={sample} />);
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    // then
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });
  });
});
