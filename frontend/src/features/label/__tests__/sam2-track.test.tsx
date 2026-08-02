// SAM2 자동추적 도구 테스트 — ISSUE-3 BE 계약 정합.
// BE record Sam2TrackRequest: { srcSn, trackId, prevPolygon, label, nextSrcSns }
// BE Sam2TrackResponseDto: { tracked: [{ srcSn, trackId, label, points, score }] }

import { fireEvent, screen, waitFor, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import {
  requestSam2Track,
  sam2TrackAllChunks,
  Sam2TrackChunkError,
  SAM2_TRACK_CHUNK_SIZE,
  type Sam2TrackedItem,
} from '../api';
import { Sam2TrackTool } from '../canvas/tools/Sam2TrackTool';

/** nextSrcSns 각 프레임에 대해 결정적 tracked item 을 만든다 (srcSn 기반 폴리곤). */
function trackedFor(nextSrcSns: number[]): Sam2TrackedItem[] {
  return nextSrcSns.map((s) => ({
    srcSn: s,
    trackId: 't-1',
    label: 'person',
    points: [
      [s, 0],
      [s, 1],
      [s, 2],
    ],
    score: 0.8,
  }));
}

const TRACK_PATH_RE = /\/frames\/(\d+)\/sam2-track/;

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

  describe('sam2TrackAllChunks_청크분할_순차호출', () => {
    it('상수는_BE_Size_상한과_정합한다', () => {
      expect(SAM2_TRACK_CHUNK_SIZE).toBe(50);
    });

    it('120개_후속프레임은_50_50_20_3청크로_분할되고_폴리곤이_체인된다', async () => {
      const next = Array.from({ length: 120 }, (_, i) => 201 + i); // 201..320
      const captured: { path: number; body: Record<string, unknown> }[] = [];
      mock.onPost(TRACK_PATH_RE).reply((config) => {
        const path = Number(TRACK_PATH_RE.exec(config.url ?? '')![1]);
        const body = JSON.parse((config.data as string) ?? '{}');
        captured.push({ path, body });
        return [
          200,
          {
            success: true,
            data: { tracked: trackedFor(body.nextSrcSns as number[]) },
            message: null,
            errorCode: null,
          },
        ];
      });

      const res = await sam2TrackAllChunks(1000, {
        trackId: 't-1',
        prevPolygon: TRIANGLE,
        label: 'person',
        nextSrcSns: next,
      });

      // 3개 청크로 분할
      expect(captured).toHaveLength(3);
      expect((captured[0].body.nextSrcSns as number[]).length).toBe(50);
      expect((captured[1].body.nextSrcSns as number[]).length).toBe(50);
      expect((captured[2].body.nextSrcSns as number[]).length).toBe(20);

      // 청크1: 시작 srcSn = 초기값 1000, prevPolygon = 원 폴리곤
      expect(captured[0].path).toBe(1000);
      expect(captured[0].body.prevPolygon).toEqual(TRIANGLE);

      // 청크2: 시작 srcSn = 청크1 마지막 프레임(250), prevPolygon = 250 추적 폴리곤
      expect(captured[1].path).toBe(250);
      expect(captured[1].body.prevPolygon).toEqual([
        [250, 0],
        [250, 1],
        [250, 2],
      ]);

      // 청크3: 시작 srcSn = 청크2 마지막 프레임(300)
      expect(captured[2].path).toBe(300);
      expect(captured[2].body.prevPolygon).toEqual([
        [300, 0],
        [300, 1],
        [300, 2],
      ]);

      // 모든 청크 결과 누적
      expect(res.tracked).toHaveLength(120);
      expect(res.tracked[0].srcSn).toBe(201);
      expect(res.tracked[119].srcSn).toBe(320);
    });

    it('30개_후속프레임은_단일_청크로_1회만_호출한다', async () => {
      const next = Array.from({ length: 30 }, (_, i) => i + 2);
      let count = 0;
      mock.onPost(TRACK_PATH_RE).reply((config) => {
        count += 1;
        const body = JSON.parse((config.data as string) ?? '{}');
        return [
          200,
          { success: true, data: { tracked: trackedFor(body.nextSrcSns as number[]) }, message: null, errorCode: null },
        ];
      });

      const res = await sam2TrackAllChunks(1, {
        trackId: 't-1',
        prevPolygon: TRIANGLE,
        label: 'person',
        nextSrcSns: next,
      });
      expect(count).toBe(1);
      expect(res.tracked).toHaveLength(30);
    });

    it('정확히_50개_후속프레임은_단일_청크', async () => {
      const next = Array.from({ length: 50 }, (_, i) => i + 2);
      let count = 0;
      mock.onPost(TRACK_PATH_RE).reply((config) => {
        count += 1;
        const body = JSON.parse((config.data as string) ?? '{}');
        return [
          200,
          { success: true, data: { tracked: trackedFor(body.nextSrcSns as number[]) }, message: null, errorCode: null },
        ];
      });

      const res = await sam2TrackAllChunks(1, {
        trackId: 't-1',
        prevPolygon: TRIANGLE,
        label: 'person',
        nextSrcSns: next,
      });
      expect(count).toBe(1);
      expect(res.tracked).toHaveLength(50);
    });

    it('빈_후속프레임은_요청없이_빈결과를_반환', async () => {
      let count = 0;
      mock.onPost(TRACK_PATH_RE).reply(() => {
        count += 1;
        return [200, { success: true, data: { tracked: [] }, message: null, errorCode: null }];
      });

      const res = await sam2TrackAllChunks(1, {
        trackId: 't-1',
        prevPolygon: TRIANGLE,
        label: 'person',
        nextSrcSns: [],
      });
      expect(count).toBe(0);
      expect(res.tracked).toEqual([]);
    });

    it('2번째_청크_실패시_1번째_청크_결과는_유지되고_에러가_표면화된다', async () => {
      const next = Array.from({ length: 80 }, (_, i) => 201 + i); // 201..280 → [201..250], [251..280]
      mock.onPost('/frames/1000/sam2-track').reply((config) => {
        const body = JSON.parse((config.data as string) ?? '{}');
        return [
          200,
          { success: true, data: { tracked: trackedFor(body.nextSrcSns as number[]) }, message: null, errorCode: null },
        ];
      });
      // 청크2 시작 프레임 = 250 → 400 실패
      mock.onPost('/frames/250/sam2-track').reply(400, {
        success: false,
        data: null,
        message: 'nextSrcSns 검증 실패',
        errorCode: 'INVALID_INPUT',
      });

      let error: unknown;
      try {
        await sam2TrackAllChunks(1000, {
          trackId: 't-1',
          prevPolygon: TRIANGLE,
          label: 'person',
          nextSrcSns: next,
        });
      } catch (e) {
        error = e;
      }

      expect(error).toBeInstanceOf(Sam2TrackChunkError);
      const ce = error as Sam2TrackChunkError;
      // 1번째 청크(50프레임) 결과는 유지 — 롤백 없음
      expect(ce.partial).toHaveLength(50);
      expect(ce.partial[0].srcSn).toBe(201);
      expect(ce.partial[49].srcSn).toBe(250);
      expect(ce.completedChunks).toBe(1);
      expect(ce.totalChunks).toBe(2);
    });

    it('실패구간_completedChunks_라벨이_실제_완료청크와_일치', async () => {
      // given — 80프레임 = 2청크. 첫 청크(1000)가 빈 tracked 반환 → 다음 청크 이어붙이기 불가.
      // 이 방어 분기의 completedChunks 는 실패 catch 분기와 동일하게 '결과를 낸 완료 청크 수'여야 한다.
      const next = Array.from({ length: 80 }, (_, i) => 201 + i); // 201..280 → 2청크
      mock.onPost('/frames/1000/sam2-track').reply(200, {
        success: true,
        data: { tracked: [] }, // 빈 응답 — BE 계약상 도달 불가하나 계약 변경 대비 방어
        message: null,
        errorCode: null,
      });

      let error: unknown;
      try {
        await sam2TrackAllChunks(1000, {
          trackId: 't-1',
          prevPolygon: TRIANGLE,
          label: 'person',
          nextSrcSns: next,
        });
      } catch (e) {
        error = e;
      }

      expect(error).toBeInstanceOf(Sam2TrackChunkError);
      const ce = error as Sam2TrackChunkError;
      // 첫 청크가 빈 결과 → 실제 완료(결과 있는) 청크 = 0. 실패 catch 분기(c)와 일관.
      expect(ce.partial).toHaveLength(0);
      expect(ce.completedChunks).toBe(0);
      expect(ce.totalChunks).toBe(2);
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

    // C-ISSUE-81 (CWE-345) — mock(모델 미로드) 응답은 BE 가 결과에서 제외하고 안내 message 를 싣는다.
    it('mock응답이면_결과가_비고_모델미로드_안내가_표시된다', async () => {
      mock.onPost('/frames/55/sam2-track').reply(200, {
        success: true,
        data: { tracked: [] },
        message: 'AI 모델 미로드 — 결과 신뢰 불가',
        errorCode: null,
      });
      const onCompleted = vi.fn();

      renderWithProviders(
        <Sam2TrackTool
          srcSn={55}
          prevPolygon={TRIANGLE}
          label="person"
          trackId="t-1"
          nextSrcSns={[56]}
          onCompleted={onCompleted}
        />,
      );

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /자동추적/i }));
      });

      // 안내가 노출되고, 병합 대상(tracked)은 비어 있어 자동 적용될 좌표가 없다.
      await waitFor(() => {
        expect(screen.getByText('AI 모델 미로드 — 결과 신뢰 불가')).toBeInTheDocument();
      });
      expect(onCompleted).toHaveBeenCalledWith([], false);
    });

    it('mock응답_message는_requestSam2Track_반환값에_보존된다', async () => {
      mock.onPost('/frames/777/sam2-track').reply(200, {
        success: true,
        data: { tracked: [] },
        message: '일부 결과의 신뢰도를 보장할 수 없습니다.',
        errorCode: null,
      });

      const res = await requestSam2Track(777, {
        trackId: 't-1',
        prevPolygon: TRIANGLE,
        label: 'person',
        nextSrcSns: [2],
      });

      expect(res.tracked).toEqual([]);
      expect(res.message).toBe('일부 결과의 신뢰도를 보장할 수 없습니다.');
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

  // R12 — 트랙 형태(박스/폴리곤)·라벨 배선.
  describe('shape/label 배선', () => {
    it('requestSam2Track_shape가_요청바디에_포함된다_BBOX', async () => {
      let captured: Record<string, unknown> = {};
      mock.onPost('/frames/777/sam2-track').reply((config) => {
        captured = JSON.parse((config.data as string) ?? '{}');
        return [200, TRACKED_OK];
      });
      await requestSam2Track(777, {
        trackId: 't-1',
        prevPolygon: TRIANGLE,
        label: 'person',
        nextSrcSns: [2],
        shape: 'BBOX',
      });
      expect(captured.shape).toBe('BBOX');
    });

    it('sam2TrackAllChunks_shape가_각_청크요청에_전달된다', async () => {
      const next = Array.from({ length: 60 }, (_, i) => 201 + i); // 50 + 10 = 2 청크
      const shapes: unknown[] = [];
      mock.onPost(TRACK_PATH_RE).reply((config) => {
        const body = JSON.parse((config.data as string) ?? '{}');
        shapes.push(body.shape);
        return [
          200,
          { success: true, data: { tracked: trackedFor(body.nextSrcSns as number[]) }, message: null, errorCode: null },
        ];
      });
      await sam2TrackAllChunks(1000, {
        trackId: 't-1',
        prevPolygon: TRIANGLE,
        label: 'person',
        nextSrcSns: next,
        shape: 'BBOX',
      });
      expect(shapes).toHaveLength(2);
      expect(shapes.every((s) => s === 'BBOX')).toBe(true);
    });

    it('Sam2TrackTool_shape_prop이_추적요청에_포함된다_박스선택시_BBOX', async () => {
      let captured: Record<string, unknown> = {};
      mock.onPost('/frames/55/sam2-track').reply((config) => {
        captured = JSON.parse((config.data as string) ?? '{}');
        return [200, TRACKED_OK];
      });
      renderWithProviders(
        <Sam2TrackTool
          srcSn={55}
          prevPolygon={TRIANGLE}
          label="person"
          trackId="t-1"
          shape="BBOX"
          nextSrcSns={[56]}
        />,
      );
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /자동추적/i }));
      });
      await waitFor(() => expect(captured.shape).toBe('BBOX'));
    });

    it('Sam2TrackTool_labelOverride가_캔버스객체_라벨보다_우선한다', async () => {
      let captured: Record<string, unknown> = {};
      mock.onPost('/frames/55/sam2-track').reply((config) => {
        captured = JSON.parse((config.data as string) ?? '{}');
        return [200, TRACKED_OK];
      });
      renderWithProviders(
        <Sam2TrackTool
          srcSn={55}
          prevPolygon={TRIANGLE}
          label="car"
          labelOverride="person"
          trackId="t-1"
          nextSrcSns={[56]}
        />,
      );
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /자동추적/i }));
      });
      await waitFor(() => expect(captured.label).toBe('person'));
    });

    it('BBOX_추적_50프레임초과시_2번째청크_prevPolygon이_4점폐곡선으로_확장된다', async () => {
      // BE 는 shape=BBOX 면 tracked.points 를 2점 외접박스([[minX,minY],[maxX,maxY]])로 반환한다.
      // 청크 체이닝이 그 2점을 그대로 다음 청크 prevPolygon 으로 쓰면 @Size(min=3) 위반 → 400.
      // 시드를 4점 폐곡선(모서리)으로 확장해 넘겨야 한다.
      const next = Array.from({ length: 60 }, (_, i) => 201 + i); // 50 + 10 = 2 청크
      const prevPolys: unknown[] = [];
      mock.onPost(TRACK_PATH_RE).reply((config) => {
        const body = JSON.parse((config.data as string) ?? '{}');
        prevPolys.push(body.prevPolygon);
        // BBOX tracked item 은 2점 외접박스로 반환된다(BE 계약).
        const tracked = (body.nextSrcSns as number[]).map((s) => ({
          srcSn: s,
          trackId: 't-1',
          label: 'person',
          points: [
            [s, 0],
            [s + 5, 10],
          ],
          score: 0.8,
          shapeType: 'BBOX',
        }));
        return [200, { success: true, data: { tracked }, message: null, errorCode: null }];
      });

      await sam2TrackAllChunks(1000, {
        trackId: 't-1',
        prevPolygon: TRIANGLE,
        label: 'person',
        nextSrcSns: next,
        shape: 'BBOX',
      });

      expect(prevPolys).toHaveLength(2);
      // 청크1 은 원 폴리곤(3점) 그대로.
      expect(prevPolys[0]).toEqual(TRIANGLE);
      // 청크2 의 prevPolygon 은 청크1 마지막 tracked(2점 박스)를 4점 폐곡선으로 확장한 것.
      // 청크1 마지막 프레임 = 250 → points [[250,0],[255,10]] → 4모서리.
      expect(prevPolys[1]).toEqual([
        [250, 0],
        [255, 0],
        [255, 10],
        [250, 10],
      ]);
    });

    it('Sam2TrackTool_shape_미지정시_요청바디에_shape없음', async () => {
      let captured: Record<string, unknown> = {};
      mock.onPost('/frames/55/sam2-track').reply((config) => {
        captured = JSON.parse((config.data as string) ?? '{}');
        return [200, TRACKED_OK];
      });
      renderWithProviders(
        <Sam2TrackTool
          srcSn={55}
          prevPolygon={TRIANGLE}
          label="person"
          trackId="t-1"
          nextSrcSns={[56]}
        />,
      );
      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: /자동추적/i }));
      });
      await waitFor(() => expect(captured.label).toBe('person'));
      expect(captured).not.toHaveProperty('shape');
    });
  });
});
