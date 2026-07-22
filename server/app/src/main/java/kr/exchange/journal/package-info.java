/** 저널 계층. command_journal/event_journal append(fsync 확정 후 ACK), 스냅샷, 재생(replay) 복구를 책임진다(DB 설계 v2.5 §3.1 6). */
package kr.exchange.journal;
