import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MockAdapter from 'axios-mock-adapter';
import { render, screen, waitFor } from '@testing-library/react';

import { AuthImage } from '@/components/common/AuthImage';
import { resetAuthImageStoreForTest } from '@/lib/api/authImageStore';
import { apiClient } from '@/lib/api/client';

/**
 * AuthImage — 인증(Authorization: Bearer) 이 필요한 이미지 바이너리를 axios blob 으로 받아
 * objectURL 로 렌더한다. raw `<img src>` 는 헤더를 못 실어 401 이 되므로 사용 금지.
 *
 * 이번 확장: BE 가 URL 문자열(`/v1/frames/{srcSn}/deid-image`)로 내려주는 경로도 지원.
 */
describe('AuthImage', () => {
  let mock: MockAdapter;
  let created: string[];
  let revoked: string[];

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    created = [];
    revoked = [];
    let seq = 0;
    // jsdom 은 createObjectURL/revokeObjectURL 미구현 — 테스트 더블로 대체
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn(() => {
        const url = `blob:mock/${++seq}`;
        created.push(url);
        return url;
      }),
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn((url: string) => {
        revoked.push(url);
      }),
    });
  });

  afterEach(() => {
    mock.restore();
    resetAuthImageStoreForTest();
  });

  it('srcSn_지정시_원본_프레임_이미지_API로_blob_요청', async () => {
    // given
    mock.onGet('/frames/77/image').reply(200, new Blob(['img']));

    // when
    render(<AuthImage srcSn={77} alt="프레임 77" data-testid="auth-img" />);

    // then
    await waitFor(() => {
      expect(screen.getByTestId('auth-img').tagName).toBe('IMG');
    });
    expect(screen.getByTestId('auth-img')).toHaveAttribute('src', created[0]);
    expect(mock.history.get.map((r) => r.url)).toEqual(['/frames/77/image']);
  });

  it('path_지정시_비식별_이미지_API로_blob_요청', async () => {
    // given: BE 가 내려주는 URL 문자열 (baseURL `/api/v1` 기준 상대경로로 변환되어야 한다)
    mock.onGet('/frames/9002/deid-image').reply(200, new Blob(['img']));

    // when
    render(
      <AuthImage
        path="/v1/frames/9002/deid-image"
        alt="비식별 프레임"
        data-testid="auth-img"
      />,
    );

    // then
    await waitFor(() => {
      expect(screen.getByTestId('auth-img').tagName).toBe('IMG');
    });
    expect(mock.history.get.map((r) => r.url)).toEqual(['/frames/9002/deid-image']);
  });

  it('허용되지_않은_path는_요청하지_않고_이미지없음_표시', async () => {
    // given: allowlist 밖 경로(경로 순회/외부 호스트 등)
    // when
    render(
      <AuthImage
        path="https://evil.example.com/steal"
        alt="악성"
        data-testid="auth-img"
      />,
    );

    // then
    await waitFor(() => {
      expect(screen.getByTestId('auth-img')).toHaveTextContent('이미지 없음');
    });
    expect(mock.history.get).toHaveLength(0);
  });

  it('상위_경로_순회_path도_차단한다', async () => {
    // given / when
    render(
      <AuthImage
        path="/v1/frames/1/../../admin/secrets"
        alt="악성"
        data-testid="auth-img"
      />,
    );

    // then
    await waitFor(() => {
      expect(screen.getByTestId('auth-img')).toHaveTextContent('이미지 없음');
    });
    expect(mock.history.get).toHaveLength(0);
  });

  it('언마운트시_objectURL을_revoke한다', async () => {
    // given
    mock.onGet('/frames/9002/deid-image').reply(200, new Blob(['img']));
    const { unmount } = render(
      <AuthImage path="/v1/frames/9002/deid-image" alt="a" data-testid="auth-img" />,
    );
    await waitFor(() => {
      expect(screen.getByTestId('auth-img').tagName).toBe('IMG');
    });

    // when
    unmount();

    // then
    expect(revoked).toEqual([created[0]]);
  });

  it('같은_경로를_보는_소비자가_여럿이어도_요청은_한_번만_나간다', async () => {
    // given: 그리드 슬롯과 좌우 비교가 선택 프레임의 같은 이미지를 함께 표시하는 상황
    mock.onGet('/frames/9002/deid-image').reply(200, new Blob(['img']));

    // when
    render(
      <>
        <AuthImage path="/v1/frames/9002/deid-image" alt="a" data-testid="img-a" />
        <AuthImage path="/v1/frames/9002/deid-image" alt="b" data-testid="img-b" />
      </>,
    );

    // then: 중복 페치 없이 하나의 blob 을 공유한다
    await waitFor(() => {
      expect(screen.getByTestId('img-a').tagName).toBe('IMG');
      expect(screen.getByTestId('img-b').tagName).toBe('IMG');
    });
    expect(mock.history.get).toHaveLength(1);
    expect(created).toHaveLength(1);
    expect(screen.getByTestId('img-b')).toHaveAttribute('src', created[0]);
  });

  it('공유중인_이미지는_마지막_소비자가_사라질_때_revoke된다', async () => {
    // given
    mock.onGet('/frames/9002/deid-image').reply(200, new Blob(['img']));
    const { rerender, unmount } = render(
      <>
        <AuthImage path="/v1/frames/9002/deid-image" alt="a" data-testid="img-a" />
        <AuthImage path="/v1/frames/9002/deid-image" alt="b" data-testid="img-b" />
      </>,
    );
    await waitFor(() => expect(created).toHaveLength(1));

    // when: 소비자 하나만 사라짐
    rerender(
      <>
        <AuthImage path="/v1/frames/9002/deid-image" alt="a" data-testid="img-a" />
      </>,
    );

    // then: 아직 보고 있는 소비자가 있으므로 revoke 하지 않는다
    expect(revoked).toHaveLength(0);
    expect(screen.getByTestId('img-a')).toHaveAttribute('src', created[0]);

    // when: 마지막 소비자까지 사라지면 즉시 폐기(영속 캐시 없음)
    unmount();

    // then
    expect(revoked).toEqual([created[0]]);
  });

  it('여러_이미지는_대기열_없이_즉시_모두_발사된다', async () => {
    // given: 12쌍 그리드처럼 이미지가 한꺼번에 마운트되는 상황.
    //        동시 실행 상한(FIFO 대기열)을 두면 브라우저 기본 동시 연결 수보다 낮아 오히려 느려지고,
    //        취소가 없어 언마운트된 요청이 슬롯을 선점한다(head-of-line blocking).
    const resolvers: Array<() => void> = [];
    mock.onGet(/\/frames\/\d+\/deid-image$/).reply(
      () =>
        new Promise((resolve) => {
          resolvers.push(() => resolve([200, new Blob(['img'])]));
        }),
    );
    const total = 8;

    // when
    render(
      <>
        {Array.from({ length: total }, (_, i) => (
          <AuthImage
            key={i}
            path={`/v1/frames/${100 + i}/deid-image`}
            alt={`f${i}`}
            data-testid={`img-${i}`}
          />
        ))}
      </>,
    );

    // then: 지연·대기 없이 전부 발사된다
    await waitFor(() => {
      expect(mock.history.get).toHaveLength(total);
    });

    // 정리 — 남은 요청 모두 해소
    for (const resolve of [...resolvers]) resolve();
    await waitFor(() => {
      expect(screen.getByTestId('img-0').tagName).toBe('IMG');
    });
  });

  it('진행중_요청이_언마운트돼도_새_페이지_요청이_지연없이_시작된다', async () => {
    // given: 이전 페이지 이미지들이 아직 응답 전인 상태(페이저 전환 직전)
    const resolvers: Array<() => void> = [];
    mock.onGet(/\/frames\/\d+\/deid-image$/).reply(
      () =>
        new Promise((resolve) => {
          resolvers.push(() => resolve([200, new Blob(['img'])]));
        }),
    );
    const { rerender } = render(
      <>
        {Array.from({ length: 6 }, (_, i) => (
          <AuthImage
            key={i}
            path={`/v1/frames/${200 + i}/deid-image`}
            alt={`p1-${i}`}
            data-testid={`p1-${i}`}
          />
        ))}
      </>,
    );
    await waitFor(() => expect(mock.history.get).toHaveLength(6));

    // when: 응답 전에 페이지를 넘긴다(이전 요청은 미완료 상태로 언마운트)
    rerender(
      <>
        {Array.from({ length: 6 }, (_, i) => (
          <AuthImage
            key={`n${i}`}
            path={`/v1/frames/${300 + i}/deid-image`}
            alt={`p2-${i}`}
            data-testid={`p2-${i}`}
          />
        ))}
      </>,
    );

    // then: 새 페이지 요청이 이전 요청 완료를 기다리지 않고 즉시 나간다(슬롯 누수·데드락 없음)
    await waitFor(() => {
      expect(mock.history.get.map((r) => r.url)).toContain('/frames/305/deid-image');
    });
    expect(mock.history.get).toHaveLength(12);

    // when: 새 페이지 응답만 해소해도 렌더가 완료된다(이전 페이지 응답 대기 불필요)
    for (const resolve of [...resolvers].slice(6)) resolve();

    // then
    await waitFor(() => {
      expect(screen.getByTestId('p2-0').tagName).toBe('IMG');
    });
    for (const resolve of [...resolvers]) resolve();
  });

  it('요청_실패후_같은_경로를_다시_마운트하면_재요청한다', async () => {
    // given: 일시 실패(5xx) 후 같은 경로를 보는 새 소비자가 마운트되는 상황
    //        (그리드 슬롯 ↔ 좌우 비교는 실제로 동일 URL 을 본다)
    mock.onGet('/frames/9002/deid-image').replyOnce(500);
    render(<AuthImage path="/v1/frames/9002/deid-image" alt="a" data-testid="img-a" />);
    await waitFor(() => {
      expect(screen.getByTestId('img-a')).toHaveTextContent('이미지 없음');
    });
    mock.onGet('/frames/9002/deid-image').reply(200, new Blob(['img']));

    // when: 기존 소비자가 마운트된 채로 같은 경로의 소비자가 추가된다
    render(<AuthImage path="/v1/frames/9002/deid-image" alt="b" data-testid="img-b" />);

    // then: 실패 promise 에 고착되지 않고 재요청이 나가 정상 표시된다
    await waitFor(() => {
      expect(screen.getByTestId('img-b').tagName).toBe('IMG');
    });
    expect(mock.history.get).toHaveLength(2);
  });

  it('로딩_폴백에도_data_testid가_유지된다', async () => {
    // given: 응답이 아직 오지 않은 상태(로딩 폴백 <div>)
    mock.onGet('/frames/9002/deid-image').reply(() => new Promise(() => undefined));

    // when
    render(
      <AuthImage
        path="/v1/frames/9002/deid-image"
        alt="로딩"
        width={120}
        height={68}
        data-testid="auth-img"
      />,
    );

    // then: 식별 속성은 남고 img 전용 속성은 걸러진다
    const loading = screen.getByTestId('auth-img');
    expect(loading.tagName).toBe('DIV');
    expect(loading).toHaveAttribute('aria-label', '로딩');
    expect(loading).not.toHaveAttribute('width');
    expect(loading).not.toHaveAttribute('height');
  });

  it('폴백_div에는_img_전용_속성을_전개하지_않는다', async () => {
    // given: 허용되지 않은 경로 → 에러 폴백 <div>
    render(
      <AuthImage
        path="https://evil.example.com/steal"
        alt="악성"
        width={120}
        height={68}
        loading="lazy"
        data-testid="auth-img"
      />,
    );

    // then
    await waitFor(() => {
      expect(screen.getByTestId('auth-img')).toHaveTextContent('이미지 없음');
    });
    const fallback = screen.getByTestId('auth-img');
    expect(fallback.tagName).toBe('DIV');
    expect(fallback).not.toHaveAttribute('width');
    expect(fallback).not.toHaveAttribute('height');
    expect(fallback).not.toHaveAttribute('loading');
    // data-*/aria-* 는 유지된다
    expect(fallback).toHaveAttribute('aria-label', '악성');
  });

  it('path_변경시_이전_objectURL을_revoke한다', async () => {
    // given
    mock.onGet('/frames/9002/deid-image').reply(200, new Blob(['a']));
    mock.onGet('/frames/9003/deid-image').reply(200, new Blob(['b']));
    const { rerender } = render(
      <AuthImage path="/v1/frames/9002/deid-image" alt="a" data-testid="auth-img" />,
    );
    await waitFor(() => expect(created).toHaveLength(1));

    // when
    rerender(
      <AuthImage path="/v1/frames/9003/deid-image" alt="b" data-testid="auth-img" />,
    );

    // then
    await waitFor(() => expect(created).toHaveLength(2));
    expect(revoked).toContain(created[0]);
  });
});
