// 배치 실패 사유 + 조치 패널. [@design SCREEN-009] [@design API-167] [@design API-198] [@design API-200]

import fs from 'node:fs';
import path from 'node:path';

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Button } from '@/components/common/Button';
import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useUiStore } from '@/stores/useUiStore';

import {
  BatchFailurePanel,
  batchPanelMode,
  hasBatchFailure,
  needsBatchAttention,
} from '../BatchFailurePanel';
import type { BatchStageItem, StageBundle, VideoDetail } from '../../types';

/**
 * 오토라벨 묶음의 화면 노출명 — 사양(SCREEN-009)의 어휘다.
 * 테스트가 이 문자열을 직접 적어 두는 이유는, 표시명이 바뀌면 **여기서 먼저 깨져** 눈에 띄게 하기
 * 위해서다(테스트가 `bundleLabel` 을 호출하면 무엇으로 바뀌든 통과한다).
 *
 * ⚠ 구 기대값 'AI 탐지 · AI 분할 · 보간'(멤버 단계명 조합) → **폐기**. 스테퍼 캡션이 이미
 * '오토라벨링' 이라 같은 묶음이 한 화면에서 두 이름으로 불리고 있었고, 사양의 어휘가 「오토라벨」이다.
 * 이름이 짧아지며 사라진 「보간 포함」 고지는 아래 재수행 경고·확인 창 가드가 진다.
 */
const AUTOLABEL_LABEL = '오토라벨링';

/**
 * `Button variant='primary'` 가 실제로 내는 배경 클래스를 기준 렌더에서 뽑는다.
 *
 * 화면 테스트가 팔레트 단수(`bg-primary-600` 등)를 직접 적으면, 단수가 한 단 움직이는 순간
 * 색과 무관한 기능 가드까지 줄줄이 깨진다. 「primary 변이인가」는 여기서 보고, 「그 변이가
 * 어느 단수인가」는 Button 자신의 테스트에서 본다 — 층을 나눈 것이지 무르게 만든 것이 아니다.
 * (되돌림 실증: 재수행 버튼을 secondary 로 바꾸면 이 단언이 그대로 깨진다.)
 */
function primaryButtonBgClass(): string {
  const { container, unmount } = render(<Button variant="primary">기준</Button>);
  const cls = [...container.querySelector('button')!.classList].find((c) =>
    c.startsWith('bg-primary-'),
  );
  unmount();
  if (!cls) throw new Error('Button variant=primary 가 bg-primary-* 를 내지 않는다');
  return cls;
}

/**
 * 시계열 묶음의 화면 노출명 — 위와 **같은 취지로** 문자열을 직접 적는다(`bundleLabel` 을 부르면
 * 표시명이 무엇으로 바뀌든 통과해 버려 개명을 여기서 잡지 못한다).
 *
 * ⚠ 구 기대값 'VLM' → **폐기**. 기술 모델명 화면 노출 금지(UI-017/UI-018)로 단계 표의 `VLM`
 * 라벨이 「시계열」이 되었고, 이 묶음 이름은 그 표에서 **파생**되므로 함께 따라왔다.
 * 단계 코드 `'VLM'` 자체는 그대로다 — 아래 `skippedStages`·`stage` 값이 코드를 계속 쓴다.
 */
const VLM_LABEL = '시계열';

function stages(overrides: Record<string, BatchStageItem['status']>): BatchStageItem[] {
  return ['DEIDENTIFY', 'MARKING', 'VLM', 'FRAME_EXTRACT', 'YOLO', 'SAM2', 'INTERPOLATE'].map(
    (name) => ({ name, status: overrides[name] ?? 'DONE', progress: null }),
  );
}

function videoOf(partial: Partial<VideoDetail> = {}): VideoDetail {
  return {
    id: 7,
    cctvName: 'CCTV-7',
    vmsClipId: 'VMS-7',
    frameCount: 0,
    status: 'FAILED',
    capturedAt: '2026-08-01T00:00:00Z',
    duration: 30,
    fileSizeMb: 1,
    resolution: '1920x1080',
    framePreviews: [],
    stages: stages({ VLM: 'FAIL' }),
    batchFailureReason: '외부 시계열 분석 서버가 응답하지 않았습니다.',
    skippedStages: [],
    // ★ 건너뛰기가 해제된 묶음 — 서버가 내려주는 **영구** 상태다(ADR-050). 화면 세션 로컬 기억이
    //   아니므로 이탈 후 재진입에도 남는다.
    clearedStages: [],
    // ★ 지금 실패한 상태인 작업 묶음 — 서버 판정(`failedStages`)이다. 기본 fixture 는 시계열 단계가
    //   실패한 영상이므로 그 묶음을 담는다(진행 축 FAIL 과 이 축은 서버에서 OR 로 합쳐진다).
    //   ⚠ 화면은 이 값만 보고 건너뛰기를 연다 — `stages` 에서 재유도하지 않는다(ADR-050).
    failedStages: ['VLM'],
    derivative: false,
    ...partial,
  } as VideoDetail;
}

describe('BatchFailurePanel', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('실패_사유를_서버_문구_그대로_보여준다', () => {
    renderWithProviders(<BatchFailurePanel video={videoOf()} />);

    expect(screen.getByTestId('batch-failure-reason')).toHaveTextContent(
      '외부 시계열 분석 서버가 응답하지 않았습니다.',
    );
  });

  it('실패한_단계는_사용자_노출명으로_표시한다', () => {
    renderWithProviders(<BatchFailurePanel video={videoOf({ stages: stages({ YOLO: 'FAIL' }) })} />);

    // 기술 모델명(YOLO)이 아니라 앱 공통 매핑의 노출명이 나와야 한다.
    expect(screen.getByTestId('batch-failure-stage')).toHaveTextContent('AI 탐지');
    expect(screen.getByTestId('batch-failure-stage')).not.toHaveTextContent('YOLO');
  });

  it('단계를_특정할_수_없는_실패도_사유는_보여주고_단계는_확인_불가로_알린다', () => {
    // dev 실측: 실패 영상 3건 중 1건이 stages 빈 배열 + 사유만 오는 경우다.
    renderWithProviders(
      <BatchFailurePanel video={videoOf({ stages: [], batchFailureReason: '알 수 없는 오류' })} />,
    );

    expect(screen.getByTestId('batch-failure-panel')).toBeInTheDocument();
    expect(screen.getByTestId('batch-failure-reason')).toHaveTextContent('알 수 없는 오류');
    expect(screen.getByTestId('batch-failure-stage')).toHaveTextContent('확인 불가');
  });

  it('실패도_건너뛴_단계도_없으면_패널_자체를_렌더하지_않는다', () => {
    renderWithProviders(
      <BatchFailurePanel
        video={videoOf({
          stages: stages({}),
          batchFailureReason: null,
          skippedStages: [],
          clearedStages: [],
          failedStages: [],
        })}
      />,
    );

    expect(screen.queryByTestId('batch-failure-panel')).not.toBeInTheDocument();
  });

  // ★ HIGH — 건너뛴 뒤 재기동이 성공하면 실패가 사라진다. 그때 패널까지 사라지면 그 단계는 이후
  //   모든 재기동에서 조용히 건너뛰어진 채 화면에는 완료로 보이고, 해제할 진입점이 없어진다.
  describe('실패가_없고_건너뛴_단계만_남은_영상', () => {
    const skippedOnly = () =>
      videoOf({
        stages: stages({}),
        batchFailureReason: null,
        skippedStages: ['VLM'],
        failedStages: [],
      });

    it('패널을_계속_보여주고_건너뛴_사실을_알린다', () => {
      renderWithProviders(<BatchFailurePanel video={skippedOnly()} />);

      expect(screen.getByTestId('batch-failure-panel')).toBeInTheDocument();
      expect(screen.getByTestId('batch-stage-skipped-VLM')).toHaveTextContent('건너뜀');
      // 색이 아니라 문구로 상태를 말한다 — 실패가 아니므로 '실패' 제목을 쓰지 않는다.
      expect(screen.getByRole('heading', { name: /건너뛴 작업/ })).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: /배치 처리 실패/ })).not.toBeInTheDocument();
      expect(screen.getByTestId('batch-skipped-note')).toHaveTextContent(
        '배치를 다시 실행해도 수행하지 않습니다',
      );
    });

    // ★ [폐기] '건너뛰기_해제_창구가_남아_있다' — 해제 버튼은 두지 않는다(ADR-050).
    //   되살릴 창구는 **재수행 하나**이며, 재수행이 건너뛴 상태를 직접 수락하고 해제 표식까지
    //   함께 남긴다. 「막는 조작에는 되돌리는 길을 함께 둔다」는 원칙은 그 버튼이 계속 진다.
    it('★되살릴_창구는_재수행_하나다_해제_버튼은_두지_않는다', async () => {
      const user = userEvent.setup();
      mock.onPost('/videos/7/batch/stages/VLM/rerun').reply(200, {
        success: true,
        data: { rawSn: 7, stage: 'VLM', accepted: true },
        message: null,
        errorCode: null,
      });

      renderWithProviders(<BatchFailurePanel video={skippedOnly()} />);

      expect(screen.queryByRole('button', { name: /건너뛰기 해제/ })).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` }));
      // 두 묶음 모두 확인 창을 거친다 — 여기서 확인해야 요청이 나간다.
      await user.click(await screen.findByRole('button', { name: '재수행' }));

      await waitFor(() => {
        expect(mock.history.post.map((h) => h.url)).toContain('/videos/7/batch/stages/VLM/rerun');
      });
      // ★ 해제 API 는 화면 어디에서도 부르지 않는다(서버에는 남아 있으나 이 화면의 동선이 아니다).
      expect(mock.history.delete).toHaveLength(0);
    });

    it('보여줄_사유가_없으므로_실패_사유_영역을_만들지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={skippedOnly()} />);

      expect(screen.queryByTestId('batch-failure-reason')).not.toBeInTheDocument();
      expect(screen.queryByTestId('batch-failure-stage')).not.toBeInTheDocument();
    });

    it('재실행_버튼은_두지_않는다_되돌릴_실패가_없다', () => {
      // 서버는 실패 상태만 선점하므로 실패가 없는 영상의 재실행은 눌러도 막힌다.
      renderWithProviders(<BatchFailurePanel video={skippedOnly()} />);

      expect(screen.queryByRole('button', { name: /배치 재실행/ })).not.toBeInTheDocument();
    });

    it('건너뛰기_버튼은_넓히지_않는다_실패한_단계에만_노출된다', () => {
      renderWithProviders(<BatchFailurePanel video={skippedOnly()} />);

      expect(screen.queryByRole('button', { name: /작업 건너뛰기$/ })).not.toBeInTheDocument();
    });
  });

  it('사유가_없어도_FAIL_단계가_있으면_패널을_보여준다', () => {
    // 사유를 못 내리는 구 응답에서도 재실행 수단이 사라지면 안 된다.
    renderWithProviders(<BatchFailurePanel video={videoOf({ batchFailureReason: null })} />);

    expect(screen.getByTestId('batch-failure-panel')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /배치 재실행/ })).toBeInTheDocument();
  });

  it('재실행_버튼은_영상별_재실행_API_를_호출한다', async () => {
    mock.onPost('/videos/7/batch/retry').reply(200, {
      success: true,
      data: { rawSn: 7, stage: 'VLM' },
      message: null,
      errorCode: null,
    });
    const user = userEvent.setup();

    renderWithProviders(<BatchFailurePanel video={videoOf()} />);
    await user.click(screen.getByRole('button', { name: /배치 재실행/ }));

    await waitFor(() => {
      expect(mock.history.post.map((h) => h.url)).toContain('/videos/7/batch/retry');
    });
    // 뮤테이션이 끝나 버튼이 다시 눌리는 상태가 될 때까지 기다린다(진행 중 상태 잔류 방지).
    await waitFor(() => expect(screen.getByRole('button', { name: /배치 재실행/ })).toBeEnabled());
  });

  it('재실행_응답은_접수를_뜻하고_완료로_읽히는_문구를_쓰지_않는다', async () => {
    // ★ 서버는 실패 상태 선점까지만 요청 안에서 처리하고 실제 실행은 비동기로 넘긴다.
    mock.onPost('/videos/7/batch/retry').reply(200, {
      success: true,
      data: { rawSn: 7, stage: 'VLM' },
      message: null,
      errorCode: null,
    });
    useUiStore.setState({ toasts: [] });
    const user = userEvent.setup();

    renderWithProviders(<BatchFailurePanel video={videoOf()} />);
    await user.click(screen.getByRole('button', { name: /배치 재실행/ }));

    await waitFor(() => expect(useUiStore.getState().toasts).toHaveLength(1));
    const message = useUiStore.getState().toasts[0]!.message;
    expect(message).toContain('접수');
    // "시작했다/완료됐다"로 읽히면 사용자가 결과를 다 본 것으로 오해한다.
    expect(message).not.toMatch(/시작했|완료/);
    // 진행을 어디서 확인하는지도 함께 알린다.
    expect(message).toContain('처리 단계');
  });

  // ★ 접수됐는데 "실패"로 보이고, 그 상태의 재실행 버튼은 누르면 반드시 막히던 결함의 회귀 가드.
  //   서버는 접수 시점에 이미 영상 상태를 처리 중으로 커밋하므로, 화면은 그 상태를 판정 축으로 쓴다.
  //   ⚠ 진행 로그(`stages`)·실패 사유는 **직전 실행의 기록 그대로**인 상태로 두고 검증한다 —
  //     그것이 실제 서버 응답 모양이고, 기록을 지운 채로는 이 결함을 재현하지 못한다.
  describe('처리 중인 영상 — 접수됐고 순서를 기다리는 구간', () => {
    const processing = () => videoOf({ status: 'PROCESSING' });

    it('실패가_아니라_처리_중이라고_말한다', () => {
      renderWithProviders(<BatchFailurePanel video={processing()} />);

      // 색이 아니라 문구가 상태를 말한다(grayscale·색각 이상에서도 구분돼야 한다).
      expect(screen.getByRole('heading', { name: /배치 처리 중/ })).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: /배치 처리 실패/ })).not.toBeInTheDocument();
      expect(screen.getByTestId('batch-failure-panel')).toHaveAttribute('data-mode', 'processing');
    });

    it('접수됐고_순서를_기다리는_중이라는_뜻을_전달한다', () => {
      renderWithProviders(<BatchFailurePanel video={processing()} />);

      const note = screen.getByTestId('batch-processing-note');
      expect(note).toHaveTextContent('접수');
      expect(note).toHaveTextContent('순서를 기다린');
      // 내부 실행기·큐·풀 같은 구현 용어는 사용자에게 노출하지 않는다.
      expect(note.textContent).not.toMatch(/큐|스레드|풀|실행기|워커/);
    });

    it('★재실행_버튼은_비활성이다_누르면_반드시_막히는_버튼을_두지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={processing()} />);

      expect(screen.getByRole('button', { name: /배치 재실행/ })).toBeDisabled();
    });

    it('왜_비활성인지를_읽을_수_있다_보조기술_포함', () => {
      renderWithProviders(<BatchFailurePanel video={processing()} />);

      const button = screen.getByRole('button', { name: /배치 재실행/ });
      const hintId = button.getAttribute('aria-describedby');
      expect(hintId).toBeTruthy();
      const hint = document.getElementById(hintId!);
      expect(hint).toHaveTextContent('이미 처리 중이라');
    });

    it('직전_실패_기록은_지우지_않고_직전임을_밝혀_보여준다', () => {
      renderWithProviders(<BatchFailurePanel video={processing()} />);

      // ⚠ 구 단언 `getByText('직전 실패 사유')` → **폐기**. 확정 시안(`.fp-reason`)에는 사유
      //   이름표가 없고 사유 문단만 있다. 「직전」임은 ①패널 제목 ②단계 이름표 **둘**이 말하며,
      //   아래 단언이 그 둘을 모두 고정하므로 가드가 약해지지 않는다.
      const alertBox = screen.getByTestId('batch-failure-alert');
      expect(alertBox).toHaveTextContent('직전 실패 단계');
      expect(alertBox).toHaveTextContent('외부 시계열 분석 서버가 응답하지 않았습니다.');
      expect(screen.getByTestId('batch-failure-reason')).toHaveTextContent(
        '외부 시계열 분석 서버가 응답하지 않았습니다.',
      );
      expect(screen.getByText('직전 실패 단계')).toBeInTheDocument();
      // 현재 실패를 뜻하는 이름표는 쓰지 않는다(문자열 매칭은 완전일치라 「직전 …」과 구분된다).
      expect(screen.queryByText('실패 단계')).not.toBeInTheDocument();
    });

    it('건너뛴_단계와_되살릴_창구는_처리_중에도_사라지지_않는다', () => {
      // 스킵 축이 처리 중 축에 먹히면 되살릴 창구가 또 없어진다(이미 한 번 난 결함).
      //   ★ 그 창구는 이제 재수행 하나다(해제 버튼 폐기, ADR-050). 처리 중이라 **비활성**이지만
      //     사라지지는 않는다 — 사라지는 것과 지금 못 누르는 것은 다른 축이다.
      renderWithProviders(
        <BatchFailurePanel video={videoOf({ status: 'PROCESSING', skippedStages: ['VLM'] })} />,
      );

      expect(screen.getByTestId('batch-stage-skipped-VLM')).toHaveTextContent('건너뜀');
      const rerun = screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` });
      expect(rerun).toBeInTheDocument();
      expect(rerun).toBeDisabled();
      expect(screen.queryByRole('button', { name: /건너뛰기 해제/ })).not.toBeInTheDocument();
    });

    it('처리가_끝나_실패로_돌아오면_다시_실패로_말하고_버튼이_열린다', () => {
      renderWithProviders(<BatchFailurePanel video={videoOf({ status: 'FAILED' })} />);

      expect(screen.getByRole('heading', { name: /배치 처리 실패/ })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /배치 재실행/ })).toBeEnabled();
    });
  });

  // ★ 묶음 재수행이 **실패**하면 서버는 영상을 완주 상태로 원상 복구하되(설계된 동작) 진행 로그에는
  //   실패를 남긴다(운영자가 실패를 봐야 하므로 의도된 것). 그 조합에서 화면은 「전 단계 완료」와
  //   「배치 처리 실패」를 동시에 내밀었고, 그 화면의 전체 재기동 버튼은 서버가 실패 상태만 받으므로
  //   **항상** 막혔다(409). 아래가 그 회귀 가드다.
  describe('묶음 재수행이 실패해 영상이 완주로 원상 복구된 상태', () => {
    /** 실제 서버 응답 모양 — 상태는 완주(COMPLETED)·전 단계 DONE 인데 실패 사유만 남아 있다. */
    const restoredAfterFailedRerun = () =>
      videoOf({
        status: 'COMPLETED',
        stages: stages({}),
        batchFailureReason: '오토라벨 재수행이 실패했습니다.',
        skippedStages: [],
        failedStages: [],
      });

    it('★전체_재기동_버튼을_두지_않는다_서버가_실패_상태만_받는다', () => {
      // 완주 영상은 `POST …/batch/retry` 가 받지 않는다(FAILED→PROCESSING 원자 클레임 실패 → 409).
      renderWithProviders(<BatchFailurePanel video={restoredAfterFailedRerun()} />);

      expect(screen.queryByRole('button', { name: /배치 재실행/ })).not.toBeInTheDocument();
    });

    it('★전_단계가_완료인데_지금_실패라고_말하지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={restoredAfterFailedRerun()} />);

      // 색이 아니라 문구가 가른다 — 「직전」이 붙은 제목은 지금 상태가 아님을 뜻한다.
      expect(screen.getByRole('heading', { name: '직전 배치 처리 실패' })).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: '배치 처리 실패' })).not.toBeInTheDocument();
      expect(screen.getByTestId('batch-failure-panel')).toHaveAttribute('data-mode', 'lastFailure');
    });

    it('★실패_사유는_계속_보여주되_직전임을_밝힌다', () => {
      // 무엇이 실패했는지가 사라지면 운영자가 알 길이 없다 — 지우는 것이 아니라 이름을 바꾼다.
      renderWithProviders(<BatchFailurePanel video={restoredAfterFailedRerun()} />);

      // ⚠ 구 단언 `getByText('직전 실패 사유')`·`queryByText('실패 사유')` → **폐기**. 확정
      //   시안에는 사유 이름표 자체가 없어 뒤엣것은 무엇을 렌더하든 통과하는 빈 단언이 된다.
      //   사유가 「직전」의 것임은 제목·단계 이름표가 말하고, 사유 본문이 그 경고 박스 안에
      //   있다는 사실을 아래에서 함께 고정한다.
      const alertBox = screen.getByTestId('batch-failure-alert');
      expect(alertBox).toHaveTextContent('직전 실패 단계');
      expect(alertBox).toContainElement(screen.getByTestId('batch-failure-reason'));
      expect(screen.getByTestId('batch-failure-reason')).toHaveTextContent(
        '오토라벨 재수행이 실패했습니다.',
      );
      expect(screen.getByText('직전 실패 단계')).toBeInTheDocument();
      // 현재 실패를 뜻하는 이름표는 쓰지 않는다(문자열 매칭은 완전일치라 「직전 …」과 구분된다).
      expect(screen.queryByText('실패 단계')).not.toBeInTheDocument();
    });

    it('★건너뛴_적이_있는_묶음의_재수행_버튼은_그대로_살아_있다', () => {
      // 재수행이 실패했으니 다시 시도하는 것이 정상 동선이다 — 이것마저 사라지면 할 수 있는 게 없다.
      //   ★ 해제 표식(`clearedStages`)은 재수행이 실패해 영상이 원상 복구돼도 남는다(영구 상태).
      renderWithProviders(
        <BatchFailurePanel video={{ ...restoredAfterFailedRerun(), clearedStages: ['VLM'] }} />,
      );

      expect(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` })).toBeEnabled();
      // 그 화면에 전체 재기동은 없다(누르면 반드시 막히는 버튼을 두지 않는다).
      expect(screen.queryByRole('button', { name: /배치 재실행/ })).not.toBeInTheDocument();
    });
  });

  // 네 축(실패 / 직전 실패 / 처리 중 / 스킵만)이 서로를 잡아먹지 않음을 **판정 함수 하나로** 고정한다.
  describe('batchPanelMode — 축들이 서로를 지우지 않는다', () => {
    const base = { stages: [] as BatchStageItem[], skippedStages: [] as StageBundle[] };

    it('처리_중이면_실패_기록이_있어도_처리_중이다', () => {
      expect(
        batchPanelMode({ ...base, status: 'PROCESSING', batchFailureReason: '외부 오류' }),
      ).toBe('processing');
    });

    it('처리_중이_아니고_실패_신호가_있으면_실패다', () => {
      expect(batchPanelMode({ ...base, status: 'FAILED', batchFailureReason: '외부 오류' })).toBe(
        'failure',
      );
    });

    it('실패도_처리_중도_아니면_스킵만_남은_것이다', () => {
      expect(
        batchPanelMode({
          ...base,
          status: 'COMPLETED',
          batchFailureReason: null,
          skippedStages: ['VLM'],
        }),
      ).toBe('skipped');
    });

    it('처리_중이면_스킵만_있어도_처리_중이다_스킵_표시는_별도_축으로_남는다', () => {
      expect(
        batchPanelMode({
          ...base,
          status: 'PROCESSING',
          batchFailureReason: null,
          skippedStages: ['VLM'],
        }),
      ).toBe('processing');
    });

    // ★ 이번에 가른 것은 「실패 사유가 있다」와 「지금 실패 상태다」 둘뿐이다.
    it('★실패_기록은_있는데_지금_실패_상태가_아니면_직전_실패다', () => {
      expect(batchPanelMode({ ...base, status: 'COMPLETED', batchFailureReason: '외부 오류' })).toBe(
        'lastFailure',
      );
    });

    // ★ 나머지 축은 그대로다 — 실패 기록이 없으면 상태와 무관하게 예전처럼 스킵만이다.
    it('★실패_기록이_없으면_상태가_실패여도_스킵만이다_기존_축_불변', () => {
      expect(
        batchPanelMode({
          ...base,
          status: 'FAILED',
          batchFailureReason: null,
          skippedStages: ['VLM'],
        }),
      ).toBe('skipped');
    });

    it('실패_판정_자체는_바뀌지_않는다_처리_중에도_기록은_있다', () => {
      // hasBatchFailure 는 "기록이 있는가" 축이고 모드는 "무엇을 말할 것인가" 축이다.
      expect(hasBatchFailure({ stages: [], batchFailureReason: '외부 오류' })).toBe(true);
    });

    // ★★ 이번 결함의 본체 — 건너뛴 것이 하나도 없는데 「건너뛴 작업 있음」이라 말하고 있었다.
    //   `skippedStages=[]` + `clearedStages` 만 있는 조합의 분기가 없어 마지막 else 로 떨어졌다.
    //   같은 종류의 거짓을 이 저장소가 이미 `bundleFailure` 로 한 번 갈랐고, 이건 그 연장이다.
    it('★해제된_묶음만_남았으면_건너뛴_작업이라고_말하지_않는다', () => {
      expect(
        batchPanelMode({
          ...base,
          status: 'COMPLETED',
          batchFailureReason: null,
          skippedStages: [],
          clearedStages: ['VLM'],
        }),
      ).toBe('cleared');
    });

    // ★ 스킵과 해제가 **동시에** 있는 상태는 실재한다(시계열은 스킵, 오토라벨은 해제).
    //   그때 「해제됨」이라 말하면 **정반대 방향의 같은 거짓**이 되므로 스킵이 이긴다.
    it('★스킵과_해제가_함께_있으면_스킵이_이긴다_반대_방향의_같은_거짓을_막는다', () => {
      expect(
        batchPanelMode({
          ...base,
          status: 'COMPLETED',
          batchFailureReason: null,
          skippedStages: ['VLM'],
          clearedStages: ['AUTOLABEL'],
        }),
      ).toBe('skipped');
    });

    // ★ 앞선 세 분기의 조건·순서는 글자 그대로 그대로다 — 해제 축이 그것들을 앞지르지 않는다.
    it('★해제가_있어도_처리_중_실패_묶음실패가_먼저다_앞_분기_불변', () => {
      const withCleared = { ...base, clearedStages: ['VLM'] as StageBundle[] };
      expect(
        batchPanelMode({ ...withCleared, status: 'PROCESSING', batchFailureReason: null }),
      ).toBe('processing');
      expect(batchPanelMode({ ...withCleared, status: 'FAILED', batchFailureReason: '외부 오류' })).toBe(
        'failure',
      );
      expect(
        batchPanelMode({ ...withCleared, status: 'COMPLETED', batchFailureReason: '외부 오류' }),
      ).toBe('lastFailure');
      expect(
        batchPanelMode({
          ...withCleared,
          status: 'COMPLETED',
          batchFailureReason: null,
          failedStages: ['AUTOLABEL'],
        }),
      ).toBe('bundleFailure');
    });

    // ★ 값을 못 내리는 구 응답(undefined)은 예전 동작 그대로다 — 새 분기가 삼키지 않는다.
    it('★해제_목록을_못_내리는_구_응답은_예전처럼_스킵만이다', () => {
      expect(
        batchPanelMode({
          ...base,
          status: 'COMPLETED',
          batchFailureReason: null,
          skippedStages: ['VLM'],
          clearedStages: undefined,
        }),
      ).toBe('skipped');
    });
  });

  // ⚠ 구 이름 '건너뛰기는_실패한_단계가_속한_묶음에만_노출된다' → **폐기**(ADR-050). 판정 축이
  //   진행 축(`stages` 의 FAIL)에서 **서버 판정(`failedStages`)** 으로 옮겨졌다. 사유는 아래
  //   '★★서버가_실패로_판정한_묶음에만…' 참조.
  it('건너뛰기는_서버가_실패로_판정한_묶음에만_노출된다', () => {
    renderWithProviders(
      <BatchFailurePanel video={videoOf({ stages: stages({ VLM: 'FAIL' }), failedStages: ['VLM'] })} />,
    );

    expect(screen.getByRole('button', { name: `${VLM_LABEL} 작업 건너뛰기` })).toBeInTheDocument();
    // 실패하지 않은 다른 묶음에는 버튼이 없다.
    expect(
      screen.queryByRole('button', { name: `${AUTOLABEL_LABEL} 작업 건너뛰기` }),
    ).not.toBeInTheDocument();
  });

  // ★ 조작 단위가 묶음이라, 실패한 **단계**를 그 단계가 속한 **묶음**으로 해석해야 버튼이 나온다.
  //   단계 코드로 직접 비교하면(구 동작) 오토라벨 안의 어떤 단계가 실패해도 버튼이 뜨지 않는다.
  it('★오토라벨_묶음의_단계가_실패하면_그_묶음_건너뛰기가_노출된다', () => {
    renderWithProviders(
      <BatchFailurePanel
        video={videoOf({ stages: stages({ SAM2: 'FAIL' }), failedStages: ['AUTOLABEL'] })}
      />,
    );

    expect(
      screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 건너뛰기` }),
    ).toBeInTheDocument();
  });

  // ★ 보간은 이제 오토라벨 묶음 **안에** 있다 — 묶음 밖에 두면 어떤 재수행에서도 무조건 돌아
  //   사람이 손댄 보간 라벨을 지운다. 그 포함 관계가 화면 조작에도 그대로 드러나야 한다.
  it('★보간이_실패해도_오토라벨_묶음으로_해석된다', () => {
    renderWithProviders(
      <BatchFailurePanel
        video={videoOf({ stages: stages({ INTERPOLATE: 'FAIL' }), failedStages: ['AUTOLABEL'] })}
      />,
    );

    expect(
      screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 건너뛰기` }),
    ).toBeInTheDocument();
  });

  it('어느_묶음에도_없는_단계가_실패하면_건너뛰기_버튼이_없다', () => {
    renderWithProviders(
      <BatchFailurePanel
        video={videoOf({ stages: stages({ FRAME_EXTRACT: 'FAIL' }), failedStages: [] })}
      />,
    );

    expect(screen.getByTestId('batch-failure-stage')).toHaveTextContent('프레임추출');
    expect(screen.queryByRole('button', { name: /작업 건너뛰기$/ })).not.toBeInTheDocument();
  });

  // ★ 조작 UI 의 묶음 이름은 **사양(SCREEN-009)의 어휘 하나**다 — 스테퍼 캡션과 같은 문자열이며
  //   그 동치는 `BatchStageIndicator.test.tsx(★스테퍼_캡션과_조작_UI_표시명이_같은_문자열이다)` 가 고정한다.
  //
  // ⚠ 구 기대 폐기: 이름이 **단계 노출명의 조합**('AI 탐지 · AI 분할 · 보간')이라 이름만 보고도
  //   보간 포함을 알 수 있다 → 폐기. 같은 묶음이 한 화면에서 두 이름으로 불리는 대가가 더 컸다.
  //   ★ 그때 이름이 지던 「보간까지 다시 만들어진다」 고지는 아래 두 가드가 대신 진다:
  //     · ★보간_라벨이_바뀐다는_사실을_누르기_전에_알린다_보조기술_포함 (경고 문단)
  //     · ★재수행_확인창이_보간_재계산을_명시한다 (확인 창 본문)
  //   그 문구가 사라지면 이 이름 변경이 곧 고지 소실이 되므로, 두 가드를 함께 지우지 말 것.
  it('★오토라벨_묶음_이름은_사양_어휘_하나다_구_멤버나열_폐기', () => {
    renderWithProviders(
      <BatchFailurePanel
        video={videoOf({ stages: stages({ YOLO: 'FAIL' }), failedStages: ['AUTOLABEL'] })}
      />,
    );

    const button = screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 건너뛰기` });
    expect(button).toBeInTheDocument();
    expect(AUTOLABEL_LABEL).toBe('오토라벨링');
    // 구 형태(멤버 나열)로 되돌아가면 여기서 걸린다.
    expect(AUTOLABEL_LABEL).not.toContain('·');
  });

  it('사유를_입력해야_건너뛰기를_제출할_수_있다', async () => {
    mock.onPost('/videos/7/batch/stages/VLM/skip').reply(200, {
      success: true,
      data: {
        rawSn: 7,
        stage: 'VLM',
        skipped: true,
        reason: '벤더 장애 지속',
        skippedAt: '2026-08-12T17:20:00',
      },
      message: null,
      errorCode: null,
    });
    const user = userEvent.setup();

    renderWithProviders(<BatchFailurePanel video={videoOf()} />);
    await user.click(screen.getByRole('button', { name: `${VLM_LABEL} 작업 건너뛰기` }));

    const submit = await screen.findByRole('button', { name: '건너뛰기' });
    // 사유가 비어 있으면 제출할 수 없다(서버 @NotBlank 와 같은 제약).
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText(/건너뛰기 사유/), '벤더 장애 지속');
    await waitFor(() => expect(submit).toBeEnabled());
    await user.click(submit);

    await waitFor(() => {
      const call = mock.history.post.find((h) => h.url === '/videos/7/batch/stages/VLM/skip');
      expect(call).toBeDefined();
      expect(JSON.parse(call!.data)).toEqual({ reason: '벤더 장애 지속' });
    });
  });

  // ★★ HIGH 회귀 가드 — 「실패 후 판단」 입구가 화면에서 도달 불가였다. [@design ADR-050]
  //
  //   시계열 위탁 실패는 **파이프라인을 멈추지 않는다** — 제출이 논블로킹이라 실패해도 예외가 위로
  //   올라가지 않고 프레임 추출·오토라벨이 그대로 완주한다. 그 결과 그 영상은
  //     ① 배치 상태가 `FAILED` 가 아니고(완주로 남는다)
  //     ② 진행 축(`stages`)에도 `FAIL` 이 서지 않으며
  //     ③ `batchFailureReason` 도 비어 있다.
  //   구 구현은 이 세 신호만 봤고, 그래서 조치 영역이 **통째로 사라져** 건너뛰기를 요청할 창구 자체가
  //   없었다. ADR-050 이 규정한 두 입구(「전체 설정」·「실패 후 판단」) 중 하나가 닫혀 있던 것이다.
  //
  //   ⚠ 과대 노출과 과소 노출은 대칭이 아니다 — 과대 노출은 서버의 건별 거부가 보정하지만, 과소
  //     노출은 **보정되지 않는다**(요청을 보낼 창구가 없어 서버까지 도달하지 못한다).
  describe('★★시계열 위탁만 실패하고 배치는 완주한 영상 — 실패 후 판단 입구', () => {
    /** 실제 서버 응답 모양 — 배치 축은 전부 조용하고 묶음 실패 축에만 흔적이 있다. */
    const vlmSubmitFailed = (extra: Partial<VideoDetail> = {}) =>
      videoOf({
        status: 'COMPLETED',
        stages: stages({}),
        batchFailureReason: null,
        skippedStages: [],
        clearedStages: [],
        failedStages: ['VLM'],
        ...extra,
      });

    it('★★조치_영역이_사라지지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={vlmSubmitFailed()} />);

      expect(screen.getByTestId('batch-failure-panel')).toBeInTheDocument();
    });

    it('★★건너뛰기_버튼이_노출된다_이것이_없으면_요청할_창구가_없다', () => {
      renderWithProviders(<BatchFailurePanel video={vlmSubmitFailed()} />);

      expect(
        screen.getByRole('button', { name: `${VLM_LABEL} 작업 건너뛰기` }),
      ).toBeInTheDocument();
    });

    it('★실패한_묶음만_열린다_다른_묶음까지_넓히지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={vlmSubmitFailed()} />);

      expect(
        screen.queryByRole('button', { name: `${AUTOLABEL_LABEL} 작업 건너뛰기` }),
      ).not.toBeInTheDocument();
    });

    // ★ 건너뛴 것이 하나도 없는데 「건너뛴 작업 있음」이라고 말하면 화면이 거짓을 말한다.
    //   상태는 색이 아니라 제목 문구가 단독으로 말한다(이 패널의 기존 원칙).
    it('★건너뛴_적이_없으므로_건너뛴_작업이라고_말하지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={vlmSubmitFailed()} />);

      expect(screen.getByRole('heading', { name: '실패한 작업 있음' })).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: '건너뛴 작업 있음' })).not.toBeInTheDocument();
      // 전 단계가 DONE 인 영상이라 「배치 처리 실패」로도 말하지 않는다.
      expect(screen.queryByRole('heading', { name: /배치 처리 실패/ })).not.toBeInTheDocument();
    });

    // ★ 사유 영역이 없는 화면이라(배치 축이 조용하다) 어느 묶음이 실패했는지를 표식이 진다.
    it('★어느_묶음이_실패했는지_표식으로_알린다', () => {
      renderWithProviders(<BatchFailurePanel video={vlmSubmitFailed()} />);

      expect(screen.getByTestId('batch-stage-failed-VLM')).toHaveTextContent('실패');
      expect(screen.queryByTestId('batch-stage-failed-AUTOLABEL')).not.toBeInTheDocument();
    });

    // ★ 완주한 영상이므로 전체 재기동은 서버가 반드시 막는다 — 기존 규칙을 넓히지 않는다.
    it('★전체_재기동_버튼은_생기지_않는다_기존_규칙_불변', () => {
      renderWithProviders(<BatchFailurePanel video={vlmSubmitFailed()} />);

      expect(screen.queryByRole('button', { name: /배치 재실행/ })).not.toBeInTheDocument();
    });

    // ★ 건너뛴 적이 없으므로 재수행 창구는 아직 없다(그 축은 건너뜀·해제가 소유한다).
    it('재수행_버튼은_아직_없다_건너뛴_적이_없다', () => {
      renderWithProviders(<BatchFailurePanel video={vlmSubmitFailed()} />);

      expect(screen.queryByRole('button', { name: /작업 재수행$/ })).not.toBeInTheDocument();
    });

    it('★버튼이_뜨는_것으로_끝나지_않고_실제로_건너뛰기를_요청한다', async () => {
      mock.onPost('/videos/7/batch/stages/VLM/skip').reply(200, {
        success: true,
        data: {
          rawSn: 7,
          stage: 'VLM',
          skipped: true,
          reason: '벤더 미연동',
          skippedAt: '2026-08-21T10:00:00',
        },
        message: null,
        errorCode: null,
      });
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={vlmSubmitFailed()} />);

      await user.click(screen.getByRole('button', { name: `${VLM_LABEL} 작업 건너뛰기` }));
      await user.type(screen.getByLabelText(/건너뛰기 사유/), '벤더 미연동');
      await user.click(await screen.findByRole('button', { name: '건너뛰기' }));

      await waitFor(() => {
        const call = mock.history.post.find((h) => h.url === '/videos/7/batch/stages/VLM/skip');
        expect(call).toBeDefined();
        expect(JSON.parse(call!.data)).toEqual({ reason: '벤더 미연동' });
      });
    });

    // ★ 판정 함수 단위 — 화면 렌더를 거치지 않고 축 자체를 고정한다.
    it('★노출_판정과_모드_판정이_묶음_실패_축을_본다', () => {
      const v = vlmSubmitFailed();

      expect(needsBatchAttention(v)).toBe(true);
      expect(batchPanelMode(v)).toBe('bundleFailure');
      // 배치 축은 여전히 조용하다 — 이 축이 그것을 대신하는 것이 아니라 **더한다**.
      expect(hasBatchFailure(v)).toBe(false);
    });

    // ★ 서버가 이 값을 못 내리는 구 응답에서는 예전 동작 그대로다(빈 배열로 정규화된다).
    it('묶음_실패가_없으면_예전처럼_패널을_두지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={vlmSubmitFailed({ failedStages: [] })} />);

      expect(screen.queryByTestId('batch-failure-panel')).not.toBeInTheDocument();
    });
  });

  it('이미_건너뛴_단계는_건너뜀_표시와_재수행_버튼을_보여준다', () => {
    // 표시기는 스킵된 단계를 완료로 그리므로, 건너뛰었다는 사실은 이 패널에서만 드러난다.
    renderWithProviders(<BatchFailurePanel video={videoOf({ skippedStages: ['VLM'] })} />);

    expect(screen.getByTestId('batch-stage-skipped-VLM')).toHaveTextContent('건너뜀');
    expect(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` })).toBeInTheDocument();
    // ★ 해제 버튼은 폐기됐다(ADR-050) — 재수행이 건너뛴 상태를 직접 수락한다.
    expect(screen.queryByRole('button', { name: /건너뛰기 해제/ })).not.toBeInTheDocument();
  });

  // ★ 건너뛴 적이 있는 **작업 묶음**의 재수행 — 「문제가 생긴 곳부터 재시도한다」. [@design API-201]
  //   전체 재기동을 완주 영상에 쓰면 파이프라인이 통째로 돌아 보간이 함께 수행되고 사람이 손댄
  //   보간 라벨이 전량 지워진다(복구 지점 없음). 그래서 그 묶음만 지목한다.
  //
  //   ★ 버튼은 **하나**다 — 범위를 고르지 않는다(묶음이 곧 범위다). 구 두 갈래(「이 단계만 실행」·
  //     「여기부터 이어서 실행」)는 폐기됐다. 되살리면 보간을 뺀 부분 수행이 다시 가능해진다.
  //
  //   ★★ 노출 근거가 **서버 응답**으로 바뀌었다(ADR-050). 구 배선은 「이 화면 세션에서 건너뛰기를
  //     해제했다」는 **로컬 기억**이었고, 그래서 새로고침·다른 화면 경유 후 재진입하면 재수행
  //     버튼이 통째로 사라졌다(다시 건너뛰었다가 해제하는 우회밖에 없었다). 이제 응답이
  //     `clearedStages` 를 내려주므로 **`skippedStages` ∪ `clearedStages`** 로 판정한다.
  //     아래 '★이탈했다_다시_들어와도…' 가 그 한계가 닫혔음을 고정하는 가드다.
  describe('건너뛴 적이 있는 작업 묶음 재수행', () => {
    /** 지금 건너뛴 상태 — 재수행이 이 상태를 직접 수락한다(해제를 먼저 부르지 않는다). */
    const skippedOnly = (bundle: StageBundle, extra: Partial<VideoDetail> = {}) =>
      videoOf({
        stages: stages({}),
        batchFailureReason: null,
        skippedStages: [bundle],
        clearedStages: [],
        failedStages: [],
        ...extra,
      });

    /** 건너뛰기가 해제된 뒤의 서버 상태 — 실패도 스킵도 없지만 해제 표식은 **영구**로 남는다. */
    const cleared = (bundle: StageBundle, extra: Partial<VideoDetail> = {}) =>
      videoOf({
        stages: stages({}),
        batchFailureReason: null,
        skippedStages: [],
        clearedStages: [bundle],
        failedStages: [],
        ...extra,
      });

    function rerunCalls(bundle: StageBundle) {
      return mock.history.post.filter((h) => h.url === `/videos/7/batch/stages/${bundle}/rerun`);
    }

    beforeEach(() => {
      useUiStore.setState({ toasts: [] });
      for (const bundle of ['VLM', 'AUTOLABEL'] as const) {
        mock.onPost(`/videos/7/batch/stages/${bundle}/rerun`).reply(200, {
          success: true,
          data: { rawSn: 7, stage: bundle, accepted: true },
          message: null,
          errorCode: null,
        });
      }
    });

    it('해제된_묶음은_실패도_스킵도_없어도_패널이_남고_재수행_버튼이_하나_생긴다', () => {
      // 패널이 사라지면 재수행 창구가 같이 사라진다(해제할 진입점 소실의 재발).
      renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);

      expect(screen.getByTestId('batch-failure-panel')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` })).toBeInTheDocument();
    });

    // ★★ 제목이 거짓을 말하던 결함의 렌더 가드. 이 영상은 건너뛴 것이 **하나도 없는데**
    //   제목만 「건너뛴 작업 있음」이라 말하고 있었고, 같은 화면의 스킵 안내 문단은 나오지
    //   않아 화면 자신이 이미 자기모순을 드러내고 있었다.
    it('★해제만_남은_영상의_제목은_건너뛰기_해제됨이다', () => {
      renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);

      expect(screen.getByRole('heading', { name: /건너뛰기 해제됨/ })).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: /건너뛴 작업 있음/ })).not.toBeInTheDocument();
      // 스킵 안내 문단은 여전히 나오지 않는다(건너뛴 묶음이 없다) — 제목이 그 사실과 맞춰졌다.
      expect(screen.queryByTestId('batch-skipped-note')).not.toBeInTheDocument();
      // ★ 모드는 제목만 가른다 — 행·재수행 창구는 그대로다.
      expect(screen.getByTestId('batch-stage-reverted-VLM')).toHaveTextContent('해제됨');
      expect(screen.getAllByRole('button', { name: /작업 재수행$/ })).toHaveLength(1);
    });

    // ★ 스킵이 남아 있으면 「건너뛴 작업 있음」이 이긴다 — 렌더 축에서도 고정한다.
    it('★스킵이_하나라도_남으면_제목은_건너뛴_작업_있음_그대로다', () => {
      renderWithProviders(
        <BatchFailurePanel
          video={cleared('AUTOLABEL', { skippedStages: ['VLM'] })}
        />,
      );

      expect(screen.getByRole('heading', { name: /건너뛴 작업 있음/ })).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: /건너뛰기 해제됨/ })).not.toBeInTheDocument();
    });

    // ★★ HIGH — 이번 변경의 존재 이유. 구 배선(로컬 기억)에서는 이 순간 버튼이 사라졌다.
    it('★이탈했다_다시_들어와도_재수행_버튼이_남는다_서버가_기억한다', () => {
      const view = renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);
      expect(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` })).toBeInTheDocument();

      // 화면을 떠났다가(언마운트) 같은 영상으로 다시 들어온다 — 로컬 상태는 전부 사라진다.
      view.unmount();
      renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);

      expect(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` })).toBeInTheDocument();
    });

    // ★ 재수행이 건너뛴 상태를 직접 수락하므로 해제를 먼저 부를 필요가 없다.
    it('★지금_건너뛴_묶음에도_재수행_버튼을_둔다', () => {
      renderWithProviders(<BatchFailurePanel video={skippedOnly('VLM')} />);

      expect(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` })).toBeInTheDocument();
    });

    // ★ 범위 선택 갈래가 폐기됐음을 고정한다 — 되살리면 보간을 뺀 부분 수행이 다시 가능해지고,
    //   그것이 이번 변경이 없앤 바로 그 형태다.
    it('★범위를_고르는_두_갈래_버튼은_없다_묶음이_곧_범위다', () => {
      renderWithProviders(<BatchFailurePanel video={cleared('AUTOLABEL')} />);

      expect(screen.queryByRole('button', { name: /이 단계만 실행/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /여기부터 이어서 실행/ })).not.toBeInTheDocument();
      // 재수행 버튼은 정확히 하나다.
      expect(screen.getAllByRole('button', { name: /작업 재수행$/ })).toHaveLength(1);
    });

    // ★ 오토라벨 재수행은 보간까지 다시 만들어 사람이 손댄 보간 라벨을 지운다 — 파괴적이라
    //   클릭이 곧 요청이 되면 안 된다. 취소 기회가 없으면 한 번의 오클릭으로 확정된다.
    it('★오토라벨_재수행은_확인_없이_실행되지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={cleared('AUTOLABEL')} />);

      await user.click(screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 재수행` }));

      expect(await screen.findByRole('dialog')).toBeInTheDocument();
      expect(rerunCalls('AUTOLABEL')).toHaveLength(0);

      await user.click(screen.getByRole('button', { name: '취소' }));
      expect(rerunCalls('AUTOLABEL')).toHaveLength(0);
    });

    it('확인해야_오토라벨_재수행을_요청한다_본문은_보내지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={cleared('AUTOLABEL')} />);

      await user.click(screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 재수행` }));
      // 확인 창의 확정 버튼은 접근명이 정확히 '재수행'이라 트리거 버튼과 구분된다(문자열 매칭은 완전일치).
      await user.click(await screen.findByRole('button', { name: '재수행' }));

      await waitFor(() => expect(rerunCalls('AUTOLABEL')).toHaveLength(1));
      // 범위를 고르지 않으므로 보낼 본문 자체가 없다(구 `{scope}` 폐지).
      expect(rerunCalls('AUTOLABEL')[0]!.data).toBeUndefined();
    });

    // ★ 묶음 이름이 '오토라벨링' 으로 짧아지며 **이름이 지던 「보간 포함」 고지**가 사라졌다.
    //   그 고지를 실제로 대신하는 곳이 여기다 — 확인 창 본문이 보간 재계산과 그 결과(손댄 라벨이
    //   지워짐)를 말해야 한다. 제목만 바뀌고 본문이 비면 파괴적 조작에 근거 없는 확인이 된다.
    it('★재수행_확인창이_보간_재계산을_명시한다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={cleared('AUTOLABEL')} />);

      await user.click(screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 재수행` }));

      const dialog = await screen.findByRole('dialog');
      expect(dialog).toHaveTextContent('보간');
      expect(dialog).toHaveTextContent('지워지고');
      // 제목은 이름 통일의 결과를 그대로 쓴다(모달이 코드→이름을 다시 정하지 않는다).
      expect(dialog).toHaveTextContent(`${AUTOLABEL_LABEL} 작업 재수행`);
    });

    // ★ [폐기] '★시계열_재수행은_확인_없이_곧바로_접수된다' — 구 정책이었다(「시계열은 보간을 품지
    //   않아 파괴적이지 않으므로 클릭이 곧 요청」). 시안대로 **두 묶음 모두 확인 창을 거치는** 쪽으로
    //   확정됐다: 시계열 재수행은 라벨을 지우지는 않지만 **외부 벤더로 재위탁을 보내는 행위**라
    //   비용·시간이 들고 동시 처리 한도(32건)를 먹는다. 확인의 근거가 「되돌릴 수 없다」에서
    //   **「공짜가 아니다」**로 바뀐 것이다. 아래 두 가드가 그 자리를 잇는다.
    it('★시계열_재수행도_확인_없이_실행되지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);

      await user.click(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` }));

      expect(await screen.findByRole('dialog')).toBeInTheDocument();
      expect(rerunCalls('VLM')).toHaveLength(0);

      await user.click(screen.getByRole('button', { name: '취소' }));
      expect(rerunCalls('VLM')).toHaveLength(0);
    });

    it('확인해야_시계열_재수행을_요청한다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);

      await user.click(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` }));
      await user.click(await screen.findByRole('button', { name: '재수행' }));

      await waitFor(() => expect(rerunCalls('VLM')).toHaveLength(1));
      expect(rerunCalls('VLM')[0]!.data).toBeUndefined();
    });

    // ★ 확인 창이 생겼다고 **경고 상자까지** 따라오면 안 된다 — 시안의 시계열 확인 창에는
    //   `.dlg-warn` 상자가 없고 확정 버튼도 `btn-primary` 다. 없는 위험을 상자로 알리면 그 상자가
    //   형태로만 남아 정작 파괴적인 오토라벨 쪽의 경고까지 가벼워진다(확인 창 자체와는 다른 축이다).
    it('★시계열_확인창에는_경고_상자를_두지_않는다_문구는_시안_그대로다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);

      await user.click(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` }));

      const dialog = await screen.findByRole('dialog');
      expect(screen.queryByTestId('confirm-dialog-warning')).not.toBeInTheDocument();
      // 보간 경고 문구가 시계열 확인 창으로 새어 들어오면 없는 위험을 알리는 오정보가 된다.
      expect(dialog).not.toHaveTextContent('보간');
      expect(dialog).toHaveTextContent('시계열 묶음을 다시 수행할까요?');
      expect(dialog).toHaveTextContent('이 묶음만 수행하고 다른 묶음은 건드리지 않습니다. 시계열은 확정된 라벨을 건드리지 않고 영상 서술만 새로 받아 옵니다.');
      expect(dialog).toHaveTextContent('건너뛴 상태였다면 이 조작이 함께 해제합니다 — 따로 해제할 필요가 없습니다. 접수까지만 즉시 확인되고 실행은 뒤에서 이어집니다.');
    });

    // ── 대상 칩 행(시안 `.dlg-target`) — 확인 창 **첫 줄** ────────────────────────
    //
    // ★ 시안은 네 확인 창(`#dialog-skip-*` · `#dialog-rerun-*`) 모두 첫 줄에 «대상 묶음» 칩을 둔다.
    //   건너뛰기 모달에만 있고 재수행 확인 창에는 없어, 같은 결정을 묻는 두 창이 갈려 있었다.
    // ⚠ 문자열을 새로 적지 않는다 — 표시명은 `bundleLabel`, 부제는 목록 행이 쓰는 것과 **같은
    //   원천**이다. 아래 가드는 그 사실을 «행의 부제와 글자가 같다»로 확인한다(둘이 갈리면 깨진다).
    it('★재수행_확인창_첫_줄이_대상_묶음_칩이다_시계열', async () => {
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);

      const rowSubtitle = screen.getByTestId('batch-stage-subtitle-VLM').textContent;
      await user.click(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` }));

      const dialog = await screen.findByRole('dialog');
      const row = within(dialog).getByTestId('bundle-target-row');
      expect(within(row).getByText('대상 묶음')).toBeInTheDocument();
      expect(within(dialog).getByTestId('bundle-target-chip')).toHaveTextContent(VLM_LABEL);
      // 부제는 목록 행과 **같은 원천**이라 글자가 같다(「영상 서술 생성」).
      expect(within(dialog).getByTestId('bundle-target-subtitle').textContent).toBe(rowSubtitle);

      // 시안 순서: 칩 행 → 설명. 설명이 위로 올라오면 «무엇에 대한 확인인가»를 나중에 알게 된다.
      const desc = within(dialog).getByText(
        '이 묶음만 수행하고 다른 묶음은 건드리지 않습니다. 시계열은 확정된 라벨을 건드리지 않고 영상 서술만 새로 받아 옵니다.',
      );
      expect(row.compareDocumentPosition(desc) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('★재수행_확인창_첫_줄이_대상_묶음_칩이다_오토라벨', async () => {
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={cleared('AUTOLABEL')} />);

      const rowSubtitle = screen.getByTestId('batch-stage-subtitle-AUTOLABEL').textContent;
      await user.click(screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 재수행` }));

      const dialog = await screen.findByRole('dialog');
      expect(within(dialog).getByTestId('bundle-target-chip')).toHaveTextContent(AUTOLABEL_LABEL);
      // 멤버 나열은 단계 표에서 파생된다 — 손으로 적으면 묶음 구성이 바뀔 때 한쪽만 낡는다.
      expect(within(dialog).getByTestId('bundle-target-subtitle').textContent).toBe(rowSubtitle);

      // 칩 행은 경고 상자보다도 위다(대상 → 경고 → 설명 순서를 시안이 그렇게 둔다).
      const row = within(dialog).getByTestId('bundle-target-row');
      const warn = screen.getByTestId('confirm-dialog-warning');
      expect(row.compareDocumentPosition(warn) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    // ★ 오토라벨 확인 창은 **그대로**다 — 시계열에 확인 창을 더한 것이지 경고 상자를 걷어낸 것이 아니다.
    it('★오토라벨_확인창의_경고_상자는_그대로_남는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={cleared('AUTOLABEL')} />);

      await user.click(screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 재수행` }));

      await screen.findByRole('dialog');
      expect(screen.getByTestId('confirm-dialog-warning')).toHaveTextContent('되돌릴 수 없습니다');
    });

    // ★ 사양: "고르는 시점에 알린다" — 누른 뒤에 뜨는 확인 창은 취소 수단이지 고지 수단이 아니다.
    it('★보간_라벨이_바뀐다는_사실을_누르기_전에_알린다_보조기술_포함', () => {
      renderWithProviders(<BatchFailurePanel video={cleared('AUTOLABEL')} />);

      const warning = screen.getByTestId('batch-rerun-warning-AUTOLABEL');
      expect(warning).toHaveTextContent('보간');
      expect(warning).toHaveTextContent('지워지고');
      // 문단이 화면에 있는 것만으로는 부족하다 — 버튼이 그것을 가리켜야 보조기술에 전달된다.
      const rerunButton = screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 재수행` });
      expect(rerunButton.getAttribute('aria-describedby')?.split(/\s+/)).toContain(
        'batch-rerun-warning-AUTOLABEL',
      );
    });

    // ★ 시계열 묶음은 보간을 다시 만들지 않는다 — 거기에 같은 경고를 붙이면 경고가 의미를 잃고,
    //   보조기술 사용자에게는 없는 위험을 알리는 오정보가 된다.
    it('★시계열_재수행에는_보간_경고를_붙이지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);

      expect(screen.queryByTestId('batch-rerun-warning-VLM')).not.toBeInTheDocument();

      const rerunButton = screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` });
      const describedText = (rerunButton.getAttribute('aria-describedby') ?? '')
        .split(/\s+/)
        .filter(Boolean)
        .map((id) => document.getElementById(id)?.textContent ?? '')
        .join(' ');

      expect(describedText).not.toMatch(/보간/);
    });

    it('재수행_응답은_접수를_뜻하고_완료로_읽히는_문구를_쓰지_않는다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);

      await user.click(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` }));
      await user.click(await screen.findByRole('button', { name: '재수행' }));

      await waitFor(() => expect(useUiStore.getState().toasts).toHaveLength(1));
      const message = useUiStore.getState().toasts[0]!.message;
      expect(message).toContain('접수');
      expect(message).not.toMatch(/시작했|완료/);
      expect(message).toContain('처리 단계');
    });

    it('처리_중에는_재수행_버튼이_비활성이고_왜인지를_읽을_수_있다', () => {
      // 재수행을 접수하면 서버가 상태를 처리 중으로 선점한다 — 그 상태의 버튼은 눌러도 막힌다.
      renderWithProviders(
        <BatchFailurePanel video={cleared('VLM', { status: 'PROCESSING' })} />,
      );

      const rerunButton = screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` });
      expect(rerunButton).toBeDisabled();
      expect(screen.getByTestId('batch-rerun-busy-hint-VLM')).toHaveTextContent('이미 처리 중이라');
      expect(rerunButton.getAttribute('aria-describedby')?.split(/\s+/)).toContain(
        'batch-rerun-busy-hint-VLM',
      );
    });

    // ★ [폐기] '건너뛰기를_해제한_묶음을_다시_건너뛰면_재수행_버튼이_사라진다' —
    //   재수행이 건너뛴 상태를 직접 수락하게 되어(ADR-050) 그 버튼은 더 이상 막히지 않는다.
    //   그 자리를 대신하는 것이 위의 '★지금_건너뛴_묶음에도_재수행_버튼을_둔다' 다.

    it('건너뛴_적이_없는_영상에는_재수행_버튼이_없다', () => {
      // 서버가 수락하는 대상은 건너뛴 적이 있는 묶음뿐이다 — 반드시 막히는 버튼을 두지 않는다.
      renderWithProviders(<BatchFailurePanel video={videoOf()} />);

      expect(screen.queryByRole('button', { name: /작업 재수행$/ })).not.toBeInTheDocument();
    });

    // ★ 전체 재기동은 완주 영상에 노출하면 안 된다 — 그 경로가 바로 보간 라벨을 지우는 파괴 경로다.
    it('★해제된_묶음이_있어도_전체_재기동_버튼은_생기지_않는다_실패한_영상_전용이다', () => {
      renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);

      expect(screen.queryByRole('button', { name: /배치 재실행/ })).not.toBeInTheDocument();
    });

    // ★ 검수가 완료된 적 있는 영상은 **묶음별로 갈린다**. [@design API-201]
    //   시계열은 확정된 라벨을 건드리지 않고 서술만 더하므로 그대로 누르고, 오토라벨은 라벨을
    //   다시 만들어 승인 시점 스냅샷과 어긋나므로 막는다. 서버가 이미 이 규칙을 강제하지만,
    //   되돌릴 수 없는 조건이라 **누르기 전에** 알린다(파생영상 차단과 같은 관례).
    describe('검수가 완료된 적 있는 영상', () => {
      const approved: Partial<VideoDetail> = { everApproved: true };

      it('★시계열_재수행은_그대로_누를_수_있다', () => {
        renderWithProviders(<BatchFailurePanel video={cleared('VLM', approved)} />);

        expect(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` })).toBeEnabled();
      });

      it('★오토라벨_재수행은_비활성이고_왜인지를_읽을_수_있다', () => {
        renderWithProviders(<BatchFailurePanel video={cleared('AUTOLABEL', approved)} />);

        const rerunButton = screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 재수행` });
        expect(rerunButton).toBeDisabled();
        // 회색 버튼만으로는 왜 못 누르는지 알 수 없다 — 사유가 보조기술에도 전달돼야 한다.
        expect(rerunButton.getAttribute('aria-describedby')?.split(/\s+/)).toContain(
          'batch-rerun-approved-lock-AUTOLABEL',
        );
        expect(screen.getByTestId('batch-rerun-approved-lock-AUTOLABEL')).toHaveTextContent(
          '검수가 완료된 적 있는 영상에서는 시계열만 다시 수행할 수 있습니다',
        );
      });

      // ★ 대조군 — 조건을 넓히지 않았음을 고정한다. 승인 이력이 없으면 오토라벨도 그대로 눌린다.
      it('승인_이력이_없으면_오토라벨_재수행은_그대로_활성이다', () => {
        renderWithProviders(<BatchFailurePanel video={cleared('AUTOLABEL')} />);

        expect(screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 재수행` })).toBeEnabled();
        expect(screen.queryByTestId('batch-rerun-approved-lock-AUTOLABEL')).not.toBeInTheDocument();
      });

      // ★ 승인 축은 기존 조건을 **대체하지 않고 더한 것**이다 — 처리 중이면 승인 이력과 무관하게
      //   둘 다 비활성이다. 여기서 시계열이 살아나면 서버가 반드시 막는 버튼이 열린다.
      it('처리_중이면_승인_이력과_무관하게_시계열도_비활성이다', () => {
        renderWithProviders(
          <BatchFailurePanel video={cleared('VLM', { ...approved, status: 'PROCESSING' })} />,
        );

        expect(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` })).toBeDisabled();
      });
    });
  });

  it('파생영상은_사유만_보여주고_조작은_노출하지_않는다', () => {
    renderWithProviders(<BatchFailurePanel video={videoOf({ derivative: true })} />);

    expect(screen.getByTestId('batch-failure-reason')).toBeInTheDocument();
    expect(screen.getByTestId('batch-failure-derivative-note')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /배치 재실행/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /건너뛰기/ })).not.toBeInTheDocument();
  });

  it('조작_패널이_마킹_화면으로_새지_않는다', () => {
    // ★사양(SCREEN-009)이 명시적으로 금지하는 것: 조작 버튼을 BatchStageIndicator 안에 두는 것.
    //   표시기는 «어느 단계까지 왔는가»만 말하고 조작은 그 바깥의 관심사다.
    //   판정은 "누가 이 패널을 마운트하는가"로 한다 — 렌더 테스트로는 배치 실수를 잡지 못한다.
    // ⚠ [폐기] 구 근거 「그 표시기는 MarkingPage 와 공유하므로 넣는 순간 마킹 화면에도 그대로
    //   나타난다」 — 마킹 화면이 표시기를 더 쓰지 않아 사실이 아니다. **금지·이 가드는 그대로**이며,
    //   사용처가 한 곳이 됐다는 사실은 패널을 표시기 안으로 옮길 근거가 아니다.
    const srcDir = path.resolve(__dirname, '../../../..');
    // 텍스트 등장이 아니라 **JSX 마운트**(`<BatchFailurePanel`)를 센다 — 주석에서 이 패널을
    // 언급하는 것(예: 표시기가 "조작은 여기 두지 않는다"고 적어 둔 것)은 배치가 아니다.
    const mounters = fs
      .readdirSync(srcDir, { recursive: true, encoding: 'utf-8' })
      .filter((f) => /\.tsx$/.test(f))
      .filter((f) => !/(^|[\\/])__tests__[\\/]/.test(f))
      .filter((f) => /<BatchFailurePanel[\s/>]/.test(fs.readFileSync(path.join(srcDir, f), 'utf-8')))
      .map((f) => f.split(path.sep).join('/'));

    expect(mounters).toEqual(['pages/VideoDetailPage.tsx']);
  });

  it('실패를_색이_아니라_문구로도_알린다', () => {
    renderWithProviders(<BatchFailurePanel video={videoOf()} />);

    // 색각 이상·grayscale 에서도 실패임을 알 수 있어야 한다.
    expect(screen.getByRole('heading', { name: /배치 처리 실패/ })).toBeInTheDocument();
  });

  /**
   * ★ 상태 표시(배지)와 조작(버튼)은 **서로 다른 시각 요소**여야 한다. [@design UI-111]
   *
   * 사용자가 「해제됨」 표식과 「재수행」 버튼을 **버튼 두 개로 읽었다**(실제 신고). 원인은
   * 배지가 공용 배지 체계를 타지 않고 그 자리에서 `rounded border border-gray-300 bg-white`
   * 알약을 만들어, 같은 행의 보조 버튼(secondary sm — 흰 배경 + 회색 테두리 알약)과 **시각
   * 언어가 같았기** 때문이다. 공용 배지는 테두리 없는 톤 배경 + 완전 둥근 모서리다.
   *
   * ⚠ 표시 **조건**은 이 라운드에서 바뀌지 않았다 — 바뀐 것은 생김새뿐이라 아래는 형태만 본다.
   */
  describe('상태 표시와 조작 버튼은 시각적으로 갈린다', () => {
    /**
     * 세 표식이 **한 화면에 모두** 뜨는 영상 — 오토라벨은 건너뜀, 시계열은 해제 + 실패.
     *
     * ★ 시계열에 해제와 실패를 함께 실은 것은 억지 조합이 아니라 **재수행이 실패해 영상이 완주로
     *   원상 복구된 상태**다(재수행이 해제 표식을 자동으로 남기므로 해제와 실패가 공존한다).
     *   묶음이 둘뿐이라 이렇게 겹치지 않으면 세 표식을 한 렌더에 세울 수 없다.
     *
     * ⚠ 구 픽스처는 `clearedStages: []` 라 **「해제됨」 배지가 한 번도 렌더되지 않았다.** 그래서
     *   아래 형태 가드 셋이 그 배지를 통과시켰고, 그 배지만 구 인라인 알약으로 되돌려도 전건이
     *   그린이었다(mutation 실증). **하필 사용자가 「재수행」 버튼과 함께 버튼 두 개로 오인한 것이
     *   바로 그 배지**이며 이번 변경의 출발점이었는데, 정작 그것만 가드 밖에 있었다.
     */
    const allMarks = () =>
      videoOf({
        stages: stages({}),
        batchFailureReason: null,
        // 건너뜀 표식
        skippedStages: ['AUTOLABEL'],
        // 해제됨 표식 — 재수행이 남긴 자동 해제
        clearedStages: ['VLM'],
        // 실패 표식 — 그 재수행이 실패했다(해제와 공존하는 실제 상태)
        failedStages: ['VLM'],
      });

    /** 형태 가드가 **빠짐없이** 훑어야 하는 배지 — 세 종류 전부. 하나라도 빼면 그것만 되돌아간다. */
    const MARK_TEST_IDS = [
      'batch-stage-skipped-AUTOLABEL',
      'batch-stage-reverted-VLM',
      'batch-stage-failed-VLM',
    ] as const;

    it('★표식은_버튼이_아니다_조작으로_오인되지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={allMarks()} />);

      for (const testId of MARK_TEST_IDS) {
        const mark = screen.getByTestId(testId);
        expect(mark.tagName, `${testId} 는 버튼이 아니어야 한다`).toBe('SPAN');
        expect(mark.closest('button'), `${testId} 가 버튼 안에 들어가면 조작으로 읽힌다`).toBeNull();
      }
      // 같은 행의 조작은 진짜 버튼이다 — 두 축이 서로 다른 요소로 렌더된다.
      expect(screen.getByRole('button', { name: /작업 건너뛰기$/ }).tagName).toBe('BUTTON');
      // ⚠ 재수행 창구는 건너뛴 적이 있는 묶음마다 하나씩이라 이 픽스처에서는 둘이다(건너뜀·해제).
      const reruns = screen.getAllByRole('button', { name: /작업 재수행$/ });
      expect(reruns).toHaveLength(2);
      for (const btn of reruns) expect(btn.tagName).toBe('BUTTON');
    });

    // ★ 형태 자체를 고정한다 — 인라인으로 흰 배경 + 회색 테두리 알약을 다시 만들면 여기서 깨진다.
    it('★표식은_공용_배지_형태다_흰배경_회색테두리_알약을_다시_만들지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={allMarks()} />);

      for (const testId of MARK_TEST_IDS) {
        const cls = screen.getByTestId(testId).className;
        expect(cls, `${testId} 는 완전 둥근 모서리여야 한다`).toContain('rounded-full');
        expect(cls, `${testId} 에 테두리를 두르면 보조 버튼과 형태가 같아진다`).not.toMatch(
          /\bborder\b/,
        );
        expect(cls, `${testId} 는 흰 배경이 아니라 톤 배경이어야 한다`).not.toContain('bg-white');
      }
    });

    // ★ 실패는 건너뜀·해제와 **다른 것을 말한다** — 같은 중립 톤으로 뭉뚱그리지 않는다.
    //   다만 색만으로 전달하지 않도록 문구가 항상 함께 있다(UI-111 의 label 필수 계약).
    it('★실패_표식만_위험_계열이고_문구가_항상_함께_있다', () => {
      renderWithProviders(<BatchFailurePanel video={allMarks()} />);

      const failed = screen.getByTestId('batch-stage-failed-VLM');
      expect(failed).toHaveTextContent('실패');
      expect(failed.className).toContain('bg-danger-50');
      expect(failed.className).toContain('text-danger-700');

      // 나머지 둘은 시안 색(건너뜀=warn · 해제됨=info)이며 각자의 문구를 항상 함께 둔다.
      // ⚠ **[폐기]** 구 단언 「나머지 둘은 중립 톤」 — 공용 배지에 warn·info variant 가 없던
      //   시절의 것이라 낡았다. 회색으로 되돌리면 아래가 먼저 깨진다.
      const skipped = screen.getByTestId('batch-stage-skipped-AUTOLABEL');
      expect(skipped).toHaveTextContent('건너뜀');
      expect(skipped.className).not.toContain('bg-danger-50');
      expect(skipped.className, '시안 `.badge-warn` = --w-0 / --w-7').toContain('bg-warning-50');
      expect(skipped.className).toContain('text-warning-700');
      expect(skipped.className, '중립으로 되돌리지 말 것').not.toContain('bg-gray-100');

      const reverted = screen.getByTestId('batch-stage-reverted-VLM');
      expect(reverted).toHaveTextContent('해제됨');
      expect(reverted.className).not.toContain('bg-danger-50');
      expect(reverted.className, '시안 `.badge-info` = --i-0 / --i-7').toContain('bg-info-50');
      expect(reverted.className).toContain('text-info-700');
      expect(reverted.className, '중립으로 되돌리지 말 것').not.toContain('bg-gray-100');
    });
  });

  /**
   * 확정 시안(SD-004 `design-main.{html,css}` 의 `.failure-panel`·`.group-row`·`.fp-*`) 정합.
   *
   * ★ 여기 단언은 **표면 규칙**을 고정한다 — 이 패널의 격차 대조에서 가장 자주 되돌아간 축이
   *   「틴트 배경 + 마진으로 쌓은 자식」이었고, 그 형태로 되돌리면 아래가 먼저 깨진다.
   * ⚠ 값이 아니라 **토큰 클래스**를 본다(hex 를 적으면 팔레트가 바뀔 때 여기만 낡는다).
   */
  describe('확정 시안 표면 정합', () => {
    /** 실패 상태 + 조작 묶음 + 전체 재기동이 한 화면에 모두 서는 기본 픽스처. */
    const failedVideo = () => videoOf();

    /** 건너뜀만 남은 완주 영상 — 행 상태 `skipped`. */
    const skippedOnly = () =>
      videoOf({
        status: 'COMPLETED',
        stages: stages({}),
        batchFailureReason: null,
        failedStages: [],
        skippedStages: ['AUTOLABEL'],
      });

    /** 해제만 남은 완주 영상 — 행 상태 `released`. */
    const clearedOnly = () =>
      videoOf({
        status: 'COMPLETED',
        stages: stages({}),
        batchFailureReason: null,
        failedStages: [],
        skippedStages: [],
        clearedStages: ['VLM'],
      });

    it('★패널은_흰_배경_카드다_틴트_배경으로_되돌리지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={failedVideo()} />);

      const cls = screen.getByTestId('batch-failure-panel').className;
      // 시안 `.failure-panel { background: var(--bg-page) }` — 흰 배경이라야 그 안의 흰 카드(행)가
      // 뜬 것처럼 보이지 않는다(패널 배경·행 표면은 한 묶음이다).
      expect(cls, '패널은 흰 배경이어야 한다').toContain('bg-white');
      expect(cls, '구 위험 틴트 배경을 되살리지 말 것').not.toContain('bg-danger/5');
      expect(cls, '구 회색 타일 배경을 되살리지 말 것').not.toContain('bg-gray-50');
      // 시안 `padding: var(--sp-md)`(16 전방향) + `gap: var(--sp-md)`.
      expect(cls, '패딩은 전방향 16이다').toContain('p-4');
      expect(cls, '자식 간격은 컨테이너 gap 이 정한다').toContain('gap-4');
      expect(cls, '구 px-4 py-3 비대칭 패딩으로 되돌리지 말 것').not.toContain('py-3');
    });

    it('★패널에_좌측_4px_강조바가_있고_톤이_상태를_따른다', () => {
      const { unmount } = renderWithProviders(<BatchFailurePanel video={failedVideo()} />);

      const panel = screen.getByTestId('batch-failure-panel');
      expect(panel.className, '시안 `border-left: 4px`').toContain('border-l-4');
      expect(panel).toHaveAttribute('data-tone', 'error');
      expect(panel.className).toContain('border-l-danger-500');
      expect(panel.className).toContain('border-danger-200');
      unmount();

      // 조작이 노출되지 않는 알림 전용(파생영상)은 중립 톤이다 — 시안 `data-tone="neutral"`.
      renderWithProviders(<BatchFailurePanel video={videoOf({ derivative: true })} />);
      const neutral = screen.getByTestId('batch-failure-panel');
      expect(neutral).toHaveAttribute('data-tone', 'neutral');
      expect(neutral.className).toContain('border-l-gray-400');
    });

    // 시안 ② 「건너뛴 작업 있음」 · ③⑤ 「건너뛰기 해제됨」 = data-tone="warn".
    // ⚠ 패널 warn 톤과 행 배지 warn/info 는 **한 묶음**이다 — 한쪽만 되돌리면 노란 패널에
    //   회색 배지가 남아 지금보다 어긋난다(위 배지 단언과 짝이다).
    it('★실패없이_건너뜀_해제만_있으면_패널이_경고_톤이다', () => {
      const { unmount } = renderWithProviders(<BatchFailurePanel video={skippedOnly()} />);
      const skipped = screen.getByTestId('batch-failure-panel');
      expect(skipped).toHaveAttribute('data-tone', 'warn');
      expect(skipped.className, '시안 테두리 --w-2').toContain('border-warning-200');
      expect(skipped.className, '시안 좌측 강조바 --w-5').toContain('border-l-warning-500');
      expect(skipped.className, '중립으로 되돌리지 말 것').not.toContain('border-l-gray-400');
      unmount();

      renderWithProviders(<BatchFailurePanel video={clearedOnly()} />);
      const cleared = screen.getByTestId('batch-failure-panel');
      expect(cleared).toHaveAttribute('data-tone', 'warn');
      expect(cleared.className).toContain('border-l-warning-500');
    });

    it('★헤드_우측에_이_영역을_누가_보는지_배지가_있다', () => {
      const { unmount } = renderWithProviders(<BatchFailurePanel video={failedVideo()} />);
      expect(screen.getByTestId('batch-failure-audience')).toHaveTextContent('검수자 전용');
      unmount();

      const { unmount: u2 } = renderWithProviders(
        <BatchFailurePanel video={videoOf({ derivative: true })} />,
      );
      expect(screen.getByTestId('batch-failure-audience')).toHaveTextContent('파생영상');
      u2();

      renderWithProviders(<BatchFailurePanel video={videoOf({ everApproved: true })} />);
      const approved = screen.getByTestId('batch-failure-audience');
      expect(approved).toHaveTextContent('검수 완료');
      expect(approved.className, '검수 완료는 성공 계열이다').toContain('bg-success-50');
    });

    it('제목은_시안_색_단(n-10)이다', () => {
      renderWithProviders(<BatchFailurePanel video={failedVideo()} />);

      const heading = screen.getByRole('heading', { name: /배치 처리 실패/ });
      expect(heading.className).toContain('text-gray-950');
      expect(heading.className, '구 n-8 로 되돌리지 말 것').not.toContain('text-gray-800');
    });

    it('★실패_사유는_평문이_아니라_경고_박스_안에_있다', () => {
      renderWithProviders(<BatchFailurePanel video={failedVideo()} />);

      const box = screen.getByTestId('batch-failure-alert');
      // 시안 `.alert.alert-error { background: e-0; border: 1px e-2; radius md; padding 16 }`
      expect(box.className).toContain('bg-danger-50');
      expect(box.className).toContain('border-danger-200');
      expect(box.className).toContain('rounded-md');
      expect(box.className).toContain('p-4');
      // 사유·단계가 그 박스 **안**에 있어야 한다(박스만 만들고 밖에 두면 의미가 없다).
      expect(box).toContainElement(screen.getByTestId('batch-failure-reason'));
      expect(box).toContainElement(screen.getByTestId('batch-failure-stage'));
      // 경고 아이콘 1개 — 박스 안에 svg 가 있어야 한다(시안 `.alert-icon`).
      expect(box.querySelector('svg')).not.toBeNull();
    });

    it('★실패_단계는_평문이_아니라_점이_달린_칩이다', () => {
      renderWithProviders(<BatchFailurePanel video={failedVideo()} />);

      const chip = screen.getByTestId('batch-failure-stage');
      // 시안 `.fp-stage-chip { background: e-1; radius sm; 14/600 }` + `.dot { background: e-5 }`
      expect(chip.className).toContain('bg-danger-100');
      expect(chip.className).toContain('rounded-sm');
      expect(chip.className).toContain('text-label');
      const dot = chip.querySelector('span');
      expect(dot, '칩에는 상태 점이 있다').not.toBeNull();
      expect(dot!.className).toContain('rounded-full');
      expect(dot!.className).toContain('bg-danger-500');
    });

    it('★단계를_특정할_수_없을_때만_서버_문구_표식을_단다', () => {
      const { unmount } = renderWithProviders(<BatchFailurePanel video={failedVideo()} />);
      // 칩이 사유의 출처를 이미 붙들고 있으므로 표식을 겹쳐 달지 않는다.
      expect(screen.queryByTestId('batch-failure-verbatim')).not.toBeInTheDocument();
      unmount();

      renderWithProviders(
        <BatchFailurePanel video={videoOf({ stages: [], batchFailureReason: '알 수 없는 오류' })} />,
      );
      expect(screen.getByTestId('batch-failure-verbatim')).toHaveTextContent(
        '문구는 서버가 보낸 그대로입니다',
      );
    });

    it('★묶음_목록에_이름이_있다_사유와_푸터_사이가_익명이_아니다', () => {
      renderWithProviders(<BatchFailurePanel video={failedVideo()} />);

      expect(screen.getByRole('heading', { name: '작업 묶음' })).toBeInTheDocument();
    });

    it('★묶음_행은_강조바를_두른_흰_카드이며_상태별로_색이_갈린다', () => {
      const { unmount } = renderWithProviders(<BatchFailurePanel video={failedVideo()} />);
      const failRow = screen.getByTestId('batch-stage-row-VLM');
      expect(failRow).toHaveAttribute('data-state', 'fail');
      // 시안 `.group-row { border 1px; border-left 4px; radius md; padding 8/16; 흰 배경 }`
      for (const token of ['border-l-4', 'rounded-md', 'bg-white', 'px-4', 'py-2', 'grid']) {
        expect(failRow.className, `행에 ${token} 이 있어야 한다`).toContain(token);
      }
      expect(failRow.className).toContain('border-l-danger-500');
      unmount();

      const { unmount: u2 } = renderWithProviders(<BatchFailurePanel video={skippedOnly()} />);
      const skippedRow = screen.getByTestId('batch-stage-row-AUTOLABEL');
      expect(skippedRow).toHaveAttribute('data-state', 'skipped');
      expect(skippedRow.className).toContain('border-l-warning-500');
      u2();

      renderWithProviders(<BatchFailurePanel video={clearedOnly()} />);
      const releasedRow = screen.getByTestId('batch-stage-row-VLM');
      expect(releasedRow).toHaveAttribute('data-state', 'released');
      expect(releasedRow.className).toContain('border-l-primary-500');
    });

    it('★조작은_행_우측으로_정렬된다_좌측_흐름으로_되돌리지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={failedVideo()} />);

      const actions = screen
        .getByRole('button', { name: `${VLM_LABEL} 작업 건너뛰기` })
        .closest('div')!;
      // 시안 데스크톱 3열 그리드에서 조작 열은 `justify-content: flex-end` 다.
      expect(actions.className).toContain('xl:justify-end');
      // 좁은 폭에서는 다음 줄 좌측으로 내려간다(시안 `@media (max-width: 1279px)`).
      expect(actions.className).toContain('col-span-full');
    });

    it('★묶음_이름_아래에_무엇을_묶은_것인지_부제가_붙는다', () => {
      const { unmount } = renderWithProviders(<BatchFailurePanel video={skippedOnly()} />);
      // 이름은 「오토라벨링」 그대로이고 부제가 멤버를 말한다 — 이름을 멤버 나열로 되돌리지 않는다.
      expect(screen.getByText(AUTOLABEL_LABEL)).toBeInTheDocument();
      // ★ 구 기대값 'AI 탐지 · AI 분할 · 보간' → 폐기. 부제는 단계 표(`stageLabel`)에서 파생되고
      //   그 표의 `INTERPOLATE` 가 「트랙 보간」으로 정정됐다(정본 `UI-018` · 시안 `.group-sub`).
      expect(screen.getByTestId('batch-stage-subtitle-AUTOLABEL')).toHaveTextContent(
        'AI 탐지 · AI 분할 · 트랙 보간',
      );
      unmount();

      renderWithProviders(<BatchFailurePanel video={clearedOnly()} />);
      expect(screen.getByTestId('batch-stage-subtitle-VLM')).toHaveTextContent('영상 서술 생성');
    });

    it('★건너뛰기_모달의_대상_칩도_같은_멤버_부제를_쓴다', async () => {
      // 목록 행과 모달이 **같은 문자열**이어야 한다 — 기대값을 손으로 적지 않고 행에서 읽어 비교해,
      // 단계 표시명이나 묶음 구성이 바뀌면 두 곳이 함께 따라오는지를 그대로 확인한다.
      const user = userEvent.setup();
      renderWithProviders(
        <BatchFailurePanel
          video={videoOf({ stages: stages({ YOLO: 'FAIL' }), failedStages: ['AUTOLABEL'] })}
        />,
      );
      const subtitle = screen.getByTestId('batch-stage-subtitle-AUTOLABEL').textContent!;
      expect(subtitle).toContain('·');

      await user.click(screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 건너뛰기` }));
      expect(await screen.findByTestId('batch-stage-skip-modal')).toHaveTextContent(subtitle);
    });

    it('멤버가_하나뿐인_묶음은_모달_대상_칩에_부제를_두지_않는다', async () => {
      // 시계열은 멤버가 자기 자신뿐이라 나열이 이름과 같아진다. 행의 부제('영상 서술 생성')는
      // 멤버 나열이 아니므로 모달로 흘러가면 안 된다 — 흘러가면 이 케이스가 잡는다.
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={videoOf()} />);

      await user.click(screen.getByRole('button', { name: `${VLM_LABEL} 작업 건너뛰기` }));
      const modal = await screen.findByTestId('batch-stage-skip-modal');
      expect(modal).not.toHaveTextContent('영상 서술 생성');
    });

    it('묶음_이름은_제목_계열_타이포다', () => {
      renderWithProviders(<BatchFailurePanel video={failedVideo()} />);

      // ⚠ 문서 전역에서 이름으로 찾지 않는다 — 같은 문자열이 실패 단계 칩에도 있어 둘이 잡힌다.
      const row = screen.getByTestId('batch-stage-row-VLM');
      const name = row.querySelector('.text-title-sm');
      expect(name, '묶음 이름이 제목 계열 토큰으로 렌더돼야 한다').not.toBeNull();
      expect(name).toHaveTextContent(VLM_LABEL);
      expect(name!.className).toContain('text-gray-950');
      expect(name!.className, '구 본문 계열(text-body-md)로 되돌리지 말 것').not.toContain(
        'text-body-md',
      );
    });

    it('★전체_재기동은_묶음_목록_위가_아니라_패널_맨_아래_푸터에_있다', () => {
      renderWithProviders(<BatchFailurePanel video={failedVideo()} />);

      const panel = screen.getByTestId('batch-failure-panel');
      const list = panel.querySelector('ul');
      const foot = screen.getByTestId('batch-failure-foot');
      expect(list, '조작 묶음 목록이 있어야 비교가 성립한다').not.toBeNull();
      // 시안 `.fp-foot` — 구분선 위, 목록 **다음**.
      expect(foot.className).toContain('border-t');
      expect(
        list!.compareDocumentPosition(foot) & Node.DOCUMENT_POSITION_FOLLOWING,
        '푸터는 묶음 목록보다 뒤에 온다',
      ).toBeTruthy();
      // 버튼은 푸터 안, 안내 문구는 그 왼쪽에 함께 있다.
      expect(foot).toContainElement(screen.getByRole('button', { name: /배치 재실행/ }));
      expect(foot).toContainElement(screen.getByTestId('batch-retry-hint'));
    });

    it('파생영상_안내도_푸터에_들어간다', () => {
      renderWithProviders(<BatchFailurePanel video={videoOf({ derivative: true })} />);

      const foot = screen.getByTestId('batch-failure-foot');
      expect(foot).toContainElement(screen.getByTestId('batch-failure-derivative-note'));
      expect(screen.queryByRole('heading', { name: '작업 묶음' })).not.toBeInTheDocument();
    });

    it('★재수행은_그_행의_주_행동이라_primary_다', () => {
      renderWithProviders(<BatchFailurePanel video={clearedOnly()} />);

      const rerun = screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` });
      // ★ 이 가드가 지키는 것은 «재수행이 primary 변이인가»(= secondary 로 되돌아가지
      //   않았는가)이지 **팔레트 단수가 아니다**. 그래서 `bg-primary-600` 같은 단수를 직접
      //   적지 않고 `Button variant='primary'` 가 실제로 내는 배경 클래스를 **기준 렌더에서
      //   뽑아** 대조한다. 단수를 여기에 박으면 팔레트가 한 단 움직일 때마다 이 무관한 기능
      //   테스트가 함께 깨진다 — 실제로 구 단언은 시안 `.btn-primary`(= `--p-5`)를 근거로
      //   인용하면서 그와 반대인 600 을 고정하고 있었다.
      //   단수 자체는 Button 자신의 테스트(`Button_primary_는_시안_btn_primary_단수를_쓴다`)가
      //   한 곳에서만 고정한다.
      expect(rerun.className, '시안 `.btn-primary` — primary 변이').toContain(
        primaryButtonBgClass(),
      );
      expect(rerun.className, '구 secondary 로 되돌리지 말 것').not.toContain('border-gray-400');
    });
  });

  // [@design SCREEN-009] [@design API-167] [@design AC-1133] [@design AC-1134]
  // ★ 선두 비식별이 실패한 영상 — 배치 진행 로그를 남기지 않아 `stages` 는 빈 배열, 실패 사유는 비고
  //   배치 상태는 대기(PENDING)다. 비식별 여부만 `'F'` 로 실패를 말한다(서버 실측 응답 모양).
  //   이 축이 빠지면 조치 영역과 재기동 버튼이 통째로 사라져 「다시 할 수단이 없다」는 신고가 재발한다.
  describe('★선두 비식별이 실패한 영상', () => {
    const leadDeidentFailed = (partial: Partial<VideoDetail> = {}) =>
      videoOf({
        status: 'PENDING',
        deIdntfYn: 'F',
        stages: [],
        batchFailureReason: null,
        skippedStages: [],
        clearedStages: [],
        failedStages: [],
        deidentHistory: [{ procLogSn: 1, procSttsCd: 'FAILED', reqKndCd: null }],
        ...partial,
      });

    it('조치_영역과_재기동_버튼을_보이고_지금_실패로_말한다', () => {
      renderWithProviders(<BatchFailurePanel video={leadDeidentFailed()} />);

      expect(screen.getByTestId('batch-failure-panel')).toHaveAttribute('data-mode', 'failure');
      // 상태는 색이 아니라 제목 문구가 말한다 — 「직전」이 아니라 지금 실패다.
      expect(screen.getByRole('heading', { name: '배치 처리 실패' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /배치 재실행/ })).toBeEnabled();
      // 실패 단계는 비식별로 세우고, 단계를 특정할 수 없다고 말하지 않는다.
      expect(screen.getByTestId('batch-failure-stage')).toHaveTextContent('비식별');
      expect(screen.getByTestId('batch-failure-stage')).not.toHaveTextContent('확인 불가');
    });

    it('사유를_지어내지_않고_확인할_곳을_알리며_서버_문구_표식을_붙이지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={leadDeidentFailed()} />);

      const reason = screen.getByTestId('batch-failure-reason');
      expect(reason).toHaveTextContent('비식별 처리가 완료되지 않아 마킹을 시작할 수 없습니다.');
      expect(reason).toHaveTextContent('비식별 이력');
      expect(reason).not.toHaveTextContent('기록된 사유가 없습니다');
      // 이 문장은 화면 문구다 — 「서버가 보낸 그대로」라고 말하면 거짓이 된다.
      expect(screen.queryByTestId('batch-failure-verbatim')).not.toBeInTheDocument();
    });

    it('서버가_사유를_내려주면_그_문구를_그대로_쓴다', () => {
      renderWithProviders(
        <BatchFailurePanel
          video={leadDeidentFailed({ batchFailureReason: '외부 비식별 서버가 응답하지 않았습니다.' })}
        />,
      );

      expect(screen.getByTestId('batch-failure-reason')).toHaveTextContent(
        '외부 비식별 서버가 응답하지 않았습니다.',
      );
    });

    it('재기동_안내는_비식별_단계를_처음부터_다시_수행한다고_말한다_마킹_이후만_다시_돈다고_말하지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={leadDeidentFailed()} />);

      const hint = screen.getByTestId('batch-retry-hint');
      expect(hint).toHaveTextContent('적재 직후와 같은 비식별 단계를 처음부터 다시 수행합니다.');
      expect(hint).toHaveTextContent('마킹을 시작할 수 있는 상태');
      expect(hint).not.toHaveTextContent('실패한 단계부터 이어서');
      // 비활성 사유·안내가 보조기술에도 전달된다.
      expect(screen.getByRole('button', { name: /배치 재실행/ })).toHaveAttribute(
        'aria-describedby',
        'batch-retry-hint',
      );
    });

    it('접수_응답의_단계가_대기여도_오류가_아니라_정상_접수로_안내한다', async () => {
      mock.onPost('/videos/7/batch/retry').reply(200, {
        success: true,
        data: { rawSn: 7, stage: 'PENDING' },
        message: null,
        errorCode: null,
      });
      useUiStore.setState({ toasts: [] });
      const user = userEvent.setup();

      renderWithProviders(<BatchFailurePanel video={leadDeidentFailed()} />);
      await user.click(screen.getByRole('button', { name: /배치 재실행/ }));

      await waitFor(() => expect(useUiStore.getState().toasts).toHaveLength(1));
      const toast = useUiStore.getState().toasts[0]!;
      expect(mock.history.post.map((h) => h.url)).toEqual(['/videos/7/batch/retry']);
      expect(toast.variant).toBe('success');
      expect(toast.message).toContain('접수');
      expect(toast.message).toContain('비식별 이력');
      expect(toast.message).not.toMatch(/시작했|완료/);
    });

    it('거부되면_서버가_내려준_사유_문구를_그대로_보인다', async () => {
      const serverMessage = '열린 비식별 누락 신고가 있어 재시작할 수 없습니다. 신고 해소 경로를 이용하세요.';
      mock.onPost('/videos/7/batch/retry').reply(409, {
        success: false,
        data: null,
        message: serverMessage,
        errorCode: 'CONFLICT',
      });
      useUiStore.setState({ toasts: [] });
      const user = userEvent.setup();

      renderWithProviders(<BatchFailurePanel video={leadDeidentFailed()} />);
      await user.click(screen.getByRole('button', { name: /배치 재실행/ }));

      await waitFor(() => expect(useUiStore.getState().toasts).toHaveLength(1));
      const toast = useUiStore.getState().toasts[0]!;
      expect(toast.variant).toBe('error');
      expect(toast.message).toBe(serverMessage);
    });

    it('파생영상에는_재기동_버튼을_두지_않는다', () => {
      renderWithProviders(<BatchFailurePanel video={leadDeidentFailed({ derivative: true })} />);

      expect(screen.queryByRole('button', { name: /배치 재실행/ })).not.toBeInTheDocument();
    });

    it('다시_요청한_비식별이_진행_중이면_처리_중으로_말하고_버튼을_막는다', () => {
      renderWithProviders(
        <BatchFailurePanel
          video={leadDeidentFailed({
            // 서버 정렬 — 최신 회차가 맨 앞이다.
            deidentHistory: [
              { procLogSn: 2, procSttsCd: 'REQUESTED', reqKndCd: null },
              { procLogSn: 1, procSttsCd: 'FAILED', reqKndCd: null },
            ],
          })}
        />,
      );

      expect(screen.getByTestId('batch-failure-panel')).toHaveAttribute('data-mode', 'processing');
      expect(screen.getByRole('heading', { name: '배치 처리 중' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /배치 재실행/ })).toBeDisabled();
      expect(screen.getByTestId('batch-retry-hint')).toHaveTextContent('이미 처리 중');
    });

    it('판정은_비식별_실패와_배치_대기가_함께일_때만이다', () => {
      const empty = {
        stages: [] as BatchStageItem[],
        batchFailureReason: null,
        skippedStages: [] as StageBundle[],
        clearedStages: [] as StageBundle[],
        failedStages: [] as StageBundle[],
      };
      expect(needsBatchAttention({ ...empty, status: 'PENDING', deIdntfYn: 'F' })).toBe(true);
      // 마킹 준비 이후의 'F'(비식별 누락 신고 표식)는 선두 비식별 실패가 아니다.
      expect(needsBatchAttention({ ...empty, status: 'MARKING_READY', deIdntfYn: 'F' })).toBe(false);
      // 아직 비식별 중인 영상(대기 + 'N')은 실패가 아니다.
      expect(needsBatchAttention({ ...empty, status: 'PENDING', deIdntfYn: 'N' })).toBe(false);
      expect(batchPanelMode({ ...empty, status: 'PENDING', deIdntfYn: 'F' })).toBe('failure');
    });
  });
});
