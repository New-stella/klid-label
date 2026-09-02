import { z } from 'zod';

import { AiSrvrType } from './types';

/**
 * AI 장비 등록·수정 zod 스키마 — **1차 검증일 뿐이다**.
 * [@design API-227] [@design API-228] [@design AC-1089] [@design ADR-046]
 *
 * 서버가 같은 축(식별자 형식·주소 스킴/형식/예약 대역)을 다시 본다. 두 검증의 범위가 완전히
 * 같지는 않으므로 화면을 통과한 값도 서버가 400 을 줄 수 있고, 그때는 **서버 문구를 그대로**
 * 보여준다.
 *
 * ★ **평문 http 와 사설 대역(10.x·192.168.x·172.16~31.x·127.0.0.1)은 막지 않는다.** 서버도 막지
 *   않으며(대역 차단은 폐기됐다) 이 장비들은 실제로 내부망에 있다. 여기서 미리 막으면 정상
 *   주소를 화면이 먼저 거부한다. 서버가 거부하는 것은 예약 대역(클라우드 메타데이터·링크로컬 등)과
 *   비허용 스킴·형식 위반이며, 그 판정은 서버가 소유한다.
 *
 * ⚠ 거부 문구에 **입력한 주소나 그 해석 결과를 되비추지 않는다** — 그러면 이 화면이 내부망을
 *   훑는 수단이 된다(`ADR-046`).
 */

/** BE `AiSrvrIdPolicy.SRVR_ID_REGEX` 와 같은 규칙. 서킷브레이커 이름·메트릭 라벨로 조립되는 기계용 값이다. */
const SRVR_ID_PATTERN = /^[a-z0-9]{1,20}$/;

/** BE `AiSrvrCreateRequest` 의 `@Size` 상한과 같다 — 컬럼 폭을 넘으면 서버가 400 이다. */
export const SRVR_NM_MAX = 100;
export const SRVR_ADDR_MAX = 200;

/** 연동 주소 카드(`sysconfig/schemas`)와 같은 형태 판정. 스킴만 보고 대역은 보지 않는다. */
const srvrAddr = z
  .string()
  .trim()
  .min(1, '주소를 입력해주세요')
  .max(SRVR_ADDR_MAX, `주소는 ${SRVR_ADDR_MAX}자를 초과할 수 없습니다`)
  .refine((v) => /^https?:\/\/[^\s/$.?#].[^\s]*$/i.test(v), {
    message: 'http:// 또는 https:// 로 시작하는 주소를 입력해주세요',
  });

const srvrNm = z.string().trim().max(SRVR_NM_MAX, `이름은 ${SRVR_NM_MAX}자를 초과할 수 없습니다`);

export const aiServerCreateSchema = z.object({
  srvrId: z
    .string()
    .trim()
    .min(1, '장비 식별자를 입력해주세요')
    .regex(SRVR_ID_PATTERN, '식별자는 소문자와 숫자만 20자 이내로 사용할 수 있습니다'),
  srvrNm,
  srvrAddr,
  srvrTypeCd: z.enum([AiSrvrType.INFERENCE, AiSrvrType.TIMESERIES]),
});
export type AiServerCreateForm = z.infer<typeof aiServerCreateSchema>;

/**
 * 수정 폼 — 식별자·유형은 잠긴다.
 *
 * 유형이 바뀌면 그 장비를 고르던 축이 통째로 바뀌고 이미 배정된 영상의 근거가 사라지므로
 * 서버가 애초에 받지 않는다.
 */
export const aiServerUpdateSchema = z.object({
  srvrNm,
  srvrAddr,
});
export type AiServerUpdateForm = z.infer<typeof aiServerUpdateSchema>;
