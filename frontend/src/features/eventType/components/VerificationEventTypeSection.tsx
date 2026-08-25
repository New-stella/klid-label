import { useEffect, useMemo, useState } from 'react';
import { ChevronDown, ChevronUp, Plus, Trash2 } from 'lucide-react';

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

/**
 * 표 헤더 셀 클래스 — 모든 `<th>` 가 이 한 값을 공유한다(표 표면 규약).
 *
 * ⚠ **반드시 `<th>` 에 직접 건다.** `font-weight` 는 상속되더라도 브라우저 UA 기본
 * `th { font-weight: bold }`(700)가 직접 적용돼 상속값을 이긴다.
 * 굵기는 `text-table-header` step(600)이 단독으로 정하므로 별도 굵기 클래스를 겹치지 않는다.
 * 글자색 하한은 `gray-600` — 헤더 배경 secondary-50 위에서 gray-500 은 AA 미달이다.
 */
const TH_CLASS = 'p-2 text-left text-table-header uppercase tracking-wide text-gray-600';

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
 * 보안: REVIEWER 만 진입(라우트 RoleGuard) + BE `@PreAuthorize` 이중 방어. 문구는 React 기본
 * escape 로 렌더한다(`dangerouslySetInnerHTML` 미사용).
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
    // selected 는 파생값이라 참조가 매 렌더 바뀔 수 있다 — 실제 축인 코드와 질문 목록만 본다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedCode, selected?.questions]);

  const updateDraft = (index: number, qstnCn: string) => {
    setDrafts((prev) => prev.map((d, i) => (i === index ? { ...d, qstnCn } : d)));
  };

  const addDraft = () => {
    setDrafts((prev) => [...prev, { key: newDraftKey(), qstnCn: '' }]);
  };

  const removeDraft = (index: number) => {
    setDrafts((prev) => prev.filter((_, i) => i !== index));
  };

  /** `index` 줄을 `delta` 만큼 옮긴다. 경계를 벗어나면 아무것도 하지 않는다. */
  const moveDraft = (index: number, delta: -1 | 1) => {
    setDrafts((prev) => {
      const next = index + delta;
      if (next < 0 || next >= prev.length) return prev;
      const copy = [...prev];
      [copy[index], copy[next]] = [copy[next], copy[index]];
      return copy;
    });
  };

  const submit = async () => {
    if (!selected) return;
    setSaveError(null);
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
      const message = resolveApiMessage(e, '저장에 실패했습니다.');
      setSaveError(message);
      pushToast({ variant: 'error', message });
    }
  };

  const cancel = () => {
    setDrafts(toDrafts(selected));
    setSaveError(null);
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
                          <span className="text-gray-400">짝 없음</span>
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
                className="space-y-3 rounded border p-3"
                data-testid="vrfc-question-editor"
              >
                <h3 id="vrfc-question-editor-heading" className="font-medium text-gray-800">
                  {`${selected.vrfcEvntTypeNm} (${selected.vrfcEvntTypeCd}) 질문 목록`}
                </h3>

                {saveError && (
                  <Alert variant="error" title="질문 문구를 저장할 수 없습니다">
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
                          value={d.qstnCn}
                          disabled={replaceMutation.isPending}
                          placeholder="영상에서 '<이벤트>' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?"
                          onChange={(e) => updateDraft(i, e.target.value)}
                        />
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
                    <Button loading={replaceMutation.isPending} onClick={() => void submit()}>
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
