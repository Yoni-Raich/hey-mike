/* Page setup. Query options (used for the PNG renders and for review):
   ?theme=light   light palette (proposal; the shipped app is dark only)
   ?still         freeze all motion (a deterministic pose per orb state)
   ?t=3.2         the clock, in seconds, for a still frame
   ?scroll=900    scroll the page before it is captured
   ?state=needs   page-specific state (see each page)
   Reduced motion (prefers-reduced-motion) behaves like ?still. */
(function () {
  var q = new URLSearchParams(location.search);
  var root = document.documentElement;
  root.dataset.theme = q.get('theme') === 'light' ? 'light' : 'dark';
  var reduced = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
  window.__STILL = q.has('still') || reduced;
  window.__T = q.has('t') ? parseFloat(q.get('t')) : null;
  window.__Q = q;
  if (window.__STILL) root.classList.add('still');
  window.addEventListener('load', function () {
    var y = parseFloat(q.get('scroll') || '0');
    if (y) window.scrollTo(0, y);
    document.title = String(Math.ceil(document.documentElement.scrollHeight));
  });
})();
