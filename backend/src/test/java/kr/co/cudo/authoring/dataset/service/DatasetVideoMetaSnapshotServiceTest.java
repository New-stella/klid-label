package kr.co.cudo.authoring.dataset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import kr.co.cudo.authoring.dataset.repository.DatasetMetaSourceRepository;
import kr.co.cudo.authoring.dataset.repository.DatasetMetaSourceRow;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.repository.LsMetaReplOutboxRepository;
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
    private DatasetVideoMetaSnapshotService service;

    @BeforeEach
    void setUp() {
        sourceRepository = mock(DatasetMetaSourceRepository.class);
        metaRepository = mock(LsDatasetVideoMetaRepository.class);
        outboxRepository = mock(LsMetaReplOutboxRepository.class);
        service = new DatasetVideoMetaSnapshotService(
                sourceRepository, metaRepository, outboxRepository,
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
