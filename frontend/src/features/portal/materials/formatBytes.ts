/**
 * 해제본 총량 표기 — GB 자리까지.
 *
 * <h3>왜 공용 포맷터를 그대로 쓰지 않나</h3>
 * 공용 `formatFileSize` 는 <b>MB 에서 멈춘다</b>(첨부파일용으로 만들어졌다). 배포본 해제 총량은
 * 기가바이트급이라 그대로 쓰면 `12288.0 MB` 처럼 자릿수로만 커져 크기를 가늠할 수 없다.
 *
 * ★ <b>그렇다고 공용 포맷터를 고치지 않는다</b> — 그것은 관제 화면(공지 첨부)이 함께 쓰는 자리라
 *   여기 사정으로 그쪽 표기를 바꿀 이유가 없다(관제향 불변 구속).
 * ★ <b>GB 미만은 공용 포맷터에 그대로 위임한다</b> — 같은 값이 화면마다 다른 모양으로 뜨지 않게.
 *   여기서 새로 정하는 것은 <b>GB 가지 하나뿐</b>이라 두 번째 진실원이 생기지 않는다.
 *
 * @design INT-014
 */

import { formatFileSize } from '@/lib/formatFileSize';

const GB = 1024 * 1024 * 1024;

/** 바이트 수를 사람이 읽는 크기로. 1GB 이상만 GB 로 쓰고 그 아래는 공용 포맷터가 정한다. */
export function formatMaterialsBytes(bytes: number): string {
  if (bytes >= GB) return `${(bytes / GB).toFixed(1)} GB`;
  return formatFileSize(bytes);
}
