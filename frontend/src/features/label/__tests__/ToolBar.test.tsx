// ISSUE-2 — ToolBar SAM2 분할/추적 도구 버튼 노출 + 선택 동작 검증.

import { fireEvent, screen, within } from '@testing-library/react';
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

  it('포털_사용자도_AI분할_도구를_AI_보조_묶음에서_쓴다', () => {
    // ★반전(2026-09-15 · SCREEN-029) — 구 가드 「포털_사용자는_AI분할_도구를_사용할_수_없다」.
    //   포털도 AI 분할을 포털 전용 창구로 쓴다. 그리기 묶음이 아니라 「AI 보조」 묶음에 선다.
    renderWithProviders(<ToolBar portalMode />);
    const aiGroup = screen.getByTestId('label-toolbar-ai-group');
    const btn = within(aiGroup).getByRole('button', { name: 'AI 분할' });
    fireEvent.click(btn);
    expect(useLabelStore.getState().activeTool).toBe(ToolType.SAM_SEGMENT);
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

  it('portalMode에서도_AI_탐지_버튼이_AI_보조_묶음에_서고_팝업_핸들러를_부른다', () => {
    // ★반전(2026-09-15 · SCREEN-029) — 구 가드 「portalMode에서_YOLO_오토라벨_버튼_숨김_ADR_013」.
    const onAutolabel = vi.fn();
    renderWithProviders(<ToolBar onAutolabel={onAutolabel} portalMode />);
    const aiGroup = screen.getByTestId('label-toolbar-ai-group');
    fireEvent.click(within(aiGroup).getByRole('button', { name: 'AI 탐지' }));
    expect(onAutolabel).toHaveBeenCalledTimes(1);
  });
});

describe('ToolBar — 포털 채널 묶음 구성 (SCREEN-029)', () => {
  beforeEach(() => {
    useLabelStore.getState().reset();
  });

  const cardTitles = () => screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
  const namesIn = (el: HTMLElement) =>
    within(el)
      .getAllByRole('button')
      .map((b) => b.getAttribute('aria-label'));

  it('포털은_그리기_AI보조_보기_세_묶음이고_AI보조에_세_버튼이_순서대로_선다', () => {
    renderWithProviders(
      <ToolBar portalMode onAutolabel={vi.fn()} onFocusAutoTrack={vi.fn()} onToggleGrid={vi.fn()} />,
    );
    expect(cardTitles()).toEqual(['그리기', 'AI 보조', '보기']);
    expect(namesIn(screen.getByTestId('label-toolbar-ai-group'))).toEqual([
      'AI 탐지',
      'AI 분할',
      // 접근성 이름은 패널의 실행 버튼(「AI 자동 추적」)과 구별된다 — 보이는 이름을 포함한다.
      'AI 자동 추적 패널로 이동',
    ]);
    // 그리기 묶음에는 AI 기능이 섞이지 않는다.
    const drawCard = screen.getByRole('heading', { name: '그리기' }).closest('section') as HTMLElement;
    expect(namesIn(drawCard)).toEqual(['선택', '바운딩 박스', '폴리곤']);
    // 스켈레톤·선택 객체 AI 추적은 계속 없다.
    expect(screen.queryByRole('button', { name: '스켈레톤' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'AI 추적' })).toBeNull();
  });

  it('내부_채널은_종전_두_묶음_그대로이고_AI보조_묶음과_자동추적_버튼이_없다', () => {
    // 관제향 무변경 가드 — onFocusAutoTrack 이 새어 들어와도 내부 채널은 그 버튼을 두지 않는다.
    renderWithProviders(<ToolBar onAutolabel={vi.fn()} onFocusAutoTrack={vi.fn()} />);
    expect(cardTitles()).toEqual(['그리기 도구', '보기']);
    expect(screen.queryByTestId('label-toolbar-ai-group')).toBeNull();
    expect(screen.queryByRole('button', { name: /AI 자동 추적/ })).toBeNull();
    const drawCard = screen
      .getByRole('heading', { name: '그리기 도구' })
      .closest('section') as HTMLElement;
    expect(namesIn(drawCard)).toEqual(['선택', '바운딩 박스', '폴리곤', 'AI 분할', '스켈레톤', 'AI 탐지']);
  });

  it('포털_AI자동추적_버튼은_실행이_아니라_이동_핸들러만_부른다', () => {
    const onFocusAutoTrack = vi.fn();
    renderWithProviders(<ToolBar portalMode onFocusAutoTrack={onFocusAutoTrack} />);
    fireEvent.click(screen.getByRole('button', { name: 'AI 자동 추적 패널로 이동' }));
    expect(onFocusAutoTrack).toHaveBeenCalledTimes(1);
  });

  it('포털_AI자동추적_사용불가_사유가_있으면_비활성이고_사유를_툴팁과_보조문으로_보인다', () => {
    const onFocusAutoTrack = vi.fn();
    const reason = '뒤따르는 프레임이 없어 AI 자동 추적을 쓸 수 없습니다.';
    renderWithProviders(
      <ToolBar portalMode onFocusAutoTrack={onFocusAutoTrack} autoTrackUnavailableReason={reason} />,
    );
    const btn = screen.getByRole('button', { name: 'AI 자동 추적 패널로 이동' });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute('title', `AI 자동 추적 (${reason})`);
    // 보조문이 화면에 보이고, 비활성 버튼이 그 문장을 설명으로 가리킨다(보조기술 전달).
    const caption = screen.getByText(reason);
    expect(btn).toHaveAttribute('aria-describedby', caption.id);
    fireEvent.click(btn);
    expect(onFocusAutoTrack).not.toHaveBeenCalled();
  });

  it('포털_AI자동추적_사유가_없으면_보조문을_두지_않는다', () => {
    renderWithProviders(<ToolBar portalMode onFocusAutoTrack={vi.fn()} />);
    const btn = screen.getByRole('button', { name: 'AI 자동 추적 패널로 이동' });
    expect(btn).toBeEnabled();
    expect(btn).not.toHaveAttribute('aria-describedby');
    expect(screen.queryByText(/AI 자동 추적을 쓸 수 없습니다/)).toBeNull();
  });
});
