/**
 * 포털 증강 요청 FE 타입 — 목록(API-232) · 단건(API-233) 응답 미러.
 *
 * <h3>요청 상태 값역은 계약이 정하지 않는다</h3>
 * 두 창구 모두 `augSttsCd` 에 대해 *"개별 표시 값은 이 산출물에서 정하지 않으므로 화면은 이 값
 * 자체로 분기하지 말고 `resultReady` 를 쓴다"* 고 못박는다. 그래서 이 파일은 그 값을 **문자열로
 * 나르기만** 하고 어떤 값역도 선언하지 않는다 — enum 을 여기 지어 두면 그 사본이 두 번째
 * 진실원이 되어, 서버가 값을 넓히는 순간 정상 값을 화면이 먼저 막는다.
 *
 * 판정은 {@link ../outcome} 한 곳이 한다(이 파일에 판정 함수를 두지 않는다).
 *
 * @design API-232
 * @design API-233
 * @design SCREEN-044
 */

/**
 * 증강 요청 1행 — 요청 현황 목록(API-232) 응답 원소.
 *
 * ★ **목록 하나로 세 구분이 성립한다.** 행이 `resultReady`(도착 여부)와 `failRsnCn`(실패 사유)을
 *   함께 나르므로, 실패를 가려내려고 행마다 단건 조회를 부르지 않는다.
 *
 * ⚠ **구 서술 폐기.** *"`failRsnCn` 은 이 목록 계약에 없어 목록만으로는 「기다리는 중」과
 *   「실패」가 구분되지 않는다"* 는 **더 이상 사실이 아니다** — 그 공백은 닫혔다(API-232 가 실패
 *   사유를 행에 싣는다). 그 문장을 근거로 목록 판정을 도착 여부 하나로 되돌리지 말 것. 되돌리면
 *   실패한 요청이 「기다리는 중」으로 보이고 재조회가 영영 멎지 않는다.
 *
 * ⚠⚠ 그래도 **판정은 fail-closed 로 남는다** — 타입은 선언일 뿐 강제가 아니고 응답의 원천은
 *   서버라, 이 자리가 실제로 비어 올 수 있다(서버가 아직 그 축을 싣지 않는 배포 형상 등). 그때는
 *   「기다리는 중」으로 읽는다. 없는 값을 근거로 실패를 지어내지 않는다.
 */
export interface PortalAugmentSummary {
  /** 증강 요청 식별자 — 단건 조회가 이 값으로 요청을 가리킨다. */
  augSn: number;
  /** 요청 대상이 된 업로드 영상 자산 식별자. */
  uldSn: number;
  /** 요청 상태 원문. 화면은 이 값으로 분기하지 않는다(위 주석). */
  augSttsCd: string;
  /** 대상 영상의 원본 파일명(표시용). */
  orgnlFileNm: string | null;
  /** 요청 접수 일시. 목록은 이 값의 내림차순으로 정렬돼 온다. */
  requestedAt: string;
  /** 결과 도착 여부 — 화면 분기의 계약상 축이다. */
  resultReady: boolean;
  /** 결과 도착 일시. 기다리는 중이거나 실패한 요청은 비어 있다. */
  resultArrivedAt: string | null;
  /**
   * 요청할 때 지정한 생성 조건 — 접수 시 보관한 값이 그대로 돌아온다.
   *
   * 항목과 값역의 정본은 **접수 창구(API-231)** 이며 다섯 항목·닫힌 값역으로 확정돼 있다. 그럼에도
   * 타입을 열어 두는 이유는 **모르는 항목이 와도 감추지 않기 위해서**다 — 표기 규칙은
   * {@link ./generationCondition} 이 소유한다(아는 다섯은 정해진 차례로 우리말 이름과 함께,
   * 그 밖은 받은 그대로 뒤에 이어 붙인다).
   */
  generationCondition: Record<string, unknown>;
  /**
   * 실패 사유. 실패한 요청에서만 채워지며 기다리는 중이거나 결과가 도착한 요청은 비어 있다.
   *
   * 값이 차 있는지 하나가 실패 판정의 축이다 — 빈 값은 「아직 실패하지 않았다」는 뜻이다.
   * 외부 채널로 그대로 나가는 값이라 내부 오류 코드·경로·제약 이름이 아니라 요청한 사람이 읽을
   * 수 있는 문장이 담긴다(그 제약은 서버가 지킨다).
   */
  failRsnCn: string | null;
}

/** 증강 요청 상세 — 단건 조회(API-233) 응답. */
export interface PortalAugmentDetail {
  augSn: number;
  uldSn: number;
  augSttsCd: string;
  /** 실패 사유. 실패한 요청에서만 채워진다. */
  failRsnCn: string | null;
  orgnlFileNm: string | null;
  requestedAt: string;
  resultReady: boolean;
  /**
   * 증강 결과물을 가리키는 **포털 업로드 자산 식별자**. 기다리는 중이거나 실패한 요청은 비어 있다.
   *
   * ★ 계약이 *"후속 작업 진입과 내려받기 창구가 받는 자산 식별자와 같은 축"* 이라고 못박는다 —
   *   그래서 이 값을 그대로 라벨링 진입 주소(SCREEN-029)와 내려받기 두 창구(API-157·API-159)에
   *   넘긴다. 화면이 자산 식별자를 따로 유추하지 않는다.
   */
  resultUldSn: number | null;
  resultArrivedAt: string | null;
  generationCondition: Record<string, unknown>;
}
