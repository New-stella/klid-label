// 검증 이벤트 유형·질문 훅 — TanStack Query v5 (커스텀 훅으로 감싼다, state-management 규칙).
// [@design API-219] [@design API-220] [@design SCREEN-038]

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { VERIFICATION_EVENT_TYPE_KEYS } from '@/lib/queryKeys';

import {
  getVerificationEventTypes,
  replaceVerificationEventQuestions,
  type VerificationEventType,
} from './verificationApi';

/** 검증 이벤트 유형 + 유형별 질문 + 관제 짝 목록. REVIEWER 전용 경로다. */
export function useVerificationEventTypes() {
  return useQuery<VerificationEventType[]>({
    queryKey: VERIFICATION_EVENT_TYPE_KEYS.list(),
    queryFn: getVerificationEventTypes,
    staleTime: 30 * 1000,
  });
}

/**
 * 한 유형의 질문 목록 전체 교체.
 *
 * ★성공 응답에 <b>교체 후 목록</b>이 실려 오므로 목록을 다시 불러오지 않고 그 유형의 행만
 * 응답값으로 갱신한다(사양 SCREEN-038). 재조회하면 같은 사실을 두 번 받아오는 왕복이 되고,
 * 그 사이 화면이 잠깐 옛 목록으로 남는다.
 *
 * ⚠ 실패 시 캐시를 건드리지 않는다 — 서버가 요청 <b>전체</b>를 거부하므로 저장 전 상태가
 * 그대로 서버의 상태다. 화면도 편집 내용을 지우지 않는다.
 */
export function useReplaceVerificationEventQuestions() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      vrfcEvntTypeCd,
      questions,
    }: {
      vrfcEvntTypeCd: string;
      questions: { qstnCn: string }[];
    }) => replaceVerificationEventQuestions(vrfcEvntTypeCd, questions),
    onSuccess: (result) => {
      queryClient.setQueryData<VerificationEventType[]>(
        VERIFICATION_EVENT_TYPE_KEYS.list(),
        // 캐시가 비어 있으면(직접 진입 전) 만들어 두지 않는다 — 목록 전체를 알 수 없다.
        (prev) =>
          prev?.map((t) =>
            t.vrfcEvntTypeCd === result.vrfcEvntTypeCd
              ? { ...t, questions: result.questions }
              : t,
          ),
      );
    },
  });
}
