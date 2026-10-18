import * as React from "react";
import { Button } from "~/components/ui/button";
import api from "~/services/api";
import { useSettingsStore } from "~/stores";
import type { UiEvent, UiNode } from "./runtime";

type Props = Record<string, any>;
type Render = (value: any) => React.ReactNode;
const icons = new Map<string, Props>();

export function CardIcon({ p, vector }: { p: Props; vector: Render }) {
  const name = /^[a-z0-9][a-z0-9-]{0,63}$/.test(p.name) ? p.name : "circle-help";
  const [data, setData] = React.useState<Props | undefined>(() => icons.get(name));
  React.useEffect(() => {
    if (icons.has(name)) {
      setData(icons.get(name));
      return;
    }
    const controller = new AbortController();
    fetch(`/intelligent-ui/icons/${name}.json`, { signal: controller.signal })
      .then((r) => {
        if (!r.ok) throw Error("Icon unavailable");
        return r.json();
      })
      .then((value) => {
        icons.set(name, value);
        if (icons.size > 32) icons.delete(icons.keys().next().value!);
        setData(value);
      })
      .catch(() => {});
    return () => controller.abort();
  }, [name]);
  const size = Math.max(12, Math.min(64, Number(p.size) || 24));
  return (
    <svg
      style={{ width: size, height: size, flexShrink: 0 }}
      viewBox="0 0 24 24"
      role={p.label ? "img" : undefined}
      aria-label={p.label}
      aria-hidden={!p.label}
      className="ui-icon"
    >
      {data?.children.map((n: UiNode, i: number) => (
        <React.Fragment key={i}>{vector(n)}</React.Fragment>
      ))}
    </svg>
  );
}

export function Carousel({ p, render }: { p: Props; render: Render }) {
  const [selected, setSelected] = React.useState(0);
  const items = p.children ?? [];
  const index = Math.min(selected, Math.max(0, items.length - 1));
  return (
    <section className="ui-col" aria-roledescription="轮播">
      <div key={index}>{render(items[index])}</div>
      {items.length > 1 && (
        <div className="ui-row ui-page-controls">
          <Button variant="outline" disabled={index === 0} onClick={() => setSelected(index - 1)}>
            上一张
          </Button>
          <span aria-live="polite">
            {index + 1} / {items.length}
          </span>
          <Button
            variant="outline"
            disabled={index + 1 === items.length}
            onClick={() => setSelected(index + 1)}
          >
            下一张
          </Button>
        </div>
      )}
    </section>
  );
}

export function Popover({ p, render }: { p: Props; render: Render }) {
  const dialog = React.useRef<HTMLDialogElement>(null);
  return (
    <>
      <Button variant="outline" onClick={() => dialog.current?.showModal()}>
        {render(p.trigger)}
      </Button>
      <dialog
        ref={dialog}
        className="intelligent-ui ui-popover"
        onClick={(e) => {
          if (e.target === e.currentTarget) dialog.current?.close();
        }}
      >
        <div className="ui-col">
          {render(p.content)}
          <Button variant="outline" onClick={() => dialog.current?.close()}>
            关闭
          </Button>
        </div>
      </dialog>
    </>
  );
}

export function Countdown({ p, dispatch }: { p: Props; dispatch: (e: UiEvent) => void }) {
  const eink = useSettingsStore((s) => s.settings?.displaySetting?.disableCursorBlink === true);
  const seconds = Math.max(0, Math.min(604800, Math.floor(Number(p.seconds) || 0)));
  const [deadline, setDeadline] = React.useState(0);
  const [shown, setShown] = React.useState(seconds);
  const initial = React.useRef<number | null>(null);
  const [done, setDone] = React.useState(false);
  const live = React.useRef({ dispatch, target: p.secondsBinding });
  live.current = { dispatch, target: p.secondsBinding };
  const change = (n: number) =>
    live.current.dispatch({ type: "change", target: live.current.target, value: n });
  React.useEffect(() => {
    if (!deadline) setShown(seconds);
  }, [seconds, deadline]);
  React.useEffect(() => {
    if (!deadline) return;
    let timer: ReturnType<typeof setTimeout>;
    function tick() {
      const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
      if (!left) {
        setDeadline(0);
        setShown(0);
        setDone(true);
        change(0);
        return;
      }
      if (!eink) setShown(left);
      timer = setTimeout(tick, eink ? Math.max(1, deadline - Date.now()) : 1000);
    }
    tick();
    return () => clearTimeout(timer);
  }, [deadline, eink]);
  return (
    <section className="ui-col" aria-label={p.label ?? "倒计时"}>
      <strong>{p.label ?? "倒计时"}</strong>
      <span role="timer">
        {deadline && eink
          ? "计时中"
          : done
            ? "计时结束"
            : `${Math.floor(shown / 60)}:${String(shown % 60).padStart(2, "0")}`}
      </span>
      <div className="ui-row">
        <Button
          disabled={!deadline && seconds === 0}
          onClick={() => {
            if (deadline) {
              const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
              setShown(left);
              setDeadline(0);
              change(left);
            } else {
              initial.current ??= seconds;
              setDone(false);
              setDeadline(Date.now() + seconds * 1000);
            }
          }}
        >
          {deadline ? "暂停" : "开始"}
        </Button>
        <Button
          variant="outline"
          onClick={() => {
            setDeadline(0);
            setDone(false);
            change(initial.current ?? seconds);
            initial.current = null;
          }}
        >
          重置
        </Button>
      </div>
    </section>
  );
}

export function SearchPhoto({ p, image }: { p: Props; image: Render }) {
  const [photo, setPhoto] = React.useState<Props>();
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState("");
  const [query, setQuery] = React.useState(p.query ?? "");
  const request = React.useRef<AbortController | null>(null);
  React.useEffect(() => {
    request.current?.abort();
    setBusy(false);
    setError("");
    setPhoto(undefined);
    setQuery(p.query ?? "");
    return () => request.current?.abort();
  }, [p.query]);
  return (
    <section className="ui-col">
      <label>
        {p.alt ?? "查找图片"}
        <input value={query} maxLength={160} onChange={(e) => setQuery(e.target.value)} />
      </label>
      <Button
        variant="outline"
        disabled={busy || !query.trim()}
        onClick={async () => {
          request.current?.abort();
          const controller = new AbortController();
          request.current = controller;
          setBusy(true);
          setError("");
          try {
            setPhoto(
              await api.get<Props>("interactive-ui/photo", {
                searchParams: { query },
                signal: controller.signal,
              }),
            );
          } catch (e) {
            if (!controller.signal.aborted)
              setError(e instanceof Error ? e.message : "图片搜索失败");
          } finally {
            if (!controller.signal.aborted) setBusy(false);
          }
        }}
      >
        {busy ? "正在查找图片" : "查找图片"}
      </Button>
      {error && <p role="alert">{error}</p>}
      {photo && (
        <>
          {image({ url: photo.url, alt: photo.title })}
          <small>{photo.credit}</small>
          <a href={photo.pageUrl} target="_blank" rel="noopener noreferrer">
            图片来源与授权
          </a>
        </>
      )}
    </section>
  );
}

export type MapPoint = { lat: number; lon: number; label: string; category: string };
export function mapPoints(raw: any[]): MapPoint[] {
  return raw
    .slice(0, 50)
    .map((item) => item.props ?? item)
    .filter(
      (p) =>
        Number.isFinite(p.lat) &&
        Number.isFinite(p.lon) &&
        Math.abs(p.lat) <= 90 &&
        Math.abs(p.lon) <= 180,
    )
    .map((p) => ({
      lat: p.lat,
      lon: p.lon,
      label: String(p.label ?? ""),
      category: String(p.category ?? ""),
    }));
}
export function mapPosition(lat: number, lon: number, zoom: number) {
  const n = 2 ** zoom,
    radians = (Math.max(-85.05112878, Math.min(85.05112878, lat)) * Math.PI) / 180;
  return { x: ((lon + 180) / 360) * n, y: ((1 - Math.asinh(Math.tan(radians)) / Math.PI) / 2) * n };
}
export function fitMap(points: MapPoint[]) {
  if (!points.length) return { zoom: 2, x: 2, y: 2 };
  for (let zoom = 15; zoom >= 1; zoom--) {
    const n = 2 ** zoom,
      first = mapPosition(points[0].lat, points[0].lon, zoom).x;
    const projected = points.map((p) => {
      const v = mapPosition(p.lat, p.lon, zoom);
      return { x: first + ((v.x - first + n * 1.5) % n) - n / 2, y: v.y };
    });
    const xs = projected.map((p) => p.x),
      ys = projected.map((p) => p.y);
    const minX = Math.min(...xs),
      maxX = Math.max(...xs),
      minY = Math.min(...ys),
      maxY = Math.max(...ys);
    if (zoom === 1 || (maxX - minX <= 1.5 && maxY - minY <= 1.5))
      return {
        zoom,
        x: ((minX + maxX) / 2 + n) % n,
        y: Math.max(0, Math.min(n - 0.001, (minY + maxY) / 2)),
      };
  }
  throw Error("No viewport");
}

export function StreetMap({ p }: { p: Props }) {
  const markers = React.useMemo(() => mapPoints(p.markers ?? []), [p.markers]);
  const [category, setCategory] = React.useState("");
  const points = React.useMemo(
    () => markers.filter((m) => !category || m.category === category),
    [markers, category],
  );
  const [view, setView] = React.useState(() => fitMap(points));
  const [opened, setOpened] = React.useState(false),
    [expanded, setExpanded] = React.useState(false);
  const [road, setRoad] = React.useState<Props>(),
    [busy, setBusy] = React.useState(false),
    [error, setError] = React.useState("");
  const [tiles, setTiles] = React.useState<Record<string, string>>({});
  const eink = useSettingsStore((s) => s.settings?.displaySetting?.disableCursorBlink === true);
  const dialog = React.useRef<HTMLDialogElement>(null);
  const roadRequest = React.useRef<AbortController | null>(null);
  React.useEffect(() => {
    setView(fitMap(points));
    setRoad(undefined);
    roadRequest.current?.abort();
    setBusy(false);
    setError("");
    return () => roadRequest.current?.abort();
  }, [points]);
  const n = 2 ** view.zoom,
    startX = Math.floor(view.x) - 1,
    startY = Math.floor(view.y) - 1;
  React.useEffect(() => {
    if (!opened) return;
    const controller = new AbortController();
    const urls: string[] = [];
    setTiles({});
    for (let dy = 0; dy < 3; dy++)
      for (let dx = 0; dx < 3; dx++) {
        const x = (((startX + dx) % n) + n) % n,
          y = startY + dy,
          key = `${dx},${dy}`;
        if (y < 0 || y >= n) continue;
        api
          .getBlob("interactive-ui/tile", {
            searchParams: { z: view.zoom, x, y },
            signal: controller.signal,
          })
          .then((blob) => {
            if (controller.signal.aborted) return;
            const url = URL.createObjectURL(blob);
            urls.push(url);
            setTiles((previous) => ({ ...previous, [key]: url }));
          })
          .catch(() => {
            if (!controller.signal.aborted) setError("部分地图未能加载，可缩放重试或打开外部地图");
          });
      }
    return () => {
      controller.abort();
      urls.forEach((url) => URL.revokeObjectURL(url));
    };
  }, [opened, startX, startY, n, view.zoom]);
  const at = (lat: number, lon: number) => {
    const v = mapPosition(lat, lon, view.zoom),
      x = view.x + ((v.x - view.x + n * 1.5) % n) - n / 2;
    return { x: (x - startX) * 256, y: (v.y - startY) * 256 };
  };
  const path = (road?.coordinates ?? points.map((m) => [m.lon, m.lat]))
    .map(([lon, lat]: number[]) => {
      const v = at(lat, lon);
      return `${v.x},${v.y}`;
    })
    .join(" ");
  const zoom = (delta: number) =>
    setView((v) => {
      const z = Math.max(1, Math.min(17, v.zoom + delta)),
        scale = 2 ** (z - v.zoom);
      return { zoom: z, x: v.x * scale, y: v.y * scale };
    });
  const move = (dx: number, dy: number) =>
    setView((v) => ({
      ...v,
      x: (v.x + dx + n) % n,
      y: Math.max(0, Math.min(n - 0.001, v.y + dy)),
    }));
  const content = (
    <div className="ui-col">
      <h3>{p.title ?? "地图"}</h3>
      <label>
        地点类别
        <select value={category} onChange={(e) => setCategory(e.target.value)}>
          <option value="">全部</option>
          {[...new Set(markers.map((m) => m.category).filter(Boolean))].map((c) => (
            <option key={c}>{c}</option>
          ))}
        </select>
      </label>
      <svg
        viewBox="0 0 768 768"
        className="ui-street-map"
        role="img"
        aria-label={p.title ?? "地图"}
      >
        {Object.entries(tiles).map(([key, url]) => {
          const [x, y] = key.split(",").map(Number);
          return (
            <image
              key={key}
              href={url}
              x={x * 256}
              y={y * 256}
              width="256"
              height="256"
              style={eink ? { filter: "grayscale(1)" } : undefined}
            />
          );
        })}
        <polyline
          points={path}
          stroke="currentColor"
          strokeWidth="4"
          fill="none"
          strokeDasharray={road ? undefined : "10 10"}
        />
        {points.map((m, i) => {
          const v = at(m.lat, m.lon);
          return (
            <g key={i}>
              <circle
                cx={v.x}
                cy={v.y}
                r="19"
                fill="var(--background)"
                stroke="currentColor"
                strokeWidth="3"
              />
              <text x={v.x} y={v.y + 7} textAnchor="middle" fontSize="22" fill="currentColor">
                {i + 1}
              </text>
            </g>
          );
        })}
      </svg>
      {!opened && (
        <Button variant="outline" onClick={() => setOpened(true)}>
          加载街道地图
        </Button>
      )}
      <div className="ui-row">
        <Button variant="outline" onClick={() => zoom(-1)} disabled={view.zoom <= 1}>
          缩小
        </Button>
        <Button variant="outline" onClick={() => zoom(1)} disabled={view.zoom >= 17}>
          放大
        </Button>
        <Button variant="outline" onClick={() => setView(fitMap(points))}>
          适应地点
        </Button>
        <Button
          variant="outline"
          onClick={() => {
            if (expanded) dialog.current?.close();
            else dialog.current?.showModal();
            setExpanded(!expanded);
          }}
        >
          {expanded ? "收起" : "展开"}
        </Button>
      </div>
      <div className="ui-row">
        {[
          ["向西", -1, 0],
          ["向北", 0, -1],
          ["向南", 0, 1],
          ["向东", 1, 0],
        ].map(([label, x, y]) => (
          <Button key={label} variant="ghost" onClick={() => move(Number(x), Number(y))}>
            {label}
          </Button>
        ))}
      </div>
      <Button
        variant="outline"
        disabled={busy || points.length < 2 || points.length > 20}
        onClick={async () => {
          roadRequest.current?.abort();
          const controller = new AbortController();
          roadRequest.current = controller;
          setBusy(true);
          setError("");
          try {
            setRoad(
              await api.post<Props>(
                "interactive-ui/road",
                { markers: points },
                { signal: controller.signal },
              ),
            );
          } catch (e) {
            if (!controller.signal.aborted)
              setError(e instanceof Error ? e.message : "道路服务不可用");
          } finally {
            if (!controller.signal.aborted) setBusy(false);
          }
        }}
      >
        {busy ? "正在获取道路" : "加载道路概览"}
      </Button>
      <small>{road?.description ?? "虚线是地点直线连接，并非道路"}</small>
      {error && <p role="alert">{error}</p>}
      <ol>
        {points.map((m, i) => (
          <li key={i}>
            <a
              href={`https://www.openstreetmap.org/?mlat=${m.lat}&mlon=${m.lon}#map=14/${m.lat}/${m.lon}`}
              target="_blank"
              rel="noopener noreferrer"
            >
              {m.label}
            </a>
          </li>
        ))}
      </ol>
      <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">
        © OpenStreetMap contributors
      </a>
    </div>
  );
  return (
    <section>
      {!expanded && content}
      <dialog
        className="intelligent-ui ui-map-dialog"
        ref={dialog}
        onClose={() => setExpanded(false)}
      >
        {expanded && content}
      </dialog>
    </section>
  );
}
