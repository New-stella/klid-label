import MockAdapter from 'axios-mock-adapter';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';

import { getEventTypeAdminList } from '../adminApi';
import { mergePinnedRows, upsertPinnedRow } from '../pinnedRows';
import type { EventTypeAdminItem } from '../adminApi';

const ok = (data: unknown) => ({ success: true, data, message: null, errorCode: null });

const rowOf = (evntTypeCd: string): EventTypeAdminItem => ({
  evntTypeCd,
  dsplNm: evntTypeCd,
  dsplNmSource: 'code',
  optrIndctNm: null,
  evntNm: null,
  evntCtgryNm: null,
  evntClsfCd: null,
  evntCtgryCd: null,
  clctYn: 'Y',
  presetLinkStatus: 'UNLINKED',
});

/**
 * 이벤트유형 관리 목록 조회 — 연결 상태 거르기 파라미터의 <b>전선 계약</b>.
 *
 * 위 화면 테스트는 "무엇을 함수에 넘겼는가"를 보고, 이 파일은 "무엇이 실제 요청에 실렸는가"를 본다.
 * 둘은 다르다 — 함수에 undefined 를 넘겨도 axios 가 `?presetLinkStatus=` 를 붙이면 서버는
 * 허용값 밖이라 400 으로 거부한다(빈 문자열은 enum 이 아니다).
 *
 * @design API-185
 */
describe('getEventTypeAdminList — 연결 상태 거르기 파라미터', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
    mock.onGet('/manage/event-types').reply(200, ok([]));
  });
  afterEach(() => mock.restore());

  it('★미지정이면_파라미터_자체를_싣지_않는다', async () => {
    await getEventTypeAdminList();

    const call = mock.history.get[0]!;
    expect(call.url).toBe('/manage/event-types');
    // params 키가 아예 없거나 비어 있어야 한다 — 빈 값으로 붙으면 서버가 400 이다.
    expect(call.params ?? {}).toEqual({});
  });

  it('★보류_거르기_값은_그대로_실린다', async () => {
    await getEventTypeAdminList('WITHHELD');

    expect(mock.history.get[0]!.params).toEqual({ presetLinkStatus: 'WITHHELD' });
  });

  it('상태_단건_거르기도_그대로_실린다', async () => {
    await getEventTypeAdminList('LINKED_INEFFECTIVE');

    expect(mock.history.get[0]!.params).toEqual({ presetLinkStatus: 'LINKED_INEFFECTIVE' });
  });
});

/**
 * 저장 직후 조건에서 벗어난 행 붙잡기 — 순수 병합 규칙.
 *
 * ⚠ 이것은 <b>거르기를 화면에서 다시 하는 것이 아니다</b>. 모집단을 줄이지 않고, 이미 보고 있던
 * 행 하나를 결과에 되돌려 놓을 뿐이다.
 *
 * @design SCREEN-038
 */
describe('mergePinnedRows', () => {
  it('고정_행이_없으면_서버_목록_그대로다', () => {
    const server = [rowOf('A'), rowOf('B')];
    expect(mergePinnedRows(server, []).map((r) => r.evntTypeCd)).toEqual(['A', 'B']);
  });

  it('★서버_목록에서_빠진_행만_원래_자리에_다시_끼운다', () => {
    const server = [rowOf('A'), rowOf('C')];
    const pinned = [{ row: rowOf('B'), index: 1 }];

    expect(mergePinnedRows(server, pinned).map((r) => r.evntTypeCd)).toEqual(['A', 'B', 'C']);
  });

  it('★서버_목록에_있으면_서버_값이_이긴다', () => {
    // 서버가 판정한 연결 상태가 최신이고, 고정 행이 든 값은 저장 응답이라 그 칸이 비어 있다.
    const server = [{ ...rowOf('A'), dsplNm: '서버가 준 이름' }];
    const pinned = [{ row: { ...rowOf('A'), dsplNm: '붙잡아 둔 이름' }, index: 0 }];

    const merged = mergePinnedRows(server, pinned);
    expect(merged).toHaveLength(1);
    expect(merged[0]!.dsplNm).toBe('서버가 준 이름');
  });

  it('자리가_목록_길이를_넘으면_끝에_붙인다', () => {
    const merged = mergePinnedRows([rowOf('A')], [{ row: rowOf('Z'), index: 9 }]);
    expect(merged.map((r) => r.evntTypeCd)).toEqual(['A', 'Z']);
  });

  it('여러_건이_빠져도_원래_순서가_유지된다', () => {
    const merged = mergePinnedRows(
      [rowOf('C')],
      [
        { row: rowOf('B'), index: 1 },
        { row: rowOf('A'), index: 0 },
      ],
    );
    expect(merged.map((r) => r.evntTypeCd)).toEqual(['A', 'B', 'C']);
  });

  it('같은_유형을_두_번_저장해도_한_번만_끼워진다', () => {
    let pinned = upsertPinnedRow([], rowOf('A'), 0);
    pinned = upsertPinnedRow(pinned, { ...rowOf('A'), dsplNm: '두 번째 저장' }, 0);

    expect(pinned).toHaveLength(1);
    expect(mergePinnedRows([], pinned).map((r) => r.dsplNm)).toEqual(['두 번째 저장']);
  });
});
