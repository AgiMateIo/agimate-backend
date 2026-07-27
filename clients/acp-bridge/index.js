#!/usr/bin/env node
// Мост ACP: stdio (NDJSON, его читает IDE) ↔ WebSocket control-api /acp.
//
// Помимо транспорта мост работает MCP-хостом: MCP-серверы, которые IDE передаёт в
// session/new (mcpServers), поднимаются здесь локально, их тулы дискаверятся (tools/list) и
// прокидываются серверу в поле _agimateMcp. Вызовы этих тулов приходят обратным JSON-RPC
// mcp/call_tool и проксируются в нужный локальный MCP-сервер. Конфиги mcpServers (env с
// токенами) — секреты этой машины, из форвардимых фреймов они вырезаются.
//
// Разрыв WebSocket (рестарт control-api) не роняет мост: реконнект с backoff, после которого
// состояние сервера восстанавливается — реплей initialize (ответ глотается) и нотификация
// _agimate/restore со списком живых сессий и их MCP-тулов. IDE разрыва не замечает.

import { createInterface } from 'node:readline';
import WebSocket from 'ws';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const url = process.env.AGIMATE_URL ?? 'wss://api.agimate.io/control/acp';
const apiKey = process.env.AGIMATE_API_KEY;

if (!apiKey) {
  process.stderr.write('AGIMATE_API_KEY is required (agent API key)\n');
  process.exit(2);
}

const log = (msg) => process.stderr.write(`agimate-acp: ${msg}\n`);

// ── состояние для восстановления после реконнекта ───────────────────────────
let initFrame = null;                 // последний initialize от IDE (реплеим на реконнекте)
const knownSessions = new Map();      // sessionId -> {mcpTools, cwd} этой сессии
const pendingSessionNew = new Map();  // rpc-id session/new -> {mcpTools, cwd} (ждём sessionId из ответа)
const swallowIds = new Set();         // id реплеев — их ответы IDE не отдаём

// ── соединение с реконнектом ────────────────────────────────────────────────
const RETRY_DELAYS_MS = [500, 1000, 2000, 4000, 8000, 10000];
const MAX_RETRIES = 20; // ~3 минуты на рестарт бэкенда

let ws = null;
let open = false;
let retries = 0;
let replaySeq = 0;
let shuttingDown = false;
const pending = []; // фреймы IDE, накопленные пока соединения нет

const sendToServer = (frameStr) => {
  if (open) ws.send(frameStr);
  else pending.push(frameStr);
};

const restoreServerState = () => {
  if (initFrame) {
    const replayId = `bridge-init-${++replaySeq}`;
    swallowIds.add(replayId);
    ws.send(JSON.stringify({ ...initFrame, id: replayId }));
  }
  if (knownSessions.size > 0) {
    const sessions = [...knownSessions.entries()]
      .map(([sessionId, state]) => ({ sessionId, mcpTools: state.mcpTools, cwd: state.cwd }));
    ws.send(JSON.stringify({ jsonrpc: '2.0', method: '_agimate/restore', params: { sessions } }));
    log(`restored ${sessions.length} session(s) after reconnect`);
  }
};

const scheduleReconnect = () => {
  if (shuttingDown) return;
  if (retries >= MAX_RETRIES) {
    log(`giving up after ${MAX_RETRIES} reconnect attempts`);
    process.exit(1);
  }
  const delay = RETRY_DELAYS_MS[Math.min(retries, RETRY_DELAYS_MS.length - 1)];
  retries += 1;
  log(`reconnecting in ${delay}ms (attempt ${retries})`);
  setTimeout(connect, delay);
};

const connect = () => {
  ws = new WebSocket(url, { headers: { 'X-Api-Key': apiKey } });

  ws.on('open', () => {
    const reconnected = retries > 0;
    open = true;
    retries = 0;
    if (reconnected) restoreServerState();
    for (const line of pending.splice(0)) ws.send(line);
  });

  ws.on('message', (data) => handleFromServer(data.toString()));

  // 401/403 на handshake — ключ не примут и через минуту, ретраи бессмысленны.
  ws.on('unexpected-response', (_req, res) => {
    if (res.statusCode === 401 || res.statusCode === 403) {
      log(`authentication failed (${res.statusCode}) — check AGIMATE_API_KEY`);
      process.exit(2);
    }
    log(`handshake failed (${res.statusCode})`);
    scheduleReconnect();
  });

  ws.on('close', (code) => {
    if (shuttingDown) return;
    open = false;
    log(`connection closed (${code})`);
    scheduleReconnect();
  });

  ws.on('error', (err) => {
    // за error всегда следует close — реконнект планируется там
    if (!shuttingDown) log(`connection error: ${err.message}`);
  });
};

// ── MCP-хост ────────────────────────────────────────────────────────────────
const mcpClients = new Map(); // serverName -> MCP Client

const toEnvObject = (envArray) => {
  const env = { ...process.env };
  for (const e of envArray ?? []) if (e && e.name) env[e.name] = e.value;
  return env;
};

const toHeaders = (headerArray) => {
  const headers = {};
  for (const h of headerArray ?? []) if (h && h.name) headers[h.name] = h.value;
  return headers;
};

const connectMcpServer = async (spec) => {
  const client = new Client({ name: 'agimate-acp', version: '0.1.0' }, { capabilities: {} });
  let transport;
  if (spec.type === 'http' || spec.type === 'sse') {
    transport = new StreamableHTTPClientTransport(new URL(spec.url), {
      requestInit: { headers: toHeaders(spec.headers) },
    });
  } else {
    transport = new StdioClientTransport({
      command: spec.command,
      args: spec.args ?? [],
      env: toEnvObject(spec.env),
    });
  }
  await client.connect(transport);
  return client;
};

// Поднимает серверы из session/new(mcpServers), возвращает агрегированный список тулов для _agimateMcp.
const startMcpServers = async (servers) => {
  const aggregated = [];
  for (const spec of servers ?? []) {
    if (!spec || !spec.name) continue;
    try {
      const client = await connectMcpServer(spec);
      mcpClients.set(spec.name, client);
      const { tools } = await client.listTools();
      for (const tool of tools ?? []) {
        aggregated.push({ server: spec.name, tool });
      }
    } catch (err) {
      log(`MCP server "${spec.name}" failed: ${err.message}`);
    }
  }
  return aggregated;
};

const closeMcpServers = async () => {
  for (const client of mcpClients.values()) {
    try { await client.close(); } catch { /* ignore */ }
  }
  mcpClients.clear();
};

// Обратный вызов сервера: проксируем в нужный локальный MCP-сервер и отвечаем по тому же id.
const handleMcpCall = async (frame) => {
  const { server, name, arguments: args } = frame.params ?? {};
  const client = mcpClients.get(server);
  const reply = (body) => sendToServer(JSON.stringify({ jsonrpc: '2.0', id: frame.id, ...body }));
  if (!client) {
    reply({ error: { code: -32000, message: `MCP server not connected: ${server}` } });
    return;
  }
  try {
    const result = await client.callTool({ name, arguments: args ?? {} });
    reply({ result });
  } catch (err) {
    reply({ error: { code: -32000, message: `MCP call failed: ${err.message}` } });
  }
};

// ── stdin (IDE → сервер) ────────────────────────────────────────────────────
const handleFromIde = async (line) => {
  let frame;
  try { frame = JSON.parse(line); } catch { sendToServer(line); return; }

  if (frame.method === 'initialize') {
    initFrame = frame; // для реплея после реконнекта
  }

  if (frame.method === 'session/new' || frame.method === 'session/load') {
    const servers = Array.isArray(frame.params?.mcpServers) ? frame.params.mcpServers : [];
    log(`${frame.method}: IDE passed ${servers.length} MCP server(s)`
        + (servers.length ? ` [${servers.map((s) => s?.name).join(', ')}]` : ''));
    // Конфиги MCP-серверов (command/env с токенами) — секреты этой машины: серверу они не
    // нужны и не должны уезжать с фреймом. Наверх идёт только список тулов (_agimateMcp).
    if (frame.params?.mcpServers !== undefined) delete frame.params.mcpServers;
    let mcpTools = [];
    if (servers.length > 0) {
      await closeMcpServers(); // на реконнекте/повторе — переподнять
      mcpTools = await startMcpServers(servers);
      log(`MCP discovery: ${mcpTools.length} tool(s) from ${mcpClients.size} server(s)`);
      frame.params._agimateMcp = mcpTools;
    }
    // Сессию запоминаем всегда, а не только при наличии MCP-серверов: в restore едет ещё и cwd
    // (корень проекта), без которого сервер после реконнекта потеряет рабочую директорию сессии.
    const state = { mcpTools, cwd: frame.params?.cwd };
    if (frame.method === 'session/load' && frame.params.sessionId) {
      knownSessions.set(frame.params.sessionId, state);
    } else if (frame.id !== undefined) {
      pendingSessionNew.set(String(frame.id), state); // sessionId узнаем из ответа
    }
    sendToServer(JSON.stringify(frame));
    return;
  }
  sendToServer(line);
};

// ── ws (сервер → IDE) ───────────────────────────────────────────────────────
const handleFromServer = (text) => {
  let frame;
  try { frame = JSON.parse(text); } catch { process.stdout.write(text + '\n'); return; }

  if (frame.id !== undefined && swallowIds.delete(String(frame.id))) {
    return; // ответ на наш реплей initialize — IDE о нём не спрашивала
  }
  if (frame.method === 'mcp/call_tool' && frame.id !== undefined) {
    handleMcpCall(frame);
    return;
  }
  // Ответ на session/new — запоминаем sessionId для restore после реконнекта.
  if (frame.id !== undefined && pendingSessionNew.has(String(frame.id))) {
    const state = pendingSessionNew.get(String(frame.id));
    pendingSessionNew.delete(String(frame.id));
    if (frame.result?.sessionId) knownSessions.set(frame.result.sessionId, state);
  }
  process.stdout.write(text + '\n');
};

// ── старт ───────────────────────────────────────────────────────────────────
connect();

const rl = createInterface({ input: process.stdin });
rl.on('line', (line) => { if (line.trim()) handleFromIde(line); });
rl.on('close', async () => {
  shuttingDown = true;
  await closeMcpServers();
  try { ws.close(); } catch { /* ignore */ }
  process.exit(0);
});
