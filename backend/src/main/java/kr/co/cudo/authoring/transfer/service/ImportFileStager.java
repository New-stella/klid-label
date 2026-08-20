package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 산출물 파일을 저작도구 저장소로 <b>옮겨 놓는</b> 단계 — 트랜잭션 밖에서만 부른다.
 *
 * <h3>왜 트랜잭션 밖인가</h3>
 * <p>영상 한 건에 프레임이 수백 장이라 복사는 곧 NAS I/O 다발이다. 그 구간을 트랜잭션 안에 두면
 * 커넥션을 오래 쥐고, 이관 요청이 몇 건만 겹쳐도 커넥션이 마른다(이 저장소에 커넥션 기아 전례가 있어
 * 프레임 이미지 서빙이 같은 이유로 트랜잭션 밖 I/O 로 분리돼 있다).
 *
 * <h3>목적지는 <b>다 쓴 뒤에</b> 제자리에 놓는다</h3>
 * <p>먼저 같은 디렉터리의 임시 이름으로 <b>새로 만들어</b> 쓰고, 다 쓰면 그 이름을 목적지로 옮긴다.
 * 이유가 둘이다.
 * <ul>
 *   <li>같은 산출물을 동시에 두 번 가져오면 두 요청이 <b>같은 파일 이름</b>에 쓴다. 목적지에 곧바로
 *       쓰면 그 사이 그 파일은 잘린 채로 읽히고, 겹쳐 쓰면 서로의 내용이 섞인다. 이름 바꾸기는 한
 *       동작이라 그 중간 상태가 보이지 않는다.</li>
 *   <li>임시 이름을 <b>새로 만드는</b> 방식으로 열면 그 자리가 바로가기여도 따라가지 않고 실패한다
 *       (CWE-59/367). 목적지를 곧바로 열면 바로가기를 따라가 저장소 밖에 쓰게 된다.</li>
 * </ul>
 *
 * <h3>바로가기를 따라가지 않는다 (CWE-59/367)</h3>
 * <p>훑기 단계가 이미 링크를 건너뛰지만, 훑고 나서 <b>복사하기 전까지</b> 그 자리를 링크로 바꿔치기할
 * 창이 남는다. 그래서 여는 시점에 다시 {@link LinkOption#NOFOLLOW_LINKS} 로 열고, 링크면 예외가 나
 * 복사가 멈춘다. 판정한 경로가 아닌 다른 경로로 여는 일이 없도록 <b>훑기가 돌려준 실경로</b>만 쓴다.
 *
 * <h3>실패하면 <b>이번에 만든 파일만</b> 지운다</h3>
 * <p>적재는 파일과 DB 가 함께 성립해야 한다. DB 가 롤백됐는데 파일만 남으면 아무도 가리키지 않는
 * 파일이 저장소에 쌓인다. 다만 지우는 대상은 <b>이번 이관이 만들기로 한 파일 목록</b>이며 디렉터리를
 * 통째로 지우지 않는다 — 디렉터리 이름이 이관 식별자라 같은 산출물을 동시에 가져온 <b>다른 요청의
 * 성공분</b>이 그 안에 함께 있을 수 있고, 통째로 지우면 그쪽 DB 행이 가리키는 파일이 사라진다.
 *
 * @design DOMAIN-017
 * @design ERD-031
 * @design AC-048
 */
@Component
public class ImportFileStager {

    private static final Logger log = LoggerFactory.getLogger(ImportFileStager.class);

    /** 다 쓰기 전의 임시 이름에 붙이는 꼬리 — 목적지 이름과 절대 겹치지 않게 한다. */
    private static final String PARTIAL_SUFFIX = ".importpart";

    /**
     * 파일 한 건을 목적지로 복사한다. 목적지 디렉터리는 없으면 만든다.
     *
     * <p>같은 디렉터리에 임시 이름으로 먼저 쓰고 마지막에 이름만 바꾼다 — 목적지에 <b>덜 쓴 내용이
     * 보이는 순간</b>이 없다.
     *
     * @param source 훑기가 판정한 <b>실경로</b>(표기 경로를 다시 만들지 않는다)
     * @param target 저작도구 저장소 안의 목적지 절대경로
     * @throws CustomException 원본이 일반 파일이 아니거나(링크 포함) 복사에 실패했을 때
     */
    public void copy(Path source, String target) {
        Path destination = Paths.get(target).toAbsolutePath().normalize();
        Path partial = destination.resolveSibling(
                destination.getFileName() + PARTIAL_SUFFIX + "." + UUID.randomUUID());
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                // 훑기와 복사 사이에 링크로 바뀐 경우가 여기 걸린다.
                throw new CustomException(ErrorCode.INVALID_INPUT, "산출물 파일을 읽을 수 없습니다.");
            }
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                 OutputStream out = Files.newOutputStream(partial,
                         // CREATE_NEW — 그 자리에 무엇이 있으면(바로가기 포함) 따라가지 않고 실패한다.
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                in.transferTo(out);
            }
            publish(partial, destination);
        } catch (IOException e) {
            // CWE-209 — 내부 경로를 메시지에 담지 않는다.
            deleteQuietly(partial);
            log.warn("[Import] file copy failed cause={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "산출물 파일을 옮기지 못했습니다.");
        } catch (RuntimeException e) {
            deleteQuietly(partial);
            throw e;
        }
    }

    /** 다 쓴 임시 파일을 목적지 이름으로 옮긴다 — 가능하면 한 동작으로. */
    private static void publish(Path partial, Path destination) throws IOException {
        try {
            Files.move(partial, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 파일시스템이 한 동작 이름 바꾸기를 지원하지 않는 경우에만 물러선다.
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 이번 이관이 만들기로 한 파일을 지운다 — <b>실패 보상 전용</b>이며 최선 노력이다.
     *
     * <p>지우지 못해도 예외를 올리지 않는다. 여기서 실패해 예외가 올라가면 <b>원래의 실패 원인</b>이
     * 그 예외에 가려 무엇 때문에 이관이 깨졌는지 알 수 없게 된다.
     *
     * <p>⚠ 디렉터리를 훑어 지우지 않는다. 이관 디렉터리 이름은 이관 식별자라 <b>같은 산출물을 동시에
     * 가져온 다른 요청</b>과 같은 자리를 쓰며, 통째로 지우면 이미 커밋에 성공한 그쪽의 파일이 사라진다.
     * 비워진 디렉터리는 <b>비어 있을 때만</b> 함께 걷어낸다.
     *
     * @param files 이번 이관이 만들기로 한 파일의 절대경로 목록
     */
    public void cleanupQuietly(List<String> files) {
        if (files == null) {
            return;
        }
        Set<Path> parents = new LinkedHashSet<>();
        for (String file : files) {
            if (file == null || file.isBlank()) {
                continue;
            }
            Path path = Paths.get(file).toAbsolutePath().normalize();
            deleteQuietly(path);
            Path parent = path.getParent();
            if (parent != null) {
                parents.add(parent);
            }
        }
        for (Path parent : parents) {
            try {
                // 비어 있지 않으면 예외가 나고 그대로 둔다 — 남의 성공분을 지우지 않는다.
                Files.deleteIfExists(parent);
            } catch (IOException | RuntimeException e) {
                log.debug("[Import] staged directory kept cause={}", e.getClass().getSimpleName());
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException e) {
            log.warn("[Import] cleanup failed cause={}", e.getClass().getSimpleName());
        }
    }
}
