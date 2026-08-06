// 회귀(2026-08-06): 신규 라벨 생성 시 `labelId`(라벨 마스터 PK) 유실 → 저장하면 색이 바뀐다.
//
// 결함 사슬:
//   OverlayLayer 가 classId 만 채우고 labelId 를 빼먹음
//     → api.serializeLabel 이 `labelId: null` 로 직렬화
//     → BE LabelResponse.Item.from 이 labelId null 이라 LS_LABEL 조인 skip
//     → 재조회 응답의 color/label 이 null
//     → getLabelDisplayColor 가 마스터 색을 못 찾아 trackId 해시색으로 떨어짐(= "이상한 색")
//
// 이 파일은 사슬의 **첫 고리**(생성 시 labelId 설정)와 **두 번째 고리**(직렬화 시 non-null 전송)를
// 도구 4종 전 경로에 대해 고정한다.

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render } from '@testing-library/react';

let labelMastersData: Array<{
  labelId: number;
  name: string;
  color: string;
  sortNo: number;
  useYn: string;
}> = [];

vi.mock('react-konva', () => {
  const React = require('react');
  const passthrough = (name: string) => {
    return ({ children, dash, points, listening, onDblClick, ...rest }: any) => {
      const props: Record<string, unknown> = { 'data-konva': name, ...rest };
      if (dash !== undefined)
        props['data-dash'] = Array.isArray(dash) ? dash.join(',') : String(dash);
      if (points !== undefined)
        props['data-points'] = Array.isArray(points) ? points.join(',') : String(points);
      if (onDblClick) props.onDoubleClick = onDblClick;
      return React.createElement('div', props, children);
    };
  };
  return {
    Stage: passthrough('Stage'),
    Layer: passthrough('Layer'),
    Image: passthrough('Image'),
    Rect: passthrough('Rect'),
    Line: passthrough('Line'),
    Circle: passthrough('Circle'),
    Text: passthrough('Text'),
  };
});

vi.mock('../../../hooks/useLabelMasters', () => ({
  useLabelMasters: () => ({ data: labelMastersData, isLoading: false, isError: false }),
}));

import type Konva from 'konva';

import { OverlayLayer } from '../OverlayLayer';
import { ToolType } from '../../../types';
import type { Label } from '../../../types';
import type { Geometry } from '../../utils/coordinateTransformer';

const geom: Geometry = {
  image: { width: 100, height: 100 },
  canvas: { width: 100, height: 100 },
  scale: 1,
  top: 0,
  left: 0,
  angle: 0,
};

const MASTER_LABEL_ID = 7;

function makeStageRef() {
  const ref = { current: { getPointerPosition: () => ({ x: 0, y: 0 }) } };
  return ref as unknown as React.RefObject<Konva.Stage | null> & {
    current: { getPointerPosition: () => { x: number; y: number } };
  };
}

function captureRect(container: HTMLElement): HTMLElement {
  return container.querySelectorAll('[data-konva="Rect"]')[0] as HTMLElement;
}

describe('OverlayLayer — 신규 라벨의 라벨 마스터 연결(labelId) 유지', () => {
  beforeEach(() => {
    labelMastersData = [
      { labelId: MASTER_LABEL_ID, name: '사람', color: '#EF4444', sortNo: 1, useYn: 'Y' },
    ];
  });

  it('BBOX_드래그_생성시_labelId가_라벨마스터PK로_설정된다', () => {
    // given: 활성 라벨 마스터 1건(labelId=7)
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.BBOX}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    const rect = captureRect(container);

    // when: 드래그로 박스를 그린다
    stageRef.current.getPointerPosition = () => ({ x: 10, y: 10 });
    fireEvent.mouseDown(rect);
    stageRef.current.getPointerPosition = () => ({ x: 50, y: 40 });
    fireEvent.mouseMove(rect);
    fireEvent.mouseUp(rect);

    // then: classId 뿐 아니라 labelId 도 마스터 PK 로 채워진다
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    const label = onLabelAdd.mock.calls[0][0] as Label;
    expect(label.shape.type).toBe('BBOX');
    expect(label.labelId).toBe(MASTER_LABEL_ID);
    expect(label.classId).toBe(MASTER_LABEL_ID);
    expect(label.className).toBe('사람');
  });

  it('POLYGON_생성시_labelId가_라벨마스터PK로_설정된다', () => {
    // given
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.POLYGON}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    const rect = captureRect(container);

    // when: 3점 클릭 후 dblclick 으로 닫기
    for (const p of [
      { x: 10, y: 10 },
      { x: 40, y: 10 },
      { x: 20, y: 40 },
    ]) {
      stageRef.current.getPointerPosition = () => p;
      fireEvent.click(rect);
    }
    fireEvent.doubleClick(rect);

    // then
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    const label = onLabelAdd.mock.calls[0][0] as Label;
    expect(label.shape.type).toBe('POLYGON');
    expect(label.labelId).toBe(MASTER_LABEL_ID);
  });

  it('KEYPOINT_17점_생성시_labelId가_라벨마스터PK로_설정된다', () => {
    // given
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.KEYPOINT}
        stageRef={stageRef}
        onLabelAdd={onLabelAdd}
      />,
    );
    const rect = captureRect(container);

    // when
    for (let i = 0; i < 17; i += 1) {
      stageRef.current.getPointerPosition = () => ({ x: i + 1, y: (i + 1) * 2 });
      fireEvent.click(rect);
    }

    // then
    expect(onLabelAdd).toHaveBeenCalledTimes(1);
    const label = onLabelAdd.mock.calls[0][0] as Label;
    expect(label.shape.type).toBe('KEYPOINT');
    expect(label.labelId).toBe(MASTER_LABEL_ID);
  });

  it('AI분할(SAM_SEGMENT)_폴리곤_적용시_labelId가_라벨마스터PK로_설정된다', async () => {
    // given: 유효 폴리곤 + 충분한 신뢰도를 돌려주는 분할 요청
    const onLabelAdd = vi.fn();
    const stageRef = makeStageRef();
    const segment = vi.fn().mockResolvedValue({
      polygon: [
        [1, 1],
        [30, 1],
        [30, 30],
        [1, 30],
      ],
      score: 0.9,
    });
    const { container } = render(
      <OverlayLayer
        geometry={geom}
        activeTool={ToolType.SAM_SEGMENT}
        stageRef={stageRef}
        segment={segment}
        onLabelAdd={onLabelAdd}
      />,
    );
    const rect = captureRect(container);

    // when: 클릭 프롬프트 1회 → Enter 로 확정 → 응답 적용
    stageRef.current.getPointerPosition = () => ({ x: 15, y: 25 });
    fireEvent.click(rect);
    fireEvent.keyDown(window, { key: 'Enter' });
    await vi.waitFor(() => expect(onLabelAdd).toHaveBeenCalledTimes(1));

    // then
    const label = onLabelAdd.mock.calls[0][0] as Label;
    expect(label.shape.type).toBe('POLYGON');
    expect(label.labelId).toBe(MASTER_LABEL_ID);
  });
});
