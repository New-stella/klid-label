import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { DeidentConfigCard } from '../components/DeidentConfigCard';

// ConfigMap 의 키는 BE 가 준 dotted 원문 그대로다(폼 별칭이 아니다).
const baseConfigs = {
  'kpst.deid.masking-type': 2,
  'kpst.deid.masking-range': 1.5,
  'kpst.deid.db-save': 1,
};

/** PUT 요청을 캡처하는 mock — key 는 path 에서 디코드해 뽑는다. */
function capturePuts(mock: MockAdapter) {
  const calls: Array<{ key: string; value: string }> = [];
  mock.onPut(/\/manage\/configs\/.+/).reply((config) => {
    const body = JSON.parse(config.data ?? '{}');
    const url = config.url ?? '';
    const key = decodeURIComponent(url.split('/').pop() ?? '');
    calls.push({ key, value: String(body.value) });
    return [
      200,
      {
        success: true,
        data: { configKey: key, configVl: body.value },
        message: null,
        errorCode: null,
      },
    ];
  });
  return calls;
}

describe('DeidentConfigCard (R9 비식별 옵션)', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('세_컨트롤이_렌더된다', () => {
    renderWithProviders(<DeidentConfigCard configs={baseConfigs} />);
    expect(screen.getByLabelText(/마스킹 방식/)).toBeInTheDocument();
    expect(screen.getByLabelText(/마스킹 범위/)).toBeInTheDocument();
    expect(screen.getByLabelText(/프레임 저장 여부/)).toBeInTheDocument();
  });

  it('저장된_값이_화면에_반영된다', () => {
    renderWithProviders(<DeidentConfigCard configs={baseConfigs} />);
    expect((screen.getByLabelText(/마스킹 방식/) as HTMLSelectElement).value).toBe('2');
    expect((screen.getByLabelText(/마스킹 범위/) as HTMLInputElement).value).toBe('1.5');
    expect((screen.getByLabelText(/프레임 저장 여부/) as HTMLSelectElement).value).toBe('1');
  });

  it('저장값이_없으면_기본값_색상_1_0배_저장안함_으로_표시된다', () => {
    renderWithProviders(<DeidentConfigCard configs={{}} />);
    expect((screen.getByLabelText(/마스킹 방식/) as HTMLSelectElement).value).toBe('0');
    expect((screen.getByLabelText(/마스킹 범위/) as HTMLInputElement).value).toBe('1');
    expect((screen.getByLabelText(/프레임 저장 여부/) as HTMLSelectElement).value).toBe('0');
  });

  // ★ 가장 중요한 가드 — 라벨↔코드값 매핑이 어긋나면 운영자가 고른 것과 다른 마스킹이 실행된다.
  it('★마스킹_방식_선택지의_라벨과_코드값_매핑이_색상0_모자이크2_블러3_이다', () => {
    renderWithProviders(<DeidentConfigCard configs={baseConfigs} />);
    const select = screen.getByLabelText(/마스킹 방식/) as HTMLSelectElement;

    expect(within(select).getByRole('option', { name: '색상' })).toHaveValue('0');
    expect(within(select).getByRole('option', { name: '모자이크' })).toHaveValue('2');
    expect(within(select).getByRole('option', { name: '블러' })).toHaveValue('3');

    // 벤더 미할당 값 1 은 선택지에 존재하지 않는다(연속 범위가 아니다).
    expect(within(select).queryByRole('option', { name: /^1$/ })).not.toBeInTheDocument();
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['0', '2', '3']);
  });

  it('프레임_저장_여부_선택지는_저장안함0_저장1_이다', () => {
    renderWithProviders(<DeidentConfigCard configs={baseConfigs} />);
    const select = screen.getByLabelText(/프레임 저장 여부/) as HTMLSelectElement;
    expect(within(select).getByRole('option', { name: '저장 안 함' })).toHaveValue('0');
    expect(within(select).getByRole('option', { name: '저장' })).toHaveValue('1');
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['0', '1']);
  });

  it('★전송되는_key는_dotted_원문이다_폼_별칭이_새어나가지_않는다', async () => {
    // given — react-hook-form 은 필드명의 점을 중첩 경로로 해석하므로 폼은 별칭을 쓴다.
    //         그 별칭(maskingType 등)이 BE 로 나가면 400(허용되지 않은 설정 키)이 된다.
    const calls = capturePuts(mock);
    renderWithProviders(<DeidentConfigCard configs={baseConfigs} />);

    fireEvent.change(screen.getByLabelText(/마스킹 방식/), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText(/마스킹 범위/), { target: { value: '0.8' } });
    fireEvent.change(screen.getByLabelText(/프레임 저장 여부/), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: /저장/ }));

    await waitFor(() => {
      expect(calls.length).toBe(3);
    });

    expect(new Set(calls.map((c) => c.key))).toEqual(
      new Set(['kpst.deid.masking-type', 'kpst.deid.masking-range', 'kpst.deid.db-save']),
    );
    expect(calls.find((c) => c.key === 'kpst.deid.masking-type')?.value).toBe('3');
    expect(calls.find((c) => c.key === 'kpst.deid.masking-range')?.value).toBe('0.8');
    expect(calls.find((c) => c.key === 'kpst.deid.db-save')?.value).toBe('0');
  });

  it('★변경한_키만_전송된다', async () => {
    const calls = capturePuts(mock);
    renderWithProviders(<DeidentConfigCard configs={baseConfigs} />);

    fireEvent.change(screen.getByLabelText(/마스킹 방식/), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: /저장/ }));

    await waitFor(() => {
      expect(calls.length).toBeGreaterThanOrEqual(1);
    });
    expect(new Set(calls.map((c) => c.key))).toEqual(new Set(['kpst.deid.masking-type']));
  });

  it('변경_전에는_저장_버튼이_비활성이다', () => {
    renderWithProviders(<DeidentConfigCard configs={baseConfigs} />);
    expect(screen.getByRole('button', { name: /저장/ })).toBeDisabled();
  });

  it('마스킹_범위_슬라이더는_0_5_2_0_범위이며_현재값을_배수로_표시한다', () => {
    renderWithProviders(<DeidentConfigCard configs={baseConfigs} />);
    const slider = screen.getByLabelText(/마스킹 범위/) as HTMLInputElement;
    expect(slider.min).toBe('0.5');
    expect(slider.max).toBe('2');
    expect(slider.step).toBe('0.1');
    expect(screen.getByText('1.5배')).toBeInTheDocument();
  });

  it('선택지에_없는_저장값은_기본값으로_정규화된다_DB_수기수정_방어', () => {
    // given — 벤더 미할당 1 이 DB 에 수기로 들어간 상태. BE 도 이 경우 기본값으로 폴백 위탁한다.
    renderWithProviders(
      <DeidentConfigCard
        configs={{ 'kpst.deid.masking-type': 1, 'kpst.deid.db-save': 9 }}
      />,
    );

    // then — 화면이 BE 의 실제 동작(기본값 폴백)과 같은 값을 보여준다.
    expect((screen.getByLabelText(/마스킹 방식/) as HTMLSelectElement).value).toBe('0');
    expect((screen.getByLabelText(/프레임 저장 여부/) as HTMLSelectElement).value).toBe('0');
    // 정규화는 표시일 뿐 dirty 가 아니다 — 저장 버튼은 비활성이어야 한다.
    expect(screen.getByRole('button', { name: /저장/ })).toBeDisabled();
  });

  it('벤더_미지원_필드는_화면에_노출하지_않는다', () => {
    // exp_quality / exp_format 은 벤더 미지원 회신이라 설정으로 열지 않는다.
    renderWithProviders(<DeidentConfigCard configs={baseConfigs} />);
    expect(screen.queryByLabelText(/화질/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/포맷/)).not.toBeInTheDocument();
  });
});
