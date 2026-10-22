// The viewer page of an artifact followed at its latest version: every 10 seconds while the page is visible, asks the
// dashboard for the current version and offers the newer one. It never reloads the frame on its own, so a page someone
// is using keeps its state. The frame's content is never read or messaged.
(() => {
  const bar = document.querySelector(".viewer-bar");
  const notice = document.getElementById("artifact-newer");
  if (!bar || !notice) return;
  const id = bar.dataset.artifact;
  const shown = Number(bar.dataset.version);
  if (!/^[0-9a-f-]{36}$/.test(id) || !Number.isInteger(shown)) return;
  let timer = null;
  const check = async () => {
    timer = null;
    if (document.hidden) return;
    try {
      const response = await fetch(`/dashboard/artifacts/${id}/live`, { credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" } });
      if (response.ok) {
        const { version } = await response.json();
        if (Number.isInteger(version) && version > shown) { notice.hidden = false; return; }
      } else if (response.status === 401 || response.status === 403 || response.status === 404) return;
    } catch { /* Offline or signed out: try again at the next tick. */ }
    schedule();
  };
  const schedule = () => { if (timer === null && notice.hidden) timer = setTimeout(check, 10000); };
  document.addEventListener("visibilitychange", () => { if (!document.hidden) schedule(); });
  schedule();
})();
