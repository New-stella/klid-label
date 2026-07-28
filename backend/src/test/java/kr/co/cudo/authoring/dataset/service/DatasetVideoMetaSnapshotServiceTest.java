package kr.co.cudo.authoring.dataset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import kr.co.cudo.authoring.dataset.repository.DatasetMetaSourceRepository;
import kr.co.cudo.authoring.dataset.repository.DatasetMetaSourceRow;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.repository.LsMetaReplOutboxRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2 materialize 어댑터 단위 검증 — 락·순서(deactivate-then-insert)·멱등·MNG 동결·outbox.
 */
class DatasetVideoMetaSnapshotServiceTest {

    private DatasetMetaSourceRepository sourceRepository;
    private LsDatasetVideoMetaRepository metaRepository;
    private LsMetaReplOutboxRepository outboxRepository;
    private LsEvntAnnoRepository evntAnnoRepository;
    private LsEvntAnnoReviewRepository evntAnnoReviewRepository;
    private DatasetVideoMetaSnapshotService service;

    @BeforeEach
    void setUp() {
        sourceRepository = mock(DatasetMetaSourceRepository.class);
        metaRepository = mock(LsDatasetVideoMetaRepository.class);
        outboxRepository = mock(LsMetaReplOutboxRepository.class);
        evntAnnoRepository = mock(LsEvntAnnoRepository.class);
        evntAnnoReviewRepository = mock(LsEvntAnnoReviewRepository.class);
        service = new DatasetVideoMetaSnapshotService(
                sourceRepository, metaRepository, outboxRepository,
                evntAnnoRepository, evntAnnoReviewRepository,
                new SnapshotHasher(), new ObjectMapper());
        // 기본: 신규 삽입(1행).
        when(metaRepository.upsertSnapshot(any())).thenReturn(1);
    }

    private DatasetMetaSourceRow sourceRow(long rawSn) {
        DatasetMetaSourceRow row = mock(DatasetMetaSourceRow.class);
        when(row.getRawSn()).thenReturn(rawSn);
        when(row.getOrgnlRawSn()).thenReturn(null);
        when(row.getVmsClipId()).thenReturn("CLIP-1");
        when(row.getVmsCctvId()).thenReturn("CCTV-001");
        when(row.getRawFilePathNm()).thenReturn("/nas/raw/" + rawSn + ".mp4");
        when(row.getShtDt()).thenReturn(LocalDateTime.of(2026, 7, 13, 21, 0)); // 야간/여름
        when(row.getVdoLenSec()).thenReturn(30);
        when(row.getLclgvCd()).thenReturn("1111000000");
        when(row.getPrvcYn()).thenReturn("Y");
        when(row.getPrvcTypeCd()).thenReturn("PRVC");
        when(row.getDeIdentYn()).thenReturn("Y");
        when(row.getEvntTypeCd()).thenReturn("EVT01");
        when(row.getCctvNm()).thenReturn("교차로 CCTV");
        when(row.getWgs84Lat()).thenReturn(new BigDecimal("37.5665000"));
        when(row.getWgs84Lot()).thenReturn(new BigDecimal("126.9780000"));
        when(row.getSidoNm()).thenReturn("서울특별시");
        when(row.getSggNm()).thenReturn("중구");
        when(row.getFileFmt()).thenReturn("mp4");
        when(row.getEvntNm()).thenReturn("보행자 감지");
        when(row.getVideoCodec()).thenReturn("h264");
        when(row.getVideoFps()).thenReturn("25");
        when(row.getVideoBitRate()).thenReturn("4000000");
        when(row.getVideoDurationMs()).thenReturn("30000");
        when(row.getVideoFilesize()).thenReturn("15000000");
        when(row.getVideoResolution()).thenReturn("1920x1080");
        return row;
    }

    @Test
    @DisplayName("승인시_통합메타_동결_적재_및_ACTIVE_전환")
    void materialize_locksDeactivatesInsertsAndOutbox() {
        // given
        long rawSn = 100L;
        DatasetMetaSourceRow row = sourceRow(rawSn);
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        // when
        service.materialize(rawSn);

        // then — 락 → deactivatePrevious → upsertSnapshot 순서(deactivate-then-insert) + outbox 저장.
        InOrder ordered = inOrder(metaRepository, outboxRepository);
        ordered.verify(metaRepository).acquireRawLock(rawSn);
        ordered.verify(metaRepository).deactivatePrevious(eq(rawSn), anyString());
        ordered.verify(metaRepository).upsertSnapshot(any(LsDatasetVideoMeta.class));
        ordered.verify(outboxRepository).save(any(LsMetaReplOutbox.class));

        // 삽입 성공(1행)이므로 재활성(activateByHash)은 호출하지 않는다.
        verify(metaRepository, never()).activateByHash(anyLong(), anyString());

        // 적재 엔티티는 ACTIVE='Y' 이고 파생 컬럼이 채워진다.
        ArgumentCaptor<LsDatasetVideoMeta> captor = ArgumentCaptor.forClass(LsDatasetVideoMeta.class);
        verify(metaRepository).upsertSnapshot(captor.capture());
        LsDatasetVideoMeta e = captor.getValue();
        assertThat(e.getActiveYn()).isEqualTo("Y");
        assertThat(e.getRawSn()).isEqualTo(rawSn);
        assertThat(e.getDayNgtCd()).isEqualTo("NGT");   // 21시 → 야간
        assertThat(e.getSesnCd()).isEqualTo("SUMMER");  // 7월 → 여름
        assertThat(e.getAiCrtYn()).isEqualTo("N");      // orgnlRawSn null
        assertThat(e.getVdoWdth()).isEqualTo(1920);
        assertThat(e.getVdoHgt()).isEqualTo(1080);
        assertThat(e.getAsprtRt()).isEqualByComparingTo("1.777778");
        assertThat(e.getFps()).isEqualByComparingTo("25");
        assertThat(e.getBitRt()).isEqualTo(4_000_000L);
        assertThat(e.getFileSz()).isEqualTo(15_000_000L);
        assertThat(e.getSnpshtHash()).hasSize(64);
    }

    @Test
    @DisplayName("MNG_조인_결과_동결_적재")
    void materialize_freezesMngJoinedValues() {
        long rawSn = 101L;
        DatasetMetaSourceRow row = sourceRow(rawSn);
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        service.materialize(rawSn);

        ArgumentCaptor<LsDatasetVideoMeta> captor = ArgumentCaptor.forClass(LsDatasetVideoMeta.class);
        verify(metaRepository).upsertSnapshot(captor.capture());
        LsDatasetVideoMeta e = captor.getValue();
        assertThat(e.getCctvNm()).isEqualTo("교차로 CCTV");
        assertThat(e.getWgs84Lat()).isEqualByComparingTo("37.5665000");
        assertThat(e.getWgs84Lot()).isEqualByComparingTo("126.9780000");
        assertThat(e.getSidoNm()).isEqualTo("서울특별시");
        assertThat(e.getSggNm()).isEqualTo("중구");
        assertThat(e.getFileFmt()).isEqualTo("mp4");
        assertThat(e.getEvntNm()).isEqualTo("보행자 감지");
        assertThat(e.getVdoCdc()).isEqualTo("h264");
    }

    @Test
    @DisplayName("동일메타_재승인시_동일해시_멱등")
    void materialize_sameSource_sameHash() {
        long rawSn = 102L;
        DatasetMetaSourceRow row = sourceRow(rawSn);
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        service.materialize(rawSn);
        service.materialize(rawSn);

        ArgumentCaptor<LsDatasetVideoMeta> captor = ArgumentCaptor.forClass(LsDatasetVideoMeta.class);
        verify(metaRepository, times(2)).upsertSnapshot(captor.capture());
        String first = captor.getAllValues().get(0).getSnpshtHash();
        String second = captor.getAllValues().get(1).getSnpshtHash();
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("upsert_0행이면_activateByHash로_재활성")
    void materialize_reactivatesWhenConflict() {
        long rawSn = 103L;
        DatasetMetaSourceRow row = sourceRow(rawSn);
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);
        when(metaRepository.upsertSnapshot(any())).thenReturn(0); // 동일 (rawSn,hash) 이미 존재

        service.materialize(rawSn);

        verify(metaRepository).activateByHash(eq(rawSn), anyString());
        // 재활성 경로에서도 outbox 는 발행된다(포털 복제 재보장).
        verify(outboxRepository).save(any(LsMetaReplOutbox.class));
    }

    @Test
    @DisplayName("수동_촬영환경값이_있으면_파생값보다_우선_동결된다")
    void materialize_freezesManualEnvironmentOverDerived() {
        // given — 파생상 야간/여름인 영상에 수동값(주간·겨울·비)이 저장돼 있음
        long rawSn = 104L;
        DatasetMetaSourceRow row = sourceRow(rawSn);
        when(row.getWthrNm()).thenReturn("비");
        when(row.getDayNgtCd()).thenReturn("DAY");
        when(row.getSesnCd()).thenReturn("WINTER");
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        // when
        service.materialize(rawSn);

        // then — 스냅샷에 수동값이 동결된다
        ArgumentCaptor<LsDatasetVideoMeta> captor = ArgumentCaptor.forClass(LsDatasetVideoMeta.class);
        verify(metaRepository).upsertSnapshot(captor.capture());
        LsDatasetVideoMeta e = captor.getValue();
        assertThat(e.getWthrNm()).isEqualTo("비");
        assertThat(e.getDayNgtCd()).isEqualTo("DAY");
        assertThat(e.getSesnCd()).isEqualTo("WINTER");
    }

    @Test
    @DisplayName("수동_촬영환경값이_없으면_기존_파생값을_동결한다")
    void materialize_fallsBackToDerivedEnvironment() {
        // given — 수동값 미입력(회귀 방어)
        long rawSn = 105L;
        DatasetMetaSourceRow row = sourceRow(rawSn);
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        // when
        service.materialize(rawSn);

        // then — 기존 동작(SHT_DT 파생) 유지
        ArgumentCaptor<LsDatasetVideoMeta> captor = ArgumentCaptor.forClass(LsDatasetVideoMeta.class);
        verify(metaRepository).upsertSnapshot(captor.capture());
        LsDatasetVideoMeta e = captor.getValue();
        assertThat(e.getWthrNm()).isNull();
        assertThat(e.getDayNgtCd()).isEqualTo("NGT");
        assertThat(e.getSesnCd()).isEqualTo("SUMMER");
    }

    @Test
    @DisplayName("수동_날씨값이_바뀌면_스냅샷_해시도_달라진다")
    void materialize_weatherIsPartOfHash() {
        // given — 동일 소스에서 날씨만 다른 두 승인
        long rawSn = 106L;
        DatasetMetaSourceRow first = sourceRow(rawSn);
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(first);
        service.materialize(rawSn);

        DatasetMetaSourceRow second = sourceRow(rawSn);
        when(second.getWthrNm()).thenReturn("안개");
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(second);
        service.materialize(rawSn);

        // then — 동결 내용이 달라졌으므로 새 버전(다른 해시)
        ArgumentCaptor<LsDatasetVideoMeta> captor = ArgumentCaptor.forClass(LsDatasetVideoMeta.class);
        verify(metaRepository, times(2)).upsertSnapshot(captor.capture());
        assertThat(captor.getAllValues().get(0).getSnpshtHash())
                .isNotEqualTo(captor.getAllValues().get(1).getSnpshtHash());
    }

    @Test
    @DisplayName("날씨_미입력_영상은_배포_전후_동일_해시를_유지한다(하위호환)")
    void materialize_weatherKeyOmittedFromHashWhenBlank() {
        // given — 날씨 미입력(WTHR_NM 도입 이전과 동일 내용)인 영상. 해시 입력 맵을 포착한다.
        long rawSn = 107L;
        SnapshotHasher hasher = mock(SnapshotHasher.class);
        when(hasher.hash(any())).thenReturn("a".repeat(64));
        DatasetVideoMetaSnapshotService svc = new DatasetVideoMetaSnapshotService(
                sourceRepository, metaRepository, outboxRepository,
                evntAnnoRepository, evntAnnoReviewRepository, hasher, new ObjectMapper());
        DatasetMetaSourceRow row = sourceRow(rawSn);
        when(row.getWthrNm()).thenReturn(null);
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        // when
        svc.materialize(rawSn);

        // then — 해시 입력에 WTHR_NM 키 자체가 없어야 배포 전(구 스킴) 해시와 동일하다.
        //        (null 도 "-1:" 토큰으로 해싱되므로 키를 넣으면 내용 무변경인데도 해시가 달라진다)
        assertThat(captureHashFields(hasher)).doesNotContainKey("WTHR_NM");
    }

    @Test
    @DisplayName("날씨_공백문자열도_해시_입력에서_생략된다")
    void materialize_blankWeatherKeyOmittedFromHash() {
        // given — 공백만 있는 날씨(미입력과 동치)
        long rawSn = 108L;
        SnapshotHasher hasher = mock(SnapshotHasher.class);
        when(hasher.hash(any())).thenReturn("b".repeat(64));
        DatasetVideoMetaSnapshotService svc = new DatasetVideoMetaSnapshotService(
                sourceRepository, metaRepository, outboxRepository,
                evntAnnoRepository, evntAnnoReviewRepository, hasher, new ObjectMapper());
        DatasetMetaSourceRow row = sourceRow(rawSn);
        when(row.getWthrNm()).thenReturn("   ");
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        // when
        svc.materialize(rawSn);

        // then — 키 생략 + 동결값도 null 정규화(값·해시 일관)
        assertThat(captureHashFields(hasher)).doesNotContainKey("WTHR_NM");
        ArgumentCaptor<LsDatasetVideoMeta> captor = ArgumentCaptor.forClass(LsDatasetVideoMeta.class);
        verify(metaRepository).upsertSnapshot(captor.capture());
        assertThat(captor.getValue().getWthrNm()).isNull();
    }

    @Test
    @DisplayName("날씨_수동값이_있으면_해시_입력에_포함된다")
    void materialize_weatherKeyIncludedWhenPresent() {
        // given — 날씨 수동 입력 영상
        long rawSn = 109L;
        SnapshotHasher hasher = mock(SnapshotHasher.class);
        when(hasher.hash(any())).thenReturn("c".repeat(64));
        DatasetVideoMetaSnapshotService svc = new DatasetVideoMetaSnapshotService(
                sourceRepository, metaRepository, outboxRepository,
                evntAnnoRepository, evntAnnoReviewRepository, hasher, new ObjectMapper());
        DatasetMetaSourceRow row = sourceRow(rawSn);
        when(row.getWthrNm()).thenReturn("눈");
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        // when
        svc.materialize(rawSn);

        // then — 값이 있으면 해시에 포함되어 "날씨만 정정해도 새 버전 append" 가 유지된다
        assertThat(captureHashFields(hasher)).containsEntry("WTHR_NM", "눈");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> captureHashFields(SnapshotHasher hasher) {
        ArgumentCaptor<java.util.Map<String, String>> captor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(hasher).hash(captor.capture());
        return captor.getValue();
    }

    /**
     * 파생영상(증강) 소스 — 부모 참조({@code ORGNL_RAW_SN})가 있고, {@code RAW_FILE_PATH_NM} 에는
     * 결함 재현을 위해 <b>부모의 비식별 이전 원본 NAS 경로</b>가 실려 있는 상태를 만든다.
     */
    private DatasetMetaSourceRow derivativeSourceRow(long rawSn, long parentRawSn, String rawFilePathNm) {
        DatasetMetaSourceRow row = sourceRow(rawSn);
        when(row.getOrgnlRawSn()).thenReturn(parentRawSn);
        when(row.getRawFilePathNm()).thenReturn(rawFilePathNm);
        return row;
    }

    @Test
    @DisplayName("증강_파생영상의_ORIGINAL_VIDEO_PATH_는_NULL_이다")
    void materialize_freezesNullOriginalPathForAugmentDerivative() {
        // given — 증강 파생영상(부모 참조 존재). 관제 뷰 V_COMPLETED_VIDEO.ORIGINAL_VIDEO_PATH 는
        //         이 동결 컬럼(m.RAW_FILE_PATH_NM)을 그대로 노출한다.
        long rawSn = 300L;
        // 중첩 when() 회피 — 행 mock 을 먼저 완성한 뒤 리포지토리를 stub 한다.
        DatasetMetaSourceRow row = derivativeSourceRow(rawSn, 100L, "/nas/raw/100.mp4");
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        // when
        service.materialize(rawSn);

        // then — 파생에는 원본이 없으므로 null 동결(관제에 원본 경로 미노출).
        ArgumentCaptor<LsDatasetVideoMeta> captor = ArgumentCaptor.forClass(LsDatasetVideoMeta.class);
        verify(metaRepository).upsertSnapshot(captor.capture());
        assertThat(captor.getValue().getRawFilePathNm()).isNull();
        assertThat(captor.getValue().getAiCrtYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("파생영상에_부모의_비식별_이전_원본경로가_기록되지_않는다")
    void materialize_neverLeaksParentOriginalPathForDerivative() {
        // given — 소스에 부모 원본 NAS 경로가 실려 있어도(구 폴백 잔재 포함)
        long rawSn = 301L;
        String parentOriginalPath = "/nas/raw/pii-source-100.mp4";
        DatasetMetaSourceRow row = derivativeSourceRow(rawSn, 100L, parentOriginalPath);
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        // when
        service.materialize(rawSn);

        // then — 동결 엔티티에도, 포털 복제 outbox payload 에도 그 경로가 실리지 않는다(CWE-359).
        ArgumentCaptor<LsDatasetVideoMeta> captor = ArgumentCaptor.forClass(LsDatasetVideoMeta.class);
        verify(metaRepository).upsertSnapshot(captor.capture());
        assertThat(captor.getValue().getRawFilePathNm()).isNull();

        ArgumentCaptor<LsMetaReplOutbox> outboxCaptor = ArgumentCaptor.forClass(LsMetaReplOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getPayload()).doesNotContain(parentOriginalPath);
    }

    @Test
    @DisplayName("해상도_파생도_동일하게_원본경로를_노출하지_않는다")
    void materialize_freezesNullOriginalPathForResolutionDerivative() {
        // given — 해상도 파생영상. RAW_FILE_PATH_NM 은 파생 자신의 비식별 사본 경로다(원본 아님).
        long rawSn = 302L;
        String derivativeCopyPath = "/nas-storage/videos/resolution/100/302/RESL_720P.mp4";
        DatasetMetaSourceRow row = derivativeSourceRow(rawSn, 100L, derivativeCopyPath);
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        // when
        service.materialize(rawSn);

        // then — 파생 판별은 ORGNL_RAW_SN 하나이므로 증강/해상도 두 경로가 동일하게 정합된다.
        ArgumentCaptor<LsDatasetVideoMeta> captor = ArgumentCaptor.forClass(LsDatasetVideoMeta.class);
        verify(metaRepository).upsertSnapshot(captor.capture());
        assertThat(captor.getValue().getRawFilePathNm()).isNull();
    }

    @Test
    @DisplayName("파생영상_동결_해시도_동결값_기준이다")
    void materialize_hashUsesFrozenNullPathForDerivative() {
        // given
        long rawSn = 303L;
        SnapshotHasher hasher = mock(SnapshotHasher.class);
        when(hasher.hash(any())).thenReturn("d".repeat(64));
        DatasetVideoMetaSnapshotService svc = new DatasetVideoMetaSnapshotService(
                sourceRepository, metaRepository, outboxRepository,
                evntAnnoRepository, evntAnnoReviewRepository, hasher, new ObjectMapper());
        DatasetMetaSourceRow row = derivativeSourceRow(rawSn, 100L, "/nas/raw/100.mp4");
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(row);

        // when
        svc.materialize(rawSn);

        // then — 해시 입력의 RAW_FILE_PATH_NM 도 동결값(null)이어야 "동결 내용 = 해시 대표 내용" 이 성립한다.
        assertThat(captureHashFields(hasher)).containsEntry("RAW_FILE_PATH_NM", null);
    }

    @Test
    @DisplayName("소스_미조회시_동결_스킵")
    void materialize_skipsWhenSourceMissing() {
        long rawSn = 999L;
        when(sourceRepository.findSnapshotSource(rawSn)).thenReturn(null);

        service.materialize(rawSn);

        verify(metaRepository).acquireRawLock(rawSn);
        verify(metaRepository, never()).upsertSnapshot(any());
        verify(outboxRepository, never()).save(any());
    }
}
