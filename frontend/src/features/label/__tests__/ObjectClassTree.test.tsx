// SCR-LABEL-001 우측 상단 객체 트리 — Phase 5 trackId 시각화 정합 테스트.

import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ObjectClassTree } from '../components/ObjectClassTree';
import type { Label } from '../types';
import { trackIdToColor } from '../utils/trackColor';

function makeLabel(over: Partial<Label> & Pick<Label, 'id'>): Label {
  return {
    frameNo: 1,
    classId: 1,
    className: 'person',
    source: 'AUTO_YOLO',
    shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 },
    ...over,
  };
}

describe('ObjectClassTree — Phase 5 trackId 시각화', () => {
  it('trackId 있는 객체는 #{trackId} 표시', () => {
    useLabelStore.getState().reset();
    const labels: Label[] = [
      makeLabel({ id: 'a', trackId: '42' }),
      makeLabel({ id: 'b', trackId: '99' }),
    ];
    renderWithProviders(<ObjectClassTree labels={labels} />);

    // displayName 은 person → '사람' 등으로 매핑될 수 있으므로 #N 부분만 검증
    expect(screen.getByText(/#42$/)).toBeInTheDocument();
    expect(screen.getByText(/#99$/)).toBeInTheDocument();
  });

  it('trackId 없는 객체는 #{1, 2, 3} 그룹 순번 fallback', () => {
    useLabelStore.getState().reset();
    const labels: Label[] = [
      makeLabel({ id: 'a' }),
      makeLabel({ id: 'b' }),
      makeLabel({ id: 'c' }),
    ];
    renderWithProviders(<ObjectClassTree labels={labels} />);

    expect(screen.getByText(/#1$/)).toBeInTheDocument();
    expect(screen.getByText(/#2$/)).toBeInTheDocument();
    expect(screen.getByText(/#3$/)).toBeInTheDocument();
  });

  it('각 행 좌측에 trackColor 컬러 바 표시', () => {
    useLabelStore.getState().reset();
    const labels: Label[] = [
      makeLabel({ id: 'a', trackId: '42' }),
      makeLabel({ id: 'b', trackId: '99' }),
    ];
    const { container } = renderWithProviders(<ObjectClassTree labels={labels} />);

    const bars = container.querySelectorAll('[data-testid="label-color-bar"]');
    expect(bars.length).toBe(2);
    const c0 = (bars[0] as HTMLElement).style.backgroundColor;
    const c1 = (bars[1] as HTMLElement).style.backgroundColor;
    expect(c0).not.toBe('');
    expect(c1).not.toBe('');
    // jsdom 의 hsl(0,0%,60%) → rgb(153,153,153) (회색 fallback 아님)
    const grayRgb = 'rgb(153, 153, 153)';
    expect(c0).not.toBe(grayRgb);
    expect(c1).not.toBe(grayRgb);
    expect(c0).not.toBe(c1);
  });

  it('같은 trackId 객체는 같은 색', () => {
    useLabelStore.getState().reset();
    // 같은 trackId 를 갖는 두 객체 (예: 같은 사람을 다른 클래스로 잘못 잡은 케이스 시뮬레이션은 어렵지만,
    // 같은 클래스 안에서 같은 trackId 가 다른 row 로 들어온 경우를 검증)
    const labels: Label[] = [
      makeLabel({ id: 'a', trackId: '42' }),
      makeLabel({ id: 'b', trackId: '42' }),
    ];
    const { container } = renderWithProviders(<ObjectClassTree labels={labels} />);

    const bars = container.querySelectorAll('[data-testid="label-color-bar"]');
    expect(bars.length).toBe(2);
    const c0 = (bars[0] as HTMLElement).style.backgroundColor;
    const c1 = (bars[1] as HTMLElement).style.backgroundColor;
    expect(c0).toBe(c1);
    // 헬퍼 함수 동일 입력 동일 출력 검증 (참조 무결성)
    expect(trackIdToColor('42')).toBe(trackIdToColor('42'));
  });

  it('aria-label 도 trackId 우선으로 갱신', () => {
    useLabelStore.getState().reset();
    const labels: Label[] = [makeLabel({ id: 'a', trackId: '42' })];
    renderWithProviders(<ObjectClassTree labels={labels} />);

    // displayName 매핑은 labelColors 모듈 책임이므로 정규식으로만 검증
    const btn = screen.getByLabelText(/#42 선택$/);
    expect(btn).toBeInTheDocument();
  });

  it('trackId 없는 객체는 회색 fallback 컬러 바', () => {
    useLabelStore.getState().reset();
    const labels: Label[] = [makeLabel({ id: 'a' })];
    const { container } = renderWithProviders(<ObjectClassTree labels={labels} />);

    const bar = container.querySelector(
      '[data-testid="label-color-bar"]',
    ) as HTMLElement | null;
    expect(bar).not.toBeNull();
    // hsl(0,0%,60%) 의 jsdom rgb 변환
    expect(bar!.style.backgroundColor).toBe('rgb(153, 153, 153)');
  });

  it('빈 라벨 목록일 때 안내 문구 표시 (회귀 방지)', () => {
    useLabelStore.getState().reset();
    renderWithProviders(<ObjectClassTree labels={[]} />);
    expect(screen.getByText(/이 프레임에 객체가 없습니다/)).toBeInTheDocument();
  });

  it('INTERPOLATED 객체 행에 🔗 아이콘 표시', () => {
    useLabelStore.getState().reset();
    const labels: Label[] = [
      makeLabel({ id: 'a', trackId: '7', source: 'AUTO_YOLO', lblSrcCd: 'INTERPOLATED' }),
    ];
    renderWithProviders(<ObjectClassTree labels={labels} />);

    const btn = screen.getByLabelText(/#7 선택$/);
    expect(btn).toBeInTheDocument();
    // aria-hidden 인 아이콘 텍스트에 🔗 포함
    expect(btn.textContent ?? '').toContain('🔗');
    expect(btn.textContent ?? '').not.toContain('🤖');
  });

  it('DETECTED 자동 객체는 🤖, 수동은 ✏️, 보간은 🔗', () => {
    useLabelStore.getState().reset();
    const labels: Label[] = [
      makeLabel({ id: 'a', trackId: '1', source: 'AUTO_YOLO', lblSrcCd: null }),
      makeLabel({ id: 'b', trackId: '2', source: 'MANUAL', lblSrcCd: null }),
      makeLabel({ id: 'c', trackId: '3', source: 'AUTO_YOLO', lblSrcCd: 'INTERPOLATED' }),
    ];
    renderWithProviders(<ObjectClassTree labels={labels} />);

    const auto = screen.getByLabelText(/#1 선택$/);
    const manual = screen.getByLabelText(/#2 선택$/);
    const interp = screen.getByLabelText(/#3 선택$/);

    expect(auto.textContent ?? '').toContain('🤖');
    expect(auto.textContent ?? '').not.toContain('🔗');
    expect(manual.textContent ?? '').toContain('✏️');
    expect(manual.textContent ?? '').not.toContain('🔗');
    expect(interp.textContent ?? '').toContain('🔗');
    expect(interp.textContent ?? '').not.toContain('🤖');
  });

  // Phase 10(축소) — 포털은 트랙 데이터모델 부재(프레임별 단건)라 rename/머지 미제공.
  // 연필(트랙 번호 변경) 진입 자체를 포털 모드에서 차단한다.
  it('포털모드_트랙_rename_버튼_미노출', () => {
    useLabelStore.getState().reset();
    const labels: Label[] = [makeLabel({ id: 'a', trackId: '42' })];
    renderWithProviders(<ObjectClassTree labels={labels} portalMode onRenameTrack={vi.fn()} />);

    // 연필(트랙 번호 변경) 버튼이 DOM 에 존재하지 않아야 한다(opacity-0 노출 여부와 무관).
    expect(screen.queryByLabelText(/트랙 번호 변경$/)).toBeNull();
    // 객체 선택/삭제 등 기본 기능은 그대로 노출 — 회귀 가드.
    expect(screen.getByLabelText(/#42 선택$/)).toBeInTheDocument();
    expect(screen.getByLabelText('객체 삭제')).toBeInTheDocument();
  });

  it('비포털_rename_기존동작_유지', async () => {
    useLabelStore.getState().reset();
    const onRenameTrack = vi.fn();
    const labels: Label[] = [makeLabel({ id: 'a', trackId: '42' })];
    // portalMode 미지정(기본 false) — 내부(REVIEWER/WORKER) 모드.
    renderWithProviders(<ObjectClassTree labels={labels} onRenameTrack={onRenameTrack} />);

    const user = userEvent.setup();
    // 연필 버튼 노출 확인 후 rename 진입.
    const pencil = screen.getByLabelText(/트랙 번호 변경$/);
    await user.click(pencil);

    const input = screen.getByLabelText('트랙 번호 입력');
    await user.clear(input);
    await user.type(input, '7');
    await user.click(screen.getByLabelText('트랙 번호 저장'));

    // 서버 트랙(current=42 != null)의 rename → 내부 콜백(mergeTracks 배선) 호출.
    expect(onRenameTrack).toHaveBeenCalledWith('42', '7');
  });

  it('shapeType 표시 회귀 방지 — BBOX/POLYGON', () => {
    useLabelStore.getState().reset();
    const labels: Label[] = [
      makeLabel({ id: 'a', trackId: '1', shape: { type: 'BBOX', left: 0, top: 0, right: 10, bottom: 10 } }),
      makeLabel({
        id: 'b',
        trackId: '2',
        shape: { type: 'POLYGON', points: [0, 0, 10, 0, 10, 10] },
      }),
    ];
    const { container } = renderWithProviders(<ObjectClassTree labels={labels} />);
    // 두 shape type 모두 노출 — within 으로 행 단위 검증은 까다로워 텍스트 매칭으로 충분
    expect(within(container).getAllByText('BBOX').length).toBeGreaterThan(0);
    expect(within(container).getAllByText('POLYGON').length).toBeGreaterThan(0);
  });
});
