"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const DEFAULT_MAX_SESSION_BYTES = 80 * 1024 * 1024;
const DEFAULT_MAX_THREAD_ITEMS = 240;
const DEFAULT_MAX_CONTINUATION_CHARS = 12_000;
const DEFAULT_MAX_ITEM_TEXT_CHARS = 8_000;

function loadDesktopSessionThread(threadId, {
  codexHome = process.env.CODEX_HOME || path.join(os.homedir(), ".codex"),
  sessionPath = null,
  maxSessionBytes = DEFAULT_MAX_SESSION_BYTES,
  maxThreadItems = DEFAULT_MAX_THREAD_ITEMS,
} = {}) {
  const cleanThreadId = cleanString(threadId);
  if (!cleanThreadId) {
    return null;
  }
  const filePath = sessionPath || findSessionFileByThreadId(cleanThreadId, { codexHome });
  if (!filePath) {
    return null;
  }
  const stat = fs.statSync(filePath);
  if (stat.size > maxSessionBytes) {
    return null;
  }

  const parsed = parseSessionJsonl(fs.readFileSync(filePath, "utf8"), {
    threadId: cleanThreadId,
    filePath,
  });
  if (!parsed) {
    return null;
  }
  parsed.thread.turns = trimThreadTurns(parsed.thread.turns, maxThreadItems);
  return parsed;
}

function findSessionFileByThreadId(threadId, {
  codexHome = process.env.CODEX_HOME || path.join(os.homedir(), ".codex"),
  maxVisited = 30_000,
} = {}) {
  const cleanThreadId = cleanString(threadId);
  if (!cleanThreadId) {
    return null;
  }
  const root = path.join(codexHome, "sessions");
  let visited = 0;
  let best = null;

  function visit(dir) {
    if (visited >= maxVisited) {
      return;
    }
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      return;
    }
    entries.sort((a, b) => a.name.localeCompare(b.name));
    for (const entry of entries) {
      visited += 1;
      if (visited >= maxVisited) {
        return;
      }
      const entryPath = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        visit(entryPath);
        continue;
      }
      if (!entry.isFile() || !entry.name.endsWith(".jsonl") || !entry.name.includes(cleanThreadId)) {
        continue;
      }
      let stat;
      try {
        stat = fs.statSync(entryPath);
      } catch {
        continue;
      }
      if (!best || stat.mtimeMs > best.mtimeMs) {
        best = { path: entryPath, mtimeMs: stat.mtimeMs };
      }
    }
  }

  visit(root);
  return best?.path || null;
}

function parseSessionJsonl(text, { threadId, filePath }) {
  const turnsById = new Map();
  let currentTurnId = null;
  let cwd = null;
  let title = null;
  let updatedAt = null;
  let originator = null;
  let itemIndex = 0;

  for (const line of text.split(/\r?\n/)) {
    const cleanLine = line.trim();
    if (!cleanLine) {
      continue;
    }
    let record;
    try {
      record = JSON.parse(cleanLine);
    } catch {
      continue;
    }
    const timestamp = cleanString(record.timestamp);
    if (timestamp) {
      updatedAt = timestamp;
    }
    const payload = isPlainObject(record.payload) ? record.payload : {};
    if (record.type === "session_meta") {
      cwd = cleanString(payload.cwd) || cwd;
      originator = cleanString(payload.originator) || originator;
      continue;
    }
    if (record.type === "turn_context") {
      currentTurnId = cleanString(payload.turn_id || payload.turnId || payload.id) || currentTurnId;
      cwd = cleanString(payload.cwd) || cwd;
      ensureTurn(turnsById, currentTurnId, timestamp);
      continue;
    }
    if (record.type !== "response_item") {
      continue;
    }

    const item = payloadToThreadItem(payload, {
      fallbackTurnId: currentTurnId,
      timestamp,
      index: itemIndex,
    });
    if (!item) {
      continue;
    }
    itemIndex += 1;
    if (!title
        && (item.type === "userMessage" || (item.type === "message" && item.role === "user"))
        && !isInjectedContextText(item.text)) {
      title = firstLine(item.text);
    }
    const turn = ensureTurn(turnsById, item.turnId || currentTurnId || "turn-1", timestamp);
    delete item.turnId;
    turn.items.push(item);
  }

  const turns = [...turnsById.values()].filter((turn) => turn.items.length > 0);
  if (turns.length === 0) {
    return null;
  }

  return {
    sourcePath: filePath,
    thread: {
      id: threadId,
      title: title || path.basename(cwd || filePath || "desktop session"),
      cwd: cwd || null,
      updatedAt: updatedAt || null,
      metadata: {
        importedFrom: "codex-session-jsonl",
        sourcePath: filePath,
        originator,
      },
      turns,
    },
  };
}

function payloadToThreadItem(payload, { fallbackTurnId, timestamp, index }) {
  if (!isPlainObject(payload)) {
    return null;
  }
  const turnId = cleanString(
    payload.internal_chat_message_metadata_passthrough?.turn_id
      || payload.internalChatMessageMetadataPassthrough?.turnId
      || payload.turn_id
      || payload.turnId
      || fallbackTurnId
  );
  const id = cleanString(payload.id) || `imported-item-${index + 1}`;
  const type = cleanString(payload.type);

  if (type === "message") {
    const role = cleanString(payload.role);
    if (role !== "user" && role !== "assistant") {
      return null;
    }
    const text = truncateText(extractMessageText(payload), DEFAULT_MAX_ITEM_TEXT_CHARS);
    if (!text) {
      return null;
    }
    return {
      id,
      type: role === "user" ? "userMessage" : "agentMessage",
      text,
      createdAt: timestamp || null,
      turnId,
    };
  }

  if (type === "function_call") {
    const command = truncateText([
      cleanString(payload.name),
      cleanString(payload.arguments),
    ].filter(Boolean).join(" "), DEFAULT_MAX_ITEM_TEXT_CHARS);
    if (!command) {
      return null;
    }
    return {
      id,
      type: "command",
      command,
      text: command,
      createdAt: timestamp || null,
      turnId,
    };
  }

  if (type === "function_call_output") {
    const output = truncateText(cleanString(payload.output), DEFAULT_MAX_ITEM_TEXT_CHARS);
    if (!output) {
      return null;
    }
    return {
      id,
      type: "command",
      command: output,
      text: output,
      status: "output",
      createdAt: timestamp || null,
      turnId,
    };
  }

  if (type === "reasoning") {
    const text = truncateText(extractReasoningText(payload), DEFAULT_MAX_ITEM_TEXT_CHARS);
    if (!text) {
      return null;
    }
    return {
      id,
      type: "reasoning",
      text,
      createdAt: timestamp || null,
      turnId,
    };
  }

  return null;
}

function buildLegacyContinuationPrompt(thread, userPrompt, {
  maxChars = DEFAULT_MAX_CONTINUATION_CHARS,
  maxMessages = 16,
} = {}) {
  const snippets = collectContinuationSnippets(thread)
    .slice(-maxMessages);
  let context = snippets
    .map((snippet) => `${snippet.label}: ${snippet.text}`)
    .join("\n\n");
  if (context.length > maxChars) {
    context = context.slice(context.length - maxChars);
  }
  const cleanPrompt = cleanString(userPrompt) || "继续处理。";
  return [
    "以下是从桌面端 Codex 会话导入的最近上下文。请把它当作当前会话背景继续处理，不要要求用户重新描述。",
    context ? `最近上下文：\n${context}` : null,
    `用户的新请求：\n${cleanPrompt}`,
  ].filter(Boolean).join("\n\n");
}

function collectContinuationSnippets(thread) {
  if (!isPlainObject(thread) || !Array.isArray(thread.turns)) {
    return [];
  }
  const snippets = [];
  for (const turn of thread.turns) {
    if (!isPlainObject(turn) || !Array.isArray(turn.items)) {
      continue;
    }
    for (const item of turn.items) {
      if (!isPlainObject(item)) {
        continue;
      }
      const text = cleanString(item.text || item.command);
      if (!text) {
        continue;
      }
      if (isInjectedContextText(text)) {
        continue;
      }
      const label = item.type === "userMessage" || item.role === "user"
        ? "用户"
        : item.type === "agentMessage" || item.role === "assistant"
          ? "助手"
          : "系统";
      snippets.push({ label, text: truncateText(text, DEFAULT_MAX_ITEM_TEXT_CHARS) });
    }
  }
  return snippets;
}

function ensureTurn(turnsById, turnId, timestamp) {
  const cleanTurnId = cleanString(turnId) || "turn-1";
  let turn = turnsById.get(cleanTurnId);
  if (!turn) {
    turn = {
      id: cleanTurnId,
      status: "completed",
      createdAt: timestamp || null,
      items: [],
    };
    turnsById.set(cleanTurnId, turn);
  }
  return turn;
}

function trimThreadTurns(turns, maxItems) {
  const all = [];
  for (const turn of turns) {
    for (const item of turn.items) {
      all.push({ turn, item });
    }
  }
  if (all.length <= maxItems) {
    return turns;
  }
  const keep = new Set(all.slice(-maxItems).map(({ item }) => item));
  return turns
    .map((turn) => ({
      ...turn,
      items: turn.items.filter((item) => keep.has(item)),
    }))
    .filter((turn) => turn.items.length > 0);
}

function extractMessageText(payload) {
  return cleanString(payload.text)
    || extractTextParts(payload.content)
    || extractTextParts(payload.message?.content)
    || cleanString(payload.message);
}

function extractReasoningText(payload) {
  return extractTextParts(payload.summary)
    || extractTextParts(payload.content)
    || cleanString(payload.text);
}

function extractTextParts(value) {
  if (typeof value === "string") {
    return value.trim();
  }
  if (Array.isArray(value)) {
    return value
      .map((entry) => extractTextParts(entry))
      .filter(Boolean)
      .join("\n")
      .trim();
  }
  if (!isPlainObject(value)) {
    return "";
  }
  return cleanString(value.text)
    || cleanString(value.message)
    || extractTextParts(value.content)
    || "";
}

function firstLine(text) {
  const clean = cleanString(text);
  if (!clean) {
    return null;
  }
  return truncateText(clean.split(/\r?\n/)[0], 80);
}

function isInjectedContextText(text) {
  const clean = cleanString(text);
  return Boolean(clean) && (
    clean.startsWith("<environment_context>")
    || clean.startsWith("<developer_context>")
    || clean.startsWith("<system_context>")
  );
}

function truncateText(text, maxChars) {
  const clean = cleanString(text);
  if (!clean || clean.length <= maxChars) {
    return clean;
  }
  return `${clean.slice(0, maxChars)}\n...[truncated]`;
}

function cleanString(value) {
  if (typeof value !== "string") {
    return null;
  }
  const clean = value.trim();
  return clean || null;
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

module.exports = {
  buildLegacyContinuationPrompt,
  findSessionFileByThreadId,
  loadDesktopSessionThread,
};
