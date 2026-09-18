import type MockAdapter from 'axios-mock-adapter';

import type { LabelAttrDef, LabelAttrValue } from '@/features/label/api/labelAttr';
import type { LabelMaster } from '@/features/label/api/labelMaster';
import type { UploadFrameLabel, UploadLabelRequest } from '@/features/portal/uploads/api';
import type { PortalUploadDetail, PortalUploadFrame } from '@/features/portal/uploads/types';
import type { PortalEventAnnotation, PortalFrameMeta, PortalMetaItem } from '@/features/portal/work/types';

import { ok } from '../mockReply';

/* 라벨링 견본 — 스토리북(6010)과 시연판이 같이 쓴다.
   견본 값(파일명 · 프레임 수 · 객체 · 라벨 · 속성)은 포털 목업(KLID_Portal `mocks/authoring.ts`)과 같고,
   프레임 번호 · 메타 항목은 실제 서버가 올린 영상에 보내는 모양을 따른다 */

/** 내 업로드 한 건 — 「내 작업」 견본의 둘째 줄과 같은 자산 */
export const 자산번호 = 4;
export const 파일명 = '실종자추적_v0.7_20260910.mp4';

/** 첫 프레임 번호 — 「내 작업」 견본의 이어서 작업 진입 프레임과 같다 */
export const 첫_프레임 = 41;

/** 마킹으로 뽑힌 프레임 23장. 프레임 번호는 실제 서버처럼 뽑힌 차례(0, 1, 2 …)다 — 영상 속 프레임 위치가 아니다 */
export const FRAMES: PortalUploadFrame[] = Array.from({ length: 23 }, (_, i) => ({
  uldFrmeSn: 첫_프레임 + i,
  uldSn: 자산번호,
  frmeNo: i,
  regDt: '2026-09-11T10:52:00',
}));

export const DETAIL: PortalUploadDetail = {
  uldSn: 자산번호,
  uldTypeCd: 'VIDEO',
  orgnlFileNm: 파일명,
  fileSz: 32925286,
  mimeTypeNm: 'video/mp4',
  uldSttsCd: 'READY',
  frmeCnt: FRAMES.length,
  vdoLenSec: 229,
  fps: 30,
  regDt: '2026-09-11T10:49:00',
  mdfcnDt: '2026-09-11T10:52:00',
  frames: FRAMES,
  expiresAt: '2026-09-18T10:49:00',
};

/** 첫 프레임의 객체 — 사람 둘 · 차량 하나. 좌표는 견본 그림(1200×750) 픽셀이다(포털 목업의 백분율 좌표를 옮김) */
export const OBJECTS: UploadFrameLabel[] = [
  {
    uldLblSn: 1,
    uldFrmeSn: 첫_프레임,
    lblTypeCd: 'BBOX',
    label: '사람',
    points: [
      [696, 225],
      [792, 465],
    ],
    regDt: '2026-09-11T11:02:00',
    mdfcnDt: null,
  },
  {
    uldLblSn: 2,
    uldFrmeSn: 첫_프레임,
    lblTypeCd: 'BBOX',
    label: '사람',
    points: [
      [240, 270],
      [324, 480],
    ],
    regDt: '2026-09-11T11:02:00',
    mdfcnDt: null,
  },
  {
    uldLblSn: 3,
    uldFrmeSn: 첫_프레임,
    lblTypeCd: 'POLYGON',
    label: '차량',
    points: [
      [408, 330],
      [624, 300],
      [672, 435],
      [576, 555],
      [384, 540],
    ],
    regDt: '2026-09-11T11:02:00',
    mdfcnDt: null,
  },
];

/** 라벨 마스터 — 포털 목업과 같은 다섯. 색은 포털 목업이 빌린 색 토큰을 그 자리에서 읽어 넣는다(값을 적어 두지 않는다) */
const LABELS = [
  { labelId: 1, name: '사람', token: '--klid-color-event-abduction' },
  { labelId: 2, name: '차량', token: '--klid-color-event-traffic' },
  { labelId: 3, name: '자전거', token: '--klid-color-event-flood' },
  { labelId: 4, name: '오토바이', token: '--klid-color-event-fire' },
  { labelId: 5, name: '동물', token: '--klid-color-event-landslide' },
] as const;

export function labelMasters(): LabelMaster[] {
  const css = getComputedStyle(document.documentElement);
  return LABELS.map((l, i) => ({
    labelId: l.labelId,
    name: l.name,
    color: css.getPropertyValue(l.token).trim(),
    type: 'BBOX',
    sortNo: i + 1,
    useYn: 'Y',
    dtctTypeCd: null,
  }));
}

/** 라벨에 연결된 속성 — 넷의 입력 모양(드롭다운 · 라디오 · 체크박스 · 입력칸)을 한 벌씩 */
export const attrDefs = (labelId: number): LabelAttrDef[] => [
  { attrId: 1, labelId, name: '가림 정도', inputType: 'SELECT', valuesJson: '["없음","일부","대부분"]', defaultVal: null, mutable: 'Y', sortNo: 1, useYn: 'Y' },
  { attrId: 2, labelId, name: '방향', inputType: 'RADIO', valuesJson: '["정면","측면","후면"]', defaultVal: null, mutable: 'Y', sortNo: 2, useYn: 'Y' },
  { attrId: 3, labelId, name: '소지품', inputType: 'CHECKBOX', valuesJson: '["우산","가방","모자"]', defaultVal: null, mutable: 'Y', sortNo: 3, useYn: 'Y' },
  { attrId: 4, labelId, name: '메모', inputType: 'TEXT', valuesJson: null, defaultVal: null, mutable: 'Y', sortNo: 4, useYn: 'Y' },
];

export const ATTR_VALUES: LabelAttrValue[] = [
  { attrId: 1, name: '가림 정도', inputType: 'SELECT', value: '일부' },
  { attrId: 2, name: '방향', inputType: 'RADIO', value: '측면' },
  { attrId: 3, name: '소지품', inputType: 'CHECKBOX', value: '["우산"]' },
  { attrId: 4, name: '메모', inputType: 'TEXT', value: '' },
];

const metaItem = (metaKey: string, scope: PortalMetaItem['scope'], metaVl: string | null = null): PortalMetaItem => ({
  metaKey,
  metaVl,
  scope,
  overridden: false,
  source: metaVl === null ? 'NONE' : 'STORED',
});

/** 메타 — 적는 칸은 모두 비어 있다. 참고 정보 · 영상 기술 정보는 실제 서버가 올린 영상에 보내는 항목 그대로 */
export const frameMeta = (srcSn: number): PortalFrameMeta => ({
  rawSn: 자산번호,
  srcSn,
  items: [
    metaItem('env.weather', 'video'),
    metaItem('env.timeOfDay', 'video'),
    metaItem('env.season', 'video'),
    metaItem('privacy.anonymity', 'video'),
    metaItem('privacy.pseudonymity', 'video'),
    metaItem('privacy.privacyIncluded', 'video'),
    metaItem('frame.description', 'frame'),
    metaItem('privacy.anonymity', 'frame'),
    metaItem('privacy.pseudonymity', 'frame'),
    metaItem('privacy.privacyIncluded', 'frame'),
  ],
  // 내가 올린 영상의 참고 정보는 실제 서버가 업로드 상태 한 줄만 보낸다 (위치 · CCTV 정보는 데이터마트 영상에만 있다)
  readOnlyMeta: [metaItem('portal.upload_status', 'video', 'READY')],
  technicalMeta: [
    metaItem('video.filesize', 'video', String(DETAIL.fileSz)),
    metaItem('video.fps', 'video', String(DETAIL.fps)),
    metaItem('video.mime', 'video', DETAIL.mimeTypeNm),
    metaItem('video.original_filename', 'video', 파일명),
  ],
});

export const EVENT_ANNOTATION: PortalEventAnnotation = { rawSn: 자산번호, annotation: null, overridden: false };

/* 주소 모양 — 상태마다 같은 모양으로 다시 걸면 기본 응답을 갈아 끼운다(가짜 응답 도구의 규칙) */
export const 자산_주소 = `/portal/uploads/${자산번호}`;
export const 라벨_주소 = /^\/portal\/uploads\/frames\/(\d+)\/labels$/;
export const 그림_주소 = /^\/portal\/uploads\/frames\/(\d+)\/image$/;
export const 속성값_주소 = /^\/labels\/(\d+)\/attrs$/;

export const 번호 = (url: string | undefined, pattern: RegExp) => Number(pattern.exec(url ?? '')?.[1]);

/** 견본 그림 — 한 번 받아 두고 프레임마다 돌려 쓴다(dummy-1 ~ dummy-6, 공개 폴더 `samples/`) */
const 그림_캐시 = new Map<number, Promise<Blob>>();
export async function 그림_응답(url: string | undefined): Promise<[number, Blob]> {
  const n = ((((번호(url, 그림_주소) - 첫_프레임) % 6) + 6) % 6) + 1;
  let blob = 그림_캐시.get(n);
  if (!blob) {
    blob = fetch(`/samples/dummy-${n}.jpg`).then((r) => r.blob());
    그림_캐시.set(n, blob);
  }
  return [200, await blob];
}

/**
 * 편집기가 부르는 응답 전부를 건다. 저장한 라벨은 기억해 두었다가 다시 불러올 때 돌려준다.
 * 같은 주소 모양으로 뒤에 다시 걸면 그 응답이 여기 것을 대신한다.
 */
export function 라벨링응답(mock: MockAdapter) {
  const saved = new Map<number, UploadFrameLabel[]>([[첫_프레임, OBJECTS]]);
  mock.onGet(자산_주소).reply(...ok(DETAIL));
  mock.onGet(라벨_주소).reply((config) => ok(saved.get(번호(config.url, 라벨_주소)) ?? []));
  mock.onPut(라벨_주소).reply((config) => {
    const sn = 번호(config.url, 라벨_주소);
    const body = JSON.parse(String(config.data)) as UploadLabelRequest[];
    const rows = body.map((r, i) => ({
      uldLblSn: 100 + i,
      uldFrmeSn: sn,
      ...r,
      regDt: '2026-09-15T10:00:00',
      mdfcnDt: null,
    }));
    saved.set(sn, rows);
    return ok(rows);
  });
  mock.onGet(그림_주소).reply((config) => 그림_응답(config.url));
  mock.onGet('/manage/labels').reply(() => ok(labelMasters()));
  mock.onGet(/^\/manage\/labels\/(\d+)\/attrs$/).reply((config) =>
    ok(attrDefs(번호(config.url, /labels\/(\d+)\/attrs/))),
  );
  mock.onGet(속성값_주소).reply(...ok(ATTR_VALUES));
  mock.onGet(/^\/portal\/frames\/(\d+)\/meta$/).reply((config) =>
    ok(frameMeta(번호(config.url, /frames\/(\d+)\/meta/))),
  );
  mock.onGet(`/portal/videos/${자산번호}/event-annotation`).reply(...ok(EVENT_ANNOTATION));
  return saved;
}
