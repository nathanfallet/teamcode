(() => {
  "use strict";
  // Progressive enhancement: the form works by pasting. This only fills the textarea from a dropped or chosen file;
  // the file is sent to the server by the form, never read by anything else.
  const drop = document.getElementById("kubeconfig-drop");
  const file = document.getElementById("kubeconfig-file");
  const text = document.getElementById("kubeconfig-text");
  const status = document.getElementById("kubeconfig-status");
  if (!drop || !file || !text || !status) return;
  const limit = Number(text.getAttribute("maxlength")) || 131072;
  const load = (selected) => {
    if (!selected) return;
    if (selected.size > limit * 8) { status.textContent = selected.name + " is too large to be a kubeconfig."; return; }
    selected.text().then((content) => {
      if (content.length > limit) { status.textContent = selected.name + " is too large to be a kubeconfig."; return; }
      text.value = content;
      status.textContent = selected.name + " loaded. Press Read kubeconfig to continue.";
      text.focus();
    }, () => { status.textContent = "The file could not be read."; });
  };
  drop.hidden = false;
  file.addEventListener("change", () => load(file.files && file.files[0]));
  for (const type of ["dragenter", "dragover"]) drop.addEventListener(type, (event) => { event.preventDefault(); drop.classList.add("over"); });
  for (const type of ["dragleave", "drop"]) drop.addEventListener(type, () => drop.classList.remove("over"));
  drop.addEventListener("drop", (event) => { event.preventDefault(); load(event.dataTransfer && event.dataTransfer.files[0]); });
})();
