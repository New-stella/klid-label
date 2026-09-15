// Phase 4 / COCO 매핑 — AI Tool 팝업 테스트 (형태 라디오 + 라벨 후보 + 일반/트랙 + 정밀도).
//
// 라벨 후보(candidates)는 부모(LabelingPage)가 useDetectCandidates 로 주입한다(BE 후보 조회).
// 매핑된 라벨만 선택 가능(체크박스 활성), 미매핑은 표시하되 disabled + '미매핑' 안내.
// 전송값은 매핑 라벨의 COCO 클래스(dtctTypeCd)이며, 사용자 노출 문구에 모델명(YOLO/SAM/SAM2) 금지.

import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders } from '@/test/renderWithProviders';
import type { DetectCandidate } from '../api/labelMaster';

import { AiToolModal } from '../components/AiToolModal';

// 매핑 라벨(사람→person) + 미매핑 라벨(가방)로 구성 — 선택/disabled 를 함께 검증.
const CANDIDATES: DetectCandidate[] = [
  { labelId: 10, name: '사람', color: '#EF4444', type: 'BBOX', dtctTypeCd: 'person', mapped: true },
  { labelId: 11, name: '가방', color: '#22C55E', type: 'BBOX', dtctTypeCd: null, mapped: false },
];

describe('AiToolModal', () => {
  it('열리면_형태_라디오와_라벨_후보가_렌더된다', () => {
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} candidates={CANDIDATES} />,
    );
    expect(screen.getByRole('radio', { name: '박스' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '폴리곤' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '사람' })).toBeInTheDocument();
    // 기본 형태는 박스.
    expect(screen.getByRole('radio', { name: '박스' })).toBeChecked();
  });

  // ★레이아웃 계약 (2026-08-08 실측 결함 회귀 가드): 모달 폭이 768px 로 넓어졌는데 라벨 목록이
  //   1열이라 9종 중 4종만 보이고 우측이 통째로 비어 있었다. 원인은 `sm:` 브레이크포인트 —
  //   tailwind.config 가 screens 를 md/xl 로 **교체**해 `sm:` 접두 클래스는 생성조차 되지 않는다.
  // ⚠ jsdom 은 CSS 를 적용하지 않아 "몇 열로 보이는지"를 재현하지 못한다 — 죽은 브레이크포인트가
  //   다시 들어오지 않는지(클래스 문자열)만 구조로 막는다. 열 수 실측은 브라우저 몫이다.
  it('★라벨_목록은_다열_배치와_스크롤_상자_계약을_갖는다_sm_은_이_프로젝트에_없는_브레이크포인트다', () => {
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} candidates={CANDIDATES} />,
    );
    const list = screen.getByTestId('ai-tool-label-list');
    // 스크롤은 legend 바깥의 별도 상자가 담당한다(제목까지 함께 스크롤되면 안 된다).
    expect(list.className).toContain('overflow-y-auto');
    expect(list.className).toMatch(/max-h-\[min\(/);

    const grid = list.querySelector('.grid');
    expect(grid?.className).toContain('md:grid-cols-2');
    // 생성되지 않는 변종이 다시 들어오면 조용히 1열로 되돌아간다.
    expect(grid?.className).not.toContain('sm:');
  });

  it('미매핑_라벨은_표시되지만_선택_불가(disabled)다', () => {
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} candidates={CANDIDATES} />,
    );
    // 매핑 라벨은 선택 가능, 미매핑 라벨(가방)은 disabled(접근명은 "가방 미매핑").
    expect(screen.getByRole('checkbox', { name: '사람' })).not.toBeDisabled();
    expect(screen.getByRole('checkbox', { name: /가방/ })).toBeDisabled();
    // 미매핑 안내 문구 노출.
    expect(screen.getByText('미매핑')).toBeInTheDocument();
  });

  it('후보가_로딩중이면_로딩_안내가_보이고_라벨_체크박스는_없다', () => {
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} candidatesLoading />,
    );
    expect(screen.getByText('라벨 목록을 불러오는 중입니다…')).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: '사람' })).not.toBeInTheDocument();
  });

  it('후보_조회_실패시_에러_안내와_다시_시도_버튼이_노출되고_클릭하면_재조회한다', () => {
    // given — 조회 실패(무한로딩/빈화면 금지: 명시적 에러 + 재시도 UI).
    const onRetryCandidates = vi.fn();
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        candidatesError
        onRetryCandidates={onRetryCandidates}
      />,
    );
    // then — 에러 문구 + 다시 시도 버튼.
    expect(screen.getByText('라벨 목록을 불러오지 못했습니다.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(onRetryCandidates).toHaveBeenCalledTimes(1);
  });

  it('매핑된_라벨이_없으면_일반_실행이_비활성이다', () => {
    // given — 미매핑 라벨만 존재.
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        candidates={[CANDIDATES[1]]}
      />,
    );
    // then — 실행 불가(매핑 대상 0).
    expect(screen.getByRole('button', { name: '일반' })).toBeDisabled();
    expect(
      screen.getByText(
        'AI 검출 클래스가 매핑된 라벨이 없습니다. 라벨 관리에서 AI 검출 클래스를 매핑해 주세요.',
      ),
    ).toBeInTheDocument();
  });

  it('AI_Tool_팝업에_모델명(YOLO/SAM)이_노출되지_않는다', () => {
    const { container } = renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} candidates={CANDIDATES} />,
    );
    const text = container.textContent ?? '';
    expect(text).not.toMatch(/YOLO/i);
    expect(text).not.toMatch(/SAM2?/i);
  });

  it('일반_실행시_onConfirm에_shape_classIds(dtctTypeCd)_detect_전달', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={onConfirm} candidates={CANDIDATES} />,
    );
    fireEvent.click(screen.getByRole('checkbox', { name: '사람' }));
    fireEvent.click(screen.getByRole('button', { name: '일반' }));
    // 매핑 라벨(사람)의 COCO 클래스(person)가 전달된다.
    expect(onConfirm).toHaveBeenCalledWith('BBOX', ['person'], 'detect');
  });

  it('AI_Tool_팝업_트랙모드_선택시_추적이_실행된다', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={onConfirm} candidates={CANDIDATES} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '트랙' }));
    expect(onConfirm).toHaveBeenCalledWith('BBOX', [], 'track');
  });

  it('폴리곤_선택후_일반_실행시_POLYGON_전달', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={onConfirm} candidates={CANDIDATES} />,
    );
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    fireEvent.click(screen.getByRole('button', { name: '일반' }));
    expect(onConfirm).toHaveBeenCalledWith('POLYGON', [], 'detect');
  });

  it('canTrack이_false면_트랙_버튼_비활성', () => {
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        canTrack={false}
        candidates={CANDIDATES}
      />,
    );
    expect(screen.getByRole('button', { name: '트랙' })).toBeDisabled();
  });

  it('취소시_onClose_호출되고_onConfirm_미호출', () => {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(
      <AiToolModal open onClose={onClose} onConfirm={onConfirm} candidates={CANDIDATES} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  // === 즉시 그리기 토글은 이 팝업에 없다(회귀 가드) ===
  // 그 옵션은 AI 분할 도구의 클릭 프리뷰에만 효력이 있어 우측 속성 패널의 "AI 분할 정밀도"
  // 섹션(ObjectAttributePanel)으로 이동했다. 동작 검증은
  // ObjectAttributePanel.segmentTolerance.test.tsx 가 담당한다.
  it('즉시_그리기_체크박스가_AI_Tool_팝업에_없다', () => {
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} candidates={CANDIDATES} />,
    );
    expect(screen.queryByRole('checkbox', { name: '즉시 그리기' })).not.toBeInTheDocument();
    expect(screen.queryByText('클릭할 때마다 미리보기가 그려집니다.')).not.toBeInTheDocument();
  });

  // === Phase 2 [FE] 정밀도 조절 (인식 민감도 / 경계 세밀함) ===
  it('AI탐지모달_인식민감도_경계세밀함이_시스템설정값으로_프리필된다', () => {
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        candidates={CANDIDATES}
        defaultConfThreshold={0.5}
        defaultSimplifyTolerance={10}
      />,
    );
    // 인식 민감도는 항상 노출(detect) — 프리필 0.50.
    expect((screen.getByRole('slider', { name: '인식 민감도' }) as HTMLInputElement).value).toBe(
      '0.5',
    );
    // 경계 세밀함은 폴리곤에서만 — 폴리곤 선택 후 프리필 10 확인.
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    expect((screen.getByRole('slider', { name: '경계 세밀함' }) as HTMLInputElement).value).toBe(
      '10',
    );
  });

  it('폴리곤_shape일때만_경계세밀함_슬라이더가_보인다', () => {
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} candidates={CANDIDATES} />,
    );
    // 기본 박스 — 경계 세밀함 미노출.
    expect(screen.queryByRole('slider', { name: '경계 세밀함' })).not.toBeInTheDocument();
    // 인식 민감도는 항상 노출.
    expect(screen.getByRole('slider', { name: '인식 민감도' })).toBeInTheDocument();
    // 폴리곤 전환 시 노출.
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    expect(screen.getByRole('slider', { name: '경계 세밀함' })).toBeInTheDocument();
  });

  it('슬라이더_조절후_확인하면_onConfirm에_confThreshold_simplifyTolerance가_전달된다', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={onConfirm}
        candidates={CANDIDATES}
        defaultConfThreshold={0.4}
        defaultSimplifyTolerance={1}
      />,
    );
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    fireEvent.change(screen.getByRole('slider', { name: '인식 민감도' }), {
      target: { value: '0.6' },
    });
    fireEvent.change(screen.getByRole('slider', { name: '경계 세밀함' }), {
      target: { value: '5' },
    });
    fireEvent.click(screen.getByRole('button', { name: '일반' }));
    expect(onConfirm).toHaveBeenCalledWith('POLYGON', [], 'detect', {
      confThreshold: 0.6,
      simplifyTolerance: 5,
    });
  });

  it('조절하지_않으면_onConfirm에_정밀도_옵션이_포함되지_않는다', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={onConfirm}
        candidates={CANDIDATES}
        defaultConfThreshold={0.4}
        defaultSimplifyTolerance={1}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '일반' }));
    // 미조절 → 4번째 인자(opts) 자체가 없어야 한다(BE 기본값).
    expect(onConfirm).toHaveBeenCalledWith('BBOX', [], 'detect');
    expect(onConfirm.mock.calls[0]).toHaveLength(3);
  });

  it('BBOX모드에서_인식민감도만_조절하면_confThreshold만_전달된다', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={onConfirm}
        candidates={CANDIDATES}
        defaultConfThreshold={0.4}
      />,
    );
    fireEvent.change(screen.getByRole('slider', { name: '인식 민감도' }), {
      target: { value: '0.55' },
    });
    fireEvent.click(screen.getByRole('button', { name: '일반' }));
    expect(onConfirm).toHaveBeenCalledWith('BBOX', [], 'detect', { confThreshold: 0.55 });
  });

  // === 회귀: 모달이 열린 도중 프리필 prop 이 뒤늦게 도착해도 사용자 조작을 덮지 않는다 ===
  it('열린_상태에서_defaultConfThreshold가_undefined에서_값으로_바뀌어도_사용자_조작이_유지된다', () => {
    const onConfirm = vi.fn();
    // 초기: useConfigs 미도착 → default 값 undefined 로 모달이 열림.
    const { rerender } = renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={onConfirm}
        candidates={CANDIDATES}
        defaultConfThreshold={undefined}
        defaultSimplifyTolerance={undefined}
      />,
    );
    // 사용자가 형태(폴리곤)·클래스(사람)·민감도 슬라이더를 조작.
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    fireEvent.click(screen.getByRole('checkbox', { name: '사람' }));
    fireEvent.change(screen.getByRole('slider', { name: '인식 민감도' }), {
      target: { value: '0.6' },
    });

    // useConfigs 가 뒤늦게 resolve → default prop 이 값으로 바뀌며 재렌더(모달은 계속 open).
    rerender(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={onConfirm}
        candidates={CANDIDATES}
        defaultConfThreshold={0.3}
        defaultSimplifyTolerance={20}
      />,
    );

    // 사용자 조작이 조용히 리셋되지 않고 그대로 유지되어야 한다.
    expect(screen.getByRole('radio', { name: '폴리곤' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: '사람' })).toBeChecked();
    expect((screen.getByRole('slider', { name: '인식 민감도' }) as HTMLInputElement).value).toBe(
      '0.6',
    );
    // 실행 시에도 사용자가 고른 값으로 요청된다(무음 파라미터 변조 방지).
    fireEvent.click(screen.getByRole('button', { name: '일반' }));
    expect(onConfirm).toHaveBeenCalledWith('POLYGON', ['person'], 'detect', { confThreshold: 0.6 });
  });

  it('모달을_닫았다_다시_열면_최신_default로_프리필되고_조작이_리셋된다', () => {
    const onConfirm = vi.fn();
    const { rerender } = renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={onConfirm}
        candidates={CANDIDATES}
        defaultConfThreshold={0.4}
        defaultSimplifyTolerance={10}
      />,
    );
    // 첫 세션에서 조작.
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    fireEvent.click(screen.getByRole('checkbox', { name: '사람' }));

    // 모달 닫기.
    rerender(
      <AiToolModal
        open={false}
        onClose={vi.fn()}
        onConfirm={onConfirm}
        candidates={CANDIDATES}
        defaultConfThreshold={0.4}
        defaultSimplifyTolerance={10}
      />,
    );
    // 다시 열기(그 사이 default 갱신).
    rerender(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={onConfirm}
        candidates={CANDIDATES}
        defaultConfThreshold={0.7}
        defaultSimplifyTolerance={10}
      />,
    );

    // 새 세션 → 형태·선택 리셋 + 최신 default 로 프리필.
    expect(screen.getByRole('radio', { name: '박스' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: '사람' })).not.toBeChecked();
    expect((screen.getByRole('slider', { name: '인식 민감도' }) as HTMLInputElement).value).toBe(
      '0.7',
    );
  });

  it('정밀도_슬라이더에도_모델명(YOLO/SAM)이_노출되지_않는다', () => {
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        candidates={CANDIDATES}
        defaultConfThreshold={0.5}
        defaultSimplifyTolerance={10}
      />,
    );
    fireEvent.click(screen.getByRole('radio', { name: '폴리곤' }));
    // Modal 은 포털로 렌더되므로 문서 전체 텍스트를 확인한다.
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/YOLO/i);
    expect(text).not.toMatch(/SAM2?/i);
    // 노출 문구는 인식 민감도 / 경계 세밀함.
    expect(text).toContain('인식 민감도');
    expect(text).toContain('경계 세밀함');
  });
});

// SCREEN-029 §AI 탐지 팝업 — 포털 채널은 실행 버튼이 하나다([탐지 실행]). 트랙 진입은 우측 「AI 자동 추적」
// 패널이 유일한 자리라 팝업에 두지 않는다(중복 진입 제거). 내부 채널(SCREEN-005) 구성은 무변경.
describe('AiToolModal — 단일 실행(detectOnly)', () => {
  it('실행_버튼은_탐지_실행_하나이고_트랙_진입과_후속프레임_안내가_없다', () => {
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        candidates={CANDIDATES}
        runMode="detectOnly"
        canTrack={false}
      />,
    );
    expect(screen.getByRole('button', { name: '탐지 실행' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '일반' })).toBeNull();
    expect(screen.queryByRole('button', { name: '트랙' })).toBeNull();
    // canTrack=false 여도 추적 안내를 띄우지 않는다 — 이 팝업에 추적이 없다.
    expect(screen.queryByText('후속 프레임이 없어 추적할 수 없습니다.')).toBeNull();
    expect(
      screen.getByText(
        '형태와 대상 라벨을 선택한 뒤 실행하세요. 라벨을 선택하지 않으면 매핑된 전체 라벨을 대상으로 합니다.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/실행 방식을 고르세요/)).toBeNull();
  });

  it('탐지_실행은_detect_모드로_확정한다', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={onConfirm} candidates={CANDIDATES} runMode="detectOnly" />,
    );
    fireEvent.click(screen.getByRole('checkbox', { name: '사람' }));
    fireEvent.click(screen.getByRole('button', { name: '탐지 실행' }));
    expect(onConfirm).toHaveBeenCalledWith('BBOX', ['person'], 'detect');
  });

  it('매핑된_라벨이_없으면_탐지_실행이_비활성이고_사유를_버튼에_잇는다', () => {
    renderWithProviders(
      <AiToolModal
        open
        onClose={vi.fn()}
        onConfirm={vi.fn()}
        candidates={[CANDIDATES[1]]}
        runMode="detectOnly"
      />,
    );
    const run = screen.getByRole('button', { name: '탐지 실행' });
    expect(run).toBeDisabled();
    const reason = screen.getByText('AI 검출 클래스가 매핑된 라벨이 없어 실행할 수 없습니다.');
    expect(run).toHaveAttribute('aria-describedby', reason.id);
  });

  it('다른_작업_진행_중이면_비활성이고_그_사유를_버튼에_잇는다', () => {
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} candidates={CANDIDATES} runMode="detectOnly" disabled />,
    );
    const run = screen.getByRole('button', { name: '탐지 실행' });
    expect(run).toBeDisabled();
    const reason = screen.getByText('다른 작업이 진행 중이라 지금은 실행할 수 없습니다.');
    expect(run).toHaveAttribute('aria-describedby', reason.id);
  });

  it('기본_구성은_종전대로_일반_트랙_두_버튼이다', () => {
    // 내부 채널 무변경 가드 — runMode 를 주지 않은 호출부.
    renderWithProviders(
      <AiToolModal open onClose={vi.fn()} onConfirm={vi.fn()} candidates={CANDIDATES} />,
    );
    expect(screen.getByRole('button', { name: '일반' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '트랙' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '탐지 실행' })).toBeNull();
    expect(screen.getByText(/실행 방식을 고르세요/)).toBeInTheDocument();
  });
});
