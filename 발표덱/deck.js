/* 발표자 도구(원본·증거 보기 단추, 전체 화면 단추)는 E 키로만 켜고 끈다.
   전체 화면 발표에서는 마우스 포인터가 늘 무대 위에 있어서 hover로 숨긴 단추가 청중에게 드러나기 때문이다.
   켠 상태는 이 탭 안에서만 기억한다(sessionStorage). 덱(index.html) 안에서는 덱이 E 키를 받아 window.deckTools로 알려 준다. */
(() => {
  const KEY = 'deck-tools';
  const read = () => { try { return sessionStorage.getItem(KEY) === '1'; } catch (e) { return false; } };
  const apply = v => { if (document.body) document.body.classList.toggle('tools', v); };
  const set = v => { apply(v); try { sessionStorage.setItem(KEY, v ? '1' : '0'); } catch (e) {} };
  if (document.body) apply(read()); else document.addEventListener('DOMContentLoaded', () => apply(read()));
  addEventListener('keydown', e => {
    if ((e.key === 'e' || e.key === 'E' || e.code === 'KeyE') && !e.ctrlKey && !e.metaKey && !e.altKey) set(!document.body.classList.contains('tools'));
  });
  window.deckTools = set;
})();
