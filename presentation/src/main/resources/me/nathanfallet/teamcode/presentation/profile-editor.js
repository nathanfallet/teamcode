(() => {
  "use strict";
  // Progressive enhancement: without it, "Reload choices from the cluster" does the same by hand. Changing the cluster
  // or the namespace reloads the lists the server read from that cluster, keeping every other field of the draft.
  const form = document.getElementById("profile-form");
  const reload = document.getElementById("profile-reload");
  if (!form || !reload) return;
  const submitReload = () => (form.requestSubmit ? form.requestSubmit(reload) : reload.click());
  const cluster = document.getElementById("profile-cluster");
  const namespace = document.getElementById("profile-namespace");
  if (cluster) cluster.addEventListener("change", () => {
    // The namespaces of another cluster differ: the server then picks the new cluster's first allowed one.
    if (namespace) namespace.disabled = true;
    submitReload();
  });
  if (namespace) namespace.addEventListener("change", submitReload);
  // The warning of a storage class that keeps its volumes follows the selection without a reload.
  const storage = document.getElementById("profile-storage-class");
  if (storage) storage.addEventListener("change", () => {
    const option = storage.options[storage.selectedIndex];
    const chosen = option ? option.getAttribute("data-class") : null;
    for (const warning of document.querySelectorAll("[data-storage-warning]")) warning.hidden = warning.getAttribute("data-storage-warning") !== chosen;
  });
})();
