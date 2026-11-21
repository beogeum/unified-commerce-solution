package com.commerceos.platform.file;

import java.io.InputStream;

/** 파일 본문 저장소. 운영은 S3 호환 객체 저장소(docs/02 §1), 개발·테스트는 로컬 디렉터리. */
public interface FileStorage {
    void put(String key, byte[] content);

    InputStream open(String key);
}
