// SAM2 자동추적 도구 테스트 — ISSUE-3 BE 계약 정합.
// BE record Sam2TrackRequest: { srcSn, trackId, prevPolygon, label, nextSrcSns }
// BE Sam2TrackResponseDto: { tracked: [{ srcSn, trackId, label, points, score }] }

import { fireEvent, screen, waitFor, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { requestSam2Track } from '../api';
import { Sam2TrackTool } from '../canvas/tools/Sam2TrackTool';

const TRACKED_OK = {
  success: true,
  data: {
    tracked: [
      { srcSn: 2, trackId: 't-1', label: 'person', points: [[0, 0], [1, 0], [1, 1]], score: 0.9 },
    ],
  },
  message: null,
  errorCode: null,
};

const TRIANGLE: number[][] = [
  [0, 0],
  [10, 0],
  [10, 10],
];

describe('SAM2 Track', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  describe('requestSam2Track_API', () => {
    it('요청_바디가_BE_스키마_필드를_가진다_구스키마_없음', async () => {
      let captured: Record<string, unknown> = {};
      mock.onPost('/frames/777/sam2-track').reply((config) => {
        captured = JSON.parse((config.data as string) ?? '{}');
        return [200, TRACKED_OK];
      });

      const res = await requestSam2Track(777, {
        trackId: 't-1',
        prevPolygon: TRIANGLE,
        label: 'person',
        nextSrcSns: [2, 3],
      });

      expect(captured.srcSn).toBe(777);
      expect(captured.trackId).toBe('t-1');
      expect(captured.label).toBe('person');
      expect(captured.prevPolygon).toEqual(TRIANGLE);
      expect(captured.nextSrcSns).toEqual([2, 3]);
      expect(captured).not.toHaveProperty('bbox');
      expect(captured).not.toHaveProperty('classId');
      expect(captured).not.toHaveProperty('targetFrameCount');

      expect(res.tracked).toHaveLength(1);
      expect(res.tracked[0].srcSn).toBe(2);
    });
  });

  describe('Sam2TrackTool_컴포넌트', () => {
    it('SAM2_트랙_진행률_표시', async () => {
      mock.onPost('/frames/55/sam2-track').reply(200, TRACKED_OK);

      renderWithProviders(
        <Sam2TrackTool
          srcSn={55}
          prevPolygon={TRIANGLE}
          label="person"
          trackId="t-1"
          nextSrcSns={[56]}
        />,
      );
      const toggle = screen.getByRole('button', { name: /자동추적/i });
      expect(toggle).toBeInTheDocument();

      await act(async () => {
        fireEvent.click(toggle);
      });

      await waitFor(() => {
        expect(screen.queryByRole('progressbar')).toBeTruthy();
      });
    });

    it('srcSn_없을때_버튼_disabled', () => {
      renderWithProviders(
        <Sam2TrackTool
          srcSn={undefined}
          prevPolygon={undefined}
          label={undefined}
          trackId={undefined}
          nextSrcSns={[]}
        />,
      );
      const toggle = screen.getByRole('button', { name: /자동추적/i });
      expect(toggle).toBeDisabled();
    });

    it('nextSrcSns_비어있으면_버튼_disabled', () => {
      renderWithProviders(
        <Sam2TrackTool
          srcSn={55}
          prevPolygon={TRIANGLE}
          label="person"
          trackId="t-1"
          nextSrcSns={[]}
        />,
      );
      const toggle = screen.getByRole('button', { name: /자동추적/i });
      expect(toggle).toBeDisabled();
    });
  });
});
