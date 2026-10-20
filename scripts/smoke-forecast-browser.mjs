// Opt-in local acceptance. Node 22+ and an owned Chrome profile; no model provider is used.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";

const pause = (ms) => new Promise((done) => setTimeout(done, ms));
async function until(check, label, timeout = 12000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const value = await check();
    if (value) return value;
    await pause(30);
  }
  throw new Error(`Timed out waiting for ${label}`);
}
async function connect(url) {
  const socket = new WebSocket(url);
  const pending = new Map();
  let sequence = 0;
  const exceptions = [];
  socket.addEventListener("message", ({ data }) => {
    const message = JSON.parse(data);
    if (message.method === "Runtime.exceptionThrown") exceptions.push(message.method);
    const entry = pending.get(message.id);
    if (!entry) return;
    pending.delete(message.id); clearTimeout(entry.timer);
    if (message.error) entry.reject(new Error(`Browser command ${entry.method} failed`));
    else entry.resolve(message.result);
  });
  socket.addEventListener("close", () => {
    for (const entry of pending.values()) { clearTimeout(entry.timer); entry.reject(new Error("Owned browser connection closed")); }
    pending.clear();
  });
  await new Promise((done, reject) => {
    socket.addEventListener("open", done, { once: true });
    socket.addEventListener("error", () => reject(new Error("Cannot connect to owned Chrome")), { once: true });
  });
  return {
    exceptions,
    close: () => socket.close(),
    send(method, params = {}) {
      const id = ++sequence;
      return new Promise((resolveResult, reject) => {
        const timer = setTimeout(() => { pending.delete(id); reject(new Error(`Browser command ${method} timed out`)); }, 10000);
        pending.set(id, { resolve: resolveResult, reject, timer, method });
        socket.send(JSON.stringify({ id, method, params }));
      });
    },
  };
}

assert.equal(typeof WebSocket, "function", "Use Node 22 or newer for the local browser acceptance");
const inputChunks = [];
let inputBytes = 0;
for await (const chunk of process.stdin) {
  inputBytes += chunk.length;
  assert(inputBytes <= 16384, "Oversized local fixture configuration");
  inputChunks.push(chunk);
}
const input = JSON.parse(Buffer.concat(inputChunks).toString("utf8"));
for (const origin of [input.originA, input.originB]) {
  const url = new URL(origin);
  assert(url.protocol === "http:" && url.hostname === "127.0.0.1" && url.pathname === "/" && !url.username && !url.password && !url.search && !url.hash, "Browser acceptance requires owned loopback fixture origins");
}
assert(typeof input.token === "string" && input.token.length > 20);
assert(/^[a-zA-Z0-9-]{1,36}$/.test(input.accountId));
const output = resolve(process.argv[2]);
await mkdir(dirname(output), { recursive: true });
const candidates = [process.env.TEAMCODE_CHROME_BINARY, "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "/usr/bin/google-chrome", "/usr/bin/chromium", "C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe"].filter(Boolean);
let binary;
for (const candidate of candidates) {
  try { await access(candidate); binary = candidate; break; } catch { /* Try another local executable. */ }
}
assert(binary, "Install Chrome or set TEAMCODE_CHROME_BINARY for this opt-in local check");
const profile = await mkdtemp(join(tmpdir(), "teamcode-forecast-cdp-"));
let chrome;
let browser;
let page;
let other;
let chromeFailed = false;
process.once("SIGTERM", () => { if (chrome?.pid && chrome.exitCode === null && chrome.signalCode === null) chrome.kill("SIGTERM"); }); // Only the exact child started below.
const fixture = async (path, body) => {
  const response = await fetch(input.originB + path, {
    method: body === undefined ? "GET" : "POST",
    headers: { Authorization: `Bearer ${input.token}`, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(5000),
  });
  assert.equal(response.status, 200, `Fixture operation ${path} failed`);
  return response.text();
};
try {
  chrome = spawn(binary, ["--headless=new", "--disable-gpu", "--disable-background-networking", "--no-first-run", "--no-default-browser-check", "--remote-debugging-port=0", `--user-data-dir=${profile}`], { stdio: ["ignore", "pipe", "pipe"] });
  chrome.once("error", () => { chromeFailed = true; });
  chrome.stdout.resume(); chrome.stderr.resume();
  const port = await until(async () => {
    if (chromeFailed || chrome.exitCode !== null || chrome.signalCode !== null) throw new Error("Owned Chrome failed to start");
    try { return Number((await readFile(join(profile, "DevToolsActivePort"), "utf8")).split("\n")[0]); } catch { return false; }
  }, "owned Chrome debugger");
  const debuggerOrigin = `http://127.0.0.1:${port}`;
  const version = await (await fetch(debuggerOrigin + "/json/version")).json();
  browser = await connect(version.webSocketDebuggerUrl);
  const target = await browser.send("Target.createTarget", { url: "about:blank" });
  const targetSocket = async (id) => {
    const targets = await (await fetch(debuggerOrigin + "/json/list")).json();
    return connect(targets.find((item) => item.id === id).webSocketDebuggerUrl);
  };
  page = await targetSocket(target.targetId);
  await page.send("Page.enable"); await page.send("Runtime.enable"); await page.send("Network.enable");
  await page.send("Emulation.setDeviceMetricsOverride", { width: 1400, height: 1200, deviceScaleFactor: 1, mobile: false });
  const cookie = await page.send("Network.setCookie", { name: "teamcode_session", value: input.token, url: input.originA + "/dashboard", path: "/dashboard", httpOnly: true, secure: false, sameSite: "Strict" });
  assert(cookie.success);
  const evaluate = async (expression) => {
    const result = await page.send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
    assert(!result.exceptionDetails, "Browser evaluation failed");
    return result.result.value;
  };
  const state = () => evaluate("document.getElementById('forecast-live-status')?.dataset.state");
  const visibleValue = (value) => evaluate(`document.getElementById('forecast-content')?.textContent.includes('${value}% observed')`);
  await page.send("Page.navigate", { url: input.originA + "/dashboard/forecasts?account=" + encodeURIComponent(input.accountId) });
  await page.send("Page.bringToFront");
  await until(async () => await state() === "live" && await visibleValue("31.0"), "initial live snapshot");
  assert(await evaluate("typeof globalThis.forecastInjected === 'undefined' && document.querySelectorAll('#forecast-account img, #forecast-content img').length === 0 && document.querySelector('#forecast-account option:checked').textContent.includes('<img')"), "Account labels must stay escaped in initial and live DOM");
  assert(await evaluate("!document.cookie.includes('teamcode_session')"), "Live updates must work without reading an HttpOnly cookie");
  // This fixture deliberately rotates connections after 1.2 seconds; the browser retries after the advertised five seconds.
  await until(async () => await state() === "reconnecting", "bounded SSE rotation");
  const betweenConnections = Number(await fixture("/fixture/loads"));
  await pause(200);
  assert.equal(Number(await fixture("/fixture/loads")), betweenConnections, "A closed stream must stop database polling");
  await until(async () => await state() === "live", "automatic SSE reconnection");
  assert(Number(await fixture("/fixture/loads")) > betweenConnections);

  await evaluate("document.getElementById('forecast-live-toggle').click()");
  await until(async () => await state() === "paused", "manual pause");
  await pause(150);
  const paused = Number(await fixture("/fixture/loads"));
  await fixture("/fixture/quota", { utilization: .47 });
  await pause(250);
  assert.equal(Number(await fixture("/fixture/loads")), paused, "Paused live updates must stop database reads");
  assert(await visibleValue("31.0"));
  await evaluate("document.getElementById('forecast-live-toggle').click()");
  await until(async () => await state() === "live" && await visibleValue("47.0"), "resumed fresh observation");

  await evaluate("(() => { const s=document.getElementById('forecast-account'); s.value=Array.from(s.options).find(o=>o.value!==document.getElementById('forecast-content').dataset.account).value; s.focus(); })()");
  const draft = await evaluate("document.getElementById('forecast-account').value");
  const beforeDraftFrame = await evaluate("document.getElementById('forecast-content').dataset.snapshot");
  await until(async () => await evaluate("document.getElementById('forecast-content').dataset.snapshot") !== beforeDraftFrame, "live frame while editing selection");
  assert.equal(await evaluate("document.getElementById('forecast-account').value"), draft);
  await evaluate("document.getElementById('forecast-account').blur()");
  await pause(150);
  assert.equal(await evaluate("document.getElementById('forecast-account').value"), draft);
  await evaluate("document.getElementById('forecast-account').value=document.getElementById('forecast-content').dataset.account");

  const blank = await browser.send("Target.createTarget", { url: "about:blank" });
  other = await targetSocket(blank.targetId);
  await other.send("Page.enable"); await other.send("Page.bringToFront");
  await until(async () => await evaluate("document.hidden") && await state() === "paused", "hidden-tab pause");
  await pause(150);
  const hidden = Number(await fixture("/fixture/loads"));
  await fixture("/fixture/quota", { utilization: .62 });
  await pause(250);
  assert.equal(Number(await fixture("/fixture/loads")), hidden, "A hidden tab must not keep polling");
  await page.send("Page.bringToFront");
  await until(async () => await state() === "live" && await visibleValue("62.0"), "visible-tab fresh snapshot");
  await browser.send("Target.closeTarget", { targetId: blank.targetId }); other.close(); other = null;
  const screenshot = await page.send("Page.captureScreenshot", { format: "png" });
  await writeFile(output, Buffer.from(screenshot.data, "base64"));

  await fixture("/fixture/revoke", {});
  await until(async () => await state() === "unavailable", "cross-replica access revocation");
  assert(await evaluate("document.querySelectorAll('.forecast-card').length === 0 && document.getElementById('forecast-account').disabled && document.getElementById('forecast-account').options.length === 0"), "Revocation must clear live private content and stop reconnecting");
  assert.equal((await fetch(input.originB + "/api/v1/auth/me", { headers: { Authorization: `Bearer ${input.token}` } })).status, 401);
  assert.equal(page.exceptions.length, 0, "The real browser must have no uncaught script errors");
  console.log("Forecast browser acceptance passed: own-origin CSP/script, escaped labels, live SSE, connection rotation/reconnect, pause/resume, pending selection, hidden-tab suspension and cross-replica revocation; no provider calls.");
} finally {
  other?.close(); page?.close();
  if (browser) { try { await browser.send("Browser.close"); } catch { /* The owned browser may close its socket first. */ } browser.close(); }
  if (chrome?.pid && chrome.exitCode === null && chrome.signalCode === null) {
    await Promise.race([new Promise((done) => chrome.once("exit", done)), pause(5000)]);
    if (chrome.exitCode === null && chrome.signalCode === null) { chrome.kill("SIGTERM"); await new Promise((done) => chrome.once("exit", done)); }
  }
  await rm(profile, { recursive: true, force: true });
}
