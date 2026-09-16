import { useEffect, useMemo, useState } from 'react';
import { AlertCircle, ChevronDown, ChevronUp, Plus, Trash2 } from 'lucide-react';

import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/common/Card';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/common/Skeleton';
import { Textarea } from '@/components/common/Textarea';
import { resolveApiMessage } from '@/lib/api/resolveApiMessage';
import { useUiStore } from '@/stores/useUiStore';

import type { VerificationEventType } from '../verificationApi';
import {
  useReplaceVerificationEventQuestions,
  useVerificationEventTypes,
} from '../verificationHooks';
import { parseFieldErrorRows, validateQuestionText } from '../verificationQuestionRules';

/**
 * 표 헤더 셀 클래스 — 모든 `<th>` 가 이 한 값을 공유한다(표 표면 규약).
 *
 * ⚠ **반드시 `<th>` 에 직접 건다.** `font-weight` 는 상속되더라도 브라우저 UA 기본
 * `th { font-weight: bold }`(700)가 직접 적용돼 상속값을 이긴다.
 * 굵기는 `text-table-header` step(600)이 단독으로 정하므로 별도 굵기 클래스를 겹치지 않는다.
 * 글자색 하한은 `gray-600` — 헤더 배경 secondary-50 위에서 gray-500 은 AA 미달이다.
 */
const TH_CLASS = 'p-2 text-left text-table-header uppercase tracking-wide text-gray-600';

/** 저장 실패에 쓸 일반 안내 — 서버가 사람이 읽을 문장을 주지 않았을 때의 자리다. */
const SAVE_FAILED_FALLBACK = '저장에 실패했습니다.';

/** 편집 중인 질문 한 줄. 새로 추가한 줄은 아직 서버에 없으므로 일련번호가 없다. */
interface DraftQuestion {
  /** React key 전용 로컬 식별자 — 서버로 보내지 않는다(문구만 보낸다). */
  key: string;
  qstnCn: string;
}

let draftKeySeq = 0;
function newDraftKey(): string {
  draftKeySeq += 1;
  return `draft-${draftKeySeq}`;
}

function toDrafts(type: VerificationEventType | null): DraftQuestion[] {
  return (type?.questions ?? []).map((q) => ({
    key: `q-${q.vrfcEvntQstnSn}`,
    qstnCn: q.qstnCn,
  }));
}

/**
 * 검증 이벤트 유형·질문 관리 절 — 이벤트유형 관리 화면(SCREEN-038)의 두 번째 축.
 * [@design SCREEN-038] [@design API-219] [@design API-220]
 *
 * <h3>왜 이 화면에 붙는가</h3>
 * 외부 시계열 분석의 추가 질문 문장은 사업자 서버가 이벤트별로 관리해 우리가 지정할 수도, 응답으로
 * 받을 수도 없다. 그런데 이벤트 어노테이션의 질문 칸은 채워져야 하므로 문구를 <b>저작도구가 보관</b>
 * 하고 거기서 조달한다(마킹에서 고른 값이 1순위, 없으면 그 유형의 첫 번째).
 * 화면을 새로 만들지 않고 여기 붙이는 이유는 <b>관제 이벤트유형↔검증 유형의 짝</b>을 한 화면에서
 * 봐야 하기 때문이다 — 화면을 나누면 그 짝이 두 화면에 흩어진다.
 *
 * <h3>★두 코드 체계를 한 목록으로 합치지 않는다</h3>
 * 관제 이벤트유형(`EV…`, 위 표)과 검증 이벤트 유형(`fire`·`car_accident`…, 이 표)은 별개 축이다.
 * 나란히 보여줄 뿐이며, 짝은 <b>인입 원장에 실려 온 값을 읽은 것</b>이라 화면이 매핑을 만들지 않는다.
 * 짝이 여러 개일 수 있고 한 건도 없을 수 있다(빈 상태가 정상이다).
 *
 * <h3>★순서가 곧 의미다</h3>
 * 정렬순서 첫 번째가 그 유형의 <b>기본 질문</b>이며 마킹에서 고르지 않았거나 고른 질문이 그 유형에
 * 속하지 않으면 서버가 그 질문으로 교정한다. 그래서 첫 줄에 「기본 질문」 표기를 달아 순서가 의미를
 * 갖는다는 사실을 드러낸다.
 *
 * <h3>★저장은 그 유형의 질문 목록 전체 교체 <b>한 번</b>이다</h3>
 * 추가·수정·삭제·순서 변경이 이 하나로 처리된다. 항목 단위 조작 통로를 따로 두면 여러 요청 사이의
 * 중간 상태에서 「첫 번째」가 흔들려, 그 사이에 조달되는 질문이 운영자가 의도하지 않은 문구가 된다.
 * 서버는 어긋난 문구가 하나라도 있으면 <b>요청 전체를 거부</b>하므로, 화면은 사유만 보여주고
 * 편집 내용을 지우지 않는다.
 *
 * <h3>유형 자체의 등록·수정·삭제 수단은 두지 않는다</h3>
 * 유형은 외부 규격이 정하는 값이며 질문만 운영자가 편집한다.
 *
 * <h3>★입력 판정은 화면이 먼저 하고, 서버 문구는 그대로 내보내지 않는다</h3>
 * 질문 문구가 비었거나 공백뿐이거나 개행·제어문자를 담았거나 길이 상한을 넘으면 <b>저장 창구를
 * 부르기 전에</b> 화면이 막고 사유를 <b>그 질문 줄 옆</b>에 보인다 — 배너 한 줄에 여러 건을
 * 이어 붙이지 않는다(그러면 어느 질문의 무엇을 고쳐야 하는지가 사라진다).
 *
 * <p>서버 검증은 <b>백스톱으로 그대로 남는다</b> — 화면을 거치지 않은 호출은 여전히 거절된다.
 * 화면이 먼저 막는 것과 서버가 막는 것은 서로를 대신하지 않는다.
 *
 * <p>★서버가 거절하더라도 그 <b>응답 문구와 거기 담긴 필드 경로·배열 순번</b>을 사용자에게 그대로
 * 내보내지 않는다. 사용자가 보아야 하는 것은 어느 질문의 무엇을 고쳐야 하는지이며 내부 구조 표기는
 * 그 답이 되지 못한다(발주처 오류 증적 — {@code questions[16].qstnCn: …} 가 화면에 그대로 떴다).
 *
 * 보안: REVIEWER 만 진입(라우트 RoleGuard) + BE `@PreAuthorize` 이중 방어. 문구는 React 기본
 * escape 로 렌더한다(`dangerouslySetInnerHTML` 미사용).
 *
 * @design AC-1129
 * @design AC-1130
 * @design UC-044
 */
export function VerificationEventTypeSection() {
  const { data, isLoading, error } = useVerificationEventTypes();
  const replaceMutation = useReplaceVerificationEventQuestions();
  const pushToast = useUiStore((s) => s.pushToast);

  const types = useMemo(() => data ?? [], [data]);
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<DraftQuestion[]>([]);
  /** 서버가 요청 전체를 거부한 사유. 저장을 다시 누르기 전까지 남긴다. */
  const [saveError, setSaveError] = useState<string | null>(null);
  /**
   * 서버가 <b>필드 단위로</b> 거부한 줄(0부터 세는 자리).
   *
   * 서버가 준 문구가 아니라 <b>자리</b>만 보관한다 — 그 문구에는 필드 경로와 배열 순번이 들어
   * 있어 사용자에게 내부 구조만 보여 주기 때문이다. 사용자에게 보일 말은 화면이 따로 만든다.
   */
  const [serverRejectedRows, setServerRejectedRows] = useState<number[]>([]);

  const selected = types.find((t) => t.vrfcEvntTypeCd === selectedCode) ?? null;

  // 목록이 도착하면 첫 유형을 편다. 고르고 있던 유형이 사라지면 다시 첫 유형으로 되돌린다.
  //   ⚠ 이미 고른 유형이 그대로 있으면 건드리지 않는다 — 재조회마다 선택이 튀면 편집 중인
  //     질문 목록이 통째로 날아간다.
  useEffect(() => {
    setSelectedCode((prev) =>
      prev !== null && types.some((t) => t.vrfcEvntTypeCd === prev)
        ? prev
        : (types[0]?.vrfcEvntTypeCd ?? null),
    );
  }, [types]);

  // 편집 대상이 바뀌면 그 유형의 저장된 목록으로 편집본을 초기화한다.
  //   ★ 의존성은 「선택한 유형의 질문 목록」이라 다른 유형의 저장이 이 편집본을 건드리지 않는다.
  useEffect(() => {
    setDrafts(toDrafts(selected));
    setSaveError(null);
    setServerRejectedRows([]);
    // selected 는 파생값이라 참조가 매 렌더 바뀔 수 있다 — 실제 축인 코드와 질문 목록만 본다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedCode, selected?.questions]);

  /**
   * 편집이 일어나면 <b>서버 거부 표시를 지운다</b>.
   *
   * 그 표시는 「보낸 그 배열의 그 자리」에 대한 서버의 판정이라, 값을 고치거나 줄을 더하고 빼고
   * 순서를 바꾸는 순간 가리키는 대상이 달라진다. 남겨 두면 엉뚱한 줄이 잘못됐다고 표시된다.
   */
  const mutateDrafts = (next: (prev: DraftQuestion[]) => DraftQuestion[]) => {
    setDrafts(next);
    setServerRejectedRows([]);
    setSaveError(null);
  };

  const updateDraft = (index: number, qstnCn: string) => {
    mutateDrafts((prev) => prev.map((d, i) => (i === index ? { ...d, qstnCn } : d)));
  };

  const addDraft = () => {
    mutateDrafts((prev) => [...prev, { key: newDraftKey(), qstnCn: '' }]);
  };

  const removeDraft = (index: number) => {
    mutateDrafts((prev) => prev.filter((_, i) => i !== index));
  };

  /** `index` 줄을 `delta` 만큼 옮긴다. 경계를 벗어나면 아무것도 하지 않는다. */
  const moveDraft = (index: number, delta: -1 | 1) => {
    mutateDrafts((prev) => {
      const next = index + delta;
      if (next < 0 || next >= prev.length) return prev;
      const copy = [...prev];
      [copy[index], copy[next]] = [copy[next], copy[index]];
      return copy;
    });
  };

  /**
   * 줄마다의 선판정 — 저장 창구를 부르기 <b>전에</b> 화면이 먼저 본다.
   *
   * 판정 규칙(길이 상한·허용 문자)은 저장 창구 계약을 옮겨 놓은 한 곳이 소유하며 이 화면은
   * 상한 숫자도 문자 집합도 따로 갖지 않는다 — 양쪽에 적으면 한쪽만 고쳐진다.
   */
  const rowViolations = useMemo(() => drafts.map((d) => validateQuestionText(d.qstnCn)), [drafts]);
  const hasInvalidRow = rowViolations.some((v) => v !== null);

  /**
   * 그 줄 옆에 보일 사유 — 화면 선판정이 1순위, 서버가 그 자리를 짚어 준 것이 2순위다.
   *
   * ★서버가 준 <b>문구</b>는 쓰지 않는다. 그 문구에는 필드 경로와 배열 순번이 들어 있어
   *   사용자에게 내부 구조만 보여 준다(발주처 오류 증적). 자리만 받아 쓰고 말은 화면이 만든다.
   */
  const rowMessage = (index: number): string | null => {
    const violation = rowViolations[index];
    if (violation) return violation.message;
    if (serverRejectedRows.includes(index)) {
      return '이 질문은 저장할 수 없습니다. 문구를 다시 확인하세요.';
    }
    return null;
  };

  /**
   * 저장 실패를 화면 문구로 바꾼다 — ★<b>서버 응답 문구를 그대로 내보내지 않는다</b>.
   *
   * 같은 400 이라도 서버가 주는 모양이 둘이고, 둘을 섞으면 한쪽이 망가진다.
   * <ul>
   *   <li><b>필드 단위 검증 실패 모음</b> — {@code questions[16].qstnCn: …} 꼴. 그대로 띄우면
   *       사용자는 자기가 몇 번째 질문의 무엇을 고쳐야 하는지 알 수 없고 내부 구조만 본다.
   *       그래서 자리만 뽑아 <b>사람이 읽는 위치 표기</b>로 바꾸고 해당 줄을 짚는다.</li>
   *   <li><b>사람이 읽는 단일 안내</b> — 서비스 2차 방어선이 주는 「3번째 질문 문구가 …」처럼
   *       이미 위치가 사람 말로 적힌 문장이다. 이건 그대로 보여준다 — 감추면 사용자가 사유를
   *       잃는다.</li>
   * </ul>
   */
  const applySaveFailure = (e: unknown) => {
    const raw = resolveApiMessage(e, SAVE_FAILED_FALLBACK);
    const rejectedRows = parseFieldErrorRows(raw);

    if (rejectedRows === null) {
      setServerRejectedRows([]);
      setSaveError(raw);
      pushToast({ variant: 'error', message: raw });
      return;
    }

    setServerRejectedRows(rejectedRows);
    const message =
      rejectedRows.length > 0
        ? `${rejectedRows.map((row) => `${row + 1}번째`).join(', ')} 질문의 문구를 고쳐 주세요.`
        : SAVE_FAILED_FALLBACK;
    setSaveError(message);
    pushToast({ variant: 'error', message });
  };

  const submit = async () => {
    if (!selected) return;
    // ★한 줄이라도 선판정을 통과하지 못하면 <b>요청 자체를 보내지 않는다</b>. 저장 단추도 잠겨
    //   있지만 그것은 표시이고, 요청을 막는 것은 이 가드다. 전체 교체라 부분 반영이 없으므로
    //   어긋난 줄 하나가 목록 전체의 저장을 막는 것이 맞다.
    if (hasInvalidRow) return;
    setSaveError(null);
    setServerRejectedRows([]);
    try {
      // 배열 순서가 곧 정렬순서다 — 서버가 그 순서로 순번을 매긴다.
      // 문구만 보낸다(일련번호·정렬순서·감사 컬럼은 요청 대상이 아니다 — Mass Assignment 방어).
      await replaceMutation.mutateAsync({
        vrfcEvntTypeCd: selected.vrfcEvntTypeCd,
        questions: drafts.map((d) => ({ qstnCn: d.qstnCn })),
      });
      pushToast({ variant: 'success', message: '질문 목록을 저장했습니다.' });
    } catch (e) {
      // 서버가 요청 전체를 거부했다 — 편집 내용을 지우지 않고 사유만 보여준다.
      applySaveFailure(e);
    }
  };

  const cancel = () => {
    setDrafts(toDrafts(selected));
    setSaveError(null);
    setServerRejectedRows([]);
  };

  if (isLoading) return <Skeleton />;
  if (error) {
    return (
      <ErrorState
        message={resolveApiMessage(error, '검증 이벤트 유형을 불러오지 못했습니다.')}
      />
    );
  }

  return (
    <Card data-testid="verification-event-type-card">
      <CardHeader>
        <CardTitle>검증 이벤트 유형·질문</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-body-sm text-gray-600">
          외부 시계열 분석에 쓰는 검증 이벤트 유형과 유형별 질문 문구를 관리합니다. 유형 자체는
          추가·삭제할 수 없고 질문만 편집합니다.
        </p>

        {types.length === 0 ? (
          <EmptyState
            title="등록된 검증 이벤트 유형이 없습니다"
            message="검증 이벤트 유형은 외부 연동 규격이 정하는 값이라 이 화면에서 추가할 수 없습니다."
          />
        ) : (
          <>
            <table className="w-full text-body-md">
              <caption className="sr-only">
                등록된 검증 이벤트 유형 목록. 행을 고르면 아래에서 그 유형의 질문을 편집합니다.
              </caption>
              <thead>
                {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과 같은
                    회색을 쓰면 열 구조가 먼저 읽히지 않는다. 글자 축은 `<th>`(TH_CLASS)가 갖는다. */}
                <tr className="border-b bg-secondary-50">
                  <th className={TH_CLASS}>정렬순서</th>
                  <th className={TH_CLASS}>검증 유형 코드</th>
                  <th className={TH_CLASS}>유형명</th>
                  <th className={TH_CLASS}>설명</th>
                  <th className={TH_CLASS}>관제 이벤트유형 코드</th>
                  <th className={TH_CLASS}>질문 수</th>
                </tr>
              </thead>
              <tbody>
                {types.map((t) => {
                  const isSelected = t.vrfcEvntTypeCd === selectedCode;
                  return (
                    <tr
                      key={t.vrfcEvntTypeCd}
                      data-testid={`vrfc-event-type-row-${t.vrfcEvntTypeCd}`}
                      className="border-b transition-colors hover:bg-rowHover"
                    >
                      <td className="p-2 text-gray-600">{t.sortSeq ?? '-'}</td>
                      <td className="p-2">
                        {/* 행 선택은 버튼으로 노출한다 — 행 전체 클릭은 보조기술에 조작 가능
                            요소로 드러나지 않고 키보드로도 닿지 않는다. */}
                        <button
                          type="button"
                          aria-pressed={isSelected}
                          onClick={() => setSelectedCode(t.vrfcEvntTypeCd)}
                          className={`rounded px-1 font-mono underline-offset-2 hover:underline ${
                            isSelected ? 'font-semibold text-primary-700' : 'text-gray-800'
                          }`}
                        >
                          {t.vrfcEvntTypeCd}
                        </button>
                      </td>
                      <td className="p-2">{t.vrfcEvntTypeNm}</td>
                      <td className="p-2 text-gray-600">{t.vrfcEvntTypeExpln ?? '-'}</td>
                      {/* 관제 짝 — 인입 원장에서 읽은 값 그대로. 여러 건이면 모두 나열하고
                          한 건도 없으면 '짝 없음'이라고 사실대로 적는다('-' 로 적으면
                          "값을 못 받았다"와 "짝이 없다"가 구분되지 않는다). */}
                      <td className="p-2 text-gray-600">
                        {t.evntTypeCds.length === 0 ? (
                          <span className="text-gray-600">짝 없음</span>
                        ) : (
                          <span className="font-mono">{t.evntTypeCds.join(', ')}</span>
                        )}
                      </td>
                      <td className="p-2 text-gray-600">{t.questions.length}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>

            {selected && (
              <section
                aria-labelledby="vrfc-question-editor-heading"
                className="space-y-3 rounded border border-gray-200 p-3"
                data-testid="vrfc-question-editor"
              >
                <h3 id="vrfc-question-editor-heading" className="font-medium text-gray-800">
                  {`${selected.vrfcEvntTypeNm} (${selected.vrfcEvntTypeCd}) 질문 목록`}
                </h3>

                {/* 저장 자체가 거부된 사실을 알리는 자리다. ★여러 줄이 한꺼번에 어긋났을 때
                    사유를 여기 <b>한 줄로 이어 붙이지 않는다</b> — 그러면 어느 질문의 무엇을
                    고쳐야 하는지가 사라진다. 사유는 각 줄 옆이 맡고 여기는 위치만 가리킨다. */}
                {saveError && (
                  <Alert
                    variant="error"
                    title="질문 문구를 저장할 수 없습니다"
                    data-testid="vrfc-question-save-error"
                  >
                    {saveError}
                  </Alert>
                )}

                {drafts.length === 0 ? (
                  <EmptyState
                    title="등록된 질문이 없습니다"
                    message="질문을 추가하지 않으면 이 유형의 어노테이션 질문 칸은 비어 있습니다."
                    className="py-6"
                  />
                ) : (
                  <ol className="space-y-3">
                    {drafts.map((d, i) => (
                      <li key={d.key} className="space-y-1">
                        <div className="flex items-center gap-2">
                          <span className="text-caption text-gray-500">{`${i + 1}번째`}</span>
                          {/* 「기본 질문」 표기는 첫 줄에만 — 순서를 바꾸면 표기도 따라 옮겨간다. */}
                          {i === 0 && (
                            <span className="rounded-full bg-primary-50 px-2 py-0.5 text-label font-semibold text-primary-700">
                              기본 질문
                            </span>
                          )}
                          <span className="ml-auto flex items-center gap-1">
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              aria-label={`${i + 1}번째 질문 순서 올리기`}
                              disabled={i === 0 || replaceMutation.isPending}
                              onClick={() => moveDraft(i, -1)}
                            >
                              <ChevronUp className="h-4 w-4" aria-hidden />
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              aria-label={`${i + 1}번째 질문 순서 내리기`}
                              disabled={i === drafts.length - 1 || replaceMutation.isPending}
                              onClick={() => moveDraft(i, 1)}
                            >
                              <ChevronDown className="h-4 w-4" aria-hidden />
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon-sm"
                              aria-label={`${i + 1}번째 질문 삭제`}
                              disabled={replaceMutation.isPending}
                              onClick={() => removeDraft(i)}
                            >
                              <Trash2 className="h-4 w-4" aria-hidden />
                            </Button>
                          </span>
                        </div>
                        <Textarea
                          aria-label={`${i + 1}번째 질문 문구`}
                          // 오류는 시각(테두리)만이 아니라 보조기술에도 닿아야 한다 — 이 두 속성이
                          // 없으면 화면에는 빨간 글씨가 있는데 스크린리더 사용자는 왜 막혔는지 모른다.
                          aria-invalid={rowMessage(i) ? true : undefined}
                          aria-describedby={rowMessage(i) ? `${d.key}-error` : undefined}
                          value={d.qstnCn}
                          disabled={replaceMutation.isPending}
                          placeholder="영상에서 '<이벤트>' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?"
                          onChange={(e) => updateDraft(i, e.target.value)}
                        />
                        {/* ★사유는 <b>그 질문 줄 옆</b>에 붙는다. 어느 질문인지는 자리가 말하므로
                            문구에 순번이나 내부 표기를 적지 않는다. */}
                        {rowMessage(i) && (
                          <p
                            id={`${d.key}-error`}
                            role="alert"
                            data-testid={`vrfc-question-error-${i}`}
                            className="flex items-center gap-1 text-caption text-danger-700"
                          >
                            <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                            {rowMessage(i)}
                          </p>
                        )}
                      </li>
                    ))}
                  </ol>
                )}

                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    variant="secondary"
                    leftIcon={Plus}
                    disabled={replaceMutation.isPending}
                    onClick={addDraft}
                  >
                    질문 추가
                  </Button>
                  <span className="ml-auto flex items-center gap-2">
                    <Button
                      variant="secondary"
                      disabled={replaceMutation.isPending}
                      onClick={cancel}
                    >
                      취소
                    </Button>
                    {/* 어긋난 줄이 있으면 잠근다 — 누르기 <b>전에</b> 막히는 것이 보여야 한다.
                        실제로 요청을 막는 것은 `submit` 의 가드다(표시와 차단은 다른 축). */}
                    <Button
                      loading={replaceMutation.isPending}
                      disabled={hasInvalidRow}
                      onClick={() => void submit()}
                    >
                      질문 목록 저장
                    </Button>
                  </span>
                </div>
                <p className="text-caption text-gray-500">
                  저장하면 이 유형의 질문 목록이 화면에 보이는 순서 그대로 통째로 교체됩니다. 첫
                  번째 질문이 이 유형의 기본 질문으로 쓰입니다.
                </p>
              </section>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}
