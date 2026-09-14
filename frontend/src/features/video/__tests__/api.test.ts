import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';

import { getVideo, listVideos } from '../api';

describe('video api', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('listVideos_쿼리_파라미터가_axios_params로_전달', async () => {
    mock.onGet('/videos').reply((config) => {
      expect(config.params).toMatchObject({
        page: 0,
        size: 20,
        cctvNameKeyword: '강남',
        eventTypeCd: 'FALL',
      });
      return [
        200,
        {
          success: true,
          data: {
            content: [],
            totalElements: 0,
            totalPages: 0,
            number: 0,
            size: 20,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    const result = await listVideos({
      page: 0,
      size: 20,
      cctvNameKeyword: '강남',
      eventTypeCd: 'FALL',
    });
    expect(result.totalElements).toBe(0);
  });

  it('capturedAt은_regDt로_폴백하지_않는다', async () => {
    // given: BE 가 촬영 시각(SHT_DT) 없는 영상을 capturedAt=null 로 내려준다.
    //   수신 시각(regDt)이 함께 와도 표시값을 그것으로 채우면 '녹화일' 컬럼이 다시 수신일이 된다.
    mock.onGet('/videos').reply(200, {
      success: true,
      data: {
        content: [
          {
            rawSn: 7,
            vmsCctvId: 'CCTV-007',
            dataSttsCd: 'COMPLETED',
            capturedAt: null,
            regDt: '2026-05-30T10:00:00',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
      },
      message: null,
      errorCode: null,
    });

    // when
    const result = await listVideos({ page: 0, size: 20 });

    // then: 화면이 '-' 를 그리도록 빈 값으로 남는다
    expect(result.content[0].capturedAt).toBe('');
  });

  it('getVideo_상세_응답_정상_파싱', async () => {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        cctvName: '강남대로 CCTV',
        vmsClipId: 'VMS-42',
        eventName: '낙상',
        eventTypeCd: 'FALL',
        localGov: '강남구',
        frameCount: 900,
        status: 'COMPLETED',
        capturedAt: '2026-05-01T12:00:00Z',
        duration: 30,
        fileSizeMb: 10,
        resolution: '1920x1080',
        framePreviews: [
          { frameNo: 1, thumbnailUrl: '/t/1.jpg' },
          { frameNo: 150, thumbnailUrl: '/t/150.jpg' },
          { frameNo: 300, thumbnailUrl: '/t/300.jpg' },
          { frameNo: 450, thumbnailUrl: '/t/450.jpg' },
          { frameNo: 600, thumbnailUrl: '/t/600.jpg' },
          { frameNo: 750, thumbnailUrl: '/t/750.jpg' },
        ],
      },
      message: null,
      errorCode: null,
    });

    const detail = await getVideo(42);
    expect(detail.id).toBe(42);
    expect(detail.framePreviews).toHaveLength(6);
    expect(detail.cctvName).toBe('강남대로 CCTV');
  });

  /**
   * ★ 신설 두 필드의 **키가 아예 없는** 구 서버 응답 — 값이 `null` 인 경우와 결과가 같아야 한다.
   *
   * 정규화가 nullish 병합이라 `undefined`·`null` 을 같게 다루지만, 그건 **구현을 읽어야 아는
   * 사실**이라 시험이 직접 고정한다. 두 필드는 「없으면 화면이 빈 표시로 둔다」가 계약이고,
   * 여기서 빈 문자열·조립값으로 접히면 화면이 없는 값을 있는 것처럼 그린다.
   */
  it('getVideo_resolution과_vmsCctvId_키가_없는_구_응답은_null_로_정규화된다', async () => {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      // resolution·vmsCctvId 키를 **아예 싣지 않는다**(값 null 이 아니라 키 부재).
      data: { id: 42, cctvName: '강남대로 CCTV', status: 'COMPLETED', framePreviews: [] },
      message: null,
      errorCode: null,
    });

    const detail = await getVideo(42);
    expect(detail.resolution).toBeNull();
    expect(detail.vmsCctvId).toBeNull();
  });

  it('getVideo_resolution과_vmsCctvId_실값은_그대로_실린다_조립하지_않는다', async () => {
    mock.onGet('/videos/42').reply(200, {
      success: true,
      data: {
        id: 42,
        cctvName: 'CCTV-001',
        vmsCctvId: 'CCTV-001',
        status: 'COMPLETED',
        resolution: '1920x1440',
        framePreviews: [],
      },
      message: null,
      errorCode: null,
    });

    const detail = await getVideo(42);
    expect(detail.resolution).toBe('1920x1440');
    // 일련번호로 조립한 문자열(`video-0042`)이 아니라 서버가 준 식별자 그대로다.
    expect(detail.vmsCctvId).toBe('CCTV-001');
  });

  it('getVideo_reviewSttsCd_정상_매핑_배치단계_status_와_별개', async () => {
    // given: BE 가 배치단계 status=COMPLETED + 검수상태 reviewSttsCd=APPROVED 를 내려준다.
    mock.onGet('/videos/9').reply(200, {
      success: true,
      data: {
        id: 9,
        cctvName: 'CCTV-9',
        vmsClipId: 'VMS-9',
        status: 'COMPLETED',
        reviewSttsCd: 'APPROVED',
        capturedAt: '2026-05-01T12:00:00Z',
        frameCount: 100,
        duration: 30,
        fileSizeMb: 5,
        resolution: '1920x1080',
        framePreviews: [],
        deIdntfYn: 'N',
      },
      message: null,
      errorCode: null,
    });

    // when / then: 배치단계 status 와 검수상태 reviewSttsCd 가 독립적으로 매핑된다.
    const detail = await getVideo(9);
    expect(detail.status).toBe('COMPLETED');
    expect(detail.reviewSttsCd).toBe('APPROVED');
  });

  it('getVideo_reviewSttsCd_미존재시_undefined', async () => {
    // given: BE 응답에 reviewSttsCd 키가 없는 영상 (검수 상태 row 부재 → BE null).
    mock.onGet('/videos/10').reply(200, {
      success: true,
      data: {
        id: 10,
        cctvName: 'CCTV-10',
        vmsClipId: 'VMS-10',
        status: 'PENDING',
        capturedAt: '2026-05-01T12:00:00Z',
        frameCount: 0,
        duration: 30,
        fileSizeMb: 5,
        resolution: '1920x1080',
        framePreviews: [],
      },
      message: null,
      errorCode: null,
    });

    const detail = await getVideo(10);
    expect(detail.reviewSttsCd).toBeUndefined();
  });

  it('M1_getVideo_deIdntfYn_정상_매핑', async () => {
    // given: BE 가 비식별 완료('Y') 코드를 내려준다.
    mock.onGet('/videos/7').reply(200, {
      success: true,
      data: {
        id: 7,
        cctvName: 'CCTV-7',
        vmsClipId: 'VMS-7',
        status: 'APPROVED',
        capturedAt: '2026-05-01T12:00:00Z',
        frameCount: 100,
        duration: 30,
        fileSizeMb: 5,
        resolution: '1920x1080',
        framePreviews: [],
        deIdntfYn: 'Y',
      },
      message: null,
      errorCode: null,
    });

    // when / then: normalizeVideo 가 deIdntfYn 을 매핑해 노출한다.
    const detail = await getVideo(7);
    expect(detail.deIdntfYn).toBe('Y');
  });

  it('L1_getVideo_deIdntfYn_미존재시_undefined_재비식별_대상', async () => {
    // given: BE 응답에 deIdntfYn 키가 없는 영상 (미처리/구버전 응답).
    mock.onGet('/videos/8').reply(200, {
      success: true,
      data: {
        id: 8,
        cctvName: 'CCTV-8',
        vmsClipId: 'VMS-8',
        status: 'APPROVED',
        capturedAt: '2026-05-01T12:00:00Z',
        frameCount: 100,
        duration: 30,
        fileSizeMb: 5,
        resolution: '1920x1080',
        framePreviews: [],
      },
      message: null,
      errorCode: null,
    });

    // when: deIdntfYn 미존재
    const detail = await getVideo(8);

    // then: undefined → 노출 가드(deIdntfYn !== 'Y')에서 재비식별 버튼 노출 대상으로 의도됨.
    expect(detail.deIdntfYn).toBeUndefined();
    expect(detail.deIdntfYn !== 'Y').toBe(true);
  });

  // ───────── 검증 이벤트 유형 전체 목록 (2026-09-14) [@design API-043] ─────────

  it('getVideo_allVrfcEvntTypes_는_서버_순서_그대로_실린다_다시_정렬하지_않는다', async () => {
    // given — BE 가 정렬순서 오름차순으로 내려준다(동률은 코드 오름차순).
    mock.onGet('/videos/43').reply(200, {
      success: true,
      data: {
        id: 43,
        cctvName: 'CCTV',
        status: 'COMPLETED',
        framePreviews: [],
        allVrfcEvntTypes: [
          { vrfcEvntTypeCd: 'car_accident', vrfcEvntTypeNm: '교통사고' },
          { vrfcEvntTypeCd: 'fire', vrfcEvntTypeNm: '화재' },
        ],
      },
      message: null,
      errorCode: null,
    });

    const detail = await getVideo(43);

    expect(detail.allVrfcEvntTypes).toEqual([
      { vrfcEvntTypeCd: 'car_accident', vrfcEvntTypeNm: '교통사고' },
      { vrfcEvntTypeCd: 'fire', vrfcEvntTypeNm: '화재' },
    ]);
  });

  it('getVideo_allVrfcEvntTypes_코드나_이름이_빈_항목은_걸러진다', async () => {
    // 코드가 없으면 이름을 찾을 키가 없고, 이름이 비면 코드만 보이는 것과 같아 항목이 무의미하다.
    mock.onGet('/videos/44').reply(200, {
      success: true,
      data: {
        id: 44,
        cctvName: 'CCTV',
        status: 'COMPLETED',
        framePreviews: [],
        allVrfcEvntTypes: [
          { vrfcEvntTypeCd: 'fire', vrfcEvntTypeNm: '화재' },
          { vrfcEvntTypeCd: '', vrfcEvntTypeNm: '이름만' },
          { vrfcEvntTypeCd: 'only_code', vrfcEvntTypeNm: '   ' },
          null,
        ],
      },
      message: null,
      errorCode: null,
    });

    const detail = await getVideo(44);

    expect(detail.allVrfcEvntTypes).toEqual([{ vrfcEvntTypeCd: 'fire', vrfcEvntTypeNm: '화재' }]);
  });

  it('★getVideo_allVrfcEvntTypes_키가_없는_구_응답은_빈_배열이다_selectable_과_서로_대신하지_않는다', async () => {
    // given — 구 서버(배포 스큐). 관제 값이 있는 영상이라 selectable 은 <b>빈 배열이 정상</b>이다.
    mock.onGet('/videos/45').reply(200, {
      success: true,
      data: {
        id: 45,
        cctvName: 'CCTV',
        status: 'COMPLETED',
        framePreviews: [],
        vrfcEvntTypeCd: 'car_accident',
        selectableVrfcEvntTypes: [],
      },
      message: null,
      errorCode: null,
    });

    const detail = await getVideo(45);

    // then — 빈 배열로 정규화해 화면 분기를 하나로 유지한다(이름을 못 찾아 코드만 보일 뿐이다).
    expect(detail.allVrfcEvntTypes).toEqual([]);
    // then — ★두 목록은 서로를 채우지 않는다. selectable 이 「비었는가」로 유형 선택 노출을 가르는
    //   계약이라, 전체 목록을 그 자리에 채우면 모든 영상에서 유형 선택이 뜬다.
    expect(detail.selectableVrfcEvntTypes).toEqual([]);
  });

});
