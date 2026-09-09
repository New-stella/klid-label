import { z } from 'zod';

/**
 * 시스템 설정 zod 스키마 (Critical 보안 — 입력 검증).
 *
 * 보안: 사용자 입력은 zod로 1차 검증한 후 BE에 전달.
 * BE는 서버 측 재검증 (이중 방어).
 */

/** 건너뛰기 사유 길이 상한 — 단건·일괄 건너뛰기 사유(`SKIP_REASON_MAX`)와 같은 축이라 값을 맞춘다. */
export const VLM_SKIP_REASON_MAX = 500;

/**
 * ADR-050 — 시계열 위탁 **전체 건너뛰기**.
 *
 * ⚠ 필드 이름에 점(.)을 쓰지 않는다 — BE 키는 `batch.vlm.skip-by-default` 처럼 dotted 인데
 * react-hook-form 은 점을 **중첩 객체 경로**로 해석한다(비식별·연동 주소 카드와 같은 이유).
 * 전송 시점에만 실제 dotted 키로 매핑한다.
 *
 * ★ **사유는 스위치를 켤 때 필수**다. 이 문구가 건너뜀 표식의 사유로 그대로 기록되어, 나중에 그
 * 영상의 시계열이 왜 비어 있는지 되짚는 유일한 근거가 된다. 서버도 400 으로 막지만 화면에서 먼저
 * 막아, 다 적고 저장한 뒤에야 거부를 알게 되는 동선을 없앤다.
 *
 * ★ 반대로 **끄는 것은 사유와 무관하게 언제나 가능**해야 한다 — 사유 검증이 끄는 길까지 막으면
 * 켜진 채로 고착되고, 그동안 들어오는 영상은 전건이 시계열 없이 확정된다.
 */
export const batchConfigSchema = z
  .object({
    BATCH_INTERVAL_SEC: z
      .number({ invalid_type_error: '숫자를 입력해주세요' })
      .int('정수만 허용')
      .min(10, '10 ~ 3600 범위 내에서 입력해주세요')
      .max(3600, '10 ~ 3600 범위 내에서 입력해주세요'),
    BATCH_CONCURRENCY: z
      .number({ invalid_type_error: '숫자를 입력해주세요' })
      .int('정수만 허용')
      .min(1, '1 ~ 10 범위 내에서 입력해주세요')
      .max(10, '1 ~ 10 범위 내에서 입력해주세요'),
    vlmSkipByDefault: z.boolean(),
    vlmSkipReason: z
      .string()
      .max(VLM_SKIP_REASON_MAX, `사유는 ${VLM_SKIP_REASON_MAX}자를 초과할 수 없습니다`),
  })
  .superRefine((values, ctx) => {
    if (values.vlmSkipByDefault && values.vlmSkipReason.trim().length === 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['vlmSkipReason'],
        message: '건너뛰기 사유를 입력해주세요',
      });
    }
  });

export type BatchConfigForm = z.infer<typeof batchConfigSchema>;

/**
 * Phase 1/5: YOLO 추론 파라미터 zod 스키마.
 * - YOLO_CONF_THRESHOLD: 25~80 (BE 가 /100 하여 0.25~0.80 confidence)
 * - YOLO_IOU: 25~80 (BE 가 /100 하여 0.25~0.80 IoU 임계값)
 *
 * ⚠ 구 키 `YOLO_IMGSZ`(추론 입력 해상도, 320~1920)는 폐지됐다 — 추론 서버가 입력 크기를 640 으로
 *   고정해 쓰므로 조정해도 결과가 달라지지 않았다. 되살리려면 추론 서버부터 고칠 것.
 */
export const yoloConfigSchema = z.object({
  YOLO_CONF_THRESHOLD: z
    .number({ invalid_type_error: '숫자를 입력해주세요' })
    .int('정수만 허용')
    .min(25, '25 ~ 80 범위 내에서 입력해주세요')
    .max(80, '25 ~ 80 범위 내에서 입력해주세요'),
  YOLO_IOU: z
    .number({ invalid_type_error: '숫자를 입력해주세요' })
    .int('정수만 허용')
    .min(25, '25 ~ 80 범위 내에서 입력해주세요')
    .max(80, '25 ~ 80 범위 내에서 입력해주세요'),
});

export type YoloConfigForm = z.infer<typeof yoloConfigSchema>;

/**
 * FEAT-007 (SFR-08-03) 라벨링 정밀도 zod 스키마.
 * - YOLO_CONF_THRESHOLD (인식 민감도): 25~80 (BE 가 /100 → 0.25~0.80 confidence)
 * - POLYGON_SIMPLIFY_TOLERANCE (경계 세밀함): 0.0~50.0 (Douglas-Peucker epsilon px, DECIMAL)
 *
 * 보안: 사용자 입력은 zod로 1차 검증 후 BE에 전달. BE는 서버 측 재검증(이중 방어).
 */
export const precisionConfigSchema = z.object({
  YOLO_CONF_THRESHOLD: z
    .number({ invalid_type_error: '숫자를 입력해주세요' })
    .int('정수만 허용')
    .min(25, '25 ~ 80 범위 내에서 입력해주세요')
    .max(80, '25 ~ 80 범위 내에서 입력해주세요'),
  POLYGON_SIMPLIFY_TOLERANCE: z
    .number({ invalid_type_error: '숫자를 입력해주세요' })
    .min(0, '0.0 ~ 50.0 범위 내에서 입력해주세요')
    .max(50, '0.0 ~ 50.0 범위 내에서 입력해주세요'),
});

export type PrecisionConfigForm = z.infer<typeof precisionConfigSchema>;

/**
 * R9 비식별 옵션 zod 스키마.
 *
 * ⚠ **필드 이름에 점(.)을 쓰지 않는다.** BE 설정 키는 `kpst.deid.masking-type` 처럼 dotted 인데,
 * react-hook-form 은 필드 이름의 점을 **중첩 객체 경로**로 해석한다(`{kpst:{deid:{...}}}`).
 * 그러면 이 스키마도 `dirtyFields` 판정도 전부 어긋나므로, 폼에서는 점 없는 별칭을 쓰고
 * 전송 시점에만 실제 dotted 키로 매핑한다(`DeidentConfigCard`).
 *
 * - maskingType  : 마스킹 방식. **범위가 아니라 열거** {0 색상, 2 모자이크, 3 블러}.
 *                  1 은 벤더 미할당이라 `.min(0).max(3)` 으로 두면 안 된다.
 * - maskingRange : 마스킹 영역 배율 0.5 ~ 2.0 (DECIMAL).
 * - dbSave       : 프레임 저장 여부 {0, 1}.
 *
 * 보안: 사용자 입력은 zod로 1차 검증 후 BE에 전달. BE는 서버 측 재검증(이중 방어).
 */
export const deidentConfigSchema = z.object({
  maskingType: z.union([z.literal(0), z.literal(2), z.literal(3)], {
    errorMap: () => ({ message: '색상 / 모자이크 / 블러 중에서 선택해주세요' }),
  }),
  maskingRange: z
    .number({ invalid_type_error: '숫자를 입력해주세요' })
    .min(0.5, '0.5 ~ 2.0 범위 내에서 입력해주세요')
    .max(2, '0.5 ~ 2.0 범위 내에서 입력해주세요'),
  dbSave: z.union([z.literal(0), z.literal(1)], {
    errorMap: () => ({ message: '저장 안 함 / 저장 중에서 선택해주세요' }),
  }),
});

export type DeidentConfigForm = z.infer<typeof deidentConfigSchema>;

/**
 * R11 연동 서버 주소 zod 스키마 — **저장한 값이 곧 진실원인 축만** 담는다.
 *
 * ⚠ AI 추론 서버·외부 시계열 분석 벤더 칸은 **여기 없는 것이 맞다**. 그 두 축의 주소 진실원은
 * 장비 원장이고 위탁도 원장 주소로 나가므로, 이 스키마에 필드를 되살리면 저장은 되는데 위탁
 * 주소는 그대로인 **조용한 실패**가 된다(SCREEN-042 v19 · API-069 v11).
 *
 * ⚠ **1차 검증일 뿐이다.** 서버도 같은 축(스킴·형식)을 다시 본다. 두 검증의 범위가 완전히 같지는
 * 않으므로 화면을 통과한 값도 서버가 400 을 줄 수 있고, 그때는 서버 문구를 그대로 보여준다.
 *
 * ⚠ **IP 대역(내부망) 차단은 없다** — 서버도 하지 않는다(2026-08-10 확정). 이 연동들은 내부망에
 * 있을 수 있고 망 통제는 인프라 계층 책임이다.
 *
 * ⚠ 필드 이름에 점(.)을 쓰지 않는다 — react-hook-form 이 중첩 객체 경로로 해석한다
 * (`DeidentConfigCard` 와 같은 이유). 전송 시점에만 실제 dotted 키로 매핑한다.
 *
 * 빈 문자열은 "바꾸지 않음"으로 취급한다 — 값을 지우는 것이 아니라 배포 기본값으로 되돌리는
 * 동작은 이 화면에 없으므로, 빈 칸은 전송 대상에서 빠진다.
 */
const endpointUrl = z
  .string()
  .trim()
  .max(300, '주소는 300자를 초과할 수 없습니다')
  .refine((v) => v === '' || /^https?:\/\/[^\s/$.?#].[^\s]*$/i.test(v), {
    message: 'http:// 또는 https:// 로 시작하는 주소를 입력해주세요',
  });

export const integrationEndpointsSchema = z.object({
  deidentify: endpointUrl,
  /**
   * 외부 증강 벤더 — **비워 두는 것이 정상 상태**다.
   *
   * ★ 비어 있음이 «아직 연동하지 않았다»를 나타내는 **유일한 표현**이다. 미리 채워 두면 연동된
   * 것으로 판정돼 아무도 받지 않는 주소로 위탁이 나가고, 그 실패가 **벤더 장애처럼** 보인다.
   * 그래서 이 칸에는 필수 검증을 걸지 않는다 — 빈 값은 다른 칸과 같이 «바꾸지 않음»이라
   * 전송 대상에서 빠지고, 저장 행이 생기지 않는다.
   */
  augment: endpointUrl,
  controlNotify: endpointUrl,
});

export type IntegrationEndpointsForm = z.infer<typeof integrationEndpointsSchema>;

// 관리자 패스워드 입력 검증은 관리자 유효창 모듈이 소유한다(`features/adminSession/schema`) —
// 유효창이 연동 주소 전용에서 관리 기능 공통으로 넓어져 이 파일의 소관이 아니게 됐다.
