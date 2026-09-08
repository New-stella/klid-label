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

/**
 * BE `AiSrvrIdPolicy.SRVR_ID_REGEX` 와 같은 규칙. 이 값이 형식 제한을 갖는 이유는 **기록·메트릭
 * 라벨·외부 벤더 요청에 그대로 실리기** 때문이다(아래 ⚠ 항목이 정본).
 *
 * ⚠ **구 서술 폐기(2026-09-08)** — 여기 *"서킷브레이커 이름·메트릭 라벨로 조립되는 기계용 값이다"*
 *   라고 적혀 있었다. **서킷 이름에는 장비 식별자가 들어가지 않는다** — 이름 조립은 용도 축만 쓴다
 *   (BE `AiWorkload#circuitName()` → `ai-batch` / `ai-interactive`). BE 가 이번 라운드에 같은 근거를
 *   코드 실측으로 폐기 표기했고, 그 전까지 두 층이 같은 규칙에 서로 다른 근거를 말하고 있었다.
 *   지우지 않고 남기는 이유는 왜 한때 그렇게 적었는지가 사라지면 다음 사람이 같은 역추정을 하기
 *   때문이다. ⚠ 장비 축을 서킷 이름에 **넣게 되면** 그 걱정이 되살아난다 — 그때는 식별자 형식을
 *   되좁히는 것이 아니라 구분자를 바꾸거나 장비 축을 이름이 아닌 태그로 둔다(BE 가 같은 판단을 적어 두었다).
 *
 * ★ **형식 판정은 이 상수 하나뿐이다** — 같은 정규식을 다른 파일에 옮겨 적지 말 것(두 번째 진실원).
 *
 * 허용 문자는 소문자·숫자·하이픈·밑줄이며 **하이픈·밑줄은 맨 앞·맨 뒤에 와도 받는다** — 막으면
 * 규칙과 거부 문구만 길어지고 기능상 해롭지 않아 단순한 쪽을 골랐다(`API-227`).
 *
 * ⚠ **길이 상한 20 은 넓히지 않는다** — 저장 폭(`SRVR_ID VARCHAR(20)`)이 그만큼이라 늘리면
 *   입구를 통과한 값이 저장 시점에 오류로 새어 나간다.
 *
 * ⚠ 형식을 아예 없애지 않는 이유는 이 값이 **외부 위탁 요청과 기록에 그대로 실리기** 때문이다 —
 *   공백·개행·제어문자가 섞이면 기록이 오염되고 상대측 해석이 깨진다. 하이픈·밑줄에는 그 위험이
 *   없어 넓혀도 안전하며, **넓히는 변경이라 종전 식별자는 전부 그대로 유효하다**.
 *
 * ⚠ 문자 클래스에서 `-` 는 **맨 뒤**에 둔다 — 가운데 두면 범위 기호로 읽힌다.
 */
const SRVR_ID_PATTERN = /^[a-z0-9_-]{1,20}$/;

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
    .regex(SRVR_ID_PATTERN, '식별자는 소문자·숫자·하이픈(-)·밑줄(_)만 20자 이내로 사용할 수 있습니다'),
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
