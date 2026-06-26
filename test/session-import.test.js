const assert = require("node:assert/strict");
const test = require("node:test");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
  buildLegacyContinuationPrompt,
  loadDesktopSessionThread,
} = require("../linux-bridge/src/session-import");

test("loads a desktop Codex JSONL session as an app-server thread", () => {
  const codexHome = fs.mkdtempSync(path.join(os.tmpdir(), "codex-session-import-test-"));
  const threadId = "thread_import_test_1";
  const sessionDir = path.join(codexHome, "sessions", "2026", "06", "25");
  fs.mkdirSync(sessionDir, { recursive: true });
  fs.writeFileSync(path.join(sessionDir, `rollout-2026-06-25T18-17-24-${threadId}.jsonl`), [
    JSON.stringify({
      timestamp: "2026-06-25T18:17:24.000Z",
      type: "session_meta",
      payload: {
        id: threadId,
        cwd: "/tmp/project",
        originator: "codex-tui",
      },
    }),
    JSON.stringify({
      timestamp: "2026-06-25T18:18:00.000Z",
      type: "turn_context",
      payload: {
        turn_id: "turn-1",
        cwd: "/tmp/project",
      },
    }),
    JSON.stringify({
      timestamp: "2026-06-25T18:18:01.000Z",
      type: "response_item",
      payload: {
        type: "message",
        role: "user",
        content: [{ type: "input_text", text: "<environment_context>\n  <cwd>/tmp/project</cwd>\n</environment_context>" }],
        internal_chat_message_metadata_passthrough: { turn_id: "turn-1" },
      },
    }),
    JSON.stringify({
      timestamp: "2026-06-25T18:18:01.500Z",
      type: "response_item",
      payload: {
        type: "message",
        role: "user",
        content: [{ type: "input_text", text: "帮我看下问题" }],
        internal_chat_message_metadata_passthrough: { turn_id: "turn-1" },
      },
    }),
    JSON.stringify({
      timestamp: "2026-06-25T18:18:02.000Z",
      type: "response_item",
      payload: {
        type: "message",
        role: "assistant",
        content: [{ type: "output_text", text: "这是桌面端回复" }],
        internal_chat_message_metadata_passthrough: { turn_id: "turn-1" },
      },
    }),
    JSON.stringify({
      timestamp: "2026-06-25T18:18:03.000Z",
      type: "response_item",
      payload: {
        type: "function_call",
        name: "shell",
        arguments: "{\"cmd\":\"pwd\"}",
        internal_chat_message_metadata_passthrough: { turn_id: "turn-1" },
      },
    }),
  ].join("\n"));

  const imported = loadDesktopSessionThread(threadId, { codexHome });

  assert.equal(imported.thread.id, threadId);
  assert.equal(imported.thread.cwd, "/tmp/project");
  assert.equal(imported.thread.title, "帮我看下问题");
  assert.equal(imported.thread.turns.length, 1);
  assert.deepEqual(imported.thread.turns[0].items.map((item) => item.type), [
    "userMessage",
    "userMessage",
    "agentMessage",
    "command",
  ]);
  assert.equal(imported.thread.turns[0].items[2].text, "这是桌面端回复");
  assert.equal(imported.thread.turns[0].items[3].command, "shell {\"cmd\":\"pwd\"}");

  const prompt = buildLegacyContinuationPrompt(imported.thread, "继续做");
  assert.match(prompt, /从桌面端 Codex 会话导入/);
  assert.match(prompt, /帮我看下问题/);
  assert.match(prompt, /这是桌面端回复/);
  assert.match(prompt, /继续做/);
  assert.doesNotMatch(prompt, /environment_context/);
});
