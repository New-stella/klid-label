// Phase 2 — **의도 고정**: 메타 편집은 busy(장시간 작업)와 독립이다.
//
// 라벨 작업본(store labels/dirtyLabels)을 건드리는 편집은 busy 동안 전부 차단하지만,
// 메타 편집(프레임 설명·촬영환경 등)은 라벨 작업본·dirty 와 공유 상태가 없고 각자 별도 쿼리 키만
// invalidate 한다 — 진행 중 저장/AI 결과 병합과 겹쳐도 정합성이 손상되는 경로가 없다.
//
// 그래서 "차단 범위에 왜 빠졌나"를 라운드마다 재논쟁하지 않도록, **차단하지 않는 것이 설계**임을
// 여기서 실행 가능한 형태로 고정한다. 이 테스트가 깨진다면 그것은 회귀가 아니라 정책 변경이며,
// 변경하려면 이 주석의 근거(공유 상태 없음)부터 반증해야 한다.

import { useRef } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';
import { selectRadixOption } from '@/test/selectTestUtils';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';

import type { AnnotationColumnHandle } from '../components/AnnotationColumn';
import { EnvironmentMetaPanel } from '../components/EnvironmentMetaPanel';
import {
  EventAnnotationPanel,
  type EventAnnotationPanelHandle,
} from '../components/EventAnnotationPanel';
import { FrameDescriptionPanel } from '../components/FrameDescriptionPanel';
import { FramePrivacyMetaPanel } from '../components/FramePrivacyMetaPanel';
import { TimeseriesSidePanel } from '../components/TimeseriesSidePanel';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

/**
 * 창의 두 칸에는 자체 저장 버튼이 없다 — 창 아래 공통 「저장」이 손잡이로 부른다.
 * 이 시험은 그 저장이 busy 와 독립인지를 보므로, 창 대신 버튼 하나짜리 껍데기를 쓴다.
 */
function AnnotationHarness({
  rawSn,
  srcSn,
}: {
  rawSn?: number;
  srcSn?: number;
}) {
  const tsRef = useRef<AnnotationColumnHandle>(null);
  const eaRef = useRef<EventAnnotationPanelHandle>(null);
  return (
    <>
      {srcSn !== undefined && <TimeseriesSidePanel ref={tsRef} srcSn={srcSn} />}
      {rawSn !== undefined && <EventAnnotationPanel ref={eaRef} rawSn={rawSn} />}
      <button
        type="button"
        data-testid="harness-save"
        onClick={() => {
          void tsRef.current?.save();
          void eaRef.current?.save();
        }}
      >
        저장
      </button>
    </>
  );
}

const SRC_SN = 900;
const RAW_SN = 77;

describe('메타 편집은 busy 와 독립이다(차단 대상 아님)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useLabelStore.getState().reset();
    useAuthStore.setState({ token: null, claims: null });
  });

  afterEach(() => {
    mock.restore();
    useLabelStore.getState().reset();
    useAuthStore.setState({ token: null, claims: null });
  });

  it('busy_중에도_프레임_설명은_편집_저장된다', async () => {
    mock.onGet(`/frames/${SRC_SN}/description`).reply(200, ok({ srcSn: SRC_SN, description: '기존' }));
    mock.onPut(`/frames/${SRC_SN}/description`).reply(200, ok({ srcSn: SRC_SN, description: '수정' }));

    renderWithProviders(<FrameDescriptionPanel srcSn={SRC_SN} />);
    const textarea = (await screen.findByLabelText('프레임 설명 입력')) as HTMLTextAreaElement;
    await waitFor(() => expect(textarea.value).toBe('기존'));

    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });

    // 입력이 잠기지 않는다.
    expect(textarea).not.toBeDisabled();
    fireEvent.change(textarea, { target: { value: '수정' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(mock.history.put.filter((r) => r.url === `/frames/${SRC_SN}/description`)).toHaveLength(1);
    });
    // 진행 중 작업도 그대로다(메타 저장이 라벨 작업 축을 건드리지 않는다).
    expect(useLabelStore.getState().busy?.kind).toBe('SAVE');
    expect(useLabelStore.getState().dirtyLabels.size).toBe(0);
  });

  it('busy_중에도_촬영환경_메타는_편집_저장된다', async () => {
    const meta = { rawSn: RAW_SN, weather: null, timeOfDay: null, season: null };
    mock.onGet(`/videos/${RAW_SN}/environment-meta`).reply(200, ok(meta));
    mock.onPut(`/videos/${RAW_SN}/environment-meta`).reply(200, ok({ ...meta, weather: 'CLEAR' }));

    renderWithProviders(<EnvironmentMetaPanel rawSn={RAW_SN} />);
    const selects = await screen.findAllByRole('combobox');
    expect(selects.length).toBeGreaterThan(0);
    // 로딩 완료(=차단이 아닌 사유의 disabled 해제)까지 대기.
    await waitFor(() => expect(selects[0]).not.toBeDisabled());

    act(() => {
      useLabelStore.getState().beginBusy('AI_DETECT', { srcSn: SRC_SN });
    });

    const weather = selects[0];
    expect(weather).not.toBeDisabled();
    const user = userEvent.setup();
    await selectRadixOption(user, weather, '맑음');
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(
        mock.history.put.filter((r) => r.url === `/videos/${RAW_SN}/environment-meta`),
      ).toHaveLength(1);
    });
    expect(useLabelStore.getState().busy?.kind).toBe('AI_DETECT');
  });

  // ── Phase 3(LOW) — 나머지 3종도 같은 의도를 고정한다(5종 전부 커버) ────────────
  it('busy_중에도_개인정보_메타는_편집_저장된다', async () => {
    mock.onGet(`/frames/${SRC_SN}/privacy-meta`).reply(200, ok({
      srcSn: SRC_SN,
      anonymity: 'N',
      pseudonymity: 'N',
      privacyIncluded: 'N',
    }));
    mock.onPut(`/frames/${SRC_SN}/privacy-meta`).reply(200, ok({
      srcSn: SRC_SN,
      anonymity: 'Y',
      pseudonymity: 'N',
      privacyIncluded: 'N',
    }));

    renderWithProviders(<FramePrivacyMetaPanel srcSn={SRC_SN} />);
    const anonymity = await screen.findByLabelText('익명여부');
    await waitFor(() => expect(anonymity).not.toBeDisabled());

    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });

    expect(anonymity).not.toBeDisabled();
    fireEvent.click(anonymity);
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => {
      expect(mock.history.put.filter((r) => r.url === `/frames/${SRC_SN}/privacy-meta`)).toHaveLength(1);
    });
    expect(useLabelStore.getState().busy?.kind).toBe('SAVE');
    expect(useLabelStore.getState().dirtyLabels.size).toBe(0);
  });

  it('busy_중에도_이벤트_어노테이션은_편집_저장된다', async () => {
    const payload = {
      rawSn: RAW_SN,
      evntAnnoSn: null,
      reviewStatus: null,
      regId: null,
      mdfcnId: null,
      payload: { event_class: '' },
    };
    mock.onGet(`/videos/${RAW_SN}/event-annotation`).reply(200, ok(payload));
    mock.onPut(`/videos/${RAW_SN}/event-annotation`).reply(200, ok(payload));

    renderWithProviders(<AnnotationHarness rawSn={RAW_SN} />);
    const eventClass = await screen.findByTestId('ea-event-class');
    // 프리필(서버 응답 반영)이 끝난 뒤 입력해야 한다 — 로드 중 입력은 프리필에 덮인다.
    await waitFor(() =>
      expect(mock.history.get.some((r) => r.url === `/videos/${RAW_SN}/event-annotation`)).toBe(true),
    );

    act(() => {
      useLabelStore.getState().beginBusy('AI_TRACK', { srcSn: SRC_SN });
    });

    expect(eventClass).not.toBeDisabled();
    fireEvent.change(eventClass, { target: { value: '화재' } });
    fireEvent.click(screen.getByTestId('harness-save'));

    await waitFor(() => {
      expect(
        mock.history.put.filter((r) => r.url === `/videos/${RAW_SN}/event-annotation`),
      ).toHaveLength(1);
    });
    expect(useLabelStore.getState().busy?.kind).toBe('AI_TRACK');
  });

  // 2026-08-03: 라벨링 화면의 검토(승인/반려) UI 는 이 칸에서 제거됐다.
  // 이 칸에 남은 편집 경로(텍스트 수정→저장)가 busy 와 독립인지를 대신 고정한다.
  it('busy_중에도_영상_분석_설명은_편집_저장된다', async () => {
    useAuthStore.setState({
      token: ['t', 'o', 'k'].join(''),
      claims: { sub: '1', role: 'REVIEWER', channel: 'INTERNAL', exp: 9999999999 },
    });
    // 2026-08-06: 편집 가능 키는 화이트리스트(vlm.description / manual-timeseries) 다.
    // 레거시 구간 키는 읽기 전용이라 편집 독립성을 고정할 대상이 아니다.
    const items = [
      {
        metaSn: 1,
        metaKey: 'vlm.description',
        metaVal: '값',
        dataMetaReviewSn: 55,
        reviewStatus: 'PENDING',
      },
    ];
    mock.onGet(`/frames/${SRC_SN}/meta`).reply(200, ok({ items }));
    mock.onPut(`/frames/${SRC_SN}/meta`).reply(200, ok({ items }));

    renderWithProviders(<AnnotationHarness srcSn={SRC_SN} />);
    // 2026-08-03: 저장 단위(metaKey)별 textarea 로 편집한다.
    // ⚠ 요소를 미리 잡아 두지 않는다 — 조회가 도착하면 슬롯 키가 신규 등록에서 서버 키로 바뀌며
    //   입력 칸이 <b>다시 마운트</b>된다. 미리 잡은 참조는 떨어져 나간 옛 요소라 값이 영영 ''다.
    await waitFor(() =>
      expect(screen.getByLabelText('영상 분석 설명 입력')).toHaveValue('값'),
    );
    const textarea = screen.getByLabelText('영상 분석 설명 입력') as HTMLTextAreaElement;

    // 검토 표면은 노출되지 않는다(회귀 가드).
    expect(screen.queryByTestId('ts-approve-55')).toBeNull();

    act(() => {
      useLabelStore.getState().beginBusy('SAVE', { srcSn: SRC_SN });
    });

    expect(textarea).not.toBeDisabled();
    fireEvent.change(textarea, { target: { value: '수정값' } });
    fireEvent.click(screen.getByTestId('harness-save'));

    await waitFor(() => {
      expect(mock.history.put.filter((r) => r.url === `/frames/${SRC_SN}/meta`)).toHaveLength(1);
    });
    expect(useLabelStore.getState().busy?.kind).toBe('SAVE');
  });
});
