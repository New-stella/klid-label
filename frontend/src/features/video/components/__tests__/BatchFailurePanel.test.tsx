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

      expect(screen.getByTestId('batch-failure-reason')).toHaveTextContent(
        '외부 시계열 분석 서버가 응답하지 않았습니다.',
      );
      expect(screen.getByText('직전 실패 사유')).toBeInTheDocument();
      expect(screen.getByText('직전 실패 단계')).toBeInTheDocument();
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

      expect(screen.getByTestId('batch-failure-reason')).toHaveTextContent(
        '오토라벨 재수행이 실패했습니다.',
      );
      expect(screen.getByText('직전 실패 사유')).toBeInTheDocument();
      expect(screen.getByText('직전 실패 단계')).toBeInTheDocument();
      // 현재 실패를 뜻하는 이름표는 쓰지 않는다(문자열 매칭은 완전일치라 「직전 …」과 구분된다).
      expect(screen.queryByText('실패 사유')).not.toBeInTheDocument();
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

    await user.type(screen.getByLabelText('건너뛰기 사유'), '벤더 장애 지속');
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
      await user.type(screen.getByLabelText('건너뛰기 사유'), '벤더 미연동');
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

    // ★ 시계열 묶음은 보간을 품지 않아 파괴적이지 않다 — 없는 위험에 확인을 받으면 확인이
    //   형식이 되어 무시되고, 정작 파괴적인 쪽의 확인도 함께 가벼워진다.
    it('★시계열_재수행은_확인_없이_곧바로_접수된다', async () => {
      const user = userEvent.setup();
      renderWithProviders(<BatchFailurePanel video={cleared('VLM')} />);

      await user.click(screen.getByRole('button', { name: `${VLM_LABEL} 작업 재수행` }));

      await waitFor(() => expect(rerunCalls('VLM')).toHaveLength(1));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
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
