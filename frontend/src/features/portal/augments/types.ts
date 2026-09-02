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
 * ⚠ **`failRsnCn` 은 이 목록 계약에 없다.** API-232 의 행 스키마에는 실패 사유가 없고
 *   `resultReady`(도착 여부)와 `resultArrivedAt` 만 있어, **목록만으로는 「기다리는 중」과
 *   「실패」가 구분되지 않는다.** 그런데 화면 사양(SCREEN-044)은 목록 상태 배지가 그 셋을
 *   구분하라고 요구한다 — 계약과 사양이 어긋나 있고, 이 어긋남은 서버가 실패 축을 실어 줘야
 *   닫힌다(보고: `notes_for_main`).
 *   그동안 화면은 **fail-closed** 로 동작한다: 서버가 이 값을 실어 주면 실패로 읽고, 없으면
 *   「기다리는 중」으로 읽는다 — 없는 값을 근거로 실패를 지어내지 않는다.
 *   그래서 이 필드는 **선택**이며, 단건 조회(API-233)에는 계약상 존재한다.
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
   * 요청할 때 지정한 생성 조건. **개별 항목과 값역을 계약이 정하지 않아** 열린 객체로 받는다.
   * 내부 채널 증강의 조건 항목을 옮겨 오지 않는다(API-231 이 명시적으로 금지).
   */
  generationCondition: Record<string, unknown>;
  /** ⚠ 목록 계약에 없다 — 위 주석 참조. 서버가 실어 주면 실패로 읽고, 없으면 대기로 읽는다. */
  failRsnCn?: string | null;
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
