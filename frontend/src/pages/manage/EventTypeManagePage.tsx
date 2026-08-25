import { useMemo, useState } from 'react';

import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/common/Card';
import { Checkbox } from '@/components/common/Checkbox';
import {
  DisplayNameSourceChip,
  isDisplayNameSource,
} from '@/components/common/DisplayNameSourceChip';
import { ErrorState } from '@/components/common/ErrorState';
import { Field, FieldLabel } from '@/components/common/Field';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import type { EventTypeAdminItem } from '@/features/eventType/adminApi';
import {
  useEventTypeAdminList,
  useUpdateEventTypeAdmin,
} from '@/features/eventType/adminHooks';
import { PresetLinkStatusChip } from '@/features/eventType/components/PresetLinkStatusChip';
import { VerificationEventTypeSection } from '@/features/eventType/components/VerificationEventTypeSection';
import {
  isPresetLinkStatus,
  PRESET_LINK_WITHHELD,
} from '@/features/eventType/presetLinkStatus';
import {
  mergePinnedRows,
  upsertPinnedRow,
  type PinnedEventTypeRow,
} from '@/features/eventType/pinnedRows';
import { resolveApiMessage } from '@/lib/api/resolveApiMessage';
import { useUiStore } from '@/stores/useUiStore';

/**
 * 이벤트유형 관리 — REVIEWER 전용.
 *
 * 이벤트유형은 <관제 인입 소비 시점에 자동 등록>되므로 이 화면에 <생성·삭제가 없다>.
 * 화면이 하는 일은 세 가지다.
 *  - 표시명 정정: 관제가 보낸 이름이 부적절하거나 아직 없을 때 운영자가 직접 정한다.
 *  - 수집여부 토글: 필터 드롭다운 노출 여부.
 *  - 프리셋 연결 상태 확인: 오토라벨이 보류되는 유형을 알아차리는 자리다.
 *
 * ★표시명은 BE 가 4단 폴백(운영자 표시명 → 관제 수신명 → 카테고리명 → 유형코드)으로 계산해
 *   내려준다(dsplNm). FE 에서 폴백을 재계산하지 않는다 — 판정이 갈라지면 화면과 산출물
 *   (승인 시점 동결 → 학습데이터 event_name)이 조용히 어긋난다.
 * ★표시명 칸에는 그 값이 <어느 단계에서 온 것인지>를 출처 칩으로 병기한다(dsplNmSource) —
 *   같은 이름이 여러 줄에 보이는 까닭이 값만으로는 드러나지 않기 때문이다. 표기 값도 서버
 *   응답을 그대로 쓴다(재판정 금지).
 * ★프리셋 칸도 같은 원칙이다 — 서버가 판정한 presetLinkStatus 를 그대로 표기하고 화면이
 *   프리셋 유무·실효 여부를 다시 판정하지 않는다.
 *
 * @design SCREEN-038, API-185, API-186, API-219, API-220, UI-126, AC-114, AC-116, AC-119
 *
 * 보안:
 * - REVIEWER 만 진입(라우트 RoleGuard=internalReviewerOnly) + BE @PreAuthorize 이중 방어.
 * - 요청 본문은 허용 필드(optrIndctNm/clctYn)만 — 관제 칸(evntNm)·PK·분류코드는 보내지 않는다.
 * - 이름 렌더는 React 기본 escape(XSS 방어), dangerouslySetInnerHTML 미사용.
 */

/**
 * 표 헤더 셀 클래스 — 모든 `<th>` 가 이 한 값을 공유한다.
 *
 * ⚠ **반드시 `<th>` 에 직접 건다.** 구 구현은 이 글자 클래스를 헤더 `<tr>` 에만 걸었는데,
 * `font-weight` 는 상속되더라도 브라우저 UA 기본 `th { font-weight: bold }`(700)가 **직접
 * 적용**되어 상속값을 이긴다 — 그래서 이 표만 700 으로 굵게 렌더됐다(브라우저 실측).
 * ⚠ 굵기는 `text-table-header` step(600)이 단독으로 정한다 — 별도 굵기 클래스를 겹치지 않는다.
 *
 * 글자색 하한은 `gray-600` 이다 — 헤더 배경이 secondary-50(#EEF2F7)이라 gray-500 은
 * 그 위에서 4.01:1 로 AA(4.5:1) 미달이다(gray-600 은 5.60:1).
 */
const TH_CLASS = 'p-2 text-left text-table-header uppercase tracking-wide text-gray-600';

export function EventTypeManagePage() {
  /**
   * 「오토라벨 보류만 보기」 — 켜면 <b>보류 전체를 뜻하는 값 하나</b>를 서버에 실어 다시 조회한다.
   *
   * ★「연결됨(무효)」와 「미연결」의 상태 값을 화면에서 조합해 보내지 않는다(사양 SCREEN-038).
   *   조합하면 「무엇이 보류를 유발하는가」의 정의를 화면이 갖게 되어, 보류 조건이 바뀔 때
   *   배치와 화면이 조용히 어긋난다. 실제로 「연결됨(제외)」는 상태 이름만 보면 "연결됨"이라
   *   화면이 조합하면 놓치기 쉬운데, 그 유형은 <b>사람이 일부러 뺀 선언</b>이라 보류가 아니다.
   *   ⚠ 그래서 이 파일에는 상태 값 이름 자체가 등장하지 않는다(정적 가드가 그것을 고정한다).
   *
   * 서버에 기본값이 없으므로 <b>진입 시에는 꺼진 상태</b>로 시작한다(파라미터 미탑재 = 전체).
   */
  const [withheldOnly, setWithheldOnly] = useState(false);
  const { data, isLoading, error } = useEventTypeAdminList(
    withheldOnly ? PRESET_LINK_WITHHELD : undefined,
  );
  const updateMutation = useUpdateEventTypeAdmin();
  const pushToast = useUiStore((s) => s.pushToast);

  const [editingCode, setEditingCode] = useState<string | null>(null);
  const [draftName, setDraftName] = useState('');
  /**
   * 저장 직후 조건에서 벗어난 행 — 사라지게 하지 않는다(사양 SCREEN-038).
   *
   * 거르기 자체는 그대로 서버가 전체를 대상으로 수행한다. 여기서 되돌려 놓는 것은 <b>방금
   * 저장한 그 행 하나</b>뿐이라 모집단을 줄이지 않는다.
   */
  const [pinnedRows, setPinnedRows] = useState<PinnedEventTypeRow[]>([]);

  const rows = useMemo(() => mergePinnedRows(data ?? [], pinnedRows), [data, pinnedRows]);

  const startEdit = (row: EventTypeAdminItem) => {
    setEditingCode(row.evntTypeCd);
    setDraftName(row.optrIndctNm ?? '');
  };

  /** 조건을 바꾸면 목록을 처음부터 다시 조회한다 — 붙잡아 둔 행·편집 중인 행도 함께 접는다. */
  const changeFilter = (next: boolean) => {
    setWithheldOnly(next);
    setPinnedRows([]);
    setEditingCode(null);
  };

  const submit = async (row: EventTypeAdminItem, body: { optrIndctNm?: string; clctYn?: string }) => {
    // 사라진 뒤에는 자리를 알 수 없으므로 저장 전에 지금 위치를 잡아 둔다.
    const index = rows.findIndex((r) => r.evntTypeCd === row.evntTypeCd);
    try {
      const updated = await updateMutation.mutateAsync({ evntTypeCd: row.evntTypeCd, body });
      setEditingCode(null);
      // 조건을 켠 상태에서만 붙잡는다 — 끄고 보면 전체가 오므로 빠질 행이 없다.
      if (withheldOnly && index >= 0) {
        setPinnedRows((prev) => upsertPinnedRow(prev, updated, index));
      }
      pushToast({ variant: 'success', message: '이벤트유형을 저장했습니다.' });
    } catch (e) {
      pushToast({ variant: 'error', message: resolveApiMessage(e, '저장에 실패했습니다.') });
    }
  };

  // 건수 요약 — 숨긴 유형이 몇 건인지 표에서 세지 않고 바로 읽게 한다(시안 `card-count`).
  const shownCount = rows.filter((row) => row.clctYn === 'Y').length;
  const hiddenCount = rows.length - shownCount;

  return (
    <div className="space-y-4">
      <PageHeader
        title="이벤트유형 관리"
        description="관제에서 인입된 이벤트유형의 표시명과 수집여부를 관리합니다. 유형은 인입 시 자동 등록되므로 직접 추가·삭제할 수 없습니다."
      />

      {/* 안내 배너 — 표시명 지정이 <필터 그룹을 가르는 조작>이라는 사실을 조작 전에 알린다.
          role 은 status(라이브 영역)가 아니라 region 이다 — 저장과 무관하게 항상 떠 있는
          정적 안내라, 낭독기에 변경으로 알릴 내용이 아니다. */}
      <Alert
        variant="info"
        role="region"
        aria-label="표시명 지정 안내"
        title="표시명을 지정하면 목록 필터의 이벤트유형 옵션이 갈립니다"
      >
        목록 화면의 필터는 표시명이 같은 유형을 한 건으로 접어 보여줍니다. 여기서 이름을 지정하면
        그동안 한 건으로 보이던 그룹이 자동으로 쪼개집니다. 표시명을 비워 저장하면 지정이 해제되어
        관제 수신명으로 되돌아갑니다.
      </Alert>

      {/* 목록 조건 — 로딩·오류 중에도 계속 보여야 한다(조건을 바꾸면 새로 조회하므로 로딩이
          뜨는데, 그때 조건이 화면에서 사라지면 방금 켠 것을 되돌릴 수단이 없다). */}
      <div
        className="flex flex-col gap-1 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
        data-testid="event-type-filter"
      >
        <Field orientation="horizontal">
          <Checkbox
            checked={withheldOnly}
            onCheckedChange={(v) => changeFilter(v === true)}
          />
          <FieldLabel>오토라벨 보류만 보기</FieldLabel>
        </Field>
        <p className="text-body-sm text-gray-600">
          프리셋이 없거나 무효인 이벤트유형의 영상은 오토라벨링이 보류됩니다.
        </p>
      </div>

      {error ? (
        <ErrorState message={resolveApiMessage(error, '목록을 불러오지 못했습니다.')} />
      ) : isLoading ? (
        <Skeleton />
      ) : (
        <Card data-testid="event-type-list-card">
          <CardHeader className="grid-cols-[1fr_auto] items-baseline">
            <CardTitle>이벤트유형 목록</CardTitle>
            <p className="text-body-sm text-gray-600">
              {`전체 ${rows.length}개 · 노출 ${shownCount}개 · 숨김 ${hiddenCount}개`}
            </p>
          </CardHeader>
          <CardContent>
            <table className="w-full text-body-md">
              <caption className="sr-only">
                등록된 이벤트유형 전체 목록. 비수집 유형과 제외 대분류에 속한 유형까지 포함합니다.
              </caption>
              <thead>
                {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과 같은 회색을
                    쓰면 열 구조가 먼저 읽히지 않는다. 글자색 gray-600 은 그 위에서 5.60:1 로 AA 를
                    만족한다(gray-500 은 4.01 로 미달).
                    `<tr>` 에는 배경·테두리만 두고 **글자 축은 `<th>`(TH_CLASS)** 가 갖는다. */}
                <tr className="border-b bg-secondary-50">
                  <th className={TH_CLASS}>유형코드</th>
                  <th className={TH_CLASS}>표시명</th>
                  <th className={TH_CLASS}>관제 원본</th>
                  <th className={TH_CLASS}>카테고리</th>
                  <th className={TH_CLASS}>프리셋</th>
                  <th className={TH_CLASS}>수집</th>
                  <th className={TH_CLASS}>관리</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr
                    key={row.evntTypeCd}
                    data-testid={`event-type-row-${row.evntTypeCd}`}
                    className="border-b transition-colors hover:bg-rowHover"
                  >
                    <td className="p-2 font-mono">{row.evntTypeCd}</td>
                    <td className="p-2" data-testid={`event-type-name-${row.evntTypeCd}`}>
                      {editingCode === row.evntTypeCd ? (
                        <input
                          aria-label={`${row.evntTypeCd} 표시명`}
                          className="w-full rounded border border-gray-300 px-2 py-1"
                          maxLength={200}
                          value={draftName}
                          onChange={(e) => setDraftName(e.target.value)}
                        />
                      ) : (
                        <span className="flex items-center gap-2">
                          <span className="min-w-0 truncate">{row.dsplNm}</span>
                          {/* 출처 칩 — 서버가 내려준 dsplNmSource 를 그대로 표기한다.
                              ⚠ 원본 이름 칸(관제 원본·카테고리)을 보고 폴백을 재판정하지 말 것.
                              모르는 값이면 아무 단계로도 추측하지 않고 칩을 생략한다. */}
                          {isDisplayNameSource(row.dsplNmSource) && (
                            <DisplayNameSourceChip source={row.dsplNmSource} />
                          )}
                        </span>
                      )}
                    </td>
                    {/* 관제 원본·카테고리는 읽기 전용 — 표시명이 어디서 왔는지 설명하는 근거다. */}
                    <td className="p-2 text-gray-600">{row.evntNm ?? '-'}</td>
                    <td className="p-2 text-gray-600">{row.evntCtgryNm ?? '-'}</td>
                    {/* 프리셋 연결 상태 — 서버 판정값을 그대로 표기한다.
                        모르는 값·미탑재(구 서버 · 저장 응답)면 어떤 상태로도 추측하지 않고 '-' 로
                        둔다. 저장 직후의 '-' 는 곧 도착할 재조회가 채운다. */}
                    <td className="p-2" data-testid={`event-type-preset-${row.evntTypeCd}`}>
                      {isPresetLinkStatus(row.presetLinkStatus) ? (
                        <PresetLinkStatusChip status={row.presetLinkStatus} />
                      ) : (
                        <span className="text-gray-600">-</span>
                      )}
                    </td>
                    <td className="p-2">
                      {/* 누르면 즉시 반대값으로 저장한다(확인 단계 없음 — 되돌리기가 같은 버튼 한 번).
                          aria-pressed 로 켜짐/꺼짐을 노출한다 — '노출/숨김' 글자와 색만으로는
                          보조기술 사용자가 이것이 토글임을 알 수 없다. */}
                      <button
                        type="button"
                        aria-label={`${row.evntTypeCd} 수집여부 토글`}
                        aria-pressed={row.clctYn === 'Y'}
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
          </CardContent>
        </Card>
      )}

      {/* [@design SCREEN-038] [@design API-219] [@design API-220]
          검증 이벤트 유형·질문 관리 — 위 표(관제 채번 코드 `EV…`)와 <b>코드 체계가 다른 별개 축</b>이다.
          한 목록으로 합치지 않고 나란히 둔다. 화면을 새로 만들지 않고 여기 붙이는 이유는
          관제 이벤트유형↔검증 유형의 짝을 한 화면에서 봐야 하기 때문이다. */}
      <VerificationEventTypeSection />
    </div>
  );
}
