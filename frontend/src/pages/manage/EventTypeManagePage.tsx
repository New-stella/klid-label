import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import type { EventTypeAdminItem } from '@/features/eventType/adminApi';
import {
  useEventTypeAdminList,
  useUpdateEventTypeAdmin,
} from '@/features/eventType/adminHooks';
import { resolveApiMessage } from '@/lib/api/resolveApiMessage';
import { useUiStore } from '@/stores/useUiStore';

/**
 * 이벤트유형 관리 — REVIEWER 전용.
 *
 * 이벤트유형은 <관제 인입 소비 시점에 자동 등록>되므로 이 화면에 <생성·삭제가 없다>.
 * 화면이 하는 일은 두 가지다.
 *  - 표시명 정정: 관제가 보낸 이름이 부적절하거나 아직 없을 때 운영자가 직접 정한다.
 *  - 수집여부 토글: 필터 드롭다운 노출 여부.
 *
 * ★표시명은 BE 가 4단 폴백(운영자 표시명 → 관제 수신명 → 카테고리명 → 유형코드)으로 계산해
 *   내려준다(dsplNm). FE 에서 폴백을 재계산하지 않는다 — 판정이 갈라지면 화면과 산출물
 *   (승인 시점 동결 → 학습데이터 event_name)이 조용히 어긋난다.
 *
 * 보안:
 * - REVIEWER 만 진입(라우트 RoleGuard=internalReviewerOnly) + BE @PreAuthorize 이중 방어.
 * - 요청 본문은 허용 필드(optrIndctNm/clctYn)만 — 관제 칸(evntNm)·PK·분류코드는 보내지 않는다.
 * - 이름 렌더는 React 기본 escape(XSS 방어), dangerouslySetInnerHTML 미사용.
 */
export function EventTypeManagePage() {
  const { data, isLoading, error } = useEventTypeAdminList();
  const updateMutation = useUpdateEventTypeAdmin();
  const pushToast = useUiStore((s) => s.pushToast);

  const [editingCode, setEditingCode] = useState<string | null>(null);
  const [draftName, setDraftName] = useState('');

  const startEdit = (row: EventTypeAdminItem) => {
    setEditingCode(row.evntTypeCd);
    setDraftName(row.optrIndctNm ?? '');
  };

  const submit = async (row: EventTypeAdminItem, body: { optrIndctNm?: string; clctYn?: string }) => {
    try {
      await updateMutation.mutateAsync({ evntTypeCd: row.evntTypeCd, body });
      setEditingCode(null);
      pushToast({ variant: 'success', message: '이벤트유형을 저장했습니다.' });
    } catch (e) {
      pushToast({ variant: 'error', message: resolveApiMessage(e, '저장에 실패했습니다.') });
    }
  };

  if (isLoading) return <Skeleton />;
  if (error) return <ErrorState message={resolveApiMessage(error, '목록을 불러오지 못했습니다.')} />;

  const rows = data ?? [];

  return (
    <div className="space-y-4">
      <PageHeader
        title="이벤트유형 관리"
        description="관제에서 인입된 이벤트유형의 표시명과 수집여부를 관리합니다. 유형은 인입 시 자동 등록되므로 직접 추가·삭제할 수 없습니다."
      />

      <table className="w-full text-sm">
        <thead>
          <tr className="border-b bg-gray-50 text-left">
            <th className="p-2">유형코드</th>
            <th className="p-2">표시명</th>
            <th className="p-2">관제 원본</th>
            <th className="p-2">카테고리</th>
            <th className="p-2">수집</th>
            <th className="p-2">관리</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.evntTypeCd} className="border-b">
              <td className="p-2 font-mono">{row.evntTypeCd}</td>
              <td className="p-2">
                {editingCode === row.evntTypeCd ? (
                  <input
                    aria-label={`${row.evntTypeCd} 표시명`}
                    className="w-full rounded border border-gray-300 px-2 py-1"
                    maxLength={200}
                    value={draftName}
                    onChange={(e) => setDraftName(e.target.value)}
                  />
                ) : (
                  <span>{row.dsplNm}</span>
                )}
              </td>
              {/* 관제 원본·카테고리는 읽기 전용 — 표시명이 어디서 왔는지 설명하는 근거다. */}
              <td className="p-2 text-gray-500">{row.evntNm ?? '-'}</td>
              <td className="p-2 text-gray-500">{row.evntCtgryNm ?? '-'}</td>
              <td className="p-2">
                <button
                  type="button"
                  aria-label={`${row.evntTypeCd} 수집여부 토글`}
                  className="rounded border px-2 py-1"
                  onClick={() => void submit(row, { clctYn: row.clctYn === 'Y' ? 'N' : 'Y' })}
                >
                  {row.clctYn === 'Y' ? '노출' : '숨김'}
                </button>
              </td>
              <td className="space-x-2 p-2">
                {editingCode === row.evntTypeCd ? (
                  <>
                    {/* 빈 문자열 저장 = 표시명 해제 → 관제 수신명으로 자연 복귀(되돌리기 경로) */}
                    <Button onClick={() => void submit(row, { optrIndctNm: draftName })}>저장</Button>
                    <Button variant="secondary" onClick={() => setEditingCode(null)}>
                      취소
                    </Button>
                  </>
                ) : (
                  <Button variant="secondary" onClick={() => startEdit(row)}>
                    표시명 수정
                  </Button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
