#!/usr/bin/env node
// A small MCP server over stdio (one JSON-RPC message per line) for the host's tests. The tests install it as `npx`
// and `uvx`, so it runs through the host's real launch path. FIXTURE_MODE: banner (non-JSON stdout line first),
// no-tools (no tools capability), exit-at-start, ignore-term (ignores SIGTERM and the end of stdin).
'use strict';
const { spawn } = require('node:child_process');
const { createInterface } = require('node:readline');
const mode = process.env.FIXTURE_MODE || '';
if (mode.includes('exit-at-start')) process.exit(7);
const stats = { initialize: 0, cancelled: [], argv: process.argv.slice(2), pid: process.pid };
const send = (message) => process.stdout.write(JSON.stringify(message) + '\n');
const text = (value) => ({ content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value) }] });
if (mode.includes('banner')) process.stdout.write('fixture server starting\n');
if (mode.includes('ignore-term')) { process.on('SIGTERM', () => {}); setInterval(() => {}, 1000); }
process.stderr.write(`fixture stderr secret=${process.env.FIXTURE_SECRET || ''}\n`);
const names = ['echo', 'slow', 'crash', 'big', 'env', 'secret', 'fail', 'ask', 'change', 'spawn', 'stats', 'stuck', 'spam'];
let added = false;
const tools = () => (added ? [...names, 'added'] : names).map((name) => ({ name, description: `fixture ${name}`, inputSchema: { type: 'object' } }));
const pageSize = Number(process.env.FIXTURE_PAGE_SIZE || 1000);
const asks = new Map();
let nextAsk = 1;
const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on('line', async (line) => {
  const msg = JSON.parse(line);
  if (msg.method === undefined) { asks.get(msg.id)?.(msg); return; }
  if (msg.method === 'notifications/cancelled') { stats.cancelled.push(msg.params.requestId); return; }
  if (msg.id === undefined) return;
  const reply = (result) => send({ jsonrpc: '2.0', id: msg.id, result });
  const error = (code, message) => send({ jsonrpc: '2.0', id: msg.id, error: { code, message } });
  if (msg.method === 'initialize') {
    stats.initialize++;
    return reply({ protocolVersion: msg.params.protocolVersion, capabilities: mode.includes('no-tools') ? {} : { tools: { listChanged: true } }, serverInfo: { name: 'fixture', version: '1.0.0' } });
  }
  if (msg.method === 'tools/list') {
    const start = Number(msg.params?.cursor || 0);
    const all = tools();
    return reply({ tools: all.slice(start, start + pageSize), ...(start + pageSize < all.length ? { nextCursor: String(start + pageSize) } : {}) });
  }
  if (msg.method !== 'tools/call') return error(-32601, 'Method not found');
  const args = msg.params.arguments || {};
  switch (msg.params.name) {
    case 'echo': return reply(text(args.text || ''));
    case 'slow': setTimeout(() => reply(text('done')), args.ms || 1000); return;
    case 'crash': process.exit(3);
    case 'big': return reply(text('x'.repeat(args.bytes)));
    case 'env': return reply(text(process.env));
    case 'secret': return reply(text(`the secret is ${process.env.FIXTURE_SECRET}`));
    case 'fail': return error(-32602, `bad arguments ${process.env.FIXTURE_SECRET}`);
    case 'ask': {
      const id = `ask-${nextAsk++}`;
      const answer = new Promise((resolve) => asks.set(id, resolve));
      send({ jsonrpc: '2.0', id, method: 'sampling/createMessage', params: { messages: [], maxTokens: 1 } });
      return reply(text({ code: (await answer).error?.code }));
    }
    case 'change': added = true; send({ jsonrpc: '2.0', method: 'notifications/tools/list_changed' }); return reply(text('changed'));
    case 'spawn': return reply(text({ pid: spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' }).pid }));
    case 'stats': return reply(text(stats));
    case 'stuck': rl.pause(); process.stdin.pause(); setInterval(() => {}, 1000); return reply(text('stuck'));
    case 'spam': for (let i = 0; i < args.lines; i++) process.stdout.write(`noise ${i}\n`); return reply(text('spammed'));
    default: return error(-32602, 'Unknown tool');
  }
});
rl.on('close', () => { if (!mode.includes('ignore-term')) process.exit(0); });
