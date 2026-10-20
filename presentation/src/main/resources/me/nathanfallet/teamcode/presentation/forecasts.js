(() => {
  "use strict";
  const content = document.getElementById("forecast-content");
  const status = document.getElementById("forecast-live-status");
  const toggle = document.getElementById("forecast-live-toggle");
  const selector = document.getElementById("forecast-account");
  if (!content || !status || !toggle || !selector) return;

  let source = null;
  let paused = false;
  let terminal = false;
  const state = (name, message) => {
    status.dataset.state = name;
    if (status.textContent !== message) status.textContent = message;
  };
  const stop = () => {
    if (source) source.close();
    source = null;
  };
  const unavailable = (accessLost) => {
    terminal = true;
    stop();
    toggle.disabled = true;
    const message = document.createElement("p");
    message.textContent = "Live data is unavailable. Refresh the page to sign in or choose another account.";
    content.replaceChildren(message);
    if (accessLost) {
      selector.replaceChildren();
      selector.disabled = true;
    }
    state("unavailable", "Live updates stopped. Refresh the page to reconnect.");
  };
  const start = () => {
    if (source || terminal || paused || document.hidden) return;
    state("connecting", "Connecting live updates. Manual refresh remains available.");
    const account = content.dataset.account;
    const url = "/dashboard/forecasts/stream" + (account ? "?account=" + encodeURIComponent(account) : "");
    // The browser sends the existing HttpOnly dashboard cookie only to this origin.
    const stream = new EventSource(url);
    source = stream;
    stream.addEventListener("snapshot", (event) => {
      if (source !== stream) return;
      try {
        if (event.data.length > 4 * 1024 * 1024) throw new Error("Oversized snapshot");
        const frame = JSON.parse(event.data);
        if (typeof frame.html !== "string" || typeof frame.options !== "string" ||
            !Number.isSafeInteger(frame.generatedAtMillis) || frame.generatedAtMillis < 0 ||
            !(frame.accountId === null || (typeof frame.accountId === "string" && /^[a-zA-Z0-9-]{1,36}$/.test(frame.accountId)))) {
          throw new Error("Invalid snapshot");
        }
        // Keep the user's pending account choice while replacing only trusted,
        // same-origin HTML produced by the server's escaped SVG renderer.
        const preserveChoice = document.activeElement === selector || selector.value !== content.dataset.account;
        content.innerHTML = frame.html;
        if (!preserveChoice) {
          selector.innerHTML = frame.options;
          selector.disabled = selector.options.length === 0;
        }
        content.dataset.account = frame.accountId || "";
        content.dataset.snapshot = String(frame.generatedAtMillis);
        state("live", "Live updates connected.");
      } catch (_) {
        unavailable(true);
      }
    });
    stream.addEventListener("unavailable", (event) => {
      if (source !== stream) return;
      let accessLost = true;
      try { accessLost = JSON.parse(event.data).reason !== "unavailable"; } catch (_) { /* Fail closed. */ }
      unavailable(accessLost);
    });
    stream.onerror = () => {
      if (source !== stream) return;
      if (stream.readyState === EventSource.CLOSED) unavailable(true);
      else state("reconnecting", "Connection lost; retrying. The last snapshot may be out of date.");
    };
  };
  const visibility = () => {
    if (terminal) return;
    if (paused || document.hidden) {
      stop();
      state("paused", paused ? "Live updates paused. Manual refresh remains available." : "Live updates paused while this tab is hidden.");
    } else start();
  };
  toggle.addEventListener("click", () => {
    paused = !paused;
    toggle.textContent = paused ? "Resume live updates" : "Pause live updates";
    visibility();
  });
  document.addEventListener("visibilitychange", visibility);
  window.addEventListener("pagehide", stop);
  window.addEventListener("pageshow", visibility);
  visibility();
})();
