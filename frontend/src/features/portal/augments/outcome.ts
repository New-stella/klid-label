/**
 * 증강 요청의 **귀결 판정** — 화면이 「기다리는 중 / 결과 도착 / 실패」를 가르는 단일 지점.
 *
 * <h3>왜 별도 파일인가</h3>
 * 판정 함수를 통신 모듈(`api.ts`)에 같이 두면, 그 모듈을 통째로 모의하는 시험(`vi.mock('../api')`)
 * 이 **판정 함수까지 `undefined` 로 지운다.** 그러면 화면은 예외 없이 「모르는 값」 분기로 조용히
 * 떨어지는데 작성자는 통신만 막았다고 믿는다. 이 저장소는 그 함정을 실제로 밟은 적이 있어
 * (분류 판정을 통신 모듈에 두었다가 칩 대신 `-` 가 렌더된 사건) **판정을 따로 둔다**.
 *
 * <h3>판정 축</h3>
 * <ul>
 *   <li>계약이 **도착 여부만** 참 값으로 준다(`resultReady`). 요청 상태 원문(`augSttsCd`)의
 *       값역은 계약이 열어 두었고 *"화면은 이 값 자체로 분기하지 말라"* 고 명시하므로
 *       **여기서도 그 값을 읽지 않는다.**</li>
 *   <li>실패는 **사유가 실려 왔을 때만** 실패다(fail-closed). 목록·단건 조회 **둘 다** 그 필드를
 *       나르므로 목록 조회 하나로 세 구분이 선다 — 실패를 가려내려고 행마다 단건 조회를 부르지
 *       않는다.
 *       ⚠ **구 서술 폐기**: *"단건 조회에는 계약상 그 필드가 있고 목록에는 없다 — 목록에서
 *       실패가 대기로 보이는 것은 계약의 공백"* 은 더 이상 사실이 아니다. 그 공백은 닫혔다.</li>
 *   <li>그래도 값이 비어 올 수 있다(타입은 선언일 뿐 강제가 아니다). 그때는 **대기**로 둔다 —
 *       어느 것도 아니면 대기가 이 판정의 기본값이다. 모르는 상태를 도착이나 실패로 단정하면
 *       없는 결과를 있다고 말하거나 아직 진행 중인 요청을 끝난 것으로 접게 된다.</li>
 * </ul>
 *
 * @design SCREEN-044
 * @design API-232
 * @design API-233
 */

/** 요청의 귀결. 화면의 모든 분기(배지·후속 진입·내려받기 노출)가 이 값 하나로 갈린다. */
export type PortalAugmentOutcome = 'ready' | 'failed' | 'waiting';

export interface PortalAugmentOutcomeInput {
  resultReady?: boolean | null;
  /**
   * 실패 사유. 목록·단건 조회 **둘 다** 나른다.
   *
   * 그럼에도 선택으로 두는 것은 **런타임 방어**다 — 서버가 아직 이 축을 싣지 않는 배포 형상에서
   * `undefined` 가 들어와도 판정이 무너지지 않고 대기로 떨어져야 한다.
   */
  failRsnCn?: string | null;
}

/**
 * 요청의 귀결을 정한다.
 *
 * 순서가 계약이다 — 도착이 먼저다. 결과가 이미 도착한 요청에 뒤늦게 사유 문자열이 남아 있어도
 * 그것이 도착 사실을 뒤집지 못한다.
 */
export function resolvePortalAugmentOutcome(row: PortalAugmentOutcomeInput): PortalAugmentOutcome {
  if (row.resultReady === true) return 'ready';
  if (typeof row.failRsnCn === 'string' && row.failRsnCn.trim() !== '') return 'failed';
  return 'waiting';
}

/** 결과가 도착했는가 — 후속 작업 진입·내려받기 노출의 유일한 조건. */
export function isPortalAugmentResultReady(row: PortalAugmentOutcomeInput): boolean {
  return resolvePortalAugmentOutcome(row) === 'ready';
}

/**
 * 귀결의 화면 표기.
 *
 * ★ **상태 표기 값은 계약이 정하지 않는다**(사양이 그렇게 적었다) — 그래서 서버 코드가 아니라
 *   **이 저장소의 기존 표기 관례**를 따른다: 상태 배지는 공용 `StatusBadge` 의 이미 있는 톤 키에
 *   한글 라벨을 함께 넘긴다(포털 업로드 목록이 `UPLOADED → '마킹 대기'` 로 하는 것과 같은 방식).
 *   새 색을 만들지 않으려고 톤 키는 기존 셋(`PENDING`·`COMPLETED`·`FAILED`)을 재사용한다.
 *
 * ⚠ 톤 키는 **서버가 보낸 상태 코드가 아니다** — 우리가 고른 색 축이다. 서버 값을 여기 흘리면
 *   위에서 금지한 「상태 값으로 분기」가 배지 자리에서 되살아난다.
 */
export const PORTAL_AUGMENT_OUTCOME_BADGE: Readonly<
  Record<PortalAugmentOutcome, { badgeStatus: string; label: string }>
> = Object.freeze({
  waiting: Object.freeze({ badgeStatus: 'PENDING', label: '결과 대기 중' }),
  ready: Object.freeze({ badgeStatus: 'COMPLETED', label: '결과 도착' }),
  failed: Object.freeze({ badgeStatus: 'FAILED', label: '실패' }),
});
