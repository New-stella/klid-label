package kr.co.cudo.authoring.dev;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.util.SeedImageGenerator;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * [개발/검수 전용] LS_DATA_SRC 의 모든 시드 프레임 (RAW_SN >= 9000) 에 대해
 * 합성 placeholder JPEG 를 storage.raw-path 아래에 생성한다.
 *
 * <p>실행 방법:
 * <pre>
 *   ./gradlew bootRun --args='--spring.profiles.active=local,seed-image-gen'
 * </pre>
 *
 * <p>운영 안전 장치:
 * <ul>
 *   <li>{@code @Profile("seed-image-gen")} — 명시적으로 활성화한 경우에만 실행</li>
 *   <li>본 클래스는 prd 프로파일에서 절대 활성화되지 않음 (LocalProfileGuard 가 prd+seed-image-gen 조합 차단 가능)</li>
 *   <li>시드 범위(rawSn >= 9000) 만 처리 — 실제 운영 데이터 RAW_SN 은 절대 건드리지 않음</li>
 *   <li>멱등성 — 이미 존재하는 파일은 skip</li>
 * </ul>
 *
 * <p>Path Traversal 방어 (CWE-22):
 * <ul>
 *   <li>FILE_PATH 는 DB 의 신뢰 가능한 데이터지만, 혹시 모를 변조 대비 normalize + base 검증</li>
 *   <li>storage.raw-path 외부로 빠지는 경로는 거부</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("seed-image-gen")
@RequiredArgsConstructor
public class SeedImageRunner implements CommandLineRunner {

    /** 시드 데이터 RAW_SN 시작값 (시드는 9001~9099 범위 사용). */
    private static final long SEED_RAW_SN_MIN = 9000L;

    private final LsDataSrcRepository srcRepository;
    private final VideoRepository videoRepository;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    @Override
    public void run(String... args) throws Exception {
        Path baseDir = Paths.get(storageRawPath).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        log.info("[SeedImage] base storage path = {}", baseDir);

        // 1) 모든 SRC 행을 가져와 시드 범위만 필터링 (시드는 어차피 165 row — 메모리 OK)
        List<LsDataSrc> seeds = srcRepository.findAll().stream()
                .filter(s -> s.getRawSn() != null && s.getRawSn() >= SEED_RAW_SN_MIN)
                .toList();
        if (seeds.isEmpty()) {
            log.warn("[SeedImage] 시드 SRC 가 없습니다 (RAW_SN >= {}). dev-seed.sql 적용 여부 확인.", SEED_RAW_SN_MIN);
            return;
        }
        log.info("[SeedImage] 처리 대상 frame {} 건", seeds.size());

        // 2) RAW 메타 한 번에 조회 → rawSn → LsDataRaw map
        List<Long> rawSns = seeds.stream().map(LsDataSrc::getRawSn).distinct().toList();
        Map<Long, LsDataRaw> rawById = videoRepository.findAllById(rawSns).stream()
                .collect(Collectors.toMap(LsDataRaw::getRawSn, Function.identity()));

        int created = 0, skipped = 0, failed = 0;
        for (LsDataSrc src : seeds) {
            LsDataRaw raw = rawById.get(src.getRawSn());
            if (raw == null) {
                log.warn("[SeedImage] RAW 메타 누락 srcSn={} rawSn={} — skip", src.getSrcSn(), src.getRawSn());
                failed++;
                continue;
            }
            try {
                Path target = resolveSafe(baseDir, src.getFilePath());
                boolean made = SeedImageGenerator.generate(
                        target,
                        raw.getEvntTypeCd(),
                        raw.getVmsCctvId(),
                        src.getFrameNo(),
                        src.getCapturedAt());
                if (made) {
                    created++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.error("[SeedImage] 생성 실패 srcSn={} path={}", src.getSrcSn(), src.getFilePath(), e);
                failed++;
            }
        }
        log.info("[SeedImage] 완료 created={} skipped={} failed={} total={}",
                created, skipped, failed, seeds.size());
    }

    /**
     * Path traversal 방어 (CWE-22).
     * filePath 가 절대 경로면 그대로 사용 (단, baseDir 안에 있어야 함).
     * 상대 경로면 baseDir 기준으로 resolve 후 normalize.
     * baseDir 밖으로 나가면 IllegalArgumentException.
     *
     * <p>VisibleForTesting.
     */
    public static Path resolveSafe(Path baseDir, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath 가 비어있습니다.");
        }
        // 누수된 운영용 절대경로 ('/data/...') 도 허용하지 않음 — 시드는 baseDir 하위로만.
        // 시드 SQL 은 'seed/{rawSn}/frame_N.jpg' 상대경로 또는 '{baseDir}/seed/...' 절대경로.
        Path candidate = Paths.get(filePath);
        Path resolved;
        if (candidate.isAbsolute()) {
            resolved = candidate.normalize();
        } else {
            resolved = baseDir.resolve(candidate).normalize();
        }
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Path traversal 의심 — baseDir 외부 경로: " + resolved);
        }
        return resolved;
    }
}
