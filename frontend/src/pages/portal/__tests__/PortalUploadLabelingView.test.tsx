// 통합 포털 라벨링 화면 — 업로드 자산 갈래 동선 검증. @design SCREEN-029
// react-konva 는 jsdom 미지원이라 passthrough 로 mock (LabelingPage 테스트 관례와 동일).
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

vi.mock('react-konva', async () => (await import('@/test/konvaMock')).createKonvaMock());

import { apiClient } from '@/lib/api/client';
import type { Label } from '@/features/label/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { useAuthStore } from '@/stores/useAuthStore';
import { useLabelStore } from '@/stores/useLabelStore';

import { PortalUploadLabelingView } from '../PortalUploadLabelingView';

// 테스트용 더미 인증값(비밀 아님 — 시크릿 스캐너 오탐 회피용 조합).
const FAKE_TOKEN = ['t', 'o', 'k'].join('');

function ok(data: unknown) {
  return { success: true, data, message: null, errorCode: null };
}

function frameRow(uldFrmeSn: number, frmeNo: number) {
  return { uldFrmeSn, uldSn: 1, frmeNo, regDt: '2026-07-17T00:00:00' };
}

function detail(over: Record<string, unknown> = {}) {
  return {
    uldSn: 1,
    // 신규 접수는 영상뿐이다 — 프레임 1건짜리 영상이 기본 픽스처다(구 픽스처는 이미지 자산이었다).
    uldTypeCd: 'VIDEO',
    orgnlFileNm: 'clip.mp4',
    fileSz: 1024,
    mimeTypeNm: 'video/mp4',
    uldSttsCd: 'READY',
    frmeCnt: 1,
    vdoLenSec: null,
    fps: null,
    regDt: '2026-07-17T00:00:00',
    mdfcnDt: null,
    frames: [frameRow(100, 0)],
    ...over,
  };
}

function bboxRaw(label: string) {
  return {
    uldLblSn: 1,
    uldFrmeSn: 100,
    lblTypeCd: 'BBOX',
    label,
    points: [
      [10, 20],
      [110, 220],
    ],
    regDt: '2026-07-17T00:00:00',
    mdfcnDt: null,
  };
}

function bboxLabel(): Label {
  return {
    id: 'tmp-1',
    frameNo: 0,
    classId: 1,
    className: 'car',
    source: 'MANUAL',
    shape: { type: 'BBOX', left: 10, top: 20, right: 110, bottom: 220 },
  };
}

/**
 * 통합 라벨링 화면의 **업로드 자산 갈래**를 그 화면의 주소에서 연다.
 * 폐기된 전용 주소(`/portal/uploads/:uldSn/label`)가 아니라 통합 주소를 쓴다.
 */
function renderPage(uldSn = 1) {
  return renderWithProviders(<PortalUploadLabelingView uldSn={uldSn} />, {
    initialEntries: [`/portal/label/${uldSn}?source=upload`],
    routes: [{ path: '/portal/label/:id', element: <PortalUploadLabelingView uldSn={uldSn} /> }],
  });
}

describe('포털 라벨링 화면 — 업로드 자산 갈래', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    useAuthStore.setState({
      token: FAKE_TOKEN,
      claims: { sub: '10', role: 'PORTAL_USER', channel: 'PORTAL', exp: 9999999999 },
    });
    useLabelStore.getState().reset();
    // 라벨 마스터(선택 UI + OverlayLayer) — 빈 목록.
    mock.onGet('/manage/labels').reply(200, ok([]));
    // 프레임 이미지 — Blob (jsdom 은 createObjectURL 미지원이라 url 은 null 로 폴백).
    mock.onGet(/\/portal\/uploads\/frames\/\d+\/image/).reply(200, new Blob());
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:x'),
      revokeObjectURL: vi.fn(),
    });
  });

  afterEach(() => {
    mock.restore();
    useAuthStore.getState().clear();
    useLabelStore.getState().reset();
    vi.restoreAllMocks();
  });

  it('헤더에_업로드_목록으로_돌아가는_뒤로가기가_있다', async () => {
    // 구 구현은 이탈 경로가 전혀 없어 브라우저 뒤로가기에만 의존했다(SCREEN-034 헤더 좌상단).
    // ⚠ 목적지는 시안 골격에 없어(설계 노트: 뒤로가기 목적지 미확인) 유일한 진입점인
    //    업로드 목록으로 보내는 보수적 선택이다.
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));

    renderPage();

    const back = await screen.findByRole('link', { name: '뒤로가기' });
    expect(back).toHaveAttribute('href', '/portal/uploads');
  });

  it('프레임_1건_영상은_단일_프레임으로_렌더되고_bbox_저장_호출', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    let putBody: unknown = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      putBody = JSON.parse(config.data as string);
      return [200, ok([])];
    });

    renderPage();

    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    // 단일 프레임 → 프레임 네비게이션(다음) 미노출.
    expect(screen.queryByRole('button', { name: '다음 프레임' })).not.toBeInTheDocument();

    // 사용자가 그린 bbox 를 스토어에 반영(캔버스 onLabelAdd 경로 대체).
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    expect(Array.isArray(putBody)).toBe(true);
    expect((putBody as unknown[])[0]).toMatchObject({ lblTypeCd: 'BBOX', label: 'car' });
  });

  /*
   * ★ 헤더 문구가 프레임 축으로만 말한다 — 구 문구 「이미지 1장」은 이미지 자산이 접수되던 시절의
   *   폴백이라, 신규 접수가 영상뿐인 지금은 «프레임 1건짜리 영상» 에 붙어 사실과 어긋난다.
   *
   * ⚠ 값을 통째로 고정한다 — 구 문구의 부재만 단언하면 헤더를 통째로 지워도 통과한다.
   */
  it('프레임이_1건이어도_헤더는_프레임_카운트로_적는다', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));

    renderPage();

    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    expect(screen.getByText('프레임 1 / 1')).toBeInTheDocument();
    expect(screen.queryByText('이미지 1장')).toBeNull();
  });

  it('저장은_현재_프레임_라벨_전체교체_PUT_1회_raw배열', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    let rawData: string | undefined;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      rawData = config.data as string;
      return [200, ok([])];
    });

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());

    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    const parsed = JSON.parse(rawData as string);
    // 최상위 raw 배열 + points 는 배열(문자열 직렬화 아님).
    expect(Array.isArray(parsed)).toBe(true);
    expect(Array.isArray(parsed[0].points)).toBe(true);
    expect(typeof parsed[0].points).not.toBe('string');
  });

  it('라벨_조회가_늦게_도착해도_사용자가_그린_라벨이_보존되어_저장된다', async () => {
    // given: 라벨 조회 응답이 늦는다(초기 로드 지연/백그라운드 재조회).
    //   응답 도착 시 무조건 store 를 덮어쓰면 방금 그린 라벨이 사라져 저장 본문이 비어버린다
    //   — 실제로 이 경합 때문에 저장 PUT 본문이 빈 배열이 되는 간헐 실패가 있었다.
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    let releaseLabels: (() => void) | null = null;
    mock.onGet('/portal/uploads/frames/100/labels').reply(
      () =>
        new Promise((resolve) => {
          releaseLabels = () => resolve([200, ok([bboxRaw('server-label')])]);
        }),
    );
    let putBody: unknown = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      putBody = JSON.parse(config.data as string);
      return [200, ok([])];
    });

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());

    // when: 사용자가 먼저 그리고, 그 뒤에 조회 응답이 도착한다.
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    await act(async () => {
      releaseLabels?.();
      await Promise.resolve();
    });
    // 서버 라벨이 작업본에 합쳐질 때까지 대기(덮어쓰기였다면 'car' 가 사라진다).
    await waitFor(() =>
      expect(useLabelStore.getState().labels.some((l) => l.className === 'server-label')).toBe(true),
    );

    // then: 그린 라벨이 살아 있고 저장 본문에도 담긴다.
    const classNames = useLabelStore.getState().labels.map((l) => l.className);
    expect(classNames).toContain('car');
    expect(classNames).toContain('server-label');
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    expect((putBody as unknown[]).some((b) => (b as { label: string }).label === 'car')).toBe(true);
  });

  it('저장_성공후_재조회분이_작업본에_반영된다', async () => {
    // given: 사용자가 그린(dirty) 상태. 저장 뒤에도 dirty 가 남으면 "미저장 편집 보호" 가드가
    //   계속 걸려, 그 프레임은 저장 이후 서버 상태와 영영 재동기화되지 않는다(이 화면에는
    //   dirty 를 비우는 다른 주체가 없다).
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock
      .onGet('/portal/uploads/frames/100/labels')
      .replyOnce(200, ok([]))
      .onGet('/portal/uploads/frames/100/labels')
      .reply(200, ok([bboxRaw('server-canon')]));
    mock.onPut('/portal/uploads/frames/100/labels').reply(200, ok([]));

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);

    // when: 저장
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(mock.history.put).toHaveLength(1));

    // then: 미저장 표시가 풀리고 재조회분(서버 확정본)이 작업본에 반영된다.
    await waitFor(() => expect(useLabelStore.getState().dirtyLabels.size).toBe(0));
    await waitFor(() =>
      expect(useLabelStore.getState().labels.map((l) => l.className)).toEqual(['server-canon']),
    );
  });

  it('영상_자산은_프레임_이동시_라벨이_프레임별로_로드됨', async () => {
    mock
      .onGet('/portal/uploads/1')
      .reply(200, ok(detail({ uldTypeCd: 'VIDEO', frmeCnt: 2, frames: [frameRow(100, 0), frameRow(101, 1)] })));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([bboxRaw('frame0')]));
    mock.onGet('/portal/uploads/frames/101/labels').reply(200, ok([bboxRaw('frame1')]));

    renderPage();

    // 첫 프레임 라벨 로드 → 스토어 반영.
    await waitFor(() => {
      expect(useLabelStore.getState().labels[0]?.className).toBe('frame0');
    });

    fireEvent.click(screen.getByRole('button', { name: '다음 프레임' }));

    await waitFor(() => {
      expect(useLabelStore.getState().labels[0]?.className).toBe('frame1');
    });
  });

  /*
   * ★ 내려받기 두 갈래(라벨 JSON · 원본 파일)는 **이 화면에 없다** — 자산 단위 조작이라 자산
   *   목록 화면의 행 액션이 갖는다(확정 사양). 여기에 되살리면 같은 조작의 진입점이 둘이 된다.
   *   조작 자체가 사라진 것이 아니라 자리를 옮긴 것이며, 그쪽 동작은 목록 화면 시험이 지킨다.
   */
  it('내려받기_조작은_라벨링_화면에_두지_않는다_목록_화면이_갖는다', async () => {
    // given
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));

    // when
    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());

    // then
    expect(screen.queryByRole('button', { name: '내보내기(JSON)' })).toBeNull();
    expect(screen.queryByRole('button', { name: '원본 다운로드' })).toBeNull();
    expect(screen.queryByRole('button', { name: '원본 다운로드 취소' })).toBeNull();
  });

  it('라벨_전체삭제_후_저장은_빈_배열_PUT', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([bboxRaw('car')]));
    let putBody: unknown = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      putBody = JSON.parse(config.data as string);
      return [200, ok([])];
    });

    renderPage();

    // 프레임 라벨 로드 → 스토어에 1건 반영됨.
    await waitFor(() => expect(useLabelStore.getState().labels).toHaveLength(1));

    // 사용자가 라벨을 모두 제거(전체삭제).
    act(() => {
      useLabelStore.getState().setLabels([]);
    });
    expect(useLabelStore.getState().labels).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mock.history.put).toHaveLength(1));
    // 전체교체 PUT body 는 빈 배열(멱등 전체 삭제).
    expect(Array.isArray(putBody)).toBe(true);
    expect(putBody).toEqual([]);
  });

  it('SAM_키포인트_오토라벨_도구가_노출되지_않음', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());

    // 노출 도구: 선택/이동/바운딩박스/폴리곤만.
    expect(screen.getByRole('button', { name: '바운딩 박스' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '폴리곤' })).toBeInTheDocument();
    // 미노출: SAM 분할/추적, 키포인트, 오토라벨.
    expect(screen.queryByRole('button', { name: /SAM/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /키포인트/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /오토라벨/ })).not.toBeInTheDocument();
  });

  it('포털업로드_저장중에는_캔버스_편집이_차단된다', async () => {
    // given: 저장 PUT 이 아직 응답하지 않은 in-flight 상태
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    let releasePut: (() => void) | null = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply(
      () =>
        new Promise((resolve) => {
          releasePut = () => resolve([200, ok([])]);
        }),
    );

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });

    // when: 저장 시작
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));

    // then: 캔버스·도구·저장 버튼이 모두 잠긴다(진행 중 편집 창 자체가 없어진다).
    expect(screen.getByTestId('canvas-shell').getAttribute('data-edit-blocked')).toBe('true');
    expect(screen.getByTestId('canvas-shell').getAttribute('data-read-only')).toBe('true');
    expect(screen.getByRole('button', { name: '바운딩 박스' })).toBeDisabled();
    // 진행 중에는 버튼 문구가 '저장 중…' 으로 바뀐다 — 이름이 아니라 상태를 본다.
    expect(screen.getByRole('button', { name: /저장/ })).toBeDisabled();

    // and: 저장이 끝나면 즉시 복구된다.
    await act(async () => {
      releasePut?.();
      await Promise.resolve();
    });
    await waitFor(() =>
      expect(screen.getByTestId('canvas-shell').getAttribute('data-edit-blocked')).toBe('false'),
    );
    expect(screen.getByRole('button', { name: '바운딩 박스' })).not.toBeDisabled();
  });

  it('포털업로드_저장중_편집이_불가하므로_저장후_재조회가_작업분을_잃지_않는다', async () => {
    // given: 저장(전체교체 PUT)이 in-flight 인 동안 캔버스가 잠겨 있으므로, 저장 스냅샷 이후에
    //   그려진 라벨이라는 것이 존재할 수 없다. 저장 성공 후 재조회분이 그대로 작업본이 된다.
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock
      .onGet('/portal/uploads/frames/100/labels')
      .replyOnce(200, ok([]))
      .onGet('/portal/uploads/frames/100/labels')
      .reply(200, ok([bboxRaw('server-canon')]));
    let releasePut: (() => void) | null = null;
    let putBody: unknown = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply((config) => {
      putBody = JSON.parse(config.data as string);
      return new Promise((resolve) => {
        releasePut = () => resolve([200, ok([])]);
      });
    });

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });

    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));
    // 저장 본문에는 그린 라벨이 담겨 있다(작업 결과가 요청에 반영됨).
    expect((putBody as unknown[]).some((b) => (b as { label: string }).label === 'car')).toBe(true);

    // when: 저장 완료 → 재조회
    await act(async () => {
      releasePut?.();
      await Promise.resolve();
    });

    // then: 미저장 표시가 풀리고, 서버 확정본이 작업본이 된다(유실된 편집 없음).
    await waitFor(() => expect(useLabelStore.getState().dirtyLabels.size).toBe(0));
    await waitFor(() =>
      expect(useLabelStore.getState().labels.map((l) => l.className)).toEqual(['server-canon']),
    );
  });

  it('저장이_취소되면_저장됨으로_취급하지_않고_미저장_표시가_유지된다', async () => {
    // given: 저장 in-flight 중 취소(프레임 이동/언마운트 등) → 도착 응답은 폐기된다.
    mock.onGet('/portal/uploads/1').reply(200, ok(detail()));
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    let releasePut: (() => void) | null = null;
    mock.onPut('/portal/uploads/frames/100/labels').reply(
      () =>
        new Promise((resolve) => {
          releasePut = () => resolve([200, ok([])]);
        }),
    );

    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());
    act(() => {
      useLabelStore.getState().addLabel(bboxLabel());
    });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(useLabelStore.getState().busy?.kind).toBe('SAVE'));

    // when
    act(() => {
      useLabelStore.getState().cancelBusy();
    });
    await act(async () => {
      releasePut?.();
      await Promise.resolve();
    });

    // then: dirty 가 유지되어 미저장 사실이 화면에 남는다.
    await waitFor(() => expect(useLabelStore.getState().busy).toBeNull());
    expect(useLabelStore.getState().dirtyLabels.size).toBeGreaterThan(0);
  });

  /*
   * ★ 프레임 0건은 **오류가 아니다.** 업로드 자산의 프레임은 마킹으로 뽑을 위치를 정한 뒤에
   *   생기므로 아직 없는 것이 정상인 구간이 있다. 실패로 그리면 사용자는 자기 자산이 깨진 줄 안다.
   */
  it('프레임이_0건이어도_실패로_그리지_않는다_상태_안내다', async () => {
    // given: 준비 완료인데 아직 프레임이 없다
    mock.onGet('/portal/uploads/1').reply(200, ok(detail({ frames: [], frmeCnt: 0 })));

    // when
    renderPage();

    // then: 상태 안내로 뜬다
    const notice = await screen.findByTestId('upload-canvas-no-frame');
    expect(notice).toHaveAttribute('role', 'status');
    // then: 실패로 단정하지 않는다 — 오류 역할도, 실패 문구도 없다
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.queryByText(/실패|불러올 수 없|오류/)).toBeNull();
  });

  /*
   * 프레임 이동이 **주소를 바꾼다** — 데이터마트 갈래는 프레임을 옮길 때 주소가 바뀌는데
   * 같은 화면의 다른 갈래만 안 바뀌면 뒤로가기·새로고침·주소 공유가 갈래마다 다르게 동작한다.
   */
  it('프레임을_옮기면_주소가_그_프레임을_가리킨다', async () => {
    // given
    mock.onGet('/portal/uploads/1').reply(
      200,
      ok(detail({ uldTypeCd: 'VIDEO', frmeCnt: 2, frames: [frameRow(100, 0), frameRow(101, 1)] })),
    );
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    mock.onGet('/portal/uploads/frames/101/labels').reply(200, ok([]));
    renderPage();
    await waitFor(() => expect(screen.getByTestId('canvas-shell')).toBeInTheDocument());

    // when
    fireEvent.click(screen.getByRole('button', { name: '다음 프레임' }));

    // then: 두 번째 프레임의 라벨을 조회한다(주소가 그 프레임을 가리킨 결과)
    await waitFor(() =>
      expect(
        mock.history.get.some((r) => r.url === '/portal/uploads/frames/101/labels'),
      ).toBe(true),
    );
    expect(screen.getByText('2 / 2')).toBeInTheDocument();
  });

  it('주소가_가리키는_프레임으로_바로_열린다', async () => {
    // given
    mock.onGet('/portal/uploads/1').reply(
      200,
      ok(detail({ uldTypeCd: 'VIDEO', frmeCnt: 2, frames: [frameRow(100, 0), frameRow(101, 1)] })),
    );
    mock.onGet('/portal/uploads/frames/100/labels').reply(200, ok([]));
    mock.onGet('/portal/uploads/frames/101/labels').reply(200, ok([]));

    // when: 두 번째 프레임을 가리키는 주소로 진입
    renderWithProviders(<PortalUploadLabelingView uldSn={1} />, {
      initialEntries: ['/portal/label/1?source=upload&frame=101'],
      routes: [{ path: '/portal/label/:id', element: <PortalUploadLabelingView uldSn={1} /> }],
    });

    // then
    await waitFor(() => expect(screen.getByText('2 / 2')).toBeInTheDocument());
  });

  it('잘못된_자산_주소는_안내로_막는다', async () => {
    // given / when
    renderWithProviders(<PortalUploadLabelingView uldSn={Number.NaN} />, {
      initialEntries: ['/portal/label/abc?source=upload'],
      routes: [{ path: '/portal/label/:id', element: <PortalUploadLabelingView uldSn={Number.NaN} /> }],
    });

    // then
    expect(await screen.findByText('잘못된 자산 주소입니다.')).toBeInTheDocument();
  });

  it('READY_아닌_자산_진입시_안내', async () => {
    mock.onGet('/portal/uploads/1').reply(200, ok(detail({ uldSttsCd: 'PROCESSING' })));

    renderPage();

    await waitFor(() => expect(screen.getByText(/준비 중인 자산/)).toBeInTheDocument());
    // 캔버스는 렌더되지 않는다.
    expect(screen.queryByTestId('canvas-shell')).not.toBeInTheDocument();
  });
});
