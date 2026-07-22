package kr.exchange.engine.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 정준 직렬화 바이트를 SHA-256으로 접어 BIGINT(snapshots.state_checksum) 폭에 맞춘다. */
final class StateHash {

    private StateHash() {
    }

    static long sha256ToLong(byte[] canonicalBytes) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes);
        } catch (NoSuchAlgorithmException e) {
            // JDK 필수 알고리즘이라 도달 불가. 검사 예외 전파를 막기 위한 변환이다.
            throw new IllegalStateException("SHA-256 미지원 JVM", e);
        }
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (digest[i] & 0xFF);
        }
        return value;
    }
}
