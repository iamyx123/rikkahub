import { sourceKey, type UiState } from "./runtime";

type Entry = { tail: Promise<void>; pending: number };
type CardState = { current: UiState; pending: number; observedSaved: string };

/** Module-owned work survives card disposal. A send waits for evaluation and the actual server write. */
export class InteractiveUiPendingUpdates {
  private entries = new Map<string, Entry>();
  private failures = new Map<string, Map<string, string>>();
  private states = new Map<string, Map<string, CardState>>();
  private listeners = new Set<() => void>();

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => { this.listeners.delete(listener); };
  };

  failure(conversationId: string, card: string): string {
    return this.failures.get(conversationId)?.get(card) ?? "";
  }

  stateFor(conversationId: string, card: string, saved: UiState = {}) {
    const states = this.states.get(conversationId) ?? new Map<string, CardState>();
    this.states.set(conversationId, states);
    let state = states.get(card);
    if (!state) {
      state = { current: saved, pending: 0, observedSaved: JSON.stringify(saved) };
      states.set(card, state);
    }
    return state;
  }

  enqueue(conversationId: string, card: string, apply: () => Promise<boolean>): Promise<void> {
    const entry = this.entries.get(conversationId) ?? { tail: Promise.resolve(), pending: 0 };
    this.entries.set(conversationId, entry);
    entry.pending++;
    const work = entry.tail.then(async () => {
      try {
        // true means a durable success (or a card with no bound values), not just a preview.
        if (await apply()) {
          this.failures.get(conversationId)?.delete(card);
          if (!this.failures.get(conversationId)?.size) this.failures.delete(conversationId);
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : "卡片未能保存，请重试";
        const failures = this.failures.get(conversationId) ?? new Map<string, string>();
        failures.set(card, message);
        this.failures.set(conversationId, failures);
        throw error;
      } finally {
        entry.pending--;
        this.listeners.forEach((listener) => listener());
      }
    });
    // Recovery allows the next operation to run; its failure stays in the per-card guard above.
    entry.tail = work.then(() => {}, () => {}).then(() => {
      if (entry.pending === 0 && this.entries.get(conversationId) === entry) this.entries.delete(conversationId);
    });
    return work;
  }

  async awaitIdle(conversationId: string, activeCards?: () => Set<string> | undefined): Promise<void> {
    while (true) {
      const entry = this.entries.get(conversationId);
      if (entry) { await entry.tail; continue; }
      const active = activeCards?.();
      // The callback may itself enqueue work (for example while committing an input).
      if (this.entries.has(conversationId)) continue;
      const failures = this.failures.get(conversationId);
      if (active && failures) {
        for (const card of failures.keys()) if (!active.has(card)) failures.delete(card);
        if (!failures.size) this.failures.delete(conversationId);
      }
      if (active) {
        const states = this.states.get(conversationId);
        if (states) for (const card of states.keys()) if (!active.has(card)) states.delete(card);
        if (!states?.size) this.states.delete(conversationId);
      }
      const failure = this.failures.get(conversationId)?.values().next().value;
      if (failure) throw Error(`交互参数未能保存，请重试：${failure}`);
      return;
    }
  }
}

type Message = { id: string; role: string; parts: { type: string; text?: string }[] };
export function activeInteractiveUiCards(nodes: { messages: Message[]; selectIndex: number }[]): Set<string> {
  const cards = new Set<string>();
  for (const node of nodes) {
    const message = node.messages[node.selectIndex] ?? node.messages[0];
    if (message?.role !== "ASSISTANT") continue;
    for (const part of message.parts) {
      if (part.type !== "text" || !part.text) continue;
      const fences = /^ {0,3}(`{3,}|~{3,})[ \t]*openui(?:-lang)?[ \t]*\r?\n([\s\S]*?)^ {0,3}\1[ \t]*(?:\r?\n|$)/gm;
      for (const match of part.text.matchAll(fences)) cards.add(`${message.id}/${sourceKey(match[2].trimEnd())}`);
    }
  }
  return cards;
}

export const interactiveUiPendingUpdates = new InteractiveUiPendingUpdates();

/** Keep draft cleanup inside submit, so a failed barrier never consumes input or attachments. */
export async function submitAfterInteractiveUiSave<T>(
  conversationId: string,
  activeCards: (() => Set<string> | undefined) | undefined,
  submit: () => Promise<T>,
  updates = interactiveUiPendingUpdates,
): Promise<T> {
  await updates.awaitIdle(conversationId, activeCards);
  return submit();
}
