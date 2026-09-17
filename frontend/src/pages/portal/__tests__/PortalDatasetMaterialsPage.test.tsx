/**
 * 회귀 가드 — 포털 데이터셋 소재 조달 화면(`/portal/datasets/:datasetId`).
 *
 * 이 파일이 지키는 축 다섯은 서로 다른 실패 모드다.
 *  ① <b>주소가 데이터셋 식별자가 아니면</b> 조용한 빈 화면이 되지 않고, 그 값으로 창구를 부르지도
 *     않는다 — 부르면 서버가 400 을 내고 화면에는 「알 수 없는 오류」만 남는다.
 *  ② <b>진입하면 스스로 착수한다</b>(이 주소로 들어온 것이 곧 그 의사다). 다만 <b>한 번만</b> —
 *     되풀이하면 확정 실패 사유에서 같은 요청이 끝없이 나간다.
 *  ③ 진행 중·준비 완료·실패가 <b>각각 다르게</b> 드러난다. 빈 화면으로 얼버무리지 않는다.
 *  ④ <b>준비 완료일 때만 영상 구역을 연다</b> — 그 전에 영상 목록을 부르면 서버가 409 로 거부한다.
 *     ⚠ 구 축 「다음 동선을 지어내지 않는다(준비 완료까지가 범위)」는 폐기 — 2026-09-15 영상 목록 →
 *     라벨링 동선이 생겼다(SCREEN-046 · ADR-068). 되살리지 말 것.
 *  ⑤ <b>응답에 없는 값을 그리지 않는다</b> — 파일 경로는 응답에 담기지 않는다(CWE-209).
 *
 * ★ ④·⑤ 는 「없다」만 단언하면 화면이 통째로 아무것도 그리지 않아도 통과한다. 그래서 <b>존치 축을
 *   짝으로</b> 둔다(같은 케이스에서 준비 완료 표기와 요약 칩이 실제로 선다).
 *
 * @design INT-014
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '@/test/renderWithProviders';
import { ApiError } from '@/lib/api/errors';
import {
  PortalMaterialsFailureReason,
  PortalMaterialsState,
  type PortalMaterialsStatus,
  type PortalMaterialsSummary,
} from '@/features/portal/materials/types';

import { PortalDatasetMaterialsPage } from '../PortalDatasetMaterialsPage';

const useDatasetMaterialsMock = vi.fn();
const useStartDatasetMaterialsMock = vi.fn();

vi.mock('@/features/portal/materials/hooks/useDatasetMaterials', () => ({
  useDatasetMaterials: (datasetId: unknown) => useDatasetMaterialsMock(datasetId),
}));
// 영상 구역은 자기 시험(DatasetVideoSection.test)이 따로 본다 — 여기서는 「언제 열리는가」만 본다.
vi.mock('@/features/portal/materials/components/DatasetVideoSection', () => ({
  DatasetVideoSection: ({ datasetId }: { datasetId: number }) => (
    <div data-testid="dataset-video-section-mock">{datasetId}</div>
  ),
}));
vi.mock('@/features/portal/materials/hooks/useStartDatasetMaterials', () => ({
  useStartDatasetMaterials: (datasetId: unknown) => useStartDatasetMaterialsMock(datasetId),
}));

/** 착수 훅의 반환은 렌더마다 같은 참조여야 한다 — 아니면 자동 착수 효과가 매 렌더 다시 돈다. */
function mockStart(over: Partial<Record<string, unknown>> = {}) {
  const mutateAsync = vi.fn().mockResolvedValue(undefined);
  const value = { mutateAsync, isPending: false, isError: false, error: null, ...over };
  useStartDatasetMaterialsMock.mockReturnValue(value);
  return mutateAsync;
}

function mockStatus(over: Partial<Record<string, unknown>> = {}) {
  const refetch = vi.fn();
  useDatasetMaterialsMock.mockReturnValue({
    data: undefined,
    isLoading: false,
    isError: false,
    error: null,
    refetch,
    ...over,
  });
  return refetch;
}

function status(over: Partial<PortalMaterialsStatus> = {}): PortalMaterialsStatus {
  return {
    datasetId: 4704,
    state: PortalMaterialsState.READY,
    failureReason: null,
    materials: null,
    ...over,
  };
}

function summary(over: Partial<PortalMaterialsSummary> = {}): PortalMaterialsSummary {
  return {
    code: 'DS-TRAFFIC',
    version: 'v3',
    variant: 'full',
    entryCount: 1240,
    totalBytes: 3 * 1024 * 1024 * 1024,
    videoCount: 12,
    provisionedAt: '2026-09-15T10:20:00',
    ...over,
  };
}

function renderAt(path = '/portal/datasets/4704') {
  return renderWithProviders(<div />, {
    initialEntries: [path],
    routes: [{ path: '/portal/datasets/:datasetId', element: <PortalDatasetMaterialsPage /> }],
  });
}

describe('포털 데이터셋 소재 조달 화면', () => {
  beforeEach(() => {
    mockStart();
    mockStatus();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe('주소가 데이터셋 식별자가 아닐 때', () => {
    it.each(['abc', '0', '-3', '1.5'])('★%s 이면 안내와 돌아갈 길을 보여 준다', async (raw) => {
      renderAt(`/portal/datasets/${raw}`);

      // 조용한 빈 화면이 아니다 — 무엇이 잘못됐는지와 어디로 가면 되는지가 함께 있다.
      expect(screen.getByTestId('materials-bad-address')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: '내 작업으로 가기' })).toHaveAttribute(
        'href',
        '/portal',
      );
    });

    it('★그_값으로_창구를_부르지_않는다', () => {
      renderAt('/portal/datasets/abc');

      expect(useDatasetMaterialsMock).toHaveBeenCalledWith(undefined);
      expect(useStartDatasetMaterialsMock).toHaveBeenCalledWith(undefined);
    });

    it('숫자_주소에서는_그_안내가_뜨지_않고_창구를_부른다_제거_축과_존치_축을_짝으로', () => {
      mockStatus({ data: status({ state: PortalMaterialsState.IN_PROGRESS }) });

      renderAt('/portal/datasets/4704');

      expect(screen.queryByTestId('materials-bad-address')).not.toBeInTheDocument();
      expect(useDatasetMaterialsMock).toHaveBeenCalledWith(4704);
    });
  });

  describe('진입 시 착수', () => {
    it('★아직_가져온_적이_없으면_스스로_착수한다', async () => {
      const mutateAsync = mockStart();
      mockStatus({ data: status({ state: PortalMaterialsState.NOT_PROVISIONED }) });

      renderAt();

      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(1));
    });

    /**
     * ★ 되풀이 방지는 <b>그냥 다시 그리는 것으로는 검증되지 않는다</b> — 그때는 효과의 의존값이
     *   그대로라 애초에 다시 돌지 않아, 가드를 지워도 초록이다(실측으로 확인했다).
     *
     * 실제로 되풀이가 일어나는 모양은 <b>상태가 오갈 때</b>다. 진행 중은 그 배포본의 <b>메모리</b>가
     * 갖는 값이라 서버가 다시 뜨면 사라져 미조달로 돌아온다(서버가 명시한 성질). 그 왕복에서
     * 가드가 없으면 화면이 조달을 또 건다.
     */
    it('★상태가_오가도_한_번만_착수한다_되풀이_방지', async () => {
      const mutateAsync = mockStart();
      mockStatus({ data: status({ state: PortalMaterialsState.NOT_PROVISIONED }) });

      const { rerender } = renderAt();
      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(1));

      // 진행 중으로 갔다가 …
      mockStatus({ data: status({ state: PortalMaterialsState.IN_PROGRESS }) });
      rerender(<div />);
      // … 서버가 다시 떠 그 기억을 잃어 미조달로 돌아온다.
      mockStatus({ data: status({ state: PortalMaterialsState.NOT_PROVISIONED }) });
      rerender(<div />);

      expect(mutateAsync).toHaveBeenCalledTimes(1);
    });

    it.each([PortalMaterialsState.IN_PROGRESS, PortalMaterialsState.READY])(
      '이미 %s 이면 착수하지 않는다',
      async (state) => {
        const mutateAsync = mockStart();
        mockStatus({ data: status({ state }) });

        renderAt();

        await waitFor(() => expect(screen.getAllByRole('status').length).toBeGreaterThan(0));
        expect(mutateAsync).not.toHaveBeenCalled();
      },
    );

    it('실패_상태에서는_자동으로_되풀이하지_않는다_사람이_누른다', async () => {
      const mutateAsync = mockStart();
      mockStatus({
        data: status({
          state: PortalMaterialsState.FAILED,
          failureReason: PortalMaterialsFailureReason.FETCH_FAILED,
        }),
      });

      renderAt();

      await waitFor(() => expect(screen.getByRole('button', { name: '다시 시도' })).toBeVisible());
      expect(mutateAsync).not.toHaveBeenCalled();

      await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
      expect(mutateAsync).toHaveBeenCalledTimes(1);
    });
  });

  describe('진행 중', () => {
    it('★기다리는_중임을_말하고_수동으로_다시_확인할_수_있다', async () => {
      const refetch = mockStatus({ data: status({ state: PortalMaterialsState.IN_PROGRESS }) });

      renderAt();

      expect(screen.getByText(/소재를 가져오는 중입니다/)).toBeInTheDocument();
      // 자동 확인에는 예산이 있어 언젠가 멎는다. 그 뒤에도 사람이 누르는 길이 남아야 한다.
      await userEvent.click(screen.getByRole('button', { name: '상태 다시 확인' }));
      expect(refetch).toHaveBeenCalledTimes(1);
    });

    it('진행률을_지어내지_않는다_응답에_없는_값이다', () => {
      mockStatus({ data: status({ state: PortalMaterialsState.IN_PROGRESS }) });

      renderAt();

      expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
      expect(screen.queryByText(/%/)).not.toBeInTheDocument();
    });
  });

  describe('준비 완료', () => {
    it('★요약을_보여_준다_항목_수와_총_용량과_영상_수', () => {
      mockStatus({ data: status({ state: PortalMaterialsState.READY, materials: summary() }) });

      renderAt();

      const facts = screen.getByTestId('materials-facts');
      expect(within(facts).getByText('1,240개')).toBeInTheDocument();
      expect(within(facts).getByText('3.0 GB')).toBeInTheDocument();
      expect(within(facts).getByText('12건')).toBeInTheDocument();
      expect(within(facts).getByText('DS-TRAFFIC')).toBeInTheDocument();
    });

    it('★★응답에_없는_파일_경로를_지어내지_않는다', () => {
      mockStatus({ data: status({ state: PortalMaterialsState.READY, materials: summary() })  });

      renderAt();

      // 존치 축 — 요약 자체는 실제로 서 있다(아래 부재 단언이 공짜로 통과하지 않게).
      const facts = screen.getByTestId('materials-facts');
      expect(within(facts).getByText('1,240개')).toBeInTheDocument();
      // 제거 축 — 경로처럼 보이는 문자열이 요약 어디에도 없다.
      expect(facts.textContent ?? '').not.toMatch(/\/[A-Za-z0-9_.-]+\//);
      expect(screen.queryByText(/nas-storage|\/data\/|경로/)).not.toBeInTheDocument();
    });

    /*
     * ★ 준비 완료 뒤에는 영상 목록 구역이 열린다(SCREEN-046, 2026-09-15). 구 동작 「지금은 여기까지입니다」
     *   안내는 폐기 — 그 자리를 영상을 골라 라벨링으로 들어가는 구역이 대신한다.
     */
    it('★준비_완료면_데이터셋_영상_구역을_연다', () => {
      mockStatus({ data: status({ state: PortalMaterialsState.READY, materials: summary() }) });

      renderAt();

      expect(screen.getByText('소재가 준비됐습니다.')).toBeInTheDocument();
      expect(screen.getByTestId('dataset-video-section-mock')).toHaveTextContent('4704');
      expect(screen.queryByText('지금은 여기까지입니다.')).not.toBeInTheDocument();
    });

    it('★준비_전에는_영상_구역을_열지_않는다_서버가_409로_거부한다', () => {
      mockStatus({ data: status({ state: PortalMaterialsState.IN_PROGRESS }) });

      renderAt();

      // 존치 축 — 진행 중 안내는 선다(아래 부재 단언이 공짜로 통과하지 않게).
      expect(screen.getByText(/소재를 가져오는 중입니다/)).toBeInTheDocument();
      expect(screen.queryByTestId('dataset-video-section-mock')).not.toBeInTheDocument();
    });

    it('★요약을_못_읽어도_준비_완료는_그대로다', () => {
      // 서버가 명시한 성질 — `READY` 인데 요약만 `null` 일 수 있다. 준비 전으로 되돌리면 안 된다.
      mockStatus({ data: status({ state: PortalMaterialsState.READY, materials: null }) });

      renderAt();

      expect(screen.getByText('소재가 준비됐습니다.')).toBeInTheDocument();
      expect(screen.queryByTestId('materials-facts')).not.toBeInTheDocument();
      expect(screen.getByText(/요약은 확인할 수 없지만/)).toBeInTheDocument();
    });

    it('비어_오는_칩은_그리지_않는다_없다와_못_읽었다를_한_표기로_뭉개지_않는다', () => {
      mockStatus({
        data: status({
          state: PortalMaterialsState.READY,
          materials: summary({ code: null, variant: '' }),
        }),
      });

      renderAt();

      const facts = screen.getByTestId('materials-facts');
      expect(within(facts).queryByText('코드')).not.toBeInTheDocument();
      expect(within(facts).queryByText('구분')).not.toBeInTheDocument();
      expect(within(facts).queryByText('-')).not.toBeInTheDocument();
      // 존치 축 — 나머지 칩은 그대로 선다.
      expect(within(facts).getByText('v3')).toBeInTheDocument();
    });
  });

  describe('실패', () => {
    it('★사유를_보여_준다_사유마다_다른_문구다', () => {
      mockStatus({
        data: status({
          state: PortalMaterialsState.FAILED,
          failureReason: PortalMaterialsFailureReason.NOT_CONFIGURED,
        }),
      });

      renderAt();

      expect(screen.getByText('조달 연동이 설정되지 않았습니다.')).toBeInTheDocument();
    });

    it('★우리가_모르는_사유가_와도_빈칸이_되지_않는다', () => {
      mockStatus({
        data: status({
          state: PortalMaterialsState.FAILED,
          failureReason: 'QUOTA_EXCEEDED' as unknown as PortalMaterialsFailureReason,
        }),
      });

      renderAt();

      expect(screen.getByText('소재를 가져오지 못했습니다.')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '다시 시도' })).toBeVisible();
    });
  });

  describe('거부·오류', () => {
    it('★착수가_거부되면_서버_안내_문장을_그대로_보여_준다', async () => {
      mockStatus({ data: status({ state: PortalMaterialsState.NOT_PROVISIONED }) });
      mockStart({
        isError: true,
        error: ApiError.fromBody(
          {
            success: false,
            data: null,
            message: '조달 작업이 밀려 있습니다. 잠시 후 다시 시도해 주세요.',
            errorCode: 'SERVICE_UNAVAILABLE',
          },
          503,
        ),
      });

      renderAt();

      const banner = await screen.findByTestId('materials-start-error');
      expect(
        within(banner).getByText('조달 작업이 밀려 있습니다. 잠시 후 다시 시도해 주세요.'),
      ).toBeInTheDocument();
    });

    it('상태_조회가_실패하면_그_사실을_따로_말하고_다시_확인할_수_있다', async () => {
      const refetch = mockStatus({
        isError: true,
        error: ApiError.fromStatus(403),
      });

      renderAt();

      const banner = screen.getByTestId('materials-status-error');
      await userEvent.click(within(banner).getByRole('button', { name: '다시 확인' }));
      expect(refetch).toHaveBeenCalledTimes(1);
    });
  });

  it('★서버가_상태_값역을_넓혀도_화면이_비지_않는다', () => {
    mockStatus({
      data: status({ state: 'ARCHIVED' as unknown as PortalMaterialsState }),
    });

    renderAt();

    expect(screen.getByTestId('materials-unknown-state')).toBeInTheDocument();
  });
});
