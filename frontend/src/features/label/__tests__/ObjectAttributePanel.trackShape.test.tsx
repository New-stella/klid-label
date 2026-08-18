// 회귀 재현: 선택 객체의 실제 형태(BBOX/POLYGON)가 AI 추적 결과 형태를 결정해야 한다.
//
// 버그: 단축키(Shift+T) 경로는 track.shape 를 설정하지 않아(undefined), 박스 객체를 추적해도
// BE 기본값(POLYGON)으로 반영되던 결함. 선택 객체 형태를 우선 반영하도록 수정.
// (구 서술의 '툴바 "AI 추적"' 경로는 폐기됐다 — 그 버튼은 사양 SCREEN-005 §좌측 도구바에 없다.)

import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectAttributePanel } from '../components/ObjectAttributePanel';
import type { Label } from '../types';
import { ToolType } from '../types';

const bboxLabel: Label = {
  id: 'bbox1',
  frameNo: 1,
  classId: 3,
  className: 'person',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 10, top: 20, right: 100, bottom: 80 },
};

const polygonLabel: Label = {
  id: 'poly1',
  frameNo: 1,
  classId: 3,
  className: 'person',
  source: 'MANUAL',
  shape: { type: 'POLYGON', points: [10, 20, 100, 20, 100, 80] },
};

/** track.shape 미지정(=툴바/단축키 경로) 컨텍스트. */
const trackNoShape = {
  srcSn: 55,
  nextSrcSns: [56],
  // shape 미지정 — 툴바/단축키 진입 시 trackShape 는 undefined 다(모달 경로만 설정).
};

describe('ObjectAttributePanel — 추적 형태는 선택 객체 형태를 따른다', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
    useLabelStore.getState().setActiveTool(ToolType.TRACK);
  });

  afterEach(() => {
    mock.restore();
  });

  it('박스객체_추적시_shape가_track미지정이어도_BBOX로_요청된다_툴바단축키경로', async () => {
    let captured: Record<string, unknown> = {};
    mock.onPost('/frames/55/sam2-track').reply((config) => {
      captured = JSON.parse((config.data as string) ?? '{}');
      return [200, { success: true, data: { tracked: [] }, message: null, errorCode: null }];
    });

    useLabelStore.getState().setLabels([bboxLabel]);
    useLabelStore.getState().selectLabel('bbox1');
    renderWithProviders(<ObjectAttributePanel labels={[bboxLabel]} track={trackNoShape} />);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /AI 추적/i }));
    });

    await waitFor(() => expect(captured.shape).toBe('BBOX'));
  });

  it('폴리곤객체_추적시_POLYGON으로_요청된다_회귀방지', async () => {
    let captured: Record<string, unknown> = {};
    mock.onPost('/frames/55/sam2-track').reply((config) => {
      captured = JSON.parse((config.data as string) ?? '{}');
      return [200, { success: true, data: { tracked: [] }, message: null, errorCode: null }];
    });

    useLabelStore.getState().setLabels([polygonLabel]);
    useLabelStore.getState().selectLabel('poly1');
    renderWithProviders(<ObjectAttributePanel labels={[polygonLabel]} track={trackNoShape} />);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /AI 추적/i }));
    });

    await waitFor(() => expect(captured.shape).toBe('POLYGON'));
  });

  it('모달[트랙]경로_track_shape는_선택객체_형태에_밀려도_객체가_우선이다', async () => {
    // 선택 객체가 박스인데 모달에서 POLYGON 을 골랐어도(stale), 실제 객체 형태(BBOX)를 따른다.
    let captured: Record<string, unknown> = {};
    mock.onPost('/frames/55/sam2-track').reply((config) => {
      captured = JSON.parse((config.data as string) ?? '{}');
      return [200, { success: true, data: { tracked: [] }, message: null, errorCode: null }];
    });

    useLabelStore.getState().setLabels([bboxLabel]);
    useLabelStore.getState().selectLabel('bbox1');
    renderWithProviders(
      <ObjectAttributePanel
        labels={[bboxLabel]}
        track={{ srcSn: 55, nextSrcSns: [56], shape: 'POLYGON' }}
      />,
    );

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /AI 추적/i }));
    });

    await waitFor(() => expect(captured.shape).toBe('BBOX'));
  });
});

/*
 * ★도구 모드 게이트 제거 가드 (2026-08-18 사양 정합).
 *
 * 구 동작: 실행 버튼이 `activeTool === ToolType.TRACK` 일 때만 노출 → 좌측 도구바에서 모드를 먼저
 *          켜야 우측에 버튼이 나타나는 2단 동선.
 * 새 동작: **선택 객체가 있으면 상시 노출**. 사양 SCREEN-005 — "AI 추적은 우측 패널 '객체' 탭에서
 *          대상 객체를 펼쳤을 때 노출되는 버튼으로 실행한다".
 *
 * 이 가드가 없으면 도구바 버튼 제거와 함께 실행 진입점이 통째로 사라진 것을 아무도 못 잡는다.
 */
describe('ObjectAttributePanel — AI 추적 실행 버튼 노출 조건', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('도구가_TRACK이_아니어도_선택객체가_있으면_AI추적_버튼이_보인다', () => {
    useLabelStore.getState().setActiveTool(ToolType.SELECT);
    useLabelStore.getState().setLabels([bboxLabel]);
    useLabelStore.getState().selectLabel('bbox1');

    renderWithProviders(<ObjectAttributePanel labels={[bboxLabel]} track={trackNoShape} />);

    expect(screen.getByRole('button', { name: /AI 추적/i })).toBeInTheDocument();
  });

  it('track_컨텍스트가_없으면_AI추적_버튼을_노출하지_않는다_포털_차단축', () => {
    // ADR-013 — 포털 채널은 호출부(LabelingPage)가 track 을 넘기지 않는다. 도구 모드 게이트가
    // 사라진 지금 그 분기가 유일한 차단이므로 여기서 고정한다.
    useLabelStore.getState().setActiveTool(ToolType.SELECT);
    useLabelStore.getState().setLabels([bboxLabel]);
    useLabelStore.getState().selectLabel('bbox1');

    renderWithProviders(<ObjectAttributePanel labels={[bboxLabel]} />);

    expect(screen.queryByRole('button', { name: /AI 추적/i })).toBeNull();
  });
});
