// ObjectAttributePanel Phase 6 완성 테스트.

import { fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectAttributePanel } from '../components/ObjectAttributePanel';
import type { Label } from '../types';

const sampleAuto: Label = {
  id: 'auto1',
  frameNo: 1,
  classId: 3,
  className: 'pedestrian',
  source: 'AUTO_YOLO',
  confidence: 0.85,
  shape: { type: 'BBOX', left: 10, top: 20, right: 100, bottom: 80 },
};

const sampleManual: Label = {
  id: 'man1',
  frameNo: 1,
  classId: 2,
  className: 'car',
  source: 'MANUAL',
  shape: { type: 'BBOX', left: 0, top: 0, right: 50, bottom: 30 },
};

describe('ObjectAttributePanel Phase 6 완성', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('자동라벨_뱃지_+_수동_뱃지_표시', () => {
    useLabelStore.getState().setLabels([sampleAuto]);
    useLabelStore.getState().selectLabel('auto1');
    const { rerender } = renderWithProviders(<ObjectAttributePanel labels={[sampleAuto]} />);
    // 생성출처 필드 텍스트 컨텐츠 검증 (placeholder 텍스트 제외)
    // 라벨이 "pedestrian"이고 생성출처가 "자동"인지 확인
    expect(screen.getByText('자동')).toBeInTheDocument();

    useLabelStore.getState().setLabels([sampleManual]);
    useLabelStore.getState().selectLabel('man1');
    rerender(<ObjectAttributePanel labels={[sampleManual]} />);
    expect(screen.getByText('수동')).toBeInTheDocument();
  });

  it('오브젝트_속성_패널_좌표_변경시_캔버스_반영', () => {
    useLabelStore.getState().setLabels([sampleAuto]);
    useLabelStore.getState().selectLabel('auto1');
    renderWithProviders(<ObjectAttributePanel labels={[sampleAuto]} />);

    const xInput = screen.getByLabelText(/X 좌표/i) as HTMLInputElement;
    fireEvent.change(xInput, { target: { value: '50' } });

    const updated = useLabelStore.getState().labels.find((l) => l.id === 'auto1');
    expect(updated?.shape.type).toBe('BBOX');
    if (updated?.shape.type === 'BBOX') {
      expect(updated.shape.left).toBe(50);
    }
  });

  it('오브젝트_속성_패널_라벨_드롭다운_변경시_색상_업데이트', () => {
    useLabelStore.getState().setLabels([sampleAuto]);
    useLabelStore.getState().selectLabel('auto1');
    renderWithProviders(
      <ObjectAttributePanel
        labels={[sampleAuto]}
        availableLabels={[
          { id: 1, name: 'car', color: '#ff0000' },
          { id: 3, name: 'pedestrian', color: '#00ff00' },
          { id: 5, name: 'bicycle', color: '#0000ff' },
        ]}
      />,
    );
    const select = screen.getByLabelText(/라벨 선택|라벨$/i) as HTMLSelectElement;
    fireEvent.change(select, { target: { value: '5' } });

    const updated = useLabelStore.getState().labels.find((l) => l.id === 'auto1');
    expect(updated?.classId).toBe(5);
    expect(updated?.className).toBe('bicycle');
  });

  it('신뢰도_바_렌더링', () => {
    useLabelStore.getState().setLabels([sampleAuto]);
    useLabelStore.getState().selectLabel('auto1');
    renderWithProviders(<ObjectAttributePanel labels={[sampleAuto]} />);
    // progressbar 또는 신뢰도 텍스트
    const bar = screen.queryByRole('progressbar');
    expect(bar).toBeTruthy();
  });

  it('낮은_신뢰도_경고_뱃지', () => {
    const low = { ...sampleAuto, confidence: 0.3 };
    useLabelStore.getState().setLabels([low]);
    useLabelStore.getState().selectLabel('auto1');
    renderWithProviders(<ObjectAttributePanel labels={[low]} />);
    expect(screen.getByRole('status')).toHaveTextContent(/낮은 신뢰도/);
  });

  it('좌표_입력_이미지_경계_초과시_clamp', () => {
    useLabelStore.getState().setLabels([sampleAuto]);
    useLabelStore.getState().selectLabel('auto1');
    renderWithProviders(
      <ObjectAttributePanel labels={[sampleAuto]} imageWidth={1000} imageHeight={500} />,
    );
    const xInput = screen.getByLabelText(/X 좌표/i) as HTMLInputElement;
    fireEvent.change(xInput, { target: { value: '-50' } });
    const updated = useLabelStore.getState().labels.find((l) => l.id === 'auto1');
    if (updated?.shape.type === 'BBOX') {
      expect(updated.shape.left).toBeGreaterThanOrEqual(0);
    }
  });

  it('비1080_프레임_H하단_1400입력이_실측기준_유지_1079로_잘리지않음', () => {
    // 회귀: 1920×1440 프레임에서 하드코딩 1080 clamp 로 bottom=1400 이 1079 로 묵음 잘리던 버그.
    useLabelStore.getState().setLabels([sampleAuto]);
    useLabelStore.getState().selectLabel('auto1');
    renderWithProviders(
      <ObjectAttributePanel labels={[sampleAuto]} imageWidth={1920} imageHeight={1440} />,
    );
    const hInput = screen.getByLabelText('H 하단') as HTMLInputElement;
    fireEvent.change(hInput, { target: { value: '1400' } });
    const updated = useLabelStore.getState().labels.find((l) => l.id === 'auto1');
    if (updated?.shape.type === 'BBOX') {
      expect(updated.shape.bottom).toBe(1400); // 1079 아님
    }
  });

  it('비1080_프레임_H하단_초과입력은_실측_1440경계로_clamp', () => {
    useLabelStore.getState().setLabels([sampleAuto]);
    useLabelStore.getState().selectLabel('auto1');
    renderWithProviders(
      <ObjectAttributePanel labels={[sampleAuto]} imageWidth={1920} imageHeight={1440} />,
    );
    const hInput = screen.getByLabelText('H 하단') as HTMLInputElement;
    fireEvent.change(hInput, { target: { value: '2000' } });
    const updated = useLabelStore.getState().labels.find((l) => l.id === 'auto1');
    if (updated?.shape.type === 'BBOX') {
      expect(updated.shape.bottom).toBe(1439); // imageHeight - 1
    }
  });

  it('실측_dims_미확정시_상한_하드코딩1080_clamp_미적용', () => {
    // 이미지 로드 전(imageWidth/Height undefined) — 상한 clamp 미적용, 하한 0 만 유지.
    useLabelStore.getState().setLabels([sampleAuto]);
    useLabelStore.getState().selectLabel('auto1');
    renderWithProviders(<ObjectAttributePanel labels={[sampleAuto]} />);
    const hInput = screen.getByLabelText('H 하단') as HTMLInputElement;
    fireEvent.change(hInput, { target: { value: '1400' } });
    const updated = useLabelStore.getState().labels.find((l) => l.id === 'auto1');
    if (updated?.shape.type === 'BBOX') {
      expect(updated.shape.bottom).toBe(1400); // 1079 로 잘리지 않음
    }
  });

  it('헤더 #N 은 trackId 를 표기하지 않고 중립 식별자(id 앞 8자)만 사용', () => {
    const withTrack: Label = { ...sampleAuto, trackId: '42' };
    useLabelStore.getState().setLabels([withTrack]);
    useLabelStore.getState().selectLabel('auto1');
    renderWithProviders(<ObjectAttributePanel labels={[withTrack]} />);
    // 헤더는 track_id(42) 가 아니라 id 슬라이스(auto1) — track_id 와 역할 분리.
    const idSpan = screen.getByTestId('object-attribute-id');
    expect(idSpan.textContent).toBe('#auto1');
    expect(idSpan.textContent).not.toContain('42');
    // track_id 는 신설 "트랙 ID" 필드가 전담.
    expect(screen.getByTestId('object-track-id')).toHaveTextContent('42');
  });

  it('trackId 없는 객체는 헤더에 id 앞 8자 fallback', () => {
    // id 가 8자보다 긴 경우 (앞 8자만)
    const longId: Label = { ...sampleAuto, id: 'abcdef0123-zzz', trackId: undefined };
    useLabelStore.getState().setLabels([longId]);
    useLabelStore.getState().selectLabel('abcdef0123-zzz');
    renderWithProviders(<ObjectAttributePanel labels={[longId]} />);
    const idSpan = screen.getByTestId('object-attribute-id');
    expect(idSpan.textContent).toBe('#abcdef01');
  });

  it('trackId 가 null 인 객체도 id fallback (legacy DB row)', () => {
    const legacy: Label = { ...sampleAuto, id: 'leg12345-rest', trackId: null };
    useLabelStore.getState().setLabels([legacy]);
    useLabelStore.getState().selectLabel('leg12345-rest');
    renderWithProviders(<ObjectAttributePanel labels={[legacy]} />);
    const idSpan = screen.getByTestId('object-attribute-id');
    expect(idSpan.textContent).toBe('#leg12345');
  });
});
