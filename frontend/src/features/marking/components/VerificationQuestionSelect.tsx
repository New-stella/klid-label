import { KRDS_FOCUS } from '@/lib/focusRing';
import type { VrfcEvntQuestion } from '@/features/video/types';

const SELECT_ID = 'marking-vrfc-question';
const FULL_TEXT_ID = 'marking-vrfc-question-full';

/**
 * 마킹과 함께 저장할 **검증 질문**을 고르는 영역. [@design SCREEN-006] [@design API-047]
 *
 * 저작도구가 검증 이벤트 유형별로 질문 문구를 보관하고, 마킹 작업자가 그중 하나를 고른다.
 * 고른 값은 마킹 등록 요청에 실려 저장되고, 이벤트 어노테이션의 질문 칸으로 조달된다.
 *
 * <h3>★ 고른 질문은 기록에만 남지 않는다 — 외부로 나간다</h3>
 * 추가 질문 축의 위탁 창구가 바뀌면서 <b>고른 질문 문구가 외부 시계열 위탁 요청 본문에 그대로
 * 실려 나간다</b>. 종전에는 사업자 서버가 질문을 관리해 우리가 지정할 수 없었고 우리 선택은
 * 기록용이었다 — 그래서 「보낸 질문」과 「기록된 질문」이 갈릴 수 있는 것이 인지·수용한 위험이었다.
 * 이제 그 자리가 생겨 둘이 같아졌으므로, 작업자에게 <b>자기 선택이 외부로 나가는 값</b>임을
 * 화면이 알린다(확정 사양). ⚠ 이 안내를 「기록용」으로 되돌리지 말 것.
 *
 * <h3>고를 것이 없으면 영역 자체를 렌더하지 않는다</h3>
 * 그 영상에 검증 이벤트 유형이 없거나 그 유형에 등록된 질문이 0건이면 목록이 빈 배열로 온다.
 * 빈 드롭다운을 띄우면 "고를 수 있는데 비어 있다"로 읽혀 작업자가 없는 값을 찾게 된다.
 * ⚠ <b>질문이 없다고 마킹이 막히지는 않는다</b> — 어노테이션의 질문 칸이 비는 것뿐이다.
 *
 * <h3>기본 선택은 「첫 번째」이며 순서는 서버가 정한다</h3>
 * 목록은 정렬순서 오름차순으로 내려오므로 <b>다시 정렬하지 않는다</b>. 정렬을 화면이 재유도하면
 * 화면이 보여준 「기본」과 서버가 교정에 쓰는 「첫 번째」가 조용히 어긋난다.
 *
 * <h3>서버 교정을 전제로 한 입력이다</h3>
 * 선택값이 비었거나 그 유형에 속하지 않으면 서버가 <b>거부가 아니라 첫 번째로 교정</b>한다.
 * ★ 그리고 <b>교정 결과를 응답으로 돌려주지 않는다</b> — 화면에 「교정됨」을 표시할 근거가 없으므로
 * 그런 표시를 만들지 말 것(없는 값을 지어내는 것이 된다).
 *
 * <h3>왜 공통 Select 가 아니라 네이티브 select 인가</h3>
 * 마킹 화면은 <b>전역 keydown</b> 으로 Space(마킹 추가)·Enter(제출)·Delete(삭제)를 받는다. 그 핸들러는
 * 포커스 엘리먼트가 INPUT·TEXTAREA·<b>SELECT</b> 일 때만 비켜서는데, Radix 기반 공통 Select 의
 * 트리거는 <b>button</b> 이라 그 예외에 걸리지 않는다 — 드롭다운을 Space 로 열면 마킹이 하나 추가되고
 * Enter 로 옵션을 고르면 마킹이 통째로 제출된다. 네이티브 select 는 그 예외로 이미 보호된다.
 */
export interface VerificationQuestionSelectProps {
  /** 그 영상의 검증 이벤트 질문 목록 — 정렬순서 오름차순(BE 가 이미 정렬해 내려준다). */
  questions: VrfcEvntQuestion[];
  /** 현재 선택된 질문 일련번호. 고를 것이 없으면 null. */
  value: number | null;
  onChange: (vrfcEvntQstnSn: number) => void;
  disabled?: boolean;
}

export function VerificationQuestionSelect({
  questions,
  value,
  onChange,
  disabled,
}: VerificationQuestionSelectProps) {
  // 고를 것이 없으면 영역 자체를 노출하지 않는다(빈 드롭다운은 오안내다).
  if (questions.length === 0) return null;

  const selected = questions.find((q) => q.vrfcEvntQstnSn === value) ?? null;

  return (
    <section
      aria-labelledby="marking-vrfc-question-heading"
      className="space-y-2 rounded border p-3 text-body-md"
      data-testid="verification-question-section"
    >
      <h3 id="marking-vrfc-question-heading" className="font-medium text-gray-700">
        검증 질문
      </h3>
      <label htmlFor={SELECT_ID} className="block text-caption text-gray-500">
        마킹과 함께 저장할 질문입니다. 고르지 않으면 첫 번째 질문이 쓰입니다.
      </label>
      <select
        id={SELECT_ID}
        className={`w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-body ${KRDS_FOCUS}`}
        // 목록이 비어 있지 않은 한 value 는 항상 실제 옵션 하나를 가리킨다(부모가 첫 번째로 맞춘다).
        // 아직 맞춰지기 전 한 프레임을 대비해 빈 문자열로 떨어뜨린다 — 그래야 브라우저가 임의의
        // 옵션을 골라 보여주고 상태는 다른 값인 불일치가 생기지 않는다.
        value={value === null ? '' : String(value)}
        aria-describedby={selected ? FULL_TEXT_ID : undefined}
        disabled={disabled}
        onChange={(e) => {
          const next = Number(e.target.value);
          if (Number.isFinite(next)) onChange(next);
        }}
      >
        {questions.map((q) => (
          <option key={q.vrfcEvntQstnSn} value={String(q.vrfcEvntQstnSn)}>
            {q.qstnCn}
          </option>
        ))}
      </select>
      {/* 드롭다운에서 잘릴 수 있는 긴 문구를 그대로 확인할 수 있게 전문을 병기한다. */}
      {selected && (
        <p id={FULL_TEXT_ID} className="whitespace-pre-wrap text-body-md text-gray-700">
          {selected.qstnCn}
        </p>
      )}
      {/* ★ 작업자의 선택이 기록에 머무르지 않고 외부로 나가는 값임을 알린다(확정 사양).
          드롭다운 아래에 함께 노출한다 — 고른 뒤가 아니라 <b>고르기 전에</b> 보여야 판단에 쓰인다. */}
      <p className="text-caption text-gray-500">
        고른 질문 문구는 외부 시계열 위탁 요청에 그대로 실려 나갑니다.
      </p>
    </section>
  );
}
