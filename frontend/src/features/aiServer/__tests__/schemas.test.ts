import { describe, expect, it } from 'vitest';

import { aiServerCreateSchema } from '../schemas';

/**
 * 식별자 형식 판정 — 판정기 자체를 부른다. [@design API-227] [@design SCREEN-042]
 *
 * <h3>왜 화면 시험과 따로 두는가</h3>
 * 화면 시험은 «입력 → 요청» 배선을 지키지만 `input[type=text]` 의 값 정제가 CR/LF 를 먼저
 * 지워 **개행 축을 원리적으로 재현하지 못한다**. 그 축은 여기서만 검증된다. 두 시험은 서로를
 * 대체하지 않는다.
 *
 * ★ 형식 규칙의 단일 진실원은 `schemas.ts` 의 `SRVR_ID_PATTERN` 하나다 — 여기에 같은 정규식을
 *   옮겨 적지 않고 **스키마를 실제로 실행해** 판정한다(정규식을 복제하면 두 번째 진실원이 되어
 *   한쪽만 넓혀도 시험이 초록으로 남는다).
 */

const VALID_ADDR = 'http://10.0.0.9:9300';

function parseId(srvrId: string) {
  return aiServerCreateSchema.safeParse({
    srvrId,
    srvrNm: '테스트 장비',
    srvrAddr: VALID_ADDR,
    srvrTypeCd: 'INFERENCE',
  });
}

describe('aiServerCreateSchema — 장비 식별자 형식', () => {
  describe('★넓힌 축 — 하이픈·밑줄을 받는다', () => {
    it.each([
      ['종전 형식(소문자·숫자)은 그대로 유효하다', 'gpu09'],
      ['가운데 하이픈', 'gpu-09'],
      ['가운데 밑줄', 'gpu_09'],
      ['맨 앞 하이픈', '-gpu09'],
      ['맨 뒤 하이픈', 'gpu09-'],
      ['맨 앞 밑줄', '_gpu09'],
      ['맨 뒤 밑줄', 'gpu09_'],
      ['하이픈·밑줄만', '-_-'],
      ['1자', 'a'],
      ['상한 20자', `_${'a'.repeat(18)}-`],
    ])('%s → 통과 (%s)', (_label, srvrId) => {
      expect(parseId(srvrId).success).toBe(true);
    });
  });

  describe('여전히 막는 축 — 넓혔다고 다 열린 것이 아니다', () => {
    it.each([
      ['21자 — 저장 폭을 넘으면 서버가 400 이다', `_${'a'.repeat(19)}-`],
      ['빈 값', ''],
      ['대문자', 'GPU09'],
      ['가운데 공백', 'gpu 09'],
      ['★개행 — 기록 오염·상대측 파싱 파손 축', 'gpu\n09'],
      ['★캐리지리턴', 'gpu\r09'],
      ['★탭', 'gpu\t09'],
      ['마침표', 'gpu.09'],
      ['슬래시', 'gpu/09'],
      ['한글', '장비09'],
    ])('%s → 거부 (%s)', (_label, srvrId) => {
      expect(parseId(srvrId).success).toBe(false);
    });
  });

  it('거부 문구가 규칙보다 좁게 남지 않는다', () => {
    const result = parseId('GPU09');
    expect(result.success).toBe(false);
    const message = result.success ? '' : (result.error.issues[0]?.message ?? '');
    // ⚠ 문구가 «소문자와 숫자만» 으로 남으면 사용자는 되는 것을 안 된다고 읽는다.
    expect(message).toContain('하이픈(-)');
    expect(message).toContain('밑줄(_)');
    expect(message).not.toContain('소문자와 숫자만');
  });
});
