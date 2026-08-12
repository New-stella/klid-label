// 배치 실패 사유 + 조치 패널. [@design SCREEN-009] [@design API-167] [@design API-198] [@design API-200]

import fs from 'node:fs';
import path from 'node:path';

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useUiStore } from '@/stores/useUiStore';

import { BatchFailurePanel, batchPanelMode, hasBatchFailure } from '../BatchFailurePanel';
import type { BatchStageItem, StageBundle, VideoDetail } from '../../types';

/**
 * 오토라벨 묶음의 화면 노출명 — `stageLabel` 에서 파생되는 값(별도 이름표를 두지 않는다).
 * 테스트가 이 문자열을 직접 적어 두는 이유는, 파생 규칙이 바뀌면 **여기서 먼저 깨져** 표시명 변경이
 * 눈에 띄게 하기 위해서다(테스트가 같은 파생을 호출하면 무엇이 바뀌든 통과한다).
 */
const AUTOLABEL_LABEL = 'AI 탐지 · AI 분할 · 보간';

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
        video={videoOf({ stages: stages({}), batchFailureReason: null, skippedStages: [] })}
      />,
    );

    expect(screen.queryByTestId('batch-failure-panel')).not.toBeInTheDocument();
  });

  // ★ HIGH — 건너뛴 뒤 재기동이 성공하면 실패가 사라진다. 그때 패널까지 사라지면 그 단계는 이후
  //   모든 재기동에서 조용히 건너뛰어진 채 화면에는 완료로 보이고, 되돌릴 진입점이 없어진다.
  describe('실패가_없고_건너뛴_단계만_남은_영상', () => {
    const skippedOnly = () =>
      videoOf({ stages: stages({}), batchFailureReason: null, skippedStages: ['VLM'] });

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

    it('되돌리기_창구가_남아_있다', async () => {
      mock.onDelete('/videos/7/batch/stages/VLM/skip').reply(204);
      const user = userEvent.setup();

      renderWithProviders(<BatchFailurePanel video={skippedOnly()} />);
      await user.click(screen.getByRole('button', { name: 'VLM 작업 건너뛰기 되돌리기' }));

      await waitFor(() => {
        expect(mock.history.delete.map((h) => h.url)).toContain('/videos/7/batch/stages/VLM/skip');
      });
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

      expect(screen.getByTestId('batch-failure-reason')).toHaveTextContent(
        '외부 시계열 분석 서버가 응답하지 않았습니다.',
      );
      expect(screen.getByText('직전 실패 사유')).toBeInTheDocument();
      expect(screen.getByText('직전 실패 단계')).toBeInTheDocument();
    });

    it('건너뛴_단계와_되돌리기는_처리_중에도_사라지지_않는다', () => {
      // 스킵 축이 처리 중 축에 먹히면 되돌릴 창구가 또 없어진다(이미 한 번 난 결함).
      renderWithProviders(
        <BatchFailurePanel video={videoOf({ status: 'PROCESSING', skippedStages: ['VLM'] })} />,
      );

      expect(screen.getByTestId('batch-stage-skipped-VLM')).toHaveTextContent('건너뜀');
      expect(
        screen.getByRole('button', { name: 'VLM 작업 건너뛰기 되돌리기' }),
      ).toBeInTheDocument();
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

      expect(screen.getByTestId('batch-failure-reason')).toHaveTextContent(
        '오토라벨 재수행이 실패했습니다.',
      );
      expect(screen.getByText('직전 실패 사유')).toBeInTheDocument();
      expect(screen.getByText('직전 실패 단계')).toBeInTheDocument();
      // 현재 실패를 뜻하는 이름표는 쓰지 않는다(문자열 매칭은 완전일치라 「직전 …」과 구분된다).
      expect(screen.queryByText('실패 사유')).not.toBeInTheDocument();
      expect(screen.queryByText('실패 단계')).not.toBeInTheDocument();
    });

    it('★되돌린_묶음의_재수행_버튼은_그대로_살아_있다', async () => {
      // 재수행이 실패했으니 다시 시도하는 것이 정상 동선이다 — 이것마저 사라지면 할 수 있는 게 없다.
      const user = userEvent.setup();
      mock.onDelete('/videos/7/batch/stages/VLM/skip').reply(204);
      useUiStore.setState({ toasts: [] });

      const view = renderWithProviders(
        <BatchFailurePanel
          video={videoOf({ stages: stages({}), batchFailureReason: null, skippedStages: ['VLM'] })}
        />,
      );
      await user.click(screen.getByRole('button', { name: 'VLM 작업 건너뛰기 되돌리기' }));
      await waitFor(() =>
        expect(useUiStore.getState().toasts.at(-1)?.message).toContain('되돌렸습니다'),
      );
      // 재수행을 요청했고 그것이 실패해 영상이 완주로 되돌아온 뒤의 상세.
      view.rerender(<BatchFailurePanel video={restoredAfterFailedRerun()} />);

      expect(await screen.findByRole('button', { name: 'VLM 작업 재수행' })).toBeEnabled();
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
  });

  it('건너뛰기는_실패한_단계가_속한_묶음에만_노출된다', () => {
    renderWithProviders(<BatchFailurePanel video={videoOf({ stages: stages({ VLM: 'FAIL' }) })} />);

    expect(screen.getByRole('button', { name: 'VLM 작업 건너뛰기' })).toBeInTheDocument();
    // 실패하지 않은 다른 묶음에는 버튼이 없다.
    expect(
      screen.queryByRole('button', { name: `${AUTOLABEL_LABEL} 작업 건너뛰기` }),
    ).not.toBeInTheDocument();
  });

  // ★ 조작 단위가 묶음이라, 실패한 **단계**를 그 단계가 속한 **묶음**으로 해석해야 버튼이 나온다.
  //   단계 코드로 직접 비교하면(구 동작) 오토라벨 안의 어떤 단계가 실패해도 버튼이 뜨지 않는다.
  it('★오토라벨_묶음의_단계가_실패하면_그_묶음_건너뛰기가_노출된다', () => {
    renderWithProviders(<BatchFailurePanel video={videoOf({ stages: stages({ SAM2: 'FAIL' }) })} />);

    expect(
      screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 건너뛰기` }),
    ).toBeInTheDocument();
  });

  // ★ 보간은 이제 오토라벨 묶음 **안에** 있다 — 묶음 밖에 두면 어떤 재수행에서도 무조건 돌아
  //   사람이 손댄 보간 라벨을 지운다. 그 포함 관계가 화면 조작에도 그대로 드러나야 한다.
  it('★보간이_실패해도_오토라벨_묶음으로_해석된다', () => {
    renderWithProviders(
      <BatchFailurePanel video={videoOf({ stages: stages({ INTERPOLATE: 'FAIL' }) })} />,
    );

    expect(
      screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 건너뛰기` }),
    ).toBeInTheDocument();
  });

  it('어느_묶음에도_없는_단계가_실패하면_건너뛰기_버튼이_없다', () => {
    renderWithProviders(
      <BatchFailurePanel video={videoOf({ stages: stages({ FRAME_EXTRACT: 'FAIL' }) })} />,
    );

    expect(screen.getByTestId('batch-failure-stage')).toHaveTextContent('프레임추출');
    expect(screen.queryByRole('button', { name: /작업 건너뛰기$/ })).not.toBeInTheDocument();
  });

  // 묶음 이름은 별도 이름표가 아니라 **단계 노출명의 조합**이다 — 그래서 이름만 보고도 보간이 이
  // 묶음 안에 있다는 사실을 알 수 있다(이번 변경의 핵심이 이름에 드러난다).
  it('★오토라벨_묶음_이름이_품은_작업을_그대로_말한다_보간_포함', () => {
    renderWithProviders(<BatchFailurePanel video={videoOf({ stages: stages({ YOLO: 'FAIL' }) })} />);

    const button = screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 건너뛰기` });
    expect(button).toBeInTheDocument();
    expect(AUTOLABEL_LABEL).toContain('보간');
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
    await user.click(screen.getByRole('button', { name: 'VLM 작업 건너뛰기' }));

    const submit = await screen.findByRole('button', { name: '건너뛰기' });
    // 사유가 비어 있으면 제출할 수 없다(서버 @NotBlank 와 같은 제약).
    expect(submit).toBeDisabled();

    await user.type(screen.getByLabelText('건너뛰기 사유'), '벤더 장애 지속');
    await waitFor(() => expect(submit).toBeEnabled());
    await user.click(submit);

    await waitFor(() => {
      const call = mock.history.post.find((h) => h.url === '/videos/7/batch/stages/VLM/skip');
      expect(call).toBeDefined();
      expect(JSON.parse(call!.data)).toEqual({ reason: '벤더 장애 지속' });
    });
  });

  it('이미_건너뛴_단계는_건너뜀_표시와_되돌리기를_보여준다', async () => {
    // 표시기는 스킵된 단계를 완료로 그리므로, 건너뛰었다는 사실은 이 패널에서만 드러난다.
    mock.onDelete('/videos/7/batch/stages/VLM/skip').reply(204);
    const user = userEvent.setup();

    renderWithProviders(<BatchFailurePanel video={videoOf({ skippedStages: ['VLM'] })} />);

    expect(screen.getByTestId('batch-stage-skipped-VLM')).toHaveTextContent('건너뜀');
    await user.click(screen.getByRole('button', { name: 'VLM 작업 건너뛰기 되돌리기' }));

    await waitFor(() => {
      expect(mock.history.delete.map((h) => h.url)).toContain('/videos/7/batch/stages/VLM/skip');
    });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'VLM 작업 건너뛰기 되돌리기' })).toBeEnabled(),
    );
  });

  // ★ 되돌린 **작업 묶음**의 재수행 — 「문제가 생긴 곳부터 재시도한다」. [@design API-201]
  //   전체 재기동을 완주 영상에 쓰면 파이프라인이 통째로 돌아 보간이 함께 수행되고 사람이 손댄
  //   보간 라벨이 전량 지워진다(복구 지점 없음). 그래서 되돌린 그 묶음만 지목한다.
  //
  //   ★ 버튼은 **하나**다 — 범위를 고르지 않는다(묶음이 곧 범위다). 구 두 갈래(「이 단계만 실행」·
  //     「여기부터 이어서 실행」)는 폐기됐다. 되살리면 보간을 뺀 부분 수행이 다시 가능해진다.
  //
  //   ⚠ 이 테스트들이 **되돌리기 → 재조회(prop 교체)** 를 실제로 거치는 이유: 「되돌린 묶음」은
  //     서버 응답에 없다. `skippedStages` 는 되돌리는 순간 그 묶음을 빼 버리므로, 화면은
  //     되돌리기가 성공한 사실을 스스로 기억할 수밖에 없다. prop 만 바꿔서는 재현되지 않는다.
  describe('되돌린 작업 묶음 재수행', () => {
    const skippedOnly = (bundle: StageBundle) =>
      videoOf({ stages: stages({}), batchFailureReason: null, skippedStages: [bundle] });
    /** 되돌리기가 반영된 뒤의 서버 상태 — 실패도 스킵도 없다(그래도 패널은 남아야 한다). */
    const afterRevert = () =>
      videoOf({ stages: stages({}), batchFailureReason: null, skippedStages: [] });

    /** 되돌리기 → 무효화로 갱신된 응답(prop 교체)까지를 재현한다. */
    async function revert(user: ReturnType<typeof userEvent.setup>, bundle: StageBundle) {
      const label = bundle === 'VLM' ? 'VLM' : AUTOLABEL_LABEL;
      mock.onDelete(`/videos/7/batch/stages/${bundle}/skip`).reply(204);
      useUiStore.setState({ toasts: [] });
      const view = renderWithProviders(<BatchFailurePanel video={skippedOnly(bundle)} />);

      await user.click(screen.getByRole('button', { name: `${label} 작업 건너뛰기 되돌리기` }));
      // 되돌림을 기억하는 상태 갱신까지 기다린 뒤에 갱신된 응답으로 교체한다(순서가 뒤집히면
      // 이 테스트가 재현하려는 "되돌리기 → 재조회" 순서가 아니게 된다).
      await waitFor(() =>
        expect(useUiStore.getState().toasts.at(-1)?.message).toContain('되돌렸습니다'),
      );
      view.rerender(<BatchFailurePanel video={afterRevert()} />);
      await screen.findByTestId(`batch-stage-reverted-${bundle}`);
      return view;
    }

    function rerunCalls(bundle: StageBundle) {
      return mock.history.post.filter((h) => h.url === `/videos/7/batch/stages/${bundle}/rerun`);
    }

    beforeEach(() => {
      for (const bundle of ['VLM', 'AUTOLABEL'] as const) {
        mock.onPost(`/videos/7/batch/stages/${bundle}/rerun`).reply(200, {
          success: true,
          data: { rawSn: 7, stage: bundle, accepted: true },
          message: null,
          errorCode: null,
        });
      }
    });

    it('되돌리면_실패도_스킵도_없어도_패널이_남고_재수행_버튼이_하나_생긴다', async () => {
      // 패널이 사라지면 방금 만든 재수행 창구가 같이 사라진다(되돌릴 진입점 소실의 재발).
      await revert(userEvent.setup(), 'VLM');

      expect(screen.getByTestId('batch-failure-panel')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'VLM 작업 재수행' })).toBeInTheDocument();
    });

    // ★ 범위 선택 갈래가 폐기됐음을 고정한다 — 되살리면 보간을 뺀 부분 수행이 다시 가능해지고,
    //   그것이 이번 변경이 없앤 바로 그 형태다.
    it('★범위를_고르는_두_갈래_버튼은_없다_묶음이_곧_범위다', async () => {
      await revert(userEvent.setup(), 'AUTOLABEL');

      expect(screen.queryByRole('button', { name: /이 단계만 실행/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /여기부터 이어서 실행/ })).not.toBeInTheDocument();
      // 재수행 버튼은 정확히 하나다.
      expect(screen.getAllByRole('button', { name: /작업 재수행$/ })).toHaveLength(1);
    });

    // ★ 오토라벨 재수행은 보간까지 다시 만들어 사람이 손댄 보간 라벨을 지운다 — 파괴적이라
    //   클릭이 곧 요청이 되면 안 된다. 취소 기회가 없으면 한 번의 오클릭으로 확정된다.
    it('★오토라벨_재수행은_확인_없이_실행되지_않는다', async () => {
      const user = userEvent.setup();
      await revert(user, 'AUTOLABEL');

      await user.click(screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 재수행` }));

      expect(await screen.findByRole('dialog')).toBeInTheDocument();
      expect(rerunCalls('AUTOLABEL')).toHaveLength(0);

      await user.click(screen.getByRole('button', { name: '취소' }));
      expect(rerunCalls('AUTOLABEL')).toHaveLength(0);
    });

    it('확인해야_오토라벨_재수행을_요청한다_본문은_보내지_않는다', async () => {
      const user = userEvent.setup();
      await revert(user, 'AUTOLABEL');

      await user.click(screen.getByRole('button', { name: `${AUTOLABEL_LABEL} 작업 재수행` }));
      // 확인 창의 확정 버튼은 접근명이 정확히 '재수행'이라 트리거 버튼과 구분된다(문자열 매칭은 완전일치).
      await user.click(await screen.findByRole('button', { name: '재수행' }));

      await waitFor(() => expect(rerunCalls('AUTOLABEL')).toHaveLength(1));
      // 범위를 고르지 않으므로 보낼 본문 자체가 없다(구 `{scope}` 폐지).
      expect(rerunCalls('AUTOLABEL')[0]!.data).toBeUndefined();
    });

    // ★ 시계열 묶음은 보간을 품지 않아 파괴적이지 않다 — 없는 위험에 확인을 받으면 확인이
    //   형식이 되어 무시되고, 정작 파괴적인 쪽의 확인도 함께 가벼워진다.
    it('★시계열_재수행은_확인_없이_곧바로_접수된다', async () => {
      const user = userEvent.setup();
      await revert(user, 'VLM');

      await user.click(screen.getByRole('button', { name: 'VLM 작업 재수행' }));

      await waitFor(() => expect(rerunCalls('VLM')).toHaveLength(1));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    // ★ 사양: "고르는 시점에 알린다" — 누른 뒤에 뜨는 확인 창은 취소 수단이지 고지 수단이 아니다.
    it('★보간_라벨이_바뀐다는_사실을_누르기_전에_알린다_보조기술_포함', async () => {
      await revert(userEvent.setup(), 'AUTOLABEL');

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
    it('★시계열_재수행에는_보간_경고를_붙이지_않는다', async () => {
      await revert(userEvent.setup(), 'VLM');

      expect(screen.queryByTestId('batch-rerun-warning-VLM')).not.toBeInTheDocument();

      const rerunButton = screen.getByRole('button', { name: 'VLM 작업 재수행' });
      const describedText = (rerunButton.getAttribute('aria-describedby') ?? '')
        .split(/\s+/)
        .filter(Boolean)
        .map((id) => document.getElementById(id)?.textContent ?? '')
        .join(' ');

      expect(describedText).not.toMatch(/보간/);
    });

    it('재수행_응답은_접수를_뜻하고_완료로_읽히는_문구를_쓰지_않는다', async () => {
      const user = userEvent.setup();
      await revert(user, 'VLM');
      useUiStore.setState({ toasts: [] });

      await user.click(screen.getByRole('button', { name: 'VLM 작업 재수행' }));

      await waitFor(() => expect(useUiStore.getState().toasts).toHaveLength(1));
      const message = useUiStore.getState().toasts[0]!.message;
      expect(message).toContain('접수');
      expect(message).not.toMatch(/시작했|완료/);
      expect(message).toContain('처리 단계');
    });

    it('처리_중에는_재수행_버튼이_비활성이고_왜인지를_읽을_수_있다', async () => {
      const user = userEvent.setup();
      const view = await revert(user, 'VLM');
      // 재수행을 접수하면 서버가 상태를 처리 중으로 선점한다 — 그 상태의 버튼은 눌러도 막힌다.
      view.rerender(
        <BatchFailurePanel
          video={videoOf({
            stages: stages({}),
            batchFailureReason: null,
            skippedStages: [],
            status: 'PROCESSING',
          })}
        />,
      );

      const rerunButton = screen.getByRole('button', { name: 'VLM 작업 재수행' });
      expect(rerunButton).toBeDisabled();
      expect(screen.getByTestId('batch-rerun-busy-hint-VLM')).toHaveTextContent('이미 처리 중이라');
      expect(rerunButton.getAttribute('aria-describedby')?.split(/\s+/)).toContain(
        'batch-rerun-busy-hint-VLM',
      );
    });

    it('되돌린_묶음을_다시_건너뛰면_재수행_버튼이_사라진다', async () => {
      // 서버는 실제로 되돌린 묶음만 수락하므로(400), 다시 건너뛴 묶음에 버튼을 남기면 안 된다.
      const user = userEvent.setup();
      const view = await revert(user, 'VLM');
      view.rerender(<BatchFailurePanel video={skippedOnly('VLM')} />);

      expect(screen.queryByRole('button', { name: 'VLM 작업 재수행' })).not.toBeInTheDocument();
    });

    it('되돌리지_않은_영상에는_재수행_버튼이_없다', () => {
      // 되돌린 묶음이 아니면 서버가 400 으로 막는다 — 누르면 반드시 막히는 버튼을 두지 않는다.
      renderWithProviders(<BatchFailurePanel video={videoOf({ skippedStages: ['VLM'] })} />);

      expect(screen.queryByRole('button', { name: /작업 재수행$/ })).not.toBeInTheDocument();
    });

    // ★ 전체 재기동은 완주 영상에 노출하면 안 된다 — 그 경로가 바로 보간 라벨을 지우는 파괴 경로다.
    it('★되돌린_뒤에도_전체_재기동_버튼은_생기지_않는다_실패한_영상_전용이다', async () => {
      await revert(userEvent.setup(), 'VLM');

      expect(screen.queryByRole('button', { name: /배치 재실행/ })).not.toBeInTheDocument();
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
    //   그 표시기는 MarkingPage 와 공유하므로 넣는 순간 마킹 화면에도 그대로 나타난다.
    //   판정은 "누가 이 패널을 마운트하는가"로 한다 — 렌더 테스트로는 배치 실수를 잡지 못한다.
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
});
