import { KRDS_FOCUS } from '@/lib/focusRing';
import type { SelectableVrfcEvntType } from '@/features/video/types';

const SELECT_ID = 'marking-vrfc-event-type';
const HINT_ID = 'marking-vrfc-event-type-hint';

/**
 * 마킹과 함께 저장할 **검증 이벤트 유형**을 고르는 영역. [@design SCREEN-006] [@design API-047]
 *
 * <h3>관제 값이 없을 때만 노출한다 (구속)</h3>
 * 관제 인입이 검증 이벤트 유형을 보낸 영상에서는 이 선택을 <b>아예 띄우지 않는다</b>. 서버가 그런
 * 영상에 <b>빈 목록</b>을 내려 주므로, 화면은 목록이 비었는지만 보고 판정한다.
 * ⚠ 「표시하되 잠근다」가 <b>아니다</b> — 그 안은 검토 후 채택되지 않았다. 되살리지 말 것.
 * 노출 자체를 가르므로 작업자 선택과 관제 값이 맞붙는 상태가 <b>구조적으로 생기지 않는다</b>.
 *
 * <h3>왜 필요한가</h3>
 * 관제가 유형을 보내지 않은 영상은 <b>두 가지를 함께 잃는다</b> — ①질문 목록이 유형으로 조회되므로
 * 비어서 질문을 고를 수 없고 ②외부 시계열 위탁의 묘사 축도 유형이 없어 사업자가 거부한다.
 * 즉 그 영상은 시계열 메타를 하나도 받지 못한다. 작업자가 유형을 고르면 그 둘이 함께 풀린다.
 *
 * <h3>기본값을 미리 고르지 않는다</h3>
 * 질문 선택과 달리 첫 항목을 기본 선택해 두지 않는다. 유형은 <b>영상 내용을 보고 사람이 판단할
 * 값</b>이라, 미리 골라 두면 작업자가 확인하지 않은 유형이 그대로 저장되고 그 값이 외부 위탁까지
 * 나간다. 「고르지 않음」이 화면에 드러나야 고르라고 말할 수 있다.
 *
 * <h3>왜 공통 Select 가 아니라 네이티브 select 인가</h3>
 * 질문 선택과 <b>같은 이유</b>다 — 마킹 화면의 전역 keydown 은 포커스가 INPUT·TEXTAREA·<b>SELECT</b>
 * 일 때만 비켜서는데, Radix 기반 공통 Select 의 트리거는 button 이라 그 예외에 걸리지 않는다.
 * 드롭다운을 Space 로 열면 마킹이 하나 추가되고 Enter 로 고르면 마킹이 통째로 제출된다.
 */
export interface VerificationEventTypeSelectProps {
  /** 고를 수 있는 유형 목록 — 정렬순서 오름차순(BE 가 이미 정렬해 내려준다). 비면 렌더하지 않는다. */
  types: SelectableVrfcEvntType[];
  /** 현재 고른 유형 코드. 아직 고르지 않았으면 null. */
  value: string | null;
  onChange: (vrfcEvntTypeCd: string) => void;
  disabled?: boolean;
}

export function VerificationEventTypeSelect({
  types,
  value,
  onChange,
  disabled,
}: VerificationEventTypeSelectProps) {
  // 목록이 비었다 = 관제 값이 있다(또는 등록된 유형이 없다). 어느 쪽이든 고를 것이 없으므로
  // 영역 자체를 노출하지 않는다 — 빈 드롭다운은 "고를 수 있는데 비어 있다"로 읽힌다.
  if (types.length === 0) return null;

  return (
    <section
      aria-labelledby="marking-vrfc-event-type-heading"
      className="space-y-2 rounded border p-3 text-body-md"
      data-testid="verification-event-type-section"
    >
      <h3 id="marking-vrfc-event-type-heading" className="font-medium text-gray-700">
        검증 이벤트 유형
      </h3>
      <label htmlFor={SELECT_ID} className="block text-caption text-gray-500">
        이 영상의 검증 이벤트 유형을 골라 주세요.
      </label>
      <select
        id={SELECT_ID}
        className={`w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-body ${KRDS_FOCUS}`}
        value={value ?? ''}
        aria-describedby={HINT_ID}
        // 고르지 않은 상태가 유효하지 않음을 보조기술에도 알린다(제출은 호출부가 막고 사유를
        // 토스트로 말한다 — 버튼을 죽이지 않는 이 화면의 확정 사양과 같은 축이다).
        aria-invalid={value === null ? true : undefined}
        disabled={disabled}
        onChange={(e) => {
          const next = e.target.value;
          if (next !== '') onChange(next);
        }}
      >
        {/* 「고르지 않음」을 실제 옵션으로 둔다 — 두지 않으면 브라우저가 첫 항목을 보여주는데
            상태는 null 이라, 화면에 보이는 유형과 저장될 값이 다른 상태가 된다. */}
        <option value="">유형을 선택하세요</option>
        {types.map((t) => (
          <option key={t.vrfcEvntTypeCd} value={t.vrfcEvntTypeCd}>
            {t.vrfcEvntTypeNm}
          </option>
        ))}
      </select>
      <p id={HINT_ID} className="text-caption text-gray-500">
        관제에서 유형을 받지 못한 영상이라 직접 골라야 합니다. 고른 유형은 마킹과 함께 저장되고,
        외부 시계열 분석 위탁에 그대로 쓰입니다.
      </p>
    </section>
  );
}
