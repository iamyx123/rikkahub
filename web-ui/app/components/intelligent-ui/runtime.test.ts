import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { sourceKey } from "./runtime";
import { renderUi, disposeUiRuntime } from "./runtime";
import { readFile } from "node:fs/promises";
import { createContext, runInContext } from "node:vm";
import { InteractiveUiPendingUpdates, submitAfterInteractiveUiSave } from "./pending-updates";

test("portable state keys match Android SHA-256 on HTTP and Unicode sources", () => {
  for (const source of [
    "",
    "abc",
    "中文输入😀",
    "a".repeat(64),
    'root = Col([Text("卡片")])',
    "x".repeat(65536),
  ]) {
    assert.equal(sourceKey(source), createHash("sha256").update(source, "utf8").digest("hex"));
  }
});

test("web worker transport preserves live chart changes and committed state on reopen", async () => {
  const engine = await readFile(new URL("../../../public/intelligent-ui/engine.js", import.meta.url), "utf8");
  const source = await readFile(new URL("../../../../intelligent-ui/examples/parameter-exploration.oui", import.meta.url), "utf8");
  const original = globalThis.Worker;
  class EngineWorker {
    onmessage: ((event: MessageEvent) => unknown) | null = null;
    onerror: ((event: ErrorEvent) => unknown) | null = null;
    context = createContext({});
    constructor(url: string) {
      assert.equal(url, "/intelligent-ui/worker.js");
      runInContext(engine, this.context);
    }
    terminate() {}
    postMessage(request: unknown) {
      const result = runInContext(`RikkaOpenUI.renderJson(${JSON.stringify(JSON.stringify(request))})`, this.context);
      setTimeout(() => this.onmessage?.({ data: JSON.parse(result) } as MessageEvent), 0);
    }
  }
  globalThis.Worker = EngineWorker as unknown as typeof Worker;
  try {
    let result = await renderUi(source, {});
    function nodes(node: any): any[] {
      if (!node || typeof node !== "object") return [];
      return [...(node.type && node.props ? [node] : []), ...Object.values(node).flatMap(nodes)];
    }
    const slider = nodes(result.root).find((node) => node.type === "Slider");
    result = await renderUi(source, result.state, { type: "change", target: slider.props.valueBinding, value: 2, transient: true });
    const chart = nodes(result.root).find((node) => node.type === "Chart");
    assert.equal(chart.props.data.find((point: any) => point.x === 1).y, 2);
    assert.equal(chart.props.yMin, -16);
    assert.equal(chart.props.yMax, 16);
    const updates = new InteractiveUiPendingUpdates();
    let releaseSave!: () => void;
    const saveGate = new Promise<void>((resolve) => { releaseSave = resolve; });
    let persisted = result.state;
    const change = updates.enqueue("conversation", "card", async () => {
      result = await renderUi(source, result.state, { type: "change", target: slider.props.valueBinding, value: -2, commit: true });
      await saveGate;
      persisted = JSON.parse(JSON.stringify(result.state));
      return true;
    });
    let submitted = false;
    const sent = submitAfterInteractiveUiSave("conversation", undefined, async () => { submitted = true; return persisted; }, updates);
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.equal(submitted, false);
    releaseSave();
    await change;
    assert.equal((await sent).$a, -2);
    const reopened = await renderUi(source, persisted);
    assert.equal(reopened.state.$a, -2);
    assert.equal(nodes(reopened.root).find((node) => node.type === "Chart").props.data.find((point: any) => point.x === 1).y, -2);
  } finally {
    disposeUiRuntime();
    globalThis.Worker = original;
  }
});
