package kr.co.cudo.authoring.batch.service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 재생 인덱스 무손실 재배치 실행기 — 영상·음성 스트림을 다시 인코딩하지 않고 인덱스만 앞으로 옮긴
 * 파일을 {@code output} 에 새로 쓴다.
 *
 * <p>실행기는 {@code output} 을 새로 만들 뿐 {@code source} 를 수정하지 않는다. 교체는 호출자
 * ({@link DeidentFaststartService})가 검증 뒤 원자 이동으로 한다. 실패(비정상 종료·대기 상한 초과)는
 * 예외로 알린다.
 *
 * @design ADR-072
 */
public interface DeidentFaststartRemuxer {

    /** 출력 컨테이너 — 고정 값만 쓴다(사용자 입력 없음). */
    enum Container {
        MP4("mp4"),
        MOV("mov");

        private final String muxer;

        Container(String muxer) {
            this.muxer = muxer;
        }

        /** ffmpeg 출력 형식 이름. */
        public String muxer() {
            return muxer;
        }
    }

    /**
     * @param source    재배치할 산출물(읽기만 한다)
     * @param output    새로 쓸 작업 파일(존재하지 않아야 한다)
     * @param container 출력 컨테이너
     * @throws IOException 실행 실패·비정상 종료·대기 상한 초과
     */
    void remux(Path source, Path output, Container container) throws IOException;
}
