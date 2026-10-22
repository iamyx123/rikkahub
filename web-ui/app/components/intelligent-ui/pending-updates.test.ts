import test from "node:test";
import assert from "node:assert/strict";
import { setImmediate } from "node:timers/promises";
import { InteractiveUiPendingUpdates, activeInteractiveUiCards, submitAfterInteractiveUiSave } from "./pending-updates";
import { sourceKey } from "./runtime";

function gate() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}

test("send waits for the last actual server write including work added while waiting", async () => {
  const updates = new InteractiveUiPendingUpdates();
  const first = gate(), second = gate();
  let persisted = 0;
  const a = updates.enqueue("c", "card", async () => { await first.promise; persisted = 1; return true; });
  let sent = false;
  const send = submitAfterInteractiveUiSave("c", undefined, async () => { sent = true; return persisted; }, updates);
  const b = updates.enqueue("c", "card", async () => { await second.promise; persisted = 2; return true; });
  first.resolve();
  await a;
  await setImmediate();
  assert.equal(sent, false);
  second.resolve();
  await b;
  assert.equal(await send, 2);
});

test("failed writes block repeated sends and preserve text and attachments until this card succeeds", async () => {
  const updates = new InteractiveUiPendingUpdates();
  const parts = [{ type: "text", text: "解释当前曲线" }, { type: "image", url: "file:///upload/example.png" }];
  let draft = parts;
  let sent = 0;
  const send = () => submitAfterInteractiveUiSave("c", undefined, async () => { sent++; draft = []; }, updates);
  await assert.rejects(updates.enqueue("c", "failed", async () => { throw Error("disk full"); }), /disk full/);
  await assert.rejects(send(), /disk full/);
  await assert.rejects(send(), /disk full/);
  assert.equal(sent, 0);
  assert.deepEqual(draft, parts);
  await updates.enqueue("c", "other", async () => true);
  await updates.enqueue("c", "failed", async () => false); // A live preview is not a successful write.
  await assert.rejects(send(), /disk full/);
  assert.deepEqual(draft, parts);
  await updates.enqueue("c", "failed", async () => true);
  await send();
  assert.equal(sent, 1);
  assert.deepEqual(draft, []);
});

test("unmount and remount share pending state instead of overwriting the final value with old defaults", async () => {
  const updates = new InteractiveUiPendingUpdates();
  const write = gate();
  const original = updates.stateFor("c", "card", { $a: 1 });
  const pending = updates.enqueue("c", "card", async () => {
    original.current = { $a: 3 };
    await write.promise;
    return true;
  });
  await setImmediate();
  const reopened = updates.stateFor("c", "card", { $a: 1 });
  assert.equal(reopened, original);
  assert.equal(reopened.current.$a, 3);
  let sent = false;
  const send = submitAfterInteractiveUiSave("c", undefined, async () => { sent = true; return reopened.current.$a; }, updates);
  await setImmediate();
  assert.equal(sent, false);
  write.resolve();
  await pending;
  assert.equal(await send, 3);
});

test("only complete sources in the selected assistant branch retain failed-card guards", async () => {
  const updates = new InteractiveUiPendingUpdates();
  const source = '$a=1\nroot=Text("中文")';
  const card = `assistant/${sourceKey(source)}`;
  const node = (id: string, role: string, text: string) => ({ id, role, parts: [{ type: "text", text }] });
  const selected = [
    { messages: [node("assistant", "ASSISTANT", `~~~openui-lang\n${source}\n~~~\n`), node("alternate", "ASSISTANT", "ordinary reply")], selectIndex: 0 },
    { messages: [node("user", "USER", `\`\`\`openui\n${source}\n\`\`\``)], selectIndex: 0 },
    { messages: [node("unfinished", "ASSISTANT", `\`\`\`openui\n${source}`)], selectIndex: 0 },
  ];
  assert.deepEqual([...activeInteractiveUiCards(selected)], [card]);
  await assert.rejects(updates.enqueue("c", card, async () => { throw Error("not saved"); }));
  await assert.rejects(updates.awaitIdle("c", () => activeInteractiveUiCards(selected)), /not saved/);
  selected[0].selectIndex = 1;
  await updates.awaitIdle("c", () => activeInteractiveUiCards(selected));
  assert.equal(updates.failure("c", card), "");
});

test("a failed conversation does not block sending another conversation", async () => {
  const updates = new InteractiveUiPendingUpdates();
  await assert.rejects(updates.enqueue("failed", "card", async () => { throw Error("failed write"); }));
  assert.equal(await submitAfterInteractiveUiSave("other", undefined, async () => "sent", updates), "sent");
  await assert.rejects(updates.awaitIdle("failed"), /failed write/);
});

test("send rechecks work enqueued while reading active source state", async () => {
  const updates = new InteractiveUiPendingUpdates();
  const write = gate();
  let inspected = false, sent = false;
  const send = submitAfterInteractiveUiSave("c", () => {
    if (!inspected) {
      inspected = true;
      void updates.enqueue("c", "card", async () => { await write.promise; return true; });
    }
    return new Set(["card"]);
  }, async () => { sent = true; }, updates);
  await setImmediate();
  assert.equal(sent, false);
  write.resolve();
  await send;
  assert.equal(sent, true);
});
