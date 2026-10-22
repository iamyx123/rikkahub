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
import { CardIcon, Carousel, Popover, Countdown, SearchPhoto, StreetMap } from "./extras";
import { interactiveUiPendingUpdates } from "./pending-updates";

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
  const card = `${host?.message.id}/${key}`;
  const state = React.useMemo(
    () => interactiveUiPendingUpdates.stateFor(host?.conversationId ?? "", card, saved),
    [host?.conversationId, card],
  );
  const [result, setResult] = React.useState<UiResult>();
  const error = React.useSyncExternalStore(
    interactiveUiPendingUpdates.subscribe,
    () => interactiveUiPendingUpdates.failure(host?.conversationId ?? "", card),
    () => "",
  );
  const [showSource, setShowSource] = React.useState(false);
  const latest = React.useRef(host);
  latest.current = host;
  const currentCard = React.useRef(card);
  currentCard.current = card;
  const alive = React.useRef(true);
  React.useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  const evaluate = React.useCallback(
    (event?: UiEvent, restored?: UiState) => {
      const h = latest.current;
      if (!h || h.loading || h.message.role !== "ASSISTANT") return;
      state.pending++;
      void interactiveUiPendingUpdates.enqueue(h.conversationId, card, async () => {
          try {
            if (restored) state.current = restored;
            const next = await renderUi(source, state.current, event);
            state.current = next.state;
            if (alive.current && currentCard.current === card) {
              setResult(next);
            }
            let persisted = !event?.transient && Object.keys(next.state).length === 0;
            if (!event?.transient && Object.keys(next.state).length > 0) {
              const saved = await api.post<{ saved: boolean }>(
                `conversations/${h.conversationId}/messages/${h.message.id}/interactive-ui`,
                { source, state: next.state },
              );
              if (!saved.saved) throw Error("原卡片已改变，状态未保存");
              persisted = true;
            }
            for (const effect of next.effects) {
              if (!alive.current || currentCard.current !== card) break;
              if (effect.type === "draft") {
                const store = useChatInputStore.getState();
                store.setText(
                  h.conversationId,
                  (store.drafts[h.conversationId]?.text ?? "") +
                    "\n" +
                    effect.text,
                );
                toast("已放入输入框，请检查后发送");
              } else if (effect.type === "copy") await copyTextToClipboard(effect.text);
              else if (effect.type === "url" && safeUrl(effect.text)) {
                window.open(effect.text, "_blank", "noopener,noreferrer");
              }
            }
            return persisted;
          } finally {
            state.pending--;
          }
        }).catch(() => {
          // The coordinator retains this card's failure and exposes it to both the card and send.
        });
    },
    [source, card, state],
  );
  React.useEffect(() => {
    const signature = JSON.stringify(saved);
    if (state.pending === 0 && saved && signature !== state.observedSaved) {
      state.current = saved;
      state.observedSaved = signature;
    }
    if (!host?.loading) evaluate();
  }, [source, host?.loading, card]);
  // Server echoes and other clients can update a card without running its button effects again.
  React.useEffect(() => {
    const signature = JSON.stringify(saved);
    if (state.pending === 0 && saved && signature !== state.observedSaved) {
      state.observedSaved = signature;
      if (signature !== JSON.stringify(state.current)) evaluate(undefined, saved);
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
  if (Array.isArray(node))
    return node.map((item, i) => <Node key={item?.id ?? i} node={item} dispatch={dispatch} />);
  if (typeof node !== "object") return <span>{String(node)}</span>;
  const p = node.props ?? {},
    type = node.type;
  const children = (p.children ?? []).map((n: UiNode, i: number) => (
    <Node key={n?.id ?? i} node={n} dispatch={dispatch} />
  ));
  const change = (value: unknown) => dispatch({ type: "change", target: p.valueBinding, value });
  switch (type) {
    case "Carousel":
      return <Carousel p={p} render={(value) => <Node node={value} dispatch={dispatch} />} />;
    case "Popover":
      return <Popover p={p} render={(value) => <Node node={value} dispatch={dispatch} />} />;
    case "Icon":
      return <CardIcon p={p} vector={(value) => <Vector node={value} />} />;
    case "Link":
      return (
        <a href={safeUrl(p.url)} target="_blank" rel="noopener noreferrer">
          {p.text}
        </a>
      );
    case "ListItem":
      return (
        <div>
          {p.label && <small>{p.label}</small>}
          <Node node={p.content} dispatch={dispatch} />
        </div>
      );
    case "IconButton":
      return (
        <Button
          aria-label={p.label}
          title={p.label}
          variant="outline"
          size="icon"
          onClick={() => dispatch({ type: "action", target: p.action })}
        >
          <CardIcon p={{ name: p.icon }} vector={(value) => <Vector node={value} />} />
        </Button>
      );
    case "Pressable":
      return (
        <div
          role="button"
          tabIndex={0}
          aria-label={p.label}
          className="ui-card ui-pressable"
          onClick={() => dispatch({ type: "action", target: p.action })}
          onKeyDown={(e) => {
            if (e.key === "Enter" || e.key === " ") {
              e.preventDefault();
              dispatch({ type: "action", target: p.action });
            }
          }}
        >
          {children}
        </div>
      );
    case "Timer":
      return <Countdown p={p} dispatch={dispatch} />;
    case "Celebration":
      return p.when ? (
        <div role="status" className="ui-card">
          ✓ {p.message ?? "完成了"}
        </div>
      ) : null;
    case "SearchImage":
      return <SearchPhoto p={p} image={(value) => <CardImage p={value} />} />;
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
      return Array.isArray(p.text) ? (
        <div className="ui-row">
          <Node node={p.text} dispatch={dispatch} />
        </div>
      ) : (
        <InteractiveMessage.Provider value={null}>
          <Markdown content={p.text} allowCodePreview={false} />
        </InteractiveMessage.Provider>
      );
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
      return React.createElement(
        p.variant === "numbers" ? "ol" : "ul",
        { className: p.variant === "timeline" ? "ui-timeline" : undefined },
        p.items?.map((n: any, i: number) => (
          <li key={i}>
            <Node node={n} dispatch={dispatch} />
          </li>
        )),
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
      return <LiveSlider p={p} dispatch={dispatch} />;
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
            {p.options?.slice(0, 40).map((option: string | {value:string;label:string}) => (
              <option key={typeof option === "string" ? option : option.value} value={typeof option === "string" ? option : option.value}>{typeof option === "string" ? option : option.label}</option>
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
            {p.options?.slice(0, 40).map((option: string | {value:string;label:string}) => {
              const v = typeof option === "string" ? option : option.value;
              return (
              <Button
                key={v}
                variant={p.value === v ? "default" : "outline"}
                aria-pressed={p.value === v}
                onClick={() => change(v)}
              >
                {typeof option === "string" ? option : option.label}
              </Button>
            );})}
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
          <summary>
            {type === "Flashcard" ? <Node node={p.front} dispatch={dispatch} /> : p.title}
          </summary>
          {type === "Flashcard" ? <Node node={p.back} dispatch={dispatch} /> : children}
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
      return <StreetMap p={p} />;
    case "MapMarker":
      return <StreetMap p={{ markers: [p] }} />;
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
function LiveSlider({p, dispatch}: {p:Record<string,any>;dispatch:(event:UiEvent)=>void}) {
  const [value,setValue]=React.useState(Number(p.value));
  const current=React.useRef(Number(p.value));
  const dragging=React.useRef(false);
  const timer=React.useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  React.useEffect(()=>{if(!dragging.current){setValue(Number(p.value));current.current=Number(p.value);}},[p.value]);
  React.useEffect(()=>()=>clearTimeout(timer.current),[]);
  const commit=()=>{
    clearTimeout(timer.current);timer.current=undefined;dragging.current=false;
    dispatch({type:"change",target:p.valueBinding,value:current.current,commit:true});
  };
  return <label>{p.label}: {value} {p.unit}
    <input aria-label={p.label??"数值"} type="range" min={p.min} max={p.max} step={p.step??"any"} value={value}
      onPointerDown={()=>{dragging.current=true;}}
      onChange={event=>{
        const next=Number(event.target.value);dragging.current=true;current.current=next;setValue(next);
        if(timer.current===undefined)timer.current=setTimeout(()=>{
          timer.current=undefined;
          dispatch({type:"change",target:p.valueBinding,value:current.current,transient:true});
        },60);
      }} onPointerUp={commit} onPointerCancel={commit} onKeyUp={commit} onBlur={()=>{if(dragging.current)commit();}} />
  </label>;
}

function Chart({ p }: { p: Record<string, any> }) {
  const rows = (p.data ?? []).slice(0, 200),
    series: string[] = (p.series ?? []).slice(0, 8);
  const values: number[] = rows.flatMap((r: any) => series.map((k) => Number(r[k]) || 0)),
    low = Number.isFinite(p.yMin) ? p.yMin : Math.min(0, ...values),
    high = Math.max(low + .001, Number.isFinite(p.yMax) ? p.yMax : Math.max(1, ...values));
  const colors = ["var(--primary)", "var(--foreground)", "var(--muted-foreground)"];
  const y = (n: number) => 180 - ((n - low) / (high - low)) * 160;
  const xs = rows.map((r: any) => Number(r[p.xKey]));
  const numericX = p.type !== "bar" && xs.every(Number.isFinite);
  const minX = Math.min(...xs),
    xRange = Math.max(0.001, Math.max(...xs) - minX);
  const x = (i: number) =>
    20 + (numericX ? (xs[i] - minX) / xRange : i / Math.max(1, rows.length - 1)) * 360;
  let angle = -Math.PI / 2;
  const total = rows.reduce((s: number, r: any) => s + Math.max(0, Number(r[series[0]]) || 0), 0);
  return (
    <figure>
      <h3>{p.title}</h3>
      <svg viewBox="0 0 400 200" role="img" aria-label={p.title ?? "图表"} data-y-min={low} data-y-max={high}>
        {!["pie","donut"].includes(p.type) && [0,.5,1].map(fraction=><g key={fraction} stroke="var(--muted-foreground)" strokeWidth=".3">
          <line x1="20" x2="380" y1={180-fraction*160} y2={180-fraction*160}/>
          <text x="20" y={176-fraction*160} fill="var(--foreground)" stroke="none" fontSize="9">{(low+fraction*(high-low)).toFixed(1)}</text>
        </g>)}
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
