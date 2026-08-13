/**
 * 바이트 수를 사람이 읽는 크기 문자열로 바꾼다 (B / KB / MB).
 *
 * 첨부파일 목록(UI-112 AttachmentList)이 `size` 를 **이미 서식화된 문자열**로 받기 때문에
 * 바이트→문자열 변환이 호출부마다 필요하다. 같은 함수가 화면 파일에 복제돼 있으면
 * 한쪽만 바뀌어 같은 파일이 화면마다 다른 크기로 보이므로 여기 한 곳에 둔다.
 *
 * BE 는 첨부 크기를 바이트(`fileSize`, int64)로 내려준다.
 */
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
