/**
 * "보이지 않는 문자" 를 걷어내 **사람이 실제로 입력한 내용이 있는지** 판정하는 정규화.
 *
 * BE `kr.co.cudo.authoring.common.util.VisibleTextNormalizer` 의 **미러**다. 규칙이 갈라지면
 * "FE 는 통과시켰는데 BE 가 400" 이 되어, 사용자가 막을 수 있었던 400 을 보게 된다.
 *
 * 규칙 (BE 와 동일):
 * 1. **제거** — ISO 제어문자(NUL·개행·탭 등), 카테고리 `Cf`(FORMAT: ZWSP·BOM·WJ·RLO·RLM 등),
 *    `Zl`(LINE_SEPARATOR), `Zp`(PARAGRAPH_SEPARATOR)
 * 2. **일반 공백으로 치환** — 카테고리 `Zs`(NBSP·전각 공백 등). 지우지 않는 이유는 단어 사이의
 *    NBSP 가 구분 의미를 갖기 때문이다(`폭우{NBSP}경보` → `폭우 경보`).
 * 3. 그 뒤 앞뒤 공백 제거. 남는 게 없으면 `null`.
 *
 * 왜 필요한가:
 * - 빈 문자열 판정을 `trim()` 으로만 하면 NBSP·ZWSP·BOM 만 채운 값이 "입력됨" 으로 통과한다.
 *   그 값이 외부 생성형 AI 로 나가면 **빈 조건**이 되어 결과가 비결정적이 된다.
 * - `U+2028`/`U+2029` 는 개행처럼 해석되어 로그 위조(CWE-117) 표면이 되고, `U+202E`(RLO)는
 *   화면에서 텍스트를 역순으로 보이게 해 조건 표시를 위조한다.
 *
 * 코드포인트 단위로 순회하므로 보조 평면 문자(이모지 등)가 쪼개지지 않는다.
 */

/** 카테고리 Cf(FORMAT) + Zl(행 구분) + Zp(문단 구분) — 보이지 않으면서 의미도 없는 문자. */
const FORMAT_OR_SEPARATOR = /[\p{Cf}\p{Zl}\p{Zp}]/u;

/** 카테고리 Zs(SPACE_SEPARATOR) — NBSP·전각 공백 등. 제거가 아니라 일반 공백으로 치환한다. */
const SPACE_SEPARATOR = /\p{Zs}/u;

/**
 * Java `Character.isISOControl` 과 동일 범위(U+0000~U+001F, U+007F~U+009F).
 * 이스케이프 리터럴 대신 코드포인트 비교로 판정한다(소스에 보이지 않는 문자를 남기지 않는다).
 */
function isIsoControl(codePoint: number): boolean {
  return (
    (codePoint >= 0x00 && codePoint <= 0x1f) ||
    (codePoint >= 0x7f && codePoint <= 0x9f)
  );
}

/**
 * 보이지 않는 문자를 제거·치환하고 앞뒤 공백을 다듬는다.
 *
 * @returns 남는 내용이 없으면 `null` (= "입력되지 않음")
 */
export function normalizeVisibleText(raw: string | null | undefined): string | null {
  if (raw === null || raw === undefined) {
    return null;
  }
  let sanitized = '';
  // for...of 는 코드포인트 단위 순회 (서로게이트 페어 보존).
  for (const ch of raw) {
    const codePoint = ch.codePointAt(0) ?? 0;
    if (isIsoControl(codePoint) || FORMAT_OR_SEPARATOR.test(ch)) {
      continue;
    }
    if (SPACE_SEPARATOR.test(ch)) {
      sanitized += ' ';
      continue;
    }
    sanitized += ch;
  }
  const trimmed = sanitized.trim();
  return trimmed.length === 0 ? null : trimmed;
}
