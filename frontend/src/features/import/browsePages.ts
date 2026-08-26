// 이관 대상 탐색의 **이어붙이기** — 폴더 축(API-221)과 영상 파일 축(API-222) 공통.
//
// @design API-221 API-222 SCREEN-039 AC-120

import type { ImportBrowseEntry, ImportBrowseResult } from './types';

/**
 * 나눠 받은 쪽들을 한 목록으로 합친다. **위치(`path`)를 키로 중복을 걷어낸다.**
 *
 * <p>계약상 이어받는 자리는 <b>배타</b>라(API-221) 한 번 돌려준 항목이 다시 오지 않는다.
 * 그런데도 합치는 자리에서 다시 걷어내는 이유는, <b>중복이 오는 길이 계약 밖에 따로 있기</b>
 * 때문이다 — 이 탐색은 캐시를 붙들지 않아(`staleTime: 0`) 창을 다시 열거나 재조회가 걸리면
 * React Query 가 <b>쌓인 쪽들을 처음부터 다시 받아온다</b>. 그 사이 저장소 내용이 바뀌었거나
 * 「더 보기」가 연달아 눌리면 같은 항목이 두 쪽에 함께 담길 수 있다.
 *
 * <p>같은 항목이 두 번 쌓이면 화면이 <b>거짓을 보여준다</b> — 사용자는 폴더가 실제로 둘 있는
 * 것으로 읽고, 그중 하나를 골라 들어가면 같은 자리로 간다. 못 본 항목은 다시 열면 보이지만
 * 두 번 쌓인 항목은 스스로 사라지지 않는다.
 *
 * <p>먼저 온 것을 남긴다 — 정렬이 이름 오름차순으로 고정돼 있으므로(API-221) 앞쪽 쪽에 담긴
 * 것이 목록에서도 앞이다. 뒤엣것으로 덮으면 이어붙인 순서가 흐트러진다.
 *
 * <p>이름이 아니라 <b>위치</b>를 키로 삼는다. 이름은 서로 다른 자리에서 같을 수 있지만
 * 위치는 그 항목을 유일하게 가리키며, 화면이 실제로 쓰는 값도 위치다.
 */
export function mergeBrowseEntries(
  pages: readonly ImportBrowseResult[] | undefined,
): ImportBrowseEntry[] {
  if (!pages) return [];
  const seen = new Set<string>();
  const merged: ImportBrowseEntry[] = [];
  for (const page of pages) {
    for (const entry of page.entries) {
      if (seen.has(entry.path)) continue;
      seen.add(entry.path);
      merged.push(entry);
    }
  }
  return merged;
}
