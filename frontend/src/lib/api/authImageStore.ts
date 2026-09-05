import { apiClient } from './client';

/**
 * 인증 프레임 이미지 blob 로더 — **경로 단위 공유(dedupe) + 즉시 폐기(revoke)**.
 *
 * ## 왜 필요한가
 * 비교 화면(FrameGrid12 12쌍 × 2 슬롯 + 좌우 비교)은 진입 즉시 24개 이상의 XHR 을 발사한다.
 * BE 가 이미지 응답에 `no-store` 를 주므로 브라우저 HTTP 캐시가 먹지 않고, 선택된 프레임은 그리드
 * 슬롯과 좌우 비교가 **같은 경로**를 각자 받아 중복 페치한다. 그 중복만 제거한다.
 *
 * ## 정책 — 동시 실행 상한(대기열)을 두지 않는다
 * 이전 구현은 동시 4건 상한 + FIFO 대기열을 뒀으나 **순이득이 아니었다**:
 * - 상한 4는 브라우저 기본 동시 연결 수(HTTP/1.1 호스트당 ~6, HTTP/2 는 사실상 무제한)보다 낮아
 *   오히려 체감이 느려졌다.
 * - 대기열에 취소가 없어, 페이저를 넘기면 **이미 언마운트된 이전 페이지 요청**이 슬롯을 먼저 점유하고
 *   새 페이지 이미지가 그 뒤에 줄 섰다(head-of-line blocking).
 * 커넥션 수 제한은 브라우저가 이미 수행하므로 상한을 걷어내고 **중복 제거·공유·폐기**만 남긴다.
 *
 * ## 보안 — 비식별 신고 게이트(CWE-359) 우회 금지
 * 이건 "캐시"가 아니라 **현재 마운트된 소비자들의 공유 참조**다. 참조 카운트가 0이 되는 즉시
 * (마지막 소비자 unmount / 경로 변경) objectURL 을 revoke 하고 엔트리를 버린다. 즉 수명이
 * **화면 세션(정확히는 그 이미지를 보고 있는 동안)** 으로 한정되며, 화면을 벗어났다 다시 들어오면
 * 반드시 BE 를 다시 호출한다 → 비식별 누락 신고(412) 등 서버 게이트가 그대로 적용된다.
 * localStorage/sessionStorage/IndexedDB 등 **영속 저장은 하지 않는다**.
 */

interface ImageEntry {
  /** 이 경로를 참조 중인 소비자 수. 0 이 되면 즉시 폐기(revoke). */
  refCount: number;
  /** 로드 완료된 objectURL. 로딩 중/실패면 null. */
  objectUrl: string | null;
  /** 현재 소비자에게 공유되는 요청. */
  promise: Promise<string>;
  /** 마지막 요청이 실패했는지 — 새 소비자가 붙을 때 재요청 판단에 쓴다. */
  failed: boolean;
  /** 요청 세대 — 재요청 시 증가. 늦게 도착한 이전 세대 응답을 무시(폐기)하는 데 쓴다. */
  generation: number;
}

const entries = new Map<string, ImageEntry>();

/**
 * 실제 요청 1회. 엔트리가 없으면 만들고, 있으면 **같은 엔트리 객체를 재사용**해 결과
 * (objectUrl/실패 여부)와 promise 를 갱신한다.
 *
 * 실패해도 엔트리를 `failed` 로 표시만 하고 **맵에서 지우지 않는다** — 지우면 아직 마운트된
 * 소비자의 release 가 새로 생성된 다른 엔트리의 refCount 를 깎아(세대 교차) 사용 중인 objectURL 을
 * 조기 revoke 한다. 재요청은 **새 소비자가 붙는 시점**(acquire)에만 일어나므로 자동 재시도 루프가
 * 생기지 않는다.
 */
function startFetch(path: string, target?: ImageEntry): ImageEntry {
  const generation = (target?.generation ?? 0) + 1;
  let entry: ImageEntry | undefined = target;
  const isCurrent = () =>
    entry !== undefined && entries.get(path) === entry && entry.generation === generation;

  const promise = apiClient
    .get<Blob>(path, { responseType: 'blob' })
    .then((res) => {
      const url = URL.createObjectURL(res.data);
      // 로딩 중에 모든 소비자가 떠났거나(엔트리 삭제) 재요청으로 세대가 바뀌었으면 즉시 폐기한다.
      if (!isCurrent() || entry === undefined) {
        URL.revokeObjectURL(url);
        return url;
      }
      entry.objectUrl = url;
      entry.failed = false;
      return url;
    })
    .catch((error: unknown) => {
      if (isCurrent() && entry !== undefined) entry.failed = true;
      throw error;
    });

  if (entry === undefined) {
    entry = { refCount: 1, objectUrl: null, promise, failed: false, generation };
  } else {
    // 재요청 — 진행 중에 붙는 소비자가 중복 요청을 내지 않도록 failed 를 즉시 내린다.
    entry.promise = promise;
    entry.failed = false;
    entry.generation = generation;
  }
  entries.set(path, entry);
  // 소비자가 각자 catch 하므로 여기서는 unhandled rejection 만 막는다.
  promise.catch(() => undefined);
  return entry;
}

/**
 * 경로 이미지를 참조한다. 이미 로딩 중/로드된 경로면 요청을 재사용하고,
 * 직전 요청이 실패한 경로면 **새 소비자가 붙는 이 시점에 한 번** 재요청한다.
 * 소비자는 사용을 마치면 **반드시** 같은 경로로 {@link releaseAuthImage} 를 호출해야 한다.
 *
 * @param path apiClient 기준 상대 경로(화이트리스트 검증을 통과한 값만 전달할 것)
 * @returns objectURL
 */
export function acquireAuthImage(path: string): Promise<string> {
  const existing = entries.get(path);
  if (existing) {
    existing.refCount += 1;
    if (!existing.failed) return existing.promise;
    return startFetch(path, existing).promise;
  }
  return startFetch(path).promise;
}

/** 참조 해제 — 마지막 소비자가 떠나면 objectURL 을 revoke 하고 엔트리를 버린다. */
export function releaseAuthImage(path: string): void {
  const entry = entries.get(path);
  if (!entry) return;
  entry.refCount -= 1;
  if (entry.refCount > 0) return;
  entries.delete(path);
  if (entry.objectUrl) {
    URL.revokeObjectURL(entry.objectUrl);
    entry.objectUrl = null;
  }
}

/** 테스트 전용 — 모듈 레벨 상태 초기화(테스트 간 누수 방지). 프로덕션 코드에서 호출하지 않는다. */
export function resetAuthImageStoreForTest(): void {
  for (const entry of entries.values()) {
    if (entry.objectUrl) URL.revokeObjectURL(entry.objectUrl);
  }
  entries.clear();
}
