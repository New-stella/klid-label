/**
 * 포털 소재 조달 — FE 타입(BE `portal.dto.PortalMaterials*` · `portal.service.PortalMaterials*` 미러).
 *
 * <h3>이 축이 하는 일</h3>
 * 포털 화면에서 「저작도구로 열기」로 넘어오면 주소의 <b>경로 변수</b>에 데이터셋 숫자 식별자가
 * 실려 온다. 우리 서버가 그 값으로 포털 소재 조회 창구를 부르고, 배포 압축본을 우리 작업영역으로
 * 복사해 푼다. 화면은 그 진행을 보여 주기만 한다.
 *
 * <h3>★ 응답에 파일 경로가 없다 — 지어내지 말 것</h3>
 * 조달처 절대경로와 저장소 루트는 <b>내부 경로</b>라 응답에 담기지 않는다(CWE-209). 화면이
 * 「어디에 풀렸는지」를 말할 근거가 없으므로 그 자리를 만들지 않는다.
 *
 * @design INT-014
 * @design INT-013
 * @design ADR-012
 */

/**
 * 조달 진행 상태 — BE `PortalMaterialsState` 와 값이 한 글자도 다르지 않다.
 *
 * ⚠ 진실원이 축마다 다르다: `READY` 는 <b>파일 시스템</b>이 정하고 `IN_PROGRESS`·`FAILED` 는
 *   <b>그 배포본의 메모리</b>가 갖는다. 즉 서버가 다시 뜨면 뒤의 둘은 사라지고 `NOT_PROVISIONED`
 *   로 보인다 — 화면이 「사라졌다」를 오류로 다루면 안 된다(다시 착수하면 되는 정상 상태다).
 */
export const PortalMaterialsState = {
  /** 조달한 적이 없다(또는 실패 기록이 밀려 사라졌다) — 착수할 수 있다. */
  NOT_PROVISIONED: 'NOT_PROVISIONED',
  /** 조달이 진행 중이다 — 다시 착수해도 새로 시작하지 않는다. */
  IN_PROGRESS: 'IN_PROGRESS',
  /** 해제본이 공개돼 있다 — 다시 착수해도 새로 시작하지 않는다. */
  READY: 'READY',
  /** 직전 조달이 실패했다 — 사유가 함께 실리며 다시 착수할 수 있다. */
  FAILED: 'FAILED',
} as const;
export type PortalMaterialsState =
  (typeof PortalMaterialsState)[keyof typeof PortalMaterialsState];

/**
 * 조달 실패 사유 — BE `PortalMaterialsFailureReason` 미러.
 *
 * ★ <b>사유를 문자열 메시지에서 파싱하지 않는다.</b> 서버가 값으로 보존하는 이유가 그것이다 —
 *   문구가 바뀌는 순간 조용히 오분류된다.
 * ⚠ 어느 값도 경로·키·외부 응답 원문을 담지 않는다.
 */
export const PortalMaterialsFailureReason = {
  /** 조달이 구성되지 않았다(연동 주소 또는 키 미설정). */
  NOT_CONFIGURED: 'NOT_CONFIGURED',
  /** 포털이 거부했다(키 불일치·없는 데이터셋 등 4xx) — 다시 물어도 같다. */
  FETCH_REJECTED: 'FETCH_REJECTED',
  /** 포털 조회가 실패했다(5xx·타임아웃·네트워크) — 잠시 뒤 다시 시도할 수 있다. */
  FETCH_FAILED: 'FETCH_FAILED',
  /** 응답에 배포 압축본이 없다 — 포털 쪽 소재 구성 문제다. */
  NO_DEPLOYMENT_ZIP: 'NO_DEPLOYMENT_ZIP',
  /** 소재 경로가 저장소 루트 밖이거나 열 수 없다 — 열지 않고 멈춘 것이다(fail-closed). */
  MATERIAL_PATH_REJECTED: 'MATERIAL_PATH_REJECTED',
  /** 압축 해제를 거부했다(경로 탈출·항목 수·총량 상한·손상). */
  UNPACK_REJECTED: 'UNPACK_REJECTED',
  /** 복사·해제 중 입출력이 실패했다. */
  IO_ERROR: 'IO_ERROR',
} as const;
export type PortalMaterialsFailureReason =
  (typeof PortalMaterialsFailureReason)[keyof typeof PortalMaterialsFailureReason];

/**
 * 공개된 해제본 요약 — BE `PortalMaterialsSummary` 미러.
 *
 * ⚠ `code`·`variant` 는 <b>비어 올 수 있다</b>(옛 데이터). 비었다고 실패로 다루지 않는다.
 * ⚠ 경로 필드는 없다 — 위 「응답에 파일 경로가 없다」 참조.
 */
export interface PortalMaterialsSummary {
  /** 데이터셋 코드. 비어 올 수 있다. */
  code: string | null;
  /** 데이터셋 버전. */
  version: string | null;
  /** 소재 구분. 비어 올 수 있다. */
  variant: string | null;
  /** 압축 해제 항목 수. */
  entryCount: number;
  /** 압축 해제 총 바이트. */
  totalBytes: number;
  /** 함께 조회된 데이터셋 영상 수(이번 범위에서 내려받지는 않는다). */
  videoCount: number;
  /** 공개 시각(ISO). */
  provisionedAt: string;
}

/**
 * 조달 착수·상태 조회의 응답 — BE `PortalMaterialsStatusResponse` 미러.
 *
 * ⚠ `materials` 는 <b>`READY` 인데도 `null` 일 수 있다</b>(요약 파일을 읽지 못한 경우).
 *   서버가 명시한 성질이며 <b>준비 완료 판정은 그대로다</b> — 요약이 없다고 준비 전으로 되돌리지 말 것.
 */
export interface PortalMaterialsStatus {
  datasetId: number;
  state: PortalMaterialsState;
  /** `FAILED` 일 때만 채워진다. */
  failureReason: PortalMaterialsFailureReason | null;
  /** `READY` 일 때의 해제본 요약. 위 ⚠ 참조. */
  materials: PortalMaterialsSummary | null;
}
