package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.transfer.ImportPathPolicy;
import kr.co.cudo.authoring.transfer.config.MarkingImportProperties;
import kr.co.cudo.authoring.transfer.parser.ExternalNameSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 폴더와 그 아래를 <b>재귀로</b> 훑어 마킹 문서와 영상을 모은다.
 *
 * <h3>바로가기는 따라가지 않는다 (CWE-22/59/367)</h3>
 * <p>폴더 위치는 허용된 저장소 범위 안인지 <b>한 겹만</b> 판정한다. 그 안의 항목이 범위 밖을 가리키는
 * 것은 그 판정으로 막히지 않는다 — 폴더 하나가 통과하면 그 아래에 걸린 바로가기를 통해 아무 파일이나
 * 읽히기 때문이다. 그래서 훑는 동안 만나는 바로가기는 <b>파일이든 폴더든 따라가지 않고 건너뛰고</b>,
 * 건너뛴 수를 알린다(말없이 빼면 항목이 이유 없이 사라진 것이 된다).
 *
 * <p>종류 판정에는 반드시 {@link LinkOption#NOFOLLOW_LINKS} 를 쓴다. 그것 없이 「폴더인가」를 물으면
 * <b>바로가기를 따라간 뒤의 대상</b>을 보게 되어, 위 판정을 통과하는 바로가기가 생긴다.
 *
 * <h3>상한에 걸리면 조용히 자르지 않는다</h3>
 * <p>깊이·파일 수·문서 수 어느 상한에 걸리든 <b>일부만 훑었다</b>는 사실을 함께 돌려준다. 잘라 담으면
 * 「덜 들어온 것」과 「원래 그만큼인 것」이 구분되지 않고, 사람은 나머지가 원래 없었다고 판단한다.
 *
 * <h3>순서를 고정한다</h3>
 * <p>폴더를 읽는 순서는 파일시스템이 정하며 보장이 없다. 그대로 두면 <b>어느 항목이 상한에 걸려 잘리는지가
 * 실행마다 달라져</b> 같은 폴더를 두 번 검사했을 때 결과가 달라진다. 이름순으로 고정한다.
 *
 * @design DOMAIN-017
 * @design API-216
 * @design SEQ-030
 * @design AC-1032
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkingFolderScanner {

    /** 마킹 문서로 볼 확장자 — 문서 형식은 하나뿐이라 설정으로 뽑지 않는다. */
    private static final String MARKING_DOCUMENT_EXTENSION = "json";

    private final MarkingImportProperties properties;

    /**
     * 폴더를 훑는다.
     *
     * @param root 경로 판정이 돌려준 <b>실경로</b>(표기 경로를 다시 만들지 않는다 — CWE-367)
     */
    public MarkingFolderScan scan(Path root) {
        Set<String> videoExtensions = properties.videoExtensions().stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<Path> documents = new ArrayList<>();
        Map<String, List<Path>> videosByName = new LinkedHashMap<>();
        Deque<Level> stack = new ArrayDeque<>();
        stack.push(new Level(root, 0));

        int scannedFileCount = 0;
        int symlinkSkipped = 0;
        int unreadableCount = 0;
        boolean truncated = false;

        while (!stack.isEmpty()) {
            Level level = stack.pop();
            List<Path> entries;
            try {
                entries = listSorted(level.dir());
            } catch (IOException | RuntimeException e) {
                // 열 수 없는 폴더 하나 때문에 묶음 전체를 멈추지 않는다 — 세어 두고 이어 간다.
                unreadableCount++;
                continue;
            }
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    symlinkSkipped++;
                    continue;
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    if (level.depth() + 1 > properties.maxDepth()) {
                        // 더 내려가지 않는다 — 그 아래에 무엇이 있는지 모른 채로 끝냈다는 사실을 알린다.
                        truncated = true;
                        continue;
                    }
                    stack.push(new Level(entry, level.depth() + 1));
                    continue;
                }
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    // 장치 파일·소켓 등 — 열지 않는다.
                    continue;
                }
                if (scannedFileCount >= properties.maxEntries()) {
                    truncated = true;
                    stack.clear();
                    break;
                }
                scannedFileCount++;

                String extension = extensionOf(entry);
                if (MARKING_DOCUMENT_EXTENSION.equals(extension)) {
                    if (documents.size() >= properties.maxItems()) {
                        truncated = true;
                        stack.clear();
                        break;
                    }
                    documents.add(entry);
                } else if (videoExtensions.contains(extension)) {
                    String key = videoKey(entry);
                    if (key != null) {
                        videosByName.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
                    }
                }
            }
        }

        documents.sort(Comparator.comparing(Path::toString));
        if (symlinkSkipped > 0 || unreadableCount > 0 || truncated) {
            log.info("[MarkingImport] scan done files={} docs={} symlinkSkipped={} unreadable={} truncated={}",
                    scannedFileCount, documents.size(), symlinkSkipped, unreadableCount, truncated);
        }
        return new MarkingFolderScan(List.copyOf(documents), Map.copyOf(videosByName),
                scannedFileCount, truncated, symlinkSkipped, unreadableCount);
    }

    /**
     * 짝짓기에 쓰는 <b>영상 이름 키</b>.
     *
     * <p>마킹 문서가 준 이름과 저장소에 놓인 파일 이름을 <b>같은 방법으로</b> 정제해야 짝이 맞는다.
     * 한쪽만 정제하면 길이 상한이나 제어문자 하나 때문에 정상 짝이 어긋난다.
     */
    public static String videoKey(Path video) {
        Path name = video.getFileName();
        return name == null ? null
                : ExternalNameSanitizer.fileName(name.toString(), ImportPathPolicy.FILE_NAME_MAX);
    }

    private static List<Path> listSorted(Path dir) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing(p -> String.valueOf(p.getFileName())));
        return entries;
    }

    private static String extensionOf(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return "";
        }
        String value = name.toString();
        int dot = value.lastIndexOf('.');
        return (dot < 0 || dot == value.length() - 1) ? ""
                : value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 훑는 중인 폴더와 그 깊이(시작 폴더가 0). */
    private record Level(Path dir, int depth) {
    }
}
