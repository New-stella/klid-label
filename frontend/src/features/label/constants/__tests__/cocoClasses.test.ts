// COCO 매핑 select 옵션의 표기·정합 회귀 가드.
//
// ★ 왜 필요한가
//   `COCO_LABEL_KO` 는 80종 중 14종만 채워져 있었고 나머지 66종은 영문 폴백으로 떨어져,
//   같은 드롭다운 안에서 어떤 항목은 한글로 어떤 항목은 영문으로 보였다. 폴백이 조용해서
//   (에러 없이 id 를 그대로 쓴다) 빠진 항목이 늘어도 아무도 알아채지 못한다. 그래서 기계로 고정한다.
//
// ★ 사전 키의 오타는 이 가드 없이는 영영 안 잡힌다
//   키를 잘못 적으면 예외가 아니라 **영문 폴백**이 된다. 그래서 "사전의 모든 키가 실제
//   COCO id 에 실재하는가"를 소스에서 직접 파싱해 검사한다.
//
// ★ 형식 검사만으로는 값이 뒤바뀌어도 통과한다 — 그래서 쌍을 통째로 고정한다
//   '여든종_전부_한글_병기_형식이다' 는 "값이 한글인가"만 본다. 그래서 `dog: '고양이'` 처럼
//   **번역값이 서로 뒤바뀌어도 살아남는다**(mutation 실증). 아래 `EXPECTED_LABEL_KO` 가
//   80쌍의 키↔값을 통째로 고정해 그 구멍을 닫는다.
//
// ★ 기대 상수는 이 파일 안에 둔다 — 변경지시서를 런타임에 파싱하지 않는다
//   문서를 테스트가 읽게 만들면 문서가 이동·개편될 때 테스트가 깨지고, 테스트가 저장소의
//   문서 구조에 결합된다. 값의 출처(변경지시서 확정표 66 + 기존 14)는 이 주석으로만 남긴다.
//
// ⚠ 이 가드가 못 보는 것
//   - 번역이 **적절한가**는 여전히 사람의 판단이다. 고정하는 것은 "확정된 값에서 바뀌지
//     않았는가"이지 "그 번역이 옳은가"가 아니다.
//   - BE `CocoClasses.LABELS` 와의 드리프트는 여기서 보지 않는다. FE↔BE 드리프트 가드는
//     현재 저장소에 없다(BE 쪽 `CocoClassesDriftTest` 는 BE↔ai-server 축이다).
//   - `COCO_IDS` 의 **순서**는 전량이 아니라 네 지점(0/4/62/79) spot-check 뿐이다. 사전에 둘 다
//     들어 있는 인접 id 를 서로 맞바꾸면 이 파일의 어느 가드에도 걸리지 않는다(기존 공백).
//
// ⚠ mutation 확인 절차(실제로 확인함)
//   - `COCO_LABEL_KO` 에서 한 줄 삭제 → '여든종_전부_한글_병기_형식이다' 가 그 id 를 지목하며 FAIL.
//   - `dog: '고양이'`(값 뒤바꿈) / `airplane: '항공기'`(한글이지만 확정값과 다름)
//     → '한글_사전은_여든쌍이_확정값과_완전히_일치한다' 가 FAIL.

import fs from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import * as cocoModule from '../cocoClasses';
import { COCO_CLASSES, isCocoClass } from '../cocoClasses';

const SOURCE = fs.readFileSync(path.resolve(__dirname, '../cocoClasses.ts'), 'utf-8');

/**
 * 소스의 `COCO_LABEL_KO` 리터럴에서 키↔값을 뽑는다(키 따옴표 유무 양쪽).
 *
 * 사전이 모듈 private 이라 import 할 수 없어 소스를 직접 읽는다 — export 로 뚫으면
 * 표시명 경로가 이 사전을 참조할 길이 생기고, 그건 라벨 마스터와 어긋나는 두 번째 진실원이 된다.
 */
function dictionaryEntries(): [string, string][] {
  const body = /const COCO_LABEL_KO[^{]*\{([\s\S]*?)\n\};/.exec(SOURCE);
  expect(body, 'COCO_LABEL_KO 리터럴을 찾지 못했다').not.toBeNull();
  return [...body![1].matchAll(/^\s*'?([^':\n]+?)'?:\s*'([^']*)',\s*$/gm)].map((m) => [
    m[1],
    m[2],
  ]);
}

function dictionaryKeys(): string[] {
  return dictionaryEntries().map(([key]) => key);
}

const HANGUL = /[가-힣]/;

/**
 * COCO 80종의 **확정 표시값** — 키↔값을 통째로 고정한다.
 *
 * 출처: 66종은 변경지시서(CO-20260826) §3 확정표, 나머지 14종은 그 이전부터 쓰이던 값이다.
 * 형식 검사('여든종_전부_한글_병기_형식이다')는 "한글인가"만 보므로 값이 서로 뒤바뀌어도
 * 통과한다 — 그 구멍을 이 상수가 닫는다. 순서는 `COCO_IDS`(= BE allowlist id 0~79) 순이다.
 *
 * ⚠ 번역을 바꾸려면 이 상수와 `cocoClasses.ts` 를 **함께** 고쳐야 한다. 한쪽만 고치면 실패하는데
 *   그게 의도다 — 표시값 변경이 조용히 새지 않게 한다.
 */
const EXPECTED_LABEL_KO: Readonly<Record<string, string>> = {
  person: '사람',
  bicycle: '자전거',
  car: '자동차',
  motorcycle: '오토바이',
  airplane: '비행기',
  bus: '버스',
  train: '기차',
  truck: '트럭',
  boat: '보트',
  'traffic light': '신호등',
  'fire hydrant': '소화전',
  'stop sign': '정지 표지판',
  'parking meter': '주차 요금기',
  bench: '벤치',
  bird: '새',
  cat: '고양이',
  dog: '개',
  horse: '말',
  sheep: '양',
  cow: '소',
  elephant: '코끼리',
  bear: '곰',
  zebra: '얼룩말',
  giraffe: '기린',
  backpack: '배낭',
  umbrella: '우산',
  handbag: '핸드백',
  tie: '넥타이',
  suitcase: '여행가방',
  frisbee: '원반',
  skis: '스키',
  snowboard: '스노보드',
  'sports ball': '공',
  kite: '연',
  'baseball bat': '야구 배트',
  'baseball glove': '야구 글러브',
  skateboard: '스케이트보드',
  surfboard: '서핑보드',
  'tennis racket': '테니스 라켓',
  bottle: '병',
  'wine glass': '와인잔',
  cup: '컵',
  fork: '포크',
  knife: '칼',
  spoon: '숟가락',
  bowl: '그릇',
  banana: '바나나',
  apple: '사과',
  sandwich: '샌드위치',
  orange: '오렌지',
  broccoli: '브로콜리',
  carrot: '당근',
  'hot dog': '핫도그',
  pizza: '피자',
  donut: '도넛',
  cake: '케이크',
  chair: '의자',
  couch: '소파',
  'potted plant': '화분',
  bed: '침대',
  'dining table': '식탁',
  toilet: '변기',
  tv: 'TV',
  laptop: '노트북',
  mouse: '마우스',
  remote: '리모컨',
  keyboard: '키보드',
  'cell phone': '휴대전화',
  microwave: '전자레인지',
  oven: '오븐',
  toaster: '토스터',
  sink: '싱크대',
  refrigerator: '냉장고',
  book: '책',
  clock: '시계',
  vase: '꽃병',
  scissors: '가위',
  'teddy bear': '곰인형',
  'hair drier': '헤어드라이어',
  toothbrush: '칫솔',
};

/**
 * 한글이 아닌 표기를 쓰기로 **확정된** 예외. 여기 없는 항목이 비-한글이면 실패한다 —
 * 예외를 allowlist 로 고정해야 "영문이 조용히 새는 것"과 구분된다.
 */
const NON_HANGUL_LABELS: Readonly<Record<string, string>> = {
  // 한글 음차("티비")보다 원문 표기가 통용된다.
  tv: 'TV',
};

describe('COCO_CLASSES — 드롭다운 표기', () => {
  it('여든종_전부_한글_병기_형식이다 — 영문_단독_0건', () => {
    const englishOnly: string[] = [];
    const malformed: string[] = [];

    for (const item of COCO_CLASSES) {
      if (item.label === item.id) {
        englishOnly.push(item.id);
        continue;
      }
      const suffix = ` (${item.id})`;
      const prefix = item.label.endsWith(suffix)
        ? item.label.slice(0, -suffix.length)
        : null;
      const expectedException = NON_HANGUL_LABELS[item.id];
      const ok =
        prefix !== null &&
        (expectedException !== undefined
          ? prefix === expectedException
          : HANGUL.test(prefix));
      if (!ok) {
        malformed.push(`${item.id} -> ${item.label}`);
      }
    }

    expect(COCO_CLASSES).toHaveLength(80);
    expect(englishOnly, '한글이 없어 영문 id 로 폴백된 항목').toEqual([]);
    expect(malformed, '"한글 (id)" 형식이 아닌 항목').toEqual([]);
  });

  it('아이디는_영문_COCO_문자열_여든종이고_중복이_없다', () => {
    const ids = COCO_CLASSES.map((c) => c.id);

    expect(ids).toHaveLength(80);
    expect(new Set(ids).size).toBe(80);
    // 저장/전송 값은 계속 영문이어야 한다 — 한글이 섞이면 BE allowlist 가 400 으로 거부한다.
    expect(ids.filter((id) => HANGUL.test(id))).toEqual([]);
    // 경계 spot-check — BE CocoClasses.LABELS 의 id 0/4/62/79.
    expect(ids[0]).toBe('person');
    expect(ids[4]).toBe('airplane');
    expect(ids[62]).toBe('tv');
    expect(ids[79]).toBe('toothbrush');
  });

  it('사전의_모든_키가_실제_COCO_아이디에_실재한다 — 오타_키는_조용히_무시된다', () => {
    const ids = new Set(COCO_CLASSES.map((c) => c.id));
    const keys = dictionaryKeys();

    expect(keys).toHaveLength(80);
    expect(keys.filter((k) => !ids.has(k)), 'COCO id 에 없는 사전 키').toEqual([]);
  });

  it('한글_사전은_여든쌍이_확정값과_완전히_일치한다 — 값_뒤바뀜도_잡는다', () => {
    const actual = Object.fromEntries(dictionaryEntries());

    // 키 누락·추가·값 불일치를 한 번에 잡는다(형식 검사가 통과시키던 축).
    expect(actual).toEqual(EXPECTED_LABEL_KO);
    expect(Object.keys(EXPECTED_LABEL_KO)).toHaveLength(80);
  });

  it('드롭다운에_실제로_그려지는_라벨이_확정값과_일치한다', () => {
    // 위 가드는 사전 리터럴을 본다. 이건 소비 지점(`COCO_CLASSES.label`)까지 내려가서 본다 —
    // 사전이 맞아도 조립 형식이 바뀌면 사용자가 보는 문자열이 달라지기 때문이다.
    const rendered = Object.fromEntries(COCO_CLASSES.map((c) => [c.id, c.label]));
    const expected = Object.fromEntries(
      Object.entries(EXPECTED_LABEL_KO).map(([id, ko]) => [id, `${ko} (${id})`]),
    );

    expect(rendered).toEqual(expected);
  });

  it('한글_사전은_외부로_공개되지_않는다 — 표시명_진실원은_라벨_마스터다', () => {
    // 2026-08-03 확정: 코드 사전을 표시명 경로에서 쓰면 라벨 마스터와 어긋나는 두 번째 진실원이 된다.
    expect(Object.keys(cocoModule)).not.toContain('COCO_LABEL_KO');
    expect(/export\s+const\s+COCO_LABEL_KO/.test(SOURCE)).toBe(false);
  });

  it('isCocoClass_는_allowlist_판정을_그대로_유지한다', () => {
    expect(isCocoClass('person')).toBe(true);
    expect(isCocoClass(' traffic light ')).toBe(true);
    expect(isCocoClass('사람')).toBe(false);
    expect(isCocoClass('not-a-coco')).toBe(false);
    expect(isCocoClass(null)).toBe(false);
    expect(isCocoClass(undefined)).toBe(false);
  });
});
