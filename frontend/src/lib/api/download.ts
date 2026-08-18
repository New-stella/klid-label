// 인증이 필요한 파일 응답을 브라우저 다운로드로 흘려보내는 공용 헬퍼.
//
// 왜 `<a href>` 직링크를 쓰지 않는가 — 이 저장소의 파일 응답은 모두 Authorization 헤더를 요구한다.
// 앵커 직링크에는 헤더가 붙지 않아 401 이 되므로, apiClient(blob)로 받은 뒤 ObjectURL 로 트리거한다.
//
// 같은 코드가 알림(notice)·포털 업로드 두 곳에 복제돼 있었다(후자 주석이 "로컬 재구현"이라고
// 스스로 밝혀 둔 상태였다). 세 번째 복제를 만들지 않기 위해 여기로 모은다.

/** 브라우저 다운로드 트리거 — a[download] + ObjectURL. 사용 후 즉시 revoke. */
export function triggerBrowserDownload(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}

/**
 * Content-Disposition 헤더에서 파일명 추출.
 * RFC 5987 `filename*=UTF-8''...` 우선, 없으면 `filename="..."` fallback.
 */
export function parseContentDispositionFilename(disposition: string): string | null {
  if (!disposition) return null;
  const extended = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(disposition);
  if (extended?.[1]) {
    try {
      return decodeURIComponent(extended[1].trim().replace(/^"|"$/g, ''));
    } catch {
      // decode 실패 시 plain filename 으로 폴백
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  if (plain?.[1]) return plain[1].trim();
  return null;
}

/** 응답 본문을 Blob 으로 정규화(jsdom+mock 은 string 으로 떨어질 수 있어 방어). */
export function toDownloadBlob(data: unknown): Blob {
  return data instanceof Blob
    ? data
    : new Blob([typeof data === 'string' ? data : JSON.stringify(data)]);
}
