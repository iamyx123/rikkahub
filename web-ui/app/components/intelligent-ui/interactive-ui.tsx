import * as React from "react";
import { toast } from "sonner";
import { Button } from "~/components/ui/button";
import Markdown from "~/components/markdown/markdown";
import { useSettingsStore, useChatInputStore } from "~/stores";
import api from "~/services/api";
import { copyTextToClipboard } from "~/lib/clipboard";
import type { MessageDto } from "~/types";
import {
  renderUi,
  sourceKey,
  type UiEvent,
  type UiNode,
  type UiResult,
  type UiState,
} from "./runtime";
import "./interactive-ui.css";

export const InteractiveMessage = React.createContext<{
  conversationId: string;
  message: MessageDto;
  loading: boolean;
} | null>(null);
const safeUrl = (value: unknown) =>
  typeof value === "string" && /^https?:\/\/[^\s]+$/i.test(value) ? value : undefined;

export function InteractiveUi({ source }: { source: string }) {
  const host = React.useContext(InteractiveMessage);
  const key = React.useMemo(() => sourceKey(source.trimEnd()), [source]);
  const saved = host?.message.interactiveUiStates?.[key];
  const pending = React.useRef(0);
  const state = React.useRef<UiState>(saved ?? {});
  const [result, setResult] = React.useState<UiResult>();
  const [error, setError] = React.useState("");
  const [showSource, setShowSource] = React.useState(false);
  const queue = React.useRef(Promise.resolve());
  const latest = React.useRef(host);
  latest.current = host;
  const alive = React.useRef(true);
  React.useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  const evaluate = React.useCallback(
    (event?: UiEvent) => {
      pending.current++;
      queue.current = queue.current
        .catch(() => {})
        .then(async () => {
          const h = latest.current;
          if (!h || h.loading || h.message.role !== "ASSISTANT") {
            pending.current--;
            return;
          }
          try {
            const next = await renderUi(source, state.current, event);
            state.current = next.state;
            if (alive.current) {
              setResult(next);
              setError("");
            }
            if (event) {
              const saved = await api.post<{ saved: boolean }>(
                `conversations/${h.conversationId}/messages/${h.message.id}/interactive-ui`,
                { source, state: next.state },
              );
              if (!saved.saved) throw Error("原卡片已改变，状态未保存");
            }
            for (const effect of next.effects) {
              if (!alive.current) break;
              if (effect.type === "draft") {
                const store = useChatInputStore.getState();
                store.setText(
                  h.conversationId,
                  (store.drafts[h.conversationId]?.text ?? "") +
                    "\n" +
                    effect.text +
                    "\n" +
                    JSON.stringify(effect.state),
                );
                toast("已放入输入框，请检查后发送");
              } else if (effect.type === "copy") await copyTextToClipboard(effect.text);
              else if (effect.type === "url" && safeUrl(effect.text)) {
                window.open(effect.text, "_blank", "noopener,noreferrer");
              }
            }
          } catch (e) {
            if (alive.current) setError(e instanceof Error ? e.message : "卡片未能保存，请重试");
          } finally {
            pending.current--;
          }
        });
    },
    [source],
  );
  React.useEffect(() => {
    state.current = saved ?? {};
    if (!host?.loading) evaluate();
  }, [source, host?.loading]);
  // Server echoes and other clients can update a card without running its button effects again.
  React.useEffect(() => {
    if (pending.current === 0 && saved && JSON.stringify(saved) !== JSON.stringify(state.current)) {
      queue.current = queue.current.then(() => {
        state.current = saved;
      });
      evaluate();
    }
  }, [saved, evaluate]);
  if (!host || host.message.role !== "ASSISTANT")
    return (
      <pre>
        <code>{source}</code>
      </pre>
    );
  if (host.loading) return <p>正在生成交互内容…</p>;
  return (
    <section className="intelligent-ui" aria-label="交互卡片">
      <header>
        <strong>交互卡片</strong>
        <Button variant="ghost" size="sm" onClick={() => setShowSource(!showSource)}>
          {showSource ? "显示卡片" : "源码"}
        </Button>
        <Button variant="ghost" size="sm" onClick={() => evaluate({ type: "reset" })}>
          重置
        </Button>
      </header>
      {error && (
        <p role="alert">
          {error}
          <Button variant="ghost" size="sm" onClick={() => evaluate()}>
            重试
          </Button>
        </p>
      )}
      {showSource || (!result && error) ? (
        <pre>
          <code>{source}</code>
        </pre>
      ) : result ? (
        <Node node={result.root} dispatch={evaluate} />
      ) : (
        <p>正在准备卡片…</p>
      )}
      {result?.effects
        .filter((effect) => effect.type === "url" && safeUrl(effect.text))
        .map((effect, index) => (
          <p key={index}>
            <a href={effect.text} target="_blank" rel="noopener noreferrer">
              打开链接
            </a>
          </p>
        ))}
      {!!result?.errors.length && <p>部分内容未能显示，可查看源码</p>}
    </section>
  );
}

function Input({
  p,
  type,
  dispatch,
}: {
  p: Record<string, any>;
  type: string;
  dispatch: (e: UiEvent) => void;
}) {
  const [value, setValue] = React.useState(String(p.value ?? ""));
  const timer = React.useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const dirty = React.useRef(false);
  const [focused, setFocused] = React.useState(false);
  React.useEffect(() => {
    if (!focused) setValue(String(p.value ?? ""));
  }, [p.value, focused]);
  React.useEffect(() => () => clearTimeout(timer.current), []);
  function commit(next: string) {
    dirty.current = false;
    const number = Number(next);
    if (type === "NumberInput" && !Number.isFinite(number)) return;
    dispatch({
      type: "change",
      target: p.valueBinding,
      value: type === "NumberInput" ? number : next,
    });
  }
  const props = {
    value,
    placeholder: p.placeholder,
    onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      const next = e.target.value;
      setValue(next);
      dirty.current = true;
      clearTimeout(timer.current);
      timer.current = setTimeout(() => commit(next), 150);
    },
    onFocus: () => setFocused(true),
    onBlur: () => {
      setFocused(false);
      clearTimeout(timer.current);
      if (dirty.current) commit(value);
    },
  };
  return (
    <label>
      {p.label}
      {type === "TextArea" ? (
        <textarea {...props} rows={4} />
      ) : (
        <input {...props} type={type === "NumberInput" ? "number" : "text"} />
      )}
    </label>
  );
}

function Node({ node, dispatch }: { node: any; dispatch: (e: UiEvent) => void }): React.ReactNode {
  if (node == null) return null;
  if (typeof node !== "object") return <span>{String(node)}</span>;
  const p = node.props ?? {},
    type = node.type;
  const children = (p.children ?? []).map((n: UiNode, i: number) => (
    <Node key={n?.id ?? i} node={n} dispatch={dispatch} />
  ));
  const change = (value: unknown) => dispatch({ type: "change", target: p.valueBinding, value });
  switch (type) {
    case "Row":
      return <div className="ui-row">{children}</div>;
    case "Grid":
      return (
        <div
          className="ui-grid"
          style={{
            gridTemplateColumns: `repeat(${Math.max(1, Math.min(3, Number(p.columns) || 2))}, minmax(0,1fr))`,
          }}
        >
          {children}
        </div>
      );
    case "Col":
    case "Form":
    case "Box":
      return <div className="ui-col">{children}</div>;
    case "Card":
      return (
        <section className="ui-card">
          <h3>{p.title}</h3>
          {children}
        </section>
      );
    case "Title":
      return <h3>{p.text}</h3>;
    case "Caption":
    case "Label":
      return <small>{p.text}</small>;
    case "Badge":
      return <span className="ui-badge">{p.text}</span>;
    case "Text":
      return <p>{p.text}</p>;
    case "Quote":
      return <blockquote>{p.text}</blockquote>;
    case "Code":
      return (
        <pre>
          <code>{p.text}</code>
        </pre>
      );
    case "Markdown":
    case "Math":
      return (
        <InteractiveMessage.Provider value={null}>
          <Markdown
            content={type === "Math" ? "$$\n" + p.text + "\n$$" : p.text}
            allowCodePreview={false}
          />
        </InteractiveMessage.Provider>
      );
    case "Divider":
      return <hr />;
    case "Spacer":
      return <div style={{ height: Math.min(48, Math.max(0, p.size ?? 8)) }} />;
    case "List":
      return (
        <ul>
          {p.items?.map((n: any, i: number) => (
            <li key={i}>
              <Node node={n} dispatch={dispatch} />
            </li>
          ))}
        </ul>
      );
    case "Button":
      return (
        <Button
          variant={p.variant === "text" ? "ghost" : p.variant === "tonal" ? "secondary" : "default"}
          onClick={() => dispatch({ type: "action", target: p.action })}
        >
          {p.label}
        </Button>
      );
    case "Input":
    case "NumberInput":
    case "TextArea":
      return <Input p={p} type={type} dispatch={dispatch} />;
    case "Slider":
      return (
        <label>
          {p.label}: {p.value}
          <input
            aria-label={p.label ?? "数值"}
            type="range"
            min={p.min}
            max={p.max}
            step={p.step ?? "any"}
            value={p.value}
            onChange={(e) => change(Number(e.target.value))}
          />
        </label>
      );
    case "Checkbox":
    case "Switch":
      return (
        <label className="ui-check">
          <input type="checkbox" checked={!!p.value} onChange={(e) => change(e.target.checked)} />
          {p.label}
        </label>
      );
    case "Select":
      return (
        <label>
          {p.label}
          <select value={p.value} onChange={(e) => change(e.target.value)}>
            {p.options?.slice(0, 40).map((v: string) => (
              <option key={v}>{v}</option>
            ))}
          </select>
        </label>
      );
    case "RadioGroup":
    case "SegmentedControl":
      return (
        <fieldset>
          <legend>{p.label}</legend>
          <div className="ui-row">
            {p.options?.slice(0, 40).map((v: string) => (
              <Button
                key={v}
                variant={p.value === v ? "default" : "outline"}
                aria-pressed={p.value === v}
                onClick={() => change(v)}
              >
                {v}
              </Button>
            ))}
          </div>
        </fieldset>
      );
    case "DatePicker":
      return (
        <label>
          {p.label}
          <input type="date" value={p.value} onChange={(e) => change(e.target.value)} />
        </label>
      );
    case "Progress":
      return (
        <label>
          {p.label}: {p.value}
          <progress value={p.value} max={p.max ?? 100} />
        </label>
      );
    case "Accordion":
    case "Flashcard":
      return (
        <details className="ui-card">
          <summary>{type === "Flashcard" ? p.front : p.title}</summary>
          {type === "Flashcard" ? <p>{p.back}</p> : children}
        </details>
      );
    case "Table":
      return (
        <div className="ui-scroll">
          <table>
            <thead>
              <tr>
                {p.columns?.map((v: string, i: number) => (
                  <th key={i}>{v}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {p.rows?.slice(0, 100).map((row: any[], i: number) => (
                <tr key={i}>
                  {row.map((v, j) => (
                    <td key={j}>
                      <Node node={v} dispatch={dispatch} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case "Image":
      return <CardImage p={p} />;
    case "Chart":
      return <Chart p={p} />;
    case "Svg":
      return (
        <figure>
          <svg
            viewBox={/^[-\d.eE+ ,]+$/.test(p.viewBox) ? p.viewBox : "0 0 400 240"}
            role="img"
            aria-label={p.description}
          >
            {p.children?.map((n: UiNode, i: number) => (
              <Vector key={i} node={n} />
            ))}
          </svg>
          <figcaption>{p.description}</figcaption>
        </figure>
      );
    case "Map":
      return (
        <section>
          <h3>{p.title ?? "地点坐标概览"}</h3>
          <small>点击坐标打开地图</small>
          <ul>
            {p.markers?.slice(0, 50).map((m: any, i: number) => (
              <li key={i}>
                <a
                  target="_blank"
                  rel="noopener noreferrer"
                  href={`https://www.openstreetmap.org/?mlat=${Number(m.lat)}&mlon=${Number(m.lon)}#map=14/${Number(m.lat)}/${Number(m.lon)}`}
                >
                  {m.label} ({m.lat}, {m.lon})
                </a>
              </li>
            ))}
          </ul>
        </section>
      );
    default:
      return <small>不支持的组件：{String(type)}</small>;
  }
}
function vectorColor(v: unknown, fallback = "currentColor") {
  return typeof v === "string" && /^#[\da-f]{3,8}$/i.test(v)
    ? v
    : v === "none"
      ? "none"
      : v === "surface"
        ? "var(--background)"
        : v === "primary"
          ? "var(--primary)"
          : v === "secondary"
            ? "var(--muted-foreground)"
            : fallback;
}
function Vector({ node }: { node: UiNode }): React.ReactNode {
  const p = node.props,
    t = node.type,
    shared = {
      fill: vectorColor(p.fill),
      stroke: vectorColor(p.stroke, "none"),
      strokeWidth: Math.min(20, Math.max(0.1, p.strokeWidth ?? 1.5)),
    };
  const n = (k: string) => Math.max(-100000, Math.min(100000, Number(p[k]) || 0));
  switch (t) {
    case "G":
      return (
        <g>
          {p.children?.map((c: UiNode, i: number) => (
            <Vector key={i} node={c} />
          ))}
        </g>
      );
    case "Circle":
      return <circle cx={n("cx")} cy={n("cy")} r={Math.max(0, n("r"))} {...shared} />;
    case "Ellipse":
      return (
        <ellipse
          cx={n("cx")}
          cy={n("cy")}
          rx={Math.max(0, n("rx"))}
          ry={Math.max(0, n("ry"))}
          {...shared}
        />
      );
    case "Rect":
      return (
        <rect
          x={n("x")}
          y={n("y")}
          width={Math.max(0, n("width"))}
          height={Math.max(0, n("height"))}
          {...shared}
        />
      );
    case "Line":
      return (
        <line
          x1={n("x1")}
          y1={n("y1")}
          x2={n("x2")}
          y2={n("y2")}
          {...shared}
          stroke={vectorColor(p.stroke)}
        />
      );
    case "Path":
      return <path d={String(p.data).slice(0, 16000)} {...shared} />;
    case "Polyline":
      return <polyline points={String(p.data).slice(0, 16000)} {...shared} />;
    case "Polygon":
      return <polygon points={String(p.data).slice(0, 16000)} {...shared} />;
    case "SvgText":
      return (
        <text
          x={n("x")}
          y={n("y")}
          fontSize={Math.min(96, Math.max(6, p.size ?? 14))}
          fill={shared.fill}
        >
          {p.text}
        </text>
      );
    default:
      return null;
  }
}
function Chart({ p }: { p: Record<string, any> }) {
  const rows = (p.data ?? []).slice(0, 200),
    series: string[] = (p.series ?? []).slice(0, 8);
  const values: number[] = rows.flatMap((r: any) => series.map((k) => Number(r[k]) || 0)),
    low = Math.min(0, ...values),
    high = Math.max(1, ...values);
  const colors = ["var(--primary)", "var(--foreground)", "var(--muted-foreground)"];
  const y = (n: number) => 180 - ((n - low) / (high - low)) * 160;
  const x = (i: number) => 20 + (i / Math.max(1, rows.length - 1)) * 360;
  let angle = -Math.PI / 2;
  const total = rows.reduce((s: number, r: any) => s + Math.max(0, Number(r[series[0]]) || 0), 0);
  return (
    <figure>
      <h3>{p.title}</h3>
      <svg viewBox="0 0 400 200" role="img" aria-label={p.title ?? "图表"}>
        {p.type === "pie" || p.type === "donut"
          ? rows.map((r: any, i: number) => {
              const v = Math.max(0, Number(r[series[0]]) || 0),
                a = angle,
                b = (angle += total ? (v / total) * Math.PI * 2 : 0);
              return (
                <path
                  key={i}
                  d={
                    v === total && v > 0
                      ? "M 200 10 A 90 90 0 1 1 199.99 10 Z"
                      : `M 200 100 L ${200 + 90 * Math.cos(a)} ${100 + 90 * Math.sin(a)} A 90 90 0 ${b - a > Math.PI ? 1 : 0} 1 ${200 + 90 * Math.cos(b)} ${100 + 90 * Math.sin(b)} Z`
                  }
                  fill={colors[i % 3]}
                  stroke="var(--background)"
                />
              );
            })
          : series.map((key, s) => (
              <g key={key} fill={colors[s % 3]} stroke={colors[s % 3]}>
                {p.type === "bar" ? (
                  rows.map((r: any, i: number) => (
                    <rect
                      key={i}
                      x={(i * 400) / rows.length + (s * 400) / rows.length / series.length}
                      y={Math.min(y(0), y(Number(r[key]) || 0))}
                      width={(400 / rows.length / series.length) * 0.8}
                      height={Math.abs(y(0) - y(Number(r[key]) || 0))}
                    />
                  ))
                ) : (
                  <>
                    {p.type !== "scatter" && (
                      <polyline
                        points={rows
                          .map((r: any, i: number) => `${x(i)},${y(Number(r[key]) || 0)}`)
                          .join(" ")}
                        fill="none"
                        strokeWidth="2"
                      />
                    )}
                    {p.type === "area" && (
                      <polygon
                        points={
                          `20,${y(0)} ` +
                          rows
                            .map((r: any, i: number) => `${x(i)},${y(Number(r[key]) || 0)}`)
                            .join(" ") +
                          ` ${x(rows.length - 1)},${y(0)}`
                        }
                        opacity=".15"
                      />
                    )}
                    {rows.map((r: any, i: number) => (
                      <circle key={i} cx={x(i)} cy={y(Number(r[key]) || 0)} r="3" />
                    ))}
                  </>
                )}
              </g>
            ))}
        {p.type === "donut" && <circle cx="200" cy="100" r="48" fill="var(--background)" />}
      </svg>
      <details>
        <summary>查看数据</summary>
        <div className="ui-scroll">
          <table>
            <tbody>
              {rows.map((r: any, i: number) => (
                <tr key={i}>
                  <th>{String(r[p.xKey] ?? i)}</th>
                  {series.map((k) => (
                    <td key={k}>
                      {k}: {String(r[k] ?? "")}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  );
}

function CardImage({ p }: { p: Record<string, any> }) {
  const [open, setOpen] = React.useState(false);
  const eink = useSettingsStore((s) => s.settings?.displaySetting?.disableCursorBlink === true);
  React.useEffect(() => setOpen(false), [p.url]);
  return (
    <figure>
      <figcaption>{p.alt}</figcaption>
      {safeUrl(p.url)?.startsWith("https:") &&
        (eink ? (
          <a href={p.url} target="_blank" rel="noopener noreferrer">
            打开图片
          </a>
        ) : open ? (
          <img src={p.url} alt={p.alt} loading="lazy" referrerPolicy="no-referrer" />
        ) : (
          <Button variant="outline" onClick={() => setOpen(true)}>
            加载图片
          </Button>
        ))}
    </figure>
  );
}
