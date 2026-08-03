// 라벨명 표시 규칙 — **라벨 마스터에 등록된 이름을 그대로 쓴다** (2026-08-03 사용자 재확정).
//
// 경위: 직전 커밋(d8a7a2cc)은 이 함수가 COCO 한글 사전 + 레거시 LABEL_CLASS_DEFS 로 라벨명을
//       치환하게 만들었다. 그러나 코드 사전은 라벨 마스터(LS_LABEL)와 어긋나는 **두 번째
//       진실원**이 되고, 사전에 있는 라벨만 한글이라 화면이 오히려 뒤섞인다. 한글로 보이길
//       원하면 운영자가 마스터에서 이름을 한글로 등록하면 된다(마스터 단일 진실원 원칙 정합).
//       → 사전 치환 로직은 전부 제거됐다. 되살리지 말 것.
//
// 함수를 남긴 이유: 치환은 없어졌지만 "표시 지점들이 같은 값을 보여야 한다"는 요구는 유효하다.
//   지우면 각 컴포넌트가 다시 제각기 필드를 고르고 빈값 처리(`?? '-'`, trim 유무)가 갈린다.
//   그래서 규칙은 얇아도 출처는 하나로 둔다 — 표시명 = 마스터 등록명(원문) + 빈값만 '-'.
//
// ★ **표시 전용이다.** 저장/전송되는 라벨 식별자·이름(LS_DATA_LBL 로 가는 className,
//   이벤트 어노테이션 obj_label, SAM2 추적 요청 label 등)은 이 함수를 거치지 않는다.
//   회귀 가드: `__tests__/labelDisplayNameNoPayloadLeak.test.tsx`.

/** 값이 비어있을 때 표시할 문자열. */
const EMPTY_DISPLAY = '-';

/**
 * 라벨 표시명 결정 — 마스터 등록명 그대로.
 *
 * @param rawName 라벨 마스터 이름(`labelName`) 또는 마스터 미연결 라벨의 className 원문.
 *                어느 쪽이든 **그 값 그대로** 표시한다(임의 대체·'미연결' 문구 없음).
 */
export function resolveLabelDisplayName(rawName: string | null | undefined): string {
  const name = (rawName ?? '').trim();
  return name.length === 0 ? EMPTY_DISPLAY : name;
}
