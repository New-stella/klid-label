// 영상 기술 정보 — 표시값 변환의 순수 단위 시험. [@design SCREEN-005] [@design SCREEN-019]
//
// ★왜 순수 함수로 따로 보나 — 화면 시험은 픽스처 한 벌이 흐르는 경로만 지난다. 못 읽는 값·
//   1분 미만·키 누락 같은 갈래는 화면에서 만들기 번거로워 결국 한 번도 실행되지 않고, 그때
//   폴백은 「배선했다」고 적혀 있을 뿐 검증되지 않은 채 남는다(이 저장소의 반복 결함).

import { describe, expect, it } from 'vitest';

import {
  formatDurationMs,
  formatFps,
  formatResolution,
  technicalRows,
} from '../VideoTechnicalMetaPanel';

describe('영상 기술 정보 — 표시값 변환', () => {
  it('해상도는_곱하기_기호로_보인다', () => {
    expect(formatResolution('640x480')).toBe('640×480');
    expect(formatResolution('1920X1080')).toBe('1920×1080');
    // 이미 곱하기 기호인 값도 그대로 성립한다(서버 표기가 바뀌어도 깨지지 않는다).
    expect(formatResolution('1280×720')).toBe('1280×720');
  });

  it('★읽을_수_없는_해상도는_감추지_않고_받은_값_그대로_보인다', () => {
    // 감추면 값이 있는데 없는 것처럼 보인다 — 「조용한 손실 금지」.
    expect(formatResolution('알 수 없음')).toBe('알 수 없음');
    expect(formatResolution('640')).toBe('640');
  });

  it('프레임률은_소수_둘째_자리까지_단위와_함께_보인다', () => {
    // 구 렌더는 `30.003982863999408` 을 날것 그대로 보였다.
    expect(formatFps('30.003982863999408')).toBe('30.00 fps');
    expect(formatFps('29.97')).toBe('29.97 fps');
    expect(formatFps('60')).toBe('60.00 fps');
  });

  it('★숫자로_읽을_수_없는_프레임률은_받은_값_그대로_보인다', () => {
    expect(formatFps('미상')).toBe('미상');
  });

  it('길이는_밀리초가_아니라_분_초로_보인다', () => {
    expect(formatDurationMs('144581')).toBe('2분 24.6초');
    expect(formatDurationMs('60000')).toBe('1분 0.0초');
  });

  it('★1분_미만은_0분을_앞에_두지_않는다', () => {
    // 사양의 보기가 1분 이상 한 건뿐이라 이 갈래는 우리가 정했다 — 사람이 「0분 24.6초」로
    // 읽지 않으므로 분을 생략한다.
    expect(formatDurationMs('24600')).toBe('24.6초');
    expect(formatDurationMs('0')).toBe('0.0초');
  });

  it('★숫자로_읽을_수_없거나_음수인_길이는_받은_값_그대로_보인다', () => {
    expect(formatDurationMs('알 수 없음')).toBe('알 수 없음');
    expect(formatDurationMs('-1')).toBe('-1');
  });
});

describe('영상 기술 정보 — 표시 항목 선별', () => {
  const ALL = [
    { metaSn: 1, metaKey: 'video.resolution', metaVal: '640x480' },
    { metaSn: 2, metaKey: 'video.codec', metaVal: 'h264' },
    { metaSn: 3, metaKey: 'video.fps', metaVal: '30.003982863999408' },
    { metaSn: 4, metaKey: 'video.duration_ms', metaVal: '144581' },
    { metaSn: 5, metaKey: 'video.filesize', metaVal: '11243026' },
    { metaSn: 6, metaKey: 'video.bit_rate', metaVal: '2500000' },
  ];

  it('★네_항목만_사양_순서로_고른다', () => {
    expect(technicalRows(ALL)).toEqual([
      { key: 'video.resolution', label: '해상도', value: '640×480' },
      { key: 'video.codec', label: '코덱', value: 'h264' },
      { key: 'video.fps', label: '프레임률', value: '30.00 fps' },
      { key: 'video.duration_ms', label: '길이', value: '2분 24.6초' },
    ]);
  });

  it('★값이_없는_항목은_행_자체를_두지_않는다', () => {
    const rows = technicalRows([
      { metaSn: 1, metaKey: 'video.codec', metaVal: 'h264' },
      // 공백만 있는 값은 「있음」이 아니다 — 이름만 있고 값이 빈 행이 남으면 안 된다.
      { metaSn: 2, metaKey: 'video.fps', metaVal: '   ' },
    ]);
    expect(rows.map((r) => r.label)).toEqual(['코덱']);
  });

  it('★화면에_두지_않는_키만_있으면_행이_하나도_없다', () => {
    // 이 판정이 「응답 건수」였다면 값이 있다고 보고 <b>안내도 값도 없는 빈 탭</b>이 된다.
    const rows = technicalRows([
      { metaSn: 5, metaKey: 'video.filesize', metaVal: '11243026' },
      { metaSn: 6, metaKey: 'video.bit_rate', metaVal: '2500000' },
    ]);
    expect(rows).toEqual([]);
  });
});
