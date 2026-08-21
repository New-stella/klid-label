package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.transfer.dto.ImportCreateRequest;
import kr.co.cudo.authoring.transfer.dto.ImportCreateResponse;
import kr.co.cudo.authoring.transfer.parser.FirstAnnotationParser;
import kr.co.cudo.authoring.transfer.service.ImportFileStager;
import kr.co.cudo.authoring.transfer.service.ImportHistoryTxService;
import kr.co.cudo.authoring.transfer.service.ImportMappingResolver;
import kr.co.cudo.authoring.transfer.service.ImportPersistTxService;
import kr.co.cudo.authoring.transfer.service.ImportService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 적재 <b>커밋 경계</b> 시험 — 커밋 이후의 실패가 적재를 되돌리지 않는다.
 *
 * <h3>왜 필요한가</h3>
 * <p>영속은 별도 트랜잭션이라 호출이 돌아오는 순간 이미 커밋됐다. 그 뒤에 오는 이관 이력 마감이 깨졌을
 * 때 보상(파일 삭제)이 돌면 <b>커밋된 행이 가리키는 파일</b>이 사라진다 — 행은 남고 산출물만 비어,
 * 되돌릴 수단이 없는 상태가 된다. 이력 마감이 실패했다는 사실은 적재가 잘못됐다는 뜻이 아니다.
 *
 * <p>표본은 실물 산출물 폴더를 그대로 읽고({@link ImportSampleFolder}) 그 밖의 협력자만 대역으로
 * 세운다 — 지어낸 표본으로 고정하면 지어낼 때의 짐작이 그대로 기대값이 된다.
 *
 * @design DOMAIN-017
 * @design API-206
 * @design AC-048
 */
class ImportCommitBoundaryTest {

    @Test
    @DisplayName("이력_마감이_실패해도_적재를_되돌리지_않고_옮긴_파일도_지우지_않는다")
    void 이력_마감이_실패해도_적재를_되돌리지_않고_옮긴_파일도_지우지_않는다() {
        ImportSourcePolicy sourcePolicy = mock(ImportSourcePolicy.class);
        when(sourcePolicy.verifyFolder(anyString()))
                .thenReturn(ImportSampleFolder.path().toAbsolutePath().normalize());

        ImportMappingResolver mappingResolver = mock(ImportMappingResolver.class);
        when(mappingResolver.resolve(any())).thenReturn(new ImportMappingResolver.Resolved(
                Map.of(ImportSampleFolder.LABEL_CATEGORY, 1L),
                Map.of(ImportSampleFolder.LABEL_CATEGORY, "도로"),
                "EV0100010", List.of()));

        ImportFileStager fileStager = mock(ImportFileStager.class);
        ImportPersistTxService persistTxService = mock(ImportPersistTxService.class);
        when(persistTxService.persist(any(), anyString()))
                .thenReturn(new ImportPersistTxService.Persisted(7L, 6, 5));

        ImportHistoryTxService historyTxService = mock(ImportHistoryTxService.class);
        when(historyTxService.start(anyString(), anyString(), any(), anyString())).thenReturn(11L);
        // 커밋 <b>이후</b>에 도는 마감이 깨진 상황이다.
        doThrow(new IllegalStateException("이력 마감 실패"))
                .when(historyTxService).succeed(anyLong(), anyLong(), anyInt(), anyInt());

        VideoRepository videoRepository = mock(VideoRepository.class);
        when(videoRepository.findByVmsClipId(anyString())).thenReturn(Optional.empty());

        ImportService service = new ImportService(sourcePolicy, new FirstAnnotationParser(new ObjectMapper()),
                mappingResolver, fileStager, persistTxService, historyTxService, videoRepository);
        ReflectionTestUtils.setField(service, "maxEntries", 20000);
        ReflectionTestUtils.setField(service, "storageRawPath", "build/tmp/import-boundary/raw");
        ReflectionTestUtils.setField(service, "storageDeidentifiedPath", "build/tmp/import-boundary/deid");

        ImportCreateResponse response = service.importFolder(new ImportCreateRequest(
                ImportSampleFolder.path().toString(), null, true, null), "1");

        // 적재는 성공으로 끝난다 — 마감 실패가 적재를 무르지 않는다.
        assertThat(response.rawSn()).isEqualTo(7L);
        assertThat(response.frameCount()).isEqualTo(6);
        // ★ 이 단언이 이 시험의 중심이다. 여기서 보상이 돌면 커밋된 행이 가리키는 파일이 사라진다.
        verify(fileStager, never()).cleanupQuietly(any());
    }
}
