import { describe, expect, it } from 'vitest';

import { PrvcType } from '@/features/dev/types';
import {
  EVENT_TYPE_MANUAL_OPTION,
  MULTIPART_MAX_BYTES,
  SERVER_FILLED_GROUPS,
  UNSENT_GROUPS,
  UploadRoute,
  canStartUpload,
  initialUnifiedUploadForm,
  multipartLimitMessage,
  resolveEventTypeCd,
  toImmediatePayload,
  toIngestPayload,
  toIsoInstant,
  type UnifiedUploadFormState,
} from '@/features/dev/components/unifiedUploadForm';

/**
 * 파일 업로드 화면(`/admin/uploads`)의 **단일 폼 상태 + 경로별 payload 변환** 단위 테스트.
 * [@design SCREEN-027]
 *
 * 화면은 폼 한 벌만 갖고 적재 경로가 「보내는 곳」만 바꾼다. 이 파일은 그 변환 규칙 — 특히
 * **어느 필드가 어느 경로에서 실리고 어느 경로에서 사라지는지** — 를 고정한다. 조용한 손실
 * (입력은 받았는데 전송되지 않음)을 여기서 기계적으로 드러내는 것이 목적이다.
 */

/** 모든 칸을 채운 폼 — "어느 필드가 어느 경로에 실리나"를 빠짐없이 관측하기 위한 픽스처. */
function filledForm(): UnifiedUploadFormState {
  return {
    ...initialUnifiedUploadForm(),
    vmsClipId: 'clip-001',
    cctvId: 'CCTV-001',
    srcType: 'RELAY',
    lclgvCd: '11680',
    shtDtLocal: '2026-05-12T10:00',
    lclgvNm: '강남구',
    wgs84Lat: '37.5',
    wgs84Lot: '127.05',
    cctvNm: '역삼로 교차로',
    cctvHgt: '4.5',
    mainSurvPanAng: '180',
    evntId: 'ABA_0001',
    evntNm: '화재 발생',
    mntrCn: '관제일지 본문',
    vrfcEvntTypeCd: 'fire',
    vdoLenSec: '60',
    fps: '30',
    frmeCnt: '1800',
    wdth: '1920',
    vrtc: '1080',
    resl: '1920x1080',
    asprtRt: '16:9',
    vdoCdc: 'h264',
    fileFmt: 'mp4',
    fileSz: '1048576',
    bit: '24bit',
    pxl: '4K',
    prvcTypeCd: PrvcType.PRVC,
  };
}

const EVENT_OPTIONS = [
  { categoryKey: '020002', memberCodes: ['EV02000201', 'EV02000202'] },
  { categoryKey: '030001', memberCodes: ['EV03000101'] },
];

function file(size: number): File {
  return new File([new Uint8Array(size)], 'clip.mp4', { type: 'video/mp4' });
}

describe('단일 업로드 폼 — 기본값', () => {
  it('클립_ID_와_CCTV_ID_와_지자체코드에_기본값이_미리_채워진다', () => {
    // given/when — 화면 진입 직후 상태
    const form = initialUnifiedUploadForm();

    // then — 바로 업로드를 누를 수 있어야 한다(입력 부담 완화가 이 폼의 설계 기준)
    expect(form.vmsClipId).not.toBe('');
    expect(form.cctvId).toBe('CCTV-001');
    expect(form.lclgvCd).toBe('11680');
    expect(form.srcType).toBe('USER_ULD');
    // 촬영일시도 현재 시각으로 채워진다(`YYYY-MM-DDTHH:mm`)
    expect(form.shtDtLocal).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
  });

  it('영상_기술메타와_부가정보는_비어_있다 — 비우면_서버가_채운다', () => {
    // given/when
    const form = initialUnifiedUploadForm();

    // then — 기술메타 12종은 전부 빈 문자열(= 키 부재로 전송 → 서버 ffprobe 추출)
    expect([
      form.vdoLenSec,
      form.fps,
      form.frmeCnt,
      form.wdth,
      form.vrtc,
      form.resl,
      form.asprtRt,
      form.vdoCdc,
      form.fileFmt,
      form.fileSz,
      form.bit,
      form.pxl,
    ]).toEqual(Array(12).fill(''));
  });
});

describe('단일 업로드 폼 — 이벤트유형 확정', () => {
  it('그룹을_고르면_그_그룹의_대표코드가_전송값이_된다', () => {
    // given
    const form = { ...initialUnifiedUploadForm(), categoryKey: '020002' };

    // when/then — memberCodes[0] (표시명 그룹의 대표코드)
    expect(resolveEventTypeCd(form, EVENT_OPTIONS)).toBe('EV02000201');
  });

  it('직접_입력이면_입력값이_대문자로_전송된다 — 표기실수는_다른_값이_아니다', () => {
    // given — 관제가 코드를 넓혔을 때 쓰는 통로
    const form = {
      ...initialUnifiedUploadForm(),
      categoryKey: EVENT_TYPE_MANUAL_OPTION,
      evntTypeCd: ' ev09000101 ',
    };

    // when/then — 센티넬이 아니라 입력값이며, 대문자로 정규화된다
    expect(resolveEventTypeCd(form, EVENT_OPTIONS)).toBe('EV09000101');
  });

  it('그룹을_못_찾으면_빈_값이다 — 추측해_채우지_않는다', () => {
    // given — 옵션 로드 실패·미등록 대표코드
    const form = { ...initialUnifiedUploadForm(), categoryKey: '999999' };

    // when/then — 엉뚱한 유형으로 적재되는 것보다 서버가 거부하는 편이 안전하다
    expect(resolveEventTypeCd(form, EVENT_OPTIONS)).toBe('');
    expect(resolveEventTypeCd(initialUnifiedUploadForm(), EVENT_OPTIONS)).toBe('');
  });
});

describe('단일 업로드 폼 — 즉시 실행 경로 payload', () => {
  it('즉시_실행_payload_에는_그_경로_계약의_필드만_실린다', () => {
    // given — 모든 칸을 채운 폼
    const form = filledForm();

    // when
    const payload = toImmediatePayload(form, 'EV03000101');

    // then — BE AutolabelTestRequest 6필드 정확히 그대로(추가 키 0)
    expect(Object.keys(payload).sort()).toEqual(
      ['capturedAt', 'cctvId', 'eventTypeCd', 'localGovCd', 'prvcTypeCd', 'vmsClipId'].sort(),
    );
    expect(payload.vmsClipId).toBe('clip-001');
    expect(payload.cctvId).toBe('CCTV-001');
    expect(payload.eventTypeCd).toBe('EV03000101');
    // 지자체코드는 폼의 `lclgvCd` 를 계약 이름 `localGovCd` 로 옮겨 싣는다
    expect(payload.localGovCd).toBe('11680');
    expect(payload.prvcTypeCd).toBe(PrvcType.PRVC);
    // 촬영일시는 timezone 없는 로컬값을 ISO Instant 로 보정해 보낸다
    expect(payload.capturedAt).toBe(toIsoInstant('2026-05-12T10:00'));
    expect(payload.capturedAt).toMatch(/Z$/);
  });

  it('즉시_실행_경로에서_전송되지_않는_입력이_명시적으로_열거돼_있다', () => {
    // given/when — 화면 안내(조용한 손실 차단)의 단일 원천
    const groups = UNSENT_GROUPS[UploadRoute.IMMEDIATE];

    // then — 계약에 없는 4묶음. 화면이 이 목록으로 "이 경로에서는 전송되지 않음"을 알린다.
    expect(groups).toEqual(['출처유형', '위치 · CCTV 제원', '이벤트 · 관제일지', '영상 기술메타']);
  });

  it('기술메타만_서버가_파일에서_읽어_채우는_묶음으로_표시된다', () => {
    // given/when — «전송되지 않는다» 와 «채워지지 않는다» 는 다른 축이다.
    //   서버는 올린 파일을 조사해 video.* 기술메타를 채우므로 이 묶음만 안내 문구가 다르다.
    // then
    expect(SERVER_FILLED_GROUPS).toEqual(['영상 기술메타']);
    // 파일에서 읽을 수 없는 묶음은 진짜로 버려진다 — 여기에 들어오면 화면이 거짓을 말한다.
    expect(SERVER_FILLED_GROUPS).not.toContain('위치 · CCTV 제원');
    expect(SERVER_FILLED_GROUPS).not.toContain('이벤트 · 관제일지');
    expect(SERVER_FILLED_GROUPS).not.toContain('출처유형');
    // 안내는 «그 경로에서 전송되지 않는 묶음» 에만 뜨므로 이 목록은 그 부분집합이어야 한다.
    expect(UNSENT_GROUPS[UploadRoute.IMMEDIATE]).toEqual(
      expect.arrayContaining([...SERVER_FILLED_GROUPS]),
    );
  });

  it('영상_길이는_즉시_실행_payload_에_실리지_않는다 — 서버가_ffprobe_로_채운다', () => {
    // given — 기술메타 묶음에 영상길이(초)를 입력한 상태
    const form = { ...filledForm(), vdoLenSec: '120' };

    // when/then — 사용자 입력이 durationSec 로 새지 않는다(위·변조 차단)
    const payload = toImmediatePayload(form, 'EV03000101') as unknown as Record<string, unknown>;
    expect(payload).not.toHaveProperty('durationSec');
    expect(payload).not.toHaveProperty('vdoLenSec');
  });
});

describe('단일 업로드 폼 — 인입 재현 경로 payload', () => {
  it('인입_재현_payload_에는_그_경로_계약의_필드만_실린다', () => {
    // given
    const form = filledForm();

    // when
    const payload = toIngestPayload(form, {
      fileName: 'clip.mp4',
      eventTypeCd: 'EV03000101',
    }) as unknown as Record<string, unknown>;

    // then — 개인정보 유형은 인입 계약에 없으므로 실리지 않는다
    expect(payload).not.toHaveProperty('prvcTypeCd');
    // 즉시 실행 계약의 이름(localGovCd/capturedAt)도 쓰지 않는다 — 인입 이름은 lclgvCd/shtDt
    expect(payload).not.toHaveProperty('localGovCd');
    expect(payload).not.toHaveProperty('capturedAt');
    expect(payload.fileName).toBe('clip.mp4');
    expect(payload.lclgvCd).toBe('11680');
    expect(payload.shtDt).toBe('2026-05-12T10:00');
    expect(payload.srcType).toBe('RELAY');
    // 즉시 실행 경로가 버리는 항목들이 여기서는 전부 실린다
    expect(payload.lclgvNm).toBe('강남구');
    expect(payload.cctvNm).toBe('역삼로 교차로');
    expect(payload.cctvHgt).toBe(4.5);
    expect(payload.wgs84Lat).toBe(37.5);
    expect(payload.mainSurvPanAng).toBe(180);
    expect(payload.evntId).toBe('ABA_0001');
    expect(payload.mntrCn).toBe('관제일지 본문');
    expect(payload.vrfcEvntTypeCd).toBe('fire');
    expect(payload.vdoLenSec).toBe(60);
    expect(payload.pxl).toBe('4K');
  });

  it('두_경로가_같은_이벤트유형코드를_보낸다', () => {
    // given — 같은 폼·같은 확정값
    const form = filledForm();

    // when
    const immediate = toImmediatePayload(form, 'EV03000101');
    const ingest = toIngestPayload(form, { fileName: 'clip.mp4', eventTypeCd: 'EV03000101' });

    // then — 계약상 키 이름만 다르고 값은 같다(경로별로 유형이 갈리면 시험 자체가 무의미)
    expect(immediate.eventTypeCd).toBe('EV03000101');
    expect(ingest.evntTypeCd).toBe('EV03000101');
  });

  it('이벤트유형이_미지정이면_인입_payload_에서_키_자체를_보내지_않는다', () => {
    // given — 관제 미송신 상태의 재현
    const form = filledForm();

    // when
    const payload = toIngestPayload(form, { fileName: 'clip.mp4', eventTypeCd: '' });

    // then — 빈 문자열이 아니라 `undefined` → 직렬화 시 **키 자체가 사라진다**.
    // BE 는 "값 없음(null)"과 ""를 구분하므로 빈 문자열을 실으면 미지정이 지정으로 뒤바뀐다.
    expect(payload.evntTypeCd).toBeUndefined();
    expect('evntTypeCd' in JSON.parse(JSON.stringify(payload))).toBe(false);
  });

  it('이벤트유형코드는_소문자로_넘겨도_대문자로_전송된다', () => {
    // given/when
    const payload = toIngestPayload(filledForm(), {
      fileName: 'clip.mp4',
      eventTypeCd: 'ev03000101',
    });

    // then — 관제 코드 체계가 대문자 표기라 소문자는 표기 실수다
    expect(payload.evntTypeCd).toBe('EV03000101');
  });

  it('인입_재현_경로에서_전송되지_않는_입력이_명시적으로_열거돼_있다', () => {
    expect(UNSENT_GROUPS[UploadRoute.INGEST]).toEqual(['개인정보 유형']);
  });
});

describe('단일 업로드 폼 — 업로드 시작 가능 여부', () => {
  it('파일이_없으면_시작할_수_없다', () => {
    expect(
      canStartUpload({
        file: null,
        form: initialUnifiedUploadForm(),
        route: UploadRoute.INGEST,
        eventTypeCd: 'EV03000101',
      }),
    ).toBe(false);
  });

  it('식별_정보_세_항목이_비면_어느_경로에서도_시작할_수_없다', () => {
    // given — 두 경로 공통 필수 3항목: 클립 ID · CCTV ID · 지자체코드
    const required = ['vmsClipId', 'cctvId', 'lclgvCd'] as const;

    for (const key of required) {
      for (const route of [UploadRoute.IMMEDIATE, UploadRoute.INGEST]) {
        // when — 그 한 칸만 비운다
        const form = { ...initialUnifiedUploadForm(), [key]: '' };

        // then
        expect(
          canStartUpload({ file: file(10), form, route, eventTypeCd: 'EV03000101' }),
          `${key} / ${route}`,
        ).toBe(false);
      }
    }
  });

  /**
   * ★출처유형은 어느 경로에서도 시작을 막지 않는다 — 서버가 요구하지 않는 값이기 때문이다.
   *
   * 즉시 실행은 계약에 그 필드가 아예 없어 전송조차 되지 않고(`UNSENT_GROUPS[IMMEDIATE]` 에
   * '출처유형' 이 들어 있다), 인입 재현은 비우면 서버가 기본값을 붙인다. 구 동작은 경로를 가리지
   * 않고 이 값을 요구해, 같은 화면 영역에서 「전송되지 않습니다」 안내와 필수 강제를 동시에
   * 내보내고 있었다. **이 케이스가 그 강제의 부활을 막는 축이다** — 위 세 항목 케이스는
   * 출처유형이 다시 필수가 되어도 통과한다(그 검사가 없어도 다른 이유로 false 이므로).
   */
  it('★출처유형이_비어도_두_경로_모두_시작할_수_있다 — 서버가_요구하지_않는다', () => {
    // given — 다른 필수는 모두 채워진 채 출처유형만 비운다
    const form = { ...initialUnifiedUploadForm(), srcType: '' };

    // then — 즉시 실행: 그 경로에서는 전송조차 되지 않는 값이다
    expect(
      canStartUpload({
        file: file(10),
        form,
        route: UploadRoute.IMMEDIATE,
        eventTypeCd: 'EV03000101',
      }),
      'IMMEDIATE',
    ).toBe(true);
    // then — 인입 재현: 전송되지만 비우면 서버가 기본값을 붙인다
    expect(
      canStartUpload({ file: file(10), form, route: UploadRoute.INGEST, eventTypeCd: '' }),
      'INGEST',
    ).toBe(true);
  });

  it('선택_묶음이_전부_비어도_시작할_수_있다', () => {
    // given — 진입 직후 상태(선택 묶음 전부 빈 값)
    const form = initialUnifiedUploadForm();

    // when/then — 두 경로 모두 시작 가능해야 한다(입력 부담 완화)
    expect(
      canStartUpload({ file: file(10), form, route: UploadRoute.INGEST, eventTypeCd: '' }),
    ).toBe(true);
    expect(
      canStartUpload({
        file: file(10),
        form,
        route: UploadRoute.IMMEDIATE,
        eventTypeCd: 'EV03000101',
      }),
    ).toBe(true);
  });

  it('즉시_실행_경로는_이벤트유형과_촬영일시가_추가로_필요하다 — BE_계약이_요구한다', () => {
    // given
    const form = initialUnifiedUploadForm();

    // when/then — 이벤트유형 미확정
    expect(
      canStartUpload({ file: file(10), form, route: UploadRoute.IMMEDIATE, eventTypeCd: '' }),
    ).toBe(false);
    // 인입 재현은 둘 다 선택이라 시작 가능하다(같은 폼, 다른 계약)
    expect(
      canStartUpload({ file: file(10), form, route: UploadRoute.INGEST, eventTypeCd: '' }),
    ).toBe(true);

    // when/then — 촬영일시를 비운 경우
    const noDate = { ...form, shtDtLocal: '' };
    expect(
      canStartUpload({
        file: file(10),
        form: noDate,
        route: UploadRoute.IMMEDIATE,
        eventTypeCd: 'EV03000101',
      }),
    ).toBe(false);
    expect(
      canStartUpload({
        file: file(10),
        form: noDate,
        route: UploadRoute.INGEST,
        eventTypeCd: '',
      }),
    ).toBe(true);
  });
});

describe('단일 업로드 폼 — 통째 전송 한도', () => {
  it('즉시_실행_경로에만_500MB_한도가_적용된다', () => {
    // given — 한도를 1바이트 넘긴 파일
    const tooBig = file(0);
    Object.defineProperty(tooBig, 'size', { value: MULTIPART_MAX_BYTES + 1 });
    const form = initialUnifiedUploadForm();

    // when/then — 즉시 실행은 시작이 막히고 사유가 안내된다
    expect(
      canStartUpload({
        file: tooBig,
        form,
        route: UploadRoute.IMMEDIATE,
        eventTypeCd: 'EV03000101',
      }),
    ).toBe(false);
    expect(multipartLimitMessage(tooBig, UploadRoute.IMMEDIATE)).toContain('500MB');

    // when/then — 인입 재현은 청크로 나눠 보내므로 이 한도를 받지 않는다
    expect(
      canStartUpload({ file: tooBig, form, route: UploadRoute.INGEST, eventTypeCd: '' }),
    ).toBe(true);
    expect(multipartLimitMessage(tooBig, UploadRoute.INGEST)).toBeNull();
  });

  it('한도_이내_파일은_안내가_없다', () => {
    const ok = file(0);
    Object.defineProperty(ok, 'size', { value: MULTIPART_MAX_BYTES });
    expect(multipartLimitMessage(ok, UploadRoute.IMMEDIATE)).toBeNull();
    expect(multipartLimitMessage(null, UploadRoute.IMMEDIATE)).toBeNull();
  });
});
