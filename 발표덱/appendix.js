(() => {
  const brief = new URLSearchParams(location.search).get('detail') !== '1';
  document.documentElement.classList.toggle('appendix-brief-mode', brief);
  document.addEventListener('DOMContentLoaded', () => {
    const stage = document.getElementById('stage');
    const button = document.createElement('button');
    button.type = 'button'; button.className = 'appendix-mode';
    button.textContent = brief ? '상세 보기' : '핵심 보기';
    button.addEventListener('click', () => {
      const url = new URL(location.href); url.hash = '';
      if (brief) url.searchParams.set('detail','1'); else url.searchParams.delete('detail');
      location.assign(url.href);
    });
    stage.appendChild(button);
    if (!brief) return;
    window.deckStep = () => false;
    const fullscreen = () => {
      if (document.fullscreenElement) document.exitFullscreen();
      else document.documentElement.requestFullscreen().catch(() => {});
    };
    document.addEventListener('keydown', e => {
      if (!e.ctrlKey && !e.metaKey && !e.altKey && e.key.toLowerCase() === 'f') fullscreen();
    });
  });
})();
