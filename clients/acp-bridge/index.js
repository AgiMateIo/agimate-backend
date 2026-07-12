#!/usr/bin/env node
// Тонкий мост ACP: stdio (NDJSON, его читает IDE) ↔ WebSocket control-api /acp.
// Никакой логики протокола — только транспорт и аутентификация handshake по X-Api-Key.

import { createInterface } from 'node:readline';
import WebSocket from 'ws';

const url = process.env.AGIMATE_URL ?? 'wss://api.agimate.io/control/acp';
const apiKey = process.env.AGIMATE_API_KEY;

if (!apiKey) {
  process.stderr.write('AGIMATE_API_KEY is required (agent API key)\n');
  process.exit(2);
}

const ws = new WebSocket(url, { headers: { 'X-Api-Key': apiKey } });

// IDE может отправить initialize раньше, чем откроется WS — буферизуем до open.
const pending = [];
let open = false;

ws.on('open', () => {
  open = true;
  for (const line of pending.splice(0)) {
    ws.send(line);
  }
});

ws.on('message', (data) => {
  process.stdout.write(data.toString() + '\n');
});

const fail = (reason) => {
  process.stderr.write(`agimate-acp: connection ${reason}\n`);
  process.exit(1); // реконнект не делаем — IDE перезапустит агента
};

ws.on('close', (code) => fail(`closed (${code})`));
ws.on('error', (err) => fail(`error: ${err.message}`));

const rl = createInterface({ input: process.stdin });
rl.on('line', (line) => {
  if (!line.trim()) return;
  if (open) {
    ws.send(line);
  } else {
    pending.push(line);
  }
});
rl.on('close', () => ws.close());
