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

/**
 * 데이터셋 영상의 원장 등록 상태 — 소재 준비(`READY`) 뒤에 이어지는 별도 단계. @design API-253
 *
 * ⚠ 소재 준비 완료가 곧 등록 완료가 아니다. 준비 직후에는 `IN_PROGRESS` 일 수 있고 그동안 목록이
 *   완전하지 않다. `DONE` 일 때만 목록을 그대로 믿는다.
 */
export const PortalDatasetVideoRegistrationState = {
  IN_PROGRESS: 'IN_PROGRESS',
  DONE: 'DONE',
  FAILED: 'FAILED',
} as const;
export type PortalDatasetVideoRegistrationState =
  (typeof PortalDatasetVideoRegistrationState)[keyof typeof PortalDatasetVideoRegistrationState];

/** 데이터셋 영상 한 건 — 라벨링으로 들어갈 대상. @design API-253 */
export interface PortalDatasetVideo {
  /** 원장에 등록된 영상 식별자. */
  rawSn: number;
  /** 목록 표시용 영상 이름. */
  videoName: string;
  /** 원장에 등록된 프레임 수. */
  frameCount: number;
  /** 배포본에 실려 온 기존 라벨 건수 — 사용자가 저장한 라벨 수가 아니다. */
  labelCount: number;
  /**
   * 라벨링 화면을 열 프레임. 저장한 라벨이 있으면 마지막 저장 라벨의 프레임, 없으면 첫 프레임이다.
   * 프레임이 없으면 `null` 이고 화면은 진입을 두지 않는다. ★화면이 스스로 프레임을 고르지 않는다.
   */
  entrySrcSn: number | null;
  /** 이 사용자가 마지막으로 저장한 시각. 저장한 적이 없으면 `null` — 저장해야 내 작업에 남는다. */
  lastSavedAt: string | null;
}

/**
 * 데이터셋 영상 등록 실패 사유 — BE `PortalDatasetRegistrationFailureReason` 미러. @design API-253 @design API-262
 *
 * ★ 사유를 문자열 메시지에서 파싱하지 않는다 — 서버가 값으로 보존하는 이유가 그것이다.
 * ⚠ 값역은 서버가 넓힐 수 있다. 응답 타입은 `string` 으로 받고 판정은 모르는 값을 견딘다
 *   (표시 축은 런타임 폴백이 가드다 — 타입은 선언일 뿐 강제가 아니다).
 * ⚠ 어느 값도 경로·표식 파일 이름을 담지 않는다.
 */
export const PortalDatasetRegistrationFailureReason = {
  /** 해제본에 등록할 내용이 없다. */
  CONTENT_MISSING: 'CONTENT_MISSING',
  /** 해제본 안의 심볼릭 링크를 열지 않고 멈췄다(fail-closed). */
  SYMLINK_REJECTED: 'SYMLINK_REJECTED',
  /** 배포본에 영상이 없다. */
  NO_VIDEO: 'NO_VIDEO',
  /** 문서에 영상 파일명이 없다 — 파서가 배포본 스키마를 읽지 못한 경우(2026-09-16 실사고의 사유). */
  VIDEO_FILENAME_MISSING: 'VIDEO_FILENAME_MISSING',
  /** 영상 키가 여럿에 걸린다. */
  AMBIGUOUS_VIDEO_KEY: 'AMBIGUOUS_VIDEO_KEY',
  /** 영상 키 형식이 맞지 않는다. */
  INVALID_VIDEO_KEY: 'INVALID_VIDEO_KEY',
  /** 이미지·문서 짝이 맞지 않는다. */
  PAIR_MISMATCH: 'PAIR_MISMATCH',
  /** 문서를 읽을 수 없다. */
  DOCUMENT_UNREADABLE: 'DOCUMENT_UNREADABLE',
  /** 이미지가 JPEG 가 아니다. */
  IMAGE_NOT_JPEG: 'IMAGE_NOT_JPEG',
  /** 문서의 값이 규격에 맞지 않는다. */
  INVALID_VALUE: 'INVALID_VALUE',
  /** 등록이 배포 설정으로 꺼져 있다 — 운영자 설정. */
  DISABLED: 'DISABLED',
  /** 등록 중 입출력이 실패했다 — 일시 장애. */
  IO_ERROR: 'IO_ERROR',
} as const;
export type PortalDatasetRegistrationFailureReason =
  (typeof PortalDatasetRegistrationFailureReason)[keyof typeof PortalDatasetRegistrationFailureReason];

/** 데이터셋 영상 목록 응답 — 페이지 + 등록 상태. @design API-253 */
export interface PortalDatasetVideoPage {
  /** 서버가 값역을 넓힐 수 있어 문자열로도 받는다 — 모르는 값은 화면이 완료로 읽지 않는다. */
  registrationState: string;
  /**
   * 등록 실패 사유 — `FAILED` 일 때만 값이 있고 그 밖에는 `null`. @design API-253
   * 값역은 `PortalDatasetRegistrationFailureReason` 이나 서버가 넓힐 수 있어 문자열로 받는다.
   */
  registrationFailureReason: string | null;
  content: PortalDatasetVideo[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * 등록 재착수 응답 — 목록 응답의 등록 상태·실패 사유와 **같은 값역**을 쓴다. @design API-262
 *
 * ⚠ 재착수를 접수했건 이미 완료·진행 중이었건 응답 코드는 언제나 200 이고 구분은 이 본문이 싣는다.
 *   재착수가 접수됐을 때 `registrationFailureReason` 은 비어 있다.
 */
export interface PortalDatasetRegistrationResult {
  registrationState: string;
  registrationFailureReason: string | null;
}
