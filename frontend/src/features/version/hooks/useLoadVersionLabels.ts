import { useMutation } from '@tanstack/react-query';

import { getVersionLabels } from '../api';
import type { VersionLabelsResponse } from '../types';

/**
 * API-195 — 산출 회차 <b>불러오기</b>(읽기 전용).
 *
 * <h3>캐시를 건드리지 않는다 (Critical)</h3>
 * 이 호출은 <b>서버 상태를 바꾸지 않는다</b>. 따라서 `invalidateQueries` 도 `removeQueries` 도 하지
 * 않는다 — 무효화하면 화면에 올려 둔 불러온 내용이 서버 작업본 재조회로 <b>덮여</b> 사용자가 방금
 * 불러온 것이 사라진다. 확정 저장(`useSaveVideoLabels`)이 성공했을 때 비로소 무효화한다.
 *
 * <p>이 저장소의 구속 규칙(“게이트가 닫히는 변화만 removeQueries”)과도 정합한다 — 불러오기는 게이트를
 * 닫지도, 값을 바꾸지도 않는다.
 *
 * <h3>왜 useQuery 가 아니라 useMutation 인가</h3>
 * 조회이지만 <b>사용자가 버튼을 눌렀을 때만</b> 일어나는 명령형 동작이고, 응답을 캐시에 남겨 두면
 * 다음 진입에서 옛 회차 내용이 되살아나 화면에 자동으로 올라간다(진행 중이던 편집을 묻지 않고 버리는
 * 동작 — 확정 사양이 금지한다).
 *
 * @design API-195
 * @req R6
 */
export function useLoadVersionLabels(rawSn: number | undefined) {
  return useMutation<VersionLabelsResponse, unknown, number>({
    mutationFn: (versionNo: number) => {
      if (rawSn === undefined) return Promise.reject(new Error('rawSn is required'));
      return getVersionLabels(rawSn, versionNo);
    },
  });
}
