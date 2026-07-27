package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 해상도 파생 이관 백필의 <b>DB 커밋 단계</b>만 담당하는 짧은 트랜잭션 빈.
 *
 * <p>{@link ResolutionBackfillService} 는 비-트랜잭션 오케스트레이터로서 파일 Copy → Verify 를 먼저
 * 끝내고, 이 빈의 {@code REQUIRES_NEW} 트랜잭션으로 경로를 원자 커밋한 뒤에야 구 파일을 삭제한다.
 * 순서를 뒤집으면(삭제 선행) 중간 실패 시 복구가 불가능하다.
 *
 * <p>별도 빈으로 분리하는 이유는 Spring 프록시 자기호출(self-invocation) 시 트랜잭션 어드바이스가
 * 적용되지 않는 함정을 피하기 위함이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionBackfillTxService {

    private final LsDataSrcRepository srcRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final VideoRepository videoRepository;
    private final StreamMetaCacheEvictor streamMetaCacheEvictor;

    /**
     * 한 파생 영상의 경로를 원자 커밋한다 — 프레임 비식별 경로 이관 + 원본 경로 NULL(정책 A) +
     * 영상 파일 경로 + 비식별 procLog 경로.
     *
     * @param rawSn            파생 RAW_SN
     * @param frameSrcSnToPath 프레임 SRC_SN → 새 비식별 절대경로
     * @param newVideoPath     새 파생 영상 절대경로(null 이면 영상 경로는 변경하지 않음)
     * @return 갱신된 프레임 수
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int commitRelocation(Long rawSn, Map<Long, String> frameSrcSnToPath, String newVideoPath) {
        int updated = 0;
        for (Map.Entry<Long, String> e : frameSrcSnToPath.entrySet()) {
            updated += srcRepository.relocateDerivativeFramePath(e.getKey(), e.getValue());
        }
        if (newVideoPath != null) {
            videoRepository.updateDerivativeVideoPath(rawSn, newVideoPath);
            deidentProcLogRepository.updateSuccessDeidFilePath(rawSn, newVideoPath);
        }
        // 스트림 메타 캐시 무효화 (HIGH — 무결성) : 이 트랜잭션이 비식별 영상 경로
        // (LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM = stream-meta 캐시의 해석 원천)를 옮긴다.
        // 백필 이전에 한 번이라도 스트리밍된 rawSn 은 TTL(5분) 동안 <b>구 경로</b>가 캐시에 남아
        // FileNotFoundException(500) 으로 재생이 깨진다(실측: 백필 후 첫 접근 rawSn 은 206, 백필 전
        // 접근분만 500). 비식별본 교체 흐름의 기존 규약(CacheConfig/StreamMetaCacheEvictor javadoc)을
        // 백필에도 동일 적용한다.
        // [시점] 반드시 <b>커밋 후</b>여야 한다 — 커밋 전 evict 는 동시 요청이 옛 경로를 다시 캐싱한다.
        // 본 메서드는 REQUIRES_NEW 라 여기서 등록한 afterCommit 콜백은 이 트랜잭션 커밋 시점에 발화한다
        // (호출자 ResolutionBackfillService.migrateOne 의 구 파일 삭제보다 앞선다 — 삭제 시점엔 이미 무효화).
        streamMetaCacheEvictor.evictAfterCommit(rawSn);
        log.info("[Video][ResolutionBackfill] relocation committed rawSn={} frames={} videoUpdated={}",
                rawSn, updated, newVideoPath != null);
        return updated;
    }
}
