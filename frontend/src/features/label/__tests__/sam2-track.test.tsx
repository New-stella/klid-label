// SAM2 자동추적 도구 테스트.

import { fireEvent, screen, waitFor, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { requestSam2Track } from '../api';
import { Sam2TrackTool } from '../canvas/tools/Sam2TrackTool';

describe('SAM2 Track', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  describe('requestSam2Track_API', () => {
    it('SAM2_트랙_단축키_T_누르면_API_호출_시뮬레이션_BE_엔드포인트_정확', async () => {
      mock.onPost('/frames/777/sam2-track').reply(200, {
        success: true,
        data: { trackId: 1, propagatedFrames: [{ frameNo: 2, bbox: [0, 0, 10, 10] }] },
        message: null,
        errorCode: null,
      });

      const res = await requestSam2Track(777, {
        bbox: [0, 0, 10, 10],
        classId: 1,
        targetFrameCount: 30,
      });
      expect(res.trackId).toBe(1);
      expect(res.propagatedFrames).toHaveLength(1);
    });

    it('SAM2_트랙_payload_classId_bbox_targetFrameCount_전달', async () => {
      mock.onPost('/frames/100/sam2-track').reply((config) => {
        const body = JSON.parse(config.data ?? '{}');
        expect(body).toMatchObject({ classId: 5, targetFrameCount: 30 });
        expect(body.bbox).toHaveLength(4);
        return [
          200,
          {
            success: true,
            data: { trackId: 99, propagatedFrames: [] },
            message: null,
            errorCode: null,
          },
        ];
      });

      await requestSam2Track(100, { bbox: [1, 2, 3, 4], classId: 5, targetFrameCount: 30 });
    });
  });

  describe('Sam2TrackTool_컴포넌트', () => {
    it('SAM2_트랙_진행률_표시', async () => {
      mock.onPost('/frames/55/sam2-track').reply(200, {
        success: true,
        data: { trackId: 1, propagatedFrames: [] },
        message: null,
        errorCode: null,
      });

      renderWithProviders(
        <Sam2TrackTool srcSn={55} bbox={[0, 0, 10, 10]} classId={1} />,
      );
      // 토글 버튼 존재
      const toggle = screen.getByRole('button', { name: /자동추적/i });
      expect(toggle).toBeInTheDocument();

      await act(async () => {
        fireEvent.click(toggle);
      });

      // 진행률 또는 완료 표시 (요청 완료 후)
      await waitFor(() => {
        expect(screen.queryByRole('progressbar')).toBeTruthy();
      });
    });

    it('srcSn_없을때_버튼_disabled', () => {
      renderWithProviders(<Sam2TrackTool srcSn={undefined} bbox={undefined} classId={undefined} />);
      const toggle = screen.getByRole('button', { name: /자동추적/i });
      expect(toggle).toBeDisabled();
    });
  });
});
