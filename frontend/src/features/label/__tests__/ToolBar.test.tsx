// ISSUE-2 — ToolBar SAM2 분할/추적 도구 버튼 노출 + 선택 동작 검증.

import { fireEvent, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useLabelStore } from '@/stores/useLabelStore';
import { renderWithProviders } from '@/test/renderWithProviders';

import { ToolBar } from '../components/ToolBar';
import { ToolType } from '../types';

describe('ToolBar — SAM2 도구', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('AI_분할_버튼이_렌더된다', () => {
    renderWithProviders(<ToolBar />);
    expect(screen.getByRole('button', { name: 'AI 분할' })).toBeInTheDocument();
  });

  /*
   * ★반전된 가드 — 구 케이스 `SAM_분할과_SAM_추적_버튼이_렌더된다` 의 추적 부분과
   *   `SAM_추적_클릭_시_activeTool이_TRACK으로_전환된다` 를 대체한다(지우지 않고 뒤집는다).
   *
   * 구 단언: 좌측 도구바에 'AI 추적' 버튼이 있고, 누르면 activeTool 이 TRACK 으로 바뀐다.
   * 새 단언: 좌측 도구바에 'AI 추적' 버튼을 **두지 않는다**.
   *
   * 왜 뒤집혔나 — 사양 SCREEN-005 §좌측 도구바의 버튼은 선택/이동·바운딩박스·폴리곤·AI 분할·
   * 키포인트·AI 탐지 여섯이며 AI 추적은 그 목록에 없다. AI 추적은 이미 그려진 객체 하나를 뒤
   * 프레임으로 전파하는 행위라 대상이 정해진 뒤에야 성립하고, 실행 진입점은 우측 객체 패널의
   * 선택 객체 속성이다(§라벨링 캔버스 — "우측 패널 '객체' 탭에서 대상 객체를 펼쳤을 때 노출되는
   * 버튼으로 실행한다"). 도구바에 모드 버튼을 두면 "모드를 켜야 실행 버튼이 나타나는" 2단 동선이
   * 되어 사양과 어긋난다.
   *
   * ⚠ 단축키(Shift+T)는 사양이 유지하므로 이 가드가 막는 것은 **도구바 버튼**뿐이다.
   */
  it('AI_추적_버튼은_좌측_도구바에_두지_않는다', () => {
    renderWithProviders(<ToolBar />);
    expect(screen.queryByRole('button', { name: 'AI 추적' })).toBeNull();
  });

  it('SAM_분할_클릭_시_activeTool이_SAM_SEGMENT로_전환된다', () => {
    renderWithProviders(<ToolBar />);
    fireEvent.click(screen.getByRole('button', { name: 'AI 분할' }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SAM_SEGMENT);
  });

});

describe('ToolBar — 키포인트 도구', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('키포인트_버튼이_렌더된다', () => {
    renderWithProviders(<ToolBar />);
    expect(screen.getByRole('button', { name: '스켈레톤' })).toBeInTheDocument();
  });

  it('키포인트_클릭_시_activeTool이_KEYPOINT로_전환된다', () => {
    renderWithProviders(<ToolBar />);
    fireEvent.click(screen.getByRole('button', { name: '스켈레톤' }));
    expect(useLabelStore.getState().activeTool).toBe(ToolType.KEYPOINT);
  });

  it('포털_사용자는_AI분할_도구를_사용할_수_없다', () => {
    // ADR-013 — 포털은 SAM2·오토라벨 미제공. 서버 엔드포인트도 제거됐다(PortalSam2RemovedTest).
    renderWithProviders(<ToolBar portalMode />);
    expect(screen.queryByRole('button', { name: 'AI 분할' })).not.toBeInTheDocument();
  });

  it('포털_사용자는_AI추적_도구를_사용할_수_없다', () => {
    // ★부재 사유가 바뀌었다 — 이제는 채널과 무관하게 도구바에 AI 추적 버튼 자체가 없다
    //   (위 `AI_추적_버튼은_좌측_도구바에_두지_않는다` 참조). 그래도 이 가드는 남긴다:
    //   포털 미제공(ADR-013)은 도구바 구성과 **다른 축**이라, 도구바에 되살아나더라도 포털에서는
    //   반드시 빠져 있어야 한다는 계약을 이 케이스가 계속 고정한다.
    renderWithProviders(<ToolBar portalMode />);
    expect(screen.queryByRole('button', { name: 'AI 추적' })).not.toBeInTheDocument();
  });

  it('포털_사용자는_스켈레톤_도구를_사용할_수_없다', () => {
    renderWithProviders(<ToolBar portalMode />);
    expect(screen.queryByRole('button', { name: '스켈레톤' })).not.toBeInTheDocument();
  });

  it('포털_모드에서도_수동_라벨링_도구는_그대로_노출된다', () => {
    // 포털 사용자는 BBOX/POLYGON 수동 라벨링을 계속 제공받는다(ADR-013 예외) — 과잉 차단 회귀 가드.
    renderWithProviders(<ToolBar portalMode />);
    expect(screen.getByRole('button', { name: '바운딩 박스' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '폴리곤' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '선택' })).toBeInTheDocument();
  });

  it('내부_라벨링_화면의_SAM2_도구는_기존과_동일하게_노출된다', () => {
    // SFR-08-01(VOS) 핵심 기능 — 포털 제거가 내부(INTERNAL) 채널을 함께 막았는지 회귀 고정.
    // ★'AI 추적'은 여기서 세지 않는다 — 도구바가 아니라 우측 객체 패널이 실행 진입점이다(사양 정합).
    renderWithProviders(<ToolBar />);
    expect(screen.getByRole('button', { name: 'AI 분할' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '스켈레톤' })).toBeInTheDocument();
  });
});

describe('ToolBar — YOLO 오토라벨', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  it('onAutolabel_핸들러_지정시_YOLO_버튼_렌더_및_클릭_호출', () => {
    const onAutolabel = vi.fn();
    renderWithProviders(<ToolBar onAutolabel={onAutolabel} />);
    const btn = screen.getByRole('button', { name: 'AI 탐지' });
    expect(btn).toBeInTheDocument();
    fireEvent.click(btn);
    expect(onAutolabel).toHaveBeenCalledTimes(1);
  });

  it('onAutolabel_미지정시_YOLO_버튼_미노출', () => {
    renderWithProviders(<ToolBar />);
    expect(screen.queryByRole('button', { name: 'AI 탐지' })).not.toBeInTheDocument();
  });

  it('진행중이면_YOLO_버튼_비활성화되어_중복클릭_방지', () => {
    const onAutolabel = vi.fn();
    renderWithProviders(<ToolBar onAutolabel={onAutolabel} isAutolabeling />);
    const btn = screen.getByRole('button', { name: 'AI 탐지' });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('aria-busy', 'true');
    fireEvent.click(btn);
    expect(onAutolabel).not.toHaveBeenCalled();
  });

  it('portalMode에서_YOLO_오토라벨_버튼_숨김_ADR_013', () => {
    renderWithProviders(<ToolBar onAutolabel={vi.fn()} portalMode />);
    expect(screen.queryByRole('button', { name: 'AI 탐지' })).not.toBeInTheDocument();
  });
});
