(() => {
  const svg = document.getElementById("graph");
  const empty = document.getElementById("graph-empty");
  const stat = document.getElementById("stat-line");
  const list = document.getElementById("server-list");
  const jarMeta = document.getElementById("jar-meta");
  const detail = document.getElementById("graph-detail");
  const zoomLabel = document.getElementById("zoom-label");

  function t(key, vars) {
    return window.mvpI18n ? window.mvpI18n.t(key, vars) : key;
  }

  const W = 960;
  const H = 520;
  const NS = "http://www.w3.org/2000/svg";
  const MIN_K = 0.35;
  const MAX_K = 3;

  let lastModel = { nodes: [], edges: [] };
  let lastServers = [];
  let lastPortals = [];
  const savedPos = new Map();
  /** Peer ids whose outbound destinations are hidden */
  const collapsed = new Set();
  let selectedId = null;
  let dragState = null;
  let panState = null;
  let viewport = null;
  let view = { x: 0, y: 0, k: 1 };
  let searchQuery = "";
  let searchFocusedOnce = false;
  /** Peer↔peer flow direction toggle (flips every 5s). */
  let peerFlowFlip = false;
  let peerFlowTimer = null;

  const searchBox = document.getElementById("graph-search");
  const searchToggle = document.getElementById("search-toggle");
  const searchInput = document.getElementById("graph-search-input");

  async function loadCatalog() {
    const res = await fetch("/mvp/v1/catalog/export", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    if (!res.ok) throw new Error("catalog " + res.status);
    return res.json();
  }

  function formatAgo(sec) {
    if (sec == null || sec < 0) return "—";
    if (sec < 60) return sec + "s ago";
    if (sec < 3600) return Math.floor(sec / 60) + "m ago";
    if (sec < 86400) return Math.floor(sec / 3600) + "h ago";
    return Math.floor(sec / 86400) + "d ago";
  }

  function hostKey(host, port) {
    return String(host || "").trim().toLowerCase() + ":" + Number(port || 25565);
  }

  function shortLabel(name, max) {
    const s = String(name || "");
    return s.length > max ? s.slice(0, max - 1) + "…" : s;
  }

  function clamp(v, min, max) {
    return Math.max(min, Math.min(max, v));
  }

  function buildGraphModel(servers, portals) {
    const nodes = [];
    const byNodeId = new Map();
    const byHost = new Map();

    for (const s of servers || []) {
      const node = {
        nodeId: s.serverId,
        serverId: s.serverId,
        kind: "peer",
        displayName: s.displayName || s.motd || s.serverId,
        description: s.description || "",
        hasIcon: !!s.hasIcon,
        iconUrl: s.iconUrl || (s.hasIcon ? "/mvp/v1/icon/" + s.serverId : ""),
        publicHost: s.publicHost,
        publicPort: s.publicPort || 25565,
        mcVersion: s.mcVersion,
        lastPingAgeSec: s.lastPingAgeSec,
        lastPingAgo: s.lastPingAgo,
        online: s.online !== false && !(s.lastPingAgeSec == null || s.lastPingAgeSec >= 5400),
      };
      nodes.push(node);
      byNodeId.set(node.nodeId, node);
      byHost.set(hostKey(node.publicHost, node.publicPort), node);
      if (s.bedrockPort > 0) {
        byHost.set(hostKey(node.publicHost, s.bedrockPort), node);
      }
    }

    function resolveDest(p) {
      if (p.destServerId && byNodeId.has(p.destServerId)) {
        return byNodeId.get(p.destServerId);
      }
      const host = p.destHost;
      if (!host) return null;
      const javaPort = p.destJavaPort > 0 ? p.destJavaPort : 25565;
      const bedPort = p.destPort > 0 ? p.destPort : 0;
      let hit =
        byHost.get(hostKey(host, javaPort)) ||
        (bedPort ? byHost.get(hostKey(host, bedPort)) : null);
      if (hit) return hit;

      const nodeId = "ext:" + hostKey(host, javaPort);
      if (byNodeId.has(nodeId)) return byNodeId.get(nodeId);

      const node = {
        nodeId,
        serverId: null,
        kind: "external",
        displayName: p.destLabel || host + ":" + javaPort,
        description: "",
        hasIcon: false,
        iconUrl: "",
        publicHost: host,
        publicPort: javaPort,
        mcVersion: null,
        lastPingAgeSec: null,
        lastPingAgo: null,
      };
      nodes.push(node);
      byNodeId.set(nodeId, node);
      byHost.set(hostKey(host, javaPort), node);
      if (bedPort && bedPort !== javaPort) {
        byHost.set(hostKey(host, bedPort), node);
      }
      return node;
    }

    const edges = [];
    const seen = new Set();
    for (const p of portals || []) {
      const from = byNodeId.get(p.serverId);
      if (!from) continue;
      const to = resolveDest(p);
      if (!to || from.nodeId === to.nodeId) continue;
      const key = from.nodeId + "->" + to.nodeId + "|" + (p.type || p.destKind || "");
      if (seen.has(key)) continue;
      seen.add(key);
      edges.push({
        from: from.nodeId,
        to: to.nodeId,
        type: p.type || p.destKind || "link",
        returnCapable: !!p.returnCapable,
        toExternal: to.kind === "external",
      });
    }

    return { nodes, edges };
  }

  function isOfflineNode(n) {
    if (!n || n.kind !== "peer") return false;
    if (n.online === false) return true;
    if (n.lastPingAgeSec == null) return true;
    return n.lastPingAgeSec >= 5400;
  }

  function childCount(nodeId, edges) {
    return edges.filter((e) => e.from === nodeId).length;
  }

  function visibleSets(nodes, edges) {
    const visibleEdges = edges.filter((e) => !collapsed.has(e.from));
    const needed = new Set(nodes.filter((n) => n.kind === "peer").map((n) => n.nodeId));
    for (const e of visibleEdges) {
      needed.add(e.from);
      needed.add(e.to);
    }
    return {
      nodes: nodes.filter((n) => needed.has(n.nodeId)),
      edges: visibleEdges,
    };
  }

  function layoutNodes(nodes) {
    const peers = nodes.filter((x) => x.kind === "peer");
    const externals = nodes.filter((x) => x.kind === "external");
    const placed = [];
    const cx = W / 2;
    const cy = H / 2;

    function place(list, radius, startAngle) {
      list.forEach((s, i) => {
        const saved = savedPos.get(s.nodeId);
        if (saved) {
          placed.push({ ...s, x: saved.x, y: saved.y });
          return;
        }
        if (list.length === 1 && peers.length === 1 && externals.length === 0 && s.kind === "peer") {
          placed.push({ ...s, x: cx, y: cy });
          return;
        }
        const a = startAngle + (Math.PI * 2 * i) / Math.max(list.length, 1);
        placed.push({
          ...s,
          x: cx + Math.cos(a) * radius,
          y: cy + Math.sin(a) * (radius * 0.82),
        });
      });
    }

    if (peers.length === 1 && externals.length > 0) {
      const hub = peers[0];
      const saved = savedPos.get(hub.nodeId);
      placed.push({ ...hub, x: saved ? saved.x : cx, y: saved ? saved.y : cy });
      place(externals, Math.min(W, H) * 0.36, -Math.PI / 2);
      return placed;
    }

    place(peers, Math.min(W, H) * (externals.length ? 0.22 : 0.34), -Math.PI / 2);
    place(externals, Math.min(W, H) * 0.38, -Math.PI / 2 + 0.2);
    return placed;
  }

  function applyView() {
    if (!viewport) return;
    viewport.setAttribute("transform", `translate(${view.x},${view.y}) scale(${view.k})`);
    if (zoomLabel) zoomLabel.textContent = Math.round(view.k * 100) + "%";
  }

  function svgPoint(evt) {
    const pt = svg.createSVGPoint();
    pt.x = evt.clientX;
    pt.y = evt.clientY;
    const ctm = svg.getScreenCTM();
    if (!ctm) return { x: 0, y: 0 };
    const p = pt.matrixTransform(ctm.inverse());
    return { x: p.x, y: p.y };
  }

  function clientToWorld(evt) {
    const p = svgPoint(evt);
    return {
      x: (p.x - view.x) / view.k,
      y: (p.y - view.y) / view.k,
    };
  }

  function zoomAt(factor, clientX, clientY) {
    const pt = svg.createSVGPoint();
    pt.x = clientX;
    pt.y = clientY;
    const ctm = svg.getScreenCTM();
    if (!ctm) return;
    const p = pt.matrixTransform(ctm.inverse());
    const wx = (p.x - view.x) / view.k;
    const wy = (p.y - view.y) / view.k;
    const next = clamp(view.k * factor, MIN_K, MAX_K);
    view.k = next;
    view.x = p.x - wx * view.k;
    view.y = p.y - wy * view.k;
    applyView();
  }

  function zoomBy(factor) {
    const rect = svg.getBoundingClientRect();
    zoomAt(factor, rect.left + rect.width / 2, rect.top + rect.height / 2);
  }

  function resetView() {
    view = { x: 0, y: 0, k: 1 };
    applyView();
  }

  function nodeSearchHaystack(n) {
    const addr = (n.publicHost || "") + (n.publicPort ? ":" + n.publicPort : "");
    return [
      n.displayName,
      n.serverId,
      n.nodeId,
      n.description,
      n.publicHost,
      addr,
      String(n.publicPort || ""),
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase();
  }

  function nodeMatchesQuery(n, q) {
    if (!q) return true;
    return nodeSearchHaystack(n).includes(q);
  }

  function focusNode(n) {
    if (!n || n.x == null || n.y == null) return;
    const k = clamp(Math.max(view.k, 1.25), MIN_K, MAX_K);
    view.k = k;
    view.x = W / 2 - n.x * k;
    view.y = H / 2 - n.y * k;
    applyView();
  }

  function applySearchHighlight(opts) {
    const q = searchQuery;
    const searching = q.length > 0;
    svg.classList.toggle("is-searching", searching);

    const matches = searching
      ? lastModel.nodes.filter((n) => nodeMatchesQuery(n, q))
      : [];
    const matchIds = new Set(matches.map((n) => n.nodeId));

    if (viewport) {
      viewport.querySelectorAll(".node").forEach((el) => {
        const id = el.getAttribute("data-id");
        const hit = !searching || matchIds.has(id);
        el.classList.toggle("is-dimmed", searching && !hit);
        el.classList.toggle("is-match", searching && hit);
      });
      viewport.querySelectorAll("[data-edge]").forEach((el) => {
        const from = el.getAttribute("data-from");
        const to = el.getAttribute("data-to");
        const hit = !searching || (matchIds.has(from) && matchIds.has(to));
        el.classList.toggle("is-dimmed", searching && !hit);
      });
    }

    if (list) {
      list.querySelectorAll("li[data-id]").forEach((el) => {
        const id = el.getAttribute("data-id");
        const hit = !searching || matchIds.has(id);
        el.classList.toggle("is-dimmed", searching && !hit);
        el.classList.toggle("is-match", searching && hit);
        el.hidden = searching && !hit;
      });
    }

    if (searching && matches.length && opts && opts.focus) {
      const first = matches[0];
      const live = lastModel.nodes.find((n) => n.nodeId === first.nodeId) || first;
      focusNode(live);
      showDetail(live);
      if (viewport) {
        viewport.querySelectorAll(".node").forEach((el) => {
          el.classList.toggle("is-selected", el.getAttribute("data-id") === selectedId);
        });
      }
    } else if (searching && !matches.length && detail) {
      detail.hidden = false;
      detail.innerHTML = "<strong>" + t("searchNone") + "</strong>";
      selectedId = null;
    } else if (!searching && selectedId == null && detail && detail.querySelector("strong")) {
      const text = detail.querySelector("strong")?.textContent;
      if (text === t("searchNone")) {
        showDetail(null);
      }
    }
  }

  function showDetail(n) {
    if (!detail) return;
    if (!n) {
      detail.hidden = true;
      detail.textContent = "";
      selectedId = null;
      return;
    }
    selectedId = n.nodeId;
    const addr = (n.publicHost || "") + (n.publicPort ? ":" + n.publicPort : "");
    const kids = childCount(n.nodeId, lastModel.edges);
    const fold =
      n.kind === "peer" && kids > 0
        ? collapsed.has(n.nodeId)
          ? t("collapsed", { n: kids })
          : t("expanded", { n: kids })
        : "";
    const kind = n.kind === "peer" ? t("kindPeer") : "";
    detail.hidden = false;
    detail.innerHTML = "";
    if (n.hasIcon && n.iconUrl) {
      const img = document.createElement("img");
      img.className = "icon";
      img.src = n.iconUrl;
      img.alt = "";
      img.width = 28;
      img.height = 28;
      detail.appendChild(img);
    }
    const block = document.createElement("div");
    block.className = "detail-main";
    const title = document.createElement("strong");
    title.textContent = n.displayName || n.nodeId;
    block.appendChild(title);
    const meta = document.createElement("div");
    meta.className = "meta";
    meta.textContent = [kind, fold, n.description, addr, n.mcVersion].filter(Boolean).join(" · ");
    block.appendChild(meta);
    detail.appendChild(block);

    if (addr) {
      const copyBtn = document.createElement("button");
      copyBtn.type = "button";
      copyBtn.className = "detail-copy";
      copyBtn.textContent = t("copyAddr");
      copyBtn.title = addr;
      copyBtn.addEventListener("click", async (evt) => {
        evt.stopPropagation();
        const ok = await copyText(addr);
        copyBtn.textContent = ok ? t("copiedAddr") : t("copyAddr");
        if (ok) {
          setTimeout(() => {
            if (selectedId === n.nodeId) copyBtn.textContent = t("copyAddr");
          }, 1600);
        }
      });
      detail.appendChild(copyBtn);
    }
  }

  async function copyText(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch (_) {
      /* fallback below */
    }
    try {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.setAttribute("readonly", "");
      ta.style.position = "fixed";
      ta.style.left = "-9999px";
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand("copy");
      document.body.removeChild(ta);
      return ok;
    } catch (_) {
      return false;
    }
  }

  function edgePath(a, b) {
    const mx = (a.x + b.x) / 2;
    const my = (a.y + b.y) / 2 - 28;
    return `M ${a.x} ${a.y} Q ${mx} ${my} ${b.x} ${b.y}`;
  }

  /** Outbound portal count — “more graphs” = busier hub. */
  function outDegree(nodeId, edges) {
    return childCount(nodeId, edges);
  }

  /**
   * Flow direction for animation:
   * - plugin → no-plugin: always from peer to external
   * - plugin ↔ plugin (both link each other): higher out-degree first, flip every 5s
   * - plugin → plugin (one way only): always follow the portal owner → dest
   */
  function hasPeerReverse(e, allEdges) {
    if (!e || e.toExternal) return false;
    return allEdges.some(
      (x) => !x.toExternal && x.from === e.to && x.to === e.from
    );
  }

  function flowEnds(e, byId, allEdges) {
    const a = byId[e.from];
    const b = byId[e.to];
    if (!a || !b) return null;
    if (e.toExternal || a.kind !== b.kind) {
      const peer = a.kind === "peer" ? a : b;
      const ext = a.kind === "external" ? a : b;
      return { from: peer, to: ext, flipable: false };
    }
    // One-way peer link: animate only owner → destination
    if (!hasPeerReverse(e, allEdges)) {
      return { from: a, to: b, flipable: false };
    }
    // Mutual peer link: keep alternating flow
    const da = outDegree(a.nodeId, allEdges);
    const db = outDegree(b.nodeId, allEdges);
    let from = a;
    let to = b;
    if (db > da) {
      from = b;
      to = a;
    } else if (db === da) {
      from = a;
      to = b;
    }
    if (peerFlowFlip) {
      return { from: to, to: from, flipable: true };
    }
    return { from, to, flipable: true };
  }

  function applyEdgeGeometry(el, byId) {
    const fa = byId[el.getAttribute("data-flow-from")];
    const fb = byId[el.getAttribute("data-flow-to")];
    if (!fa || !fb) return;
    el.setAttribute("d", edgePath(fa, fb));
  }

  function syncEdges() {
    const byId = Object.fromEntries(lastModel.nodes.map((n) => [n.nodeId, n]));
    if (!viewport) return;
    viewport.querySelectorAll("[data-edge]").forEach((el) => applyEdgeGeometry(el, byId));
  }

  function flipPeerFlows() {
    peerFlowFlip = !peerFlowFlip;
    if (!viewport) return;
    const byId = Object.fromEntries(lastModel.nodes.map((n) => [n.nodeId, n]));
    viewport.querySelectorAll('[data-edge][data-flipable="1"]').forEach((el) => {
      const a = el.getAttribute("data-flow-from");
      const b = el.getAttribute("data-flow-to");
      el.setAttribute("data-flow-from", b);
      el.setAttribute("data-flow-to", a);
      applyEdgeGeometry(el, byId);
    });
  }

  function ensurePeerFlowTimer() {
    if (peerFlowTimer != null) return;
    peerFlowTimer = setInterval(flipPeerFlows, 5000);
  }

  function moveNodeGroup(g, n) {
    g.setAttribute("transform", `translate(${n.x},${n.y})`);
    savedPos.set(n.nodeId, { x: n.x, y: n.y });
  }

  function renderGraph(servers, portals) {
    lastServers = servers;
    lastPortals = portals;
    const full = buildGraphModel(servers, portals);
    lastModel = full;
    const vis = visibleSets(full.nodes, full.edges);
    const nodes = layoutNodes(vis.nodes);
    const edges = vis.edges;
    // Keep positions on full model nodes for drag
    const posById = Object.fromEntries(nodes.map((n) => [n.nodeId, n]));
    for (const n of full.nodes) {
      if (posById[n.nodeId]) {
        n.x = posById[n.nodeId].x;
        n.y = posById[n.nodeId].y;
      } else if (savedPos.has(n.nodeId)) {
        const s = savedPos.get(n.nodeId);
        n.x = s.x;
        n.y = s.y;
      }
    }

    while (svg.firstChild) svg.removeChild(svg.firstChild);

    const defs = document.createElementNS(NS, "defs");
    defs.innerHTML = `
      <filter id="node-glow" x="-50%" y="-50%" width="200%" height="200%">
        <feGaussianBlur stdDeviation="2.2" result="b"/>
        <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
      </filter>
      <marker id="arrow-peer" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
        <path d="M 0 0 L 10 5 L 0 10 z" fill="rgba(61,214,198,0.75)"/>
      </marker>
      <marker id="arrow-ext" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
        <path d="M 0 0 L 10 5 L 0 10 z" fill="rgba(240,163,94,0.8)"/>
      </marker>
    `;
    svg.appendChild(defs);

    viewport = document.createElementNS(NS, "g");
    viewport.setAttribute("class", "viewport");
    svg.appendChild(viewport);
    applyView();

    const gEdges = document.createElementNS(NS, "g");
    gEdges.setAttribute("class", "edges");
    const gNodes = document.createElementNS(NS, "g");
    gNodes.setAttribute("class", "nodes");
    viewport.appendChild(gEdges);
    viewport.appendChild(gNodes);

    const byId = Object.fromEntries(nodes.map((n) => [n.nodeId, n]));

    for (const e of edges) {
      const a = byId[e.from];
      const b = byId[e.to];
      if (!a || !b) continue;
      const flow = flowEnds(e, byId, full.edges);
      if (!flow) continue;
      const path = document.createElementNS(NS, "path");
      path.setAttribute("data-edge", "1");
      path.setAttribute("data-from", e.from);
      path.setAttribute("data-to", e.to);
      path.setAttribute("data-flow-from", flow.from.nodeId);
      path.setAttribute("data-flow-to", flow.to.nodeId);
      path.setAttribute("d", edgePath(flow.from, flow.to));
      path.setAttribute("fill", "none");
      // Links from an offline MVP server: muted + static (no dash animation)
      const fromOffline = isOfflineNode(byId[e.from]);
      const kindClass = fromOffline
        ? (e.toExternal ? "edge edge--ext edge--offline" : e.returnCapable
            ? "edge edge--return edge--offline" : "edge edge--peer edge--offline")
        : e.toExternal
          ? "edge edge--ext edge--flow"
          : e.returnCapable
            ? "edge edge--return edge--flow"
            : "edge edge--peer edge--flow";
      path.setAttribute("class", kindClass);
      if (fromOffline) {
        path.setAttribute("data-offline-from", "1");
      } else {
        path.setAttribute("data-flipable", flow.flipable ? "1" : "0");
      }
      path.setAttribute("marker-end", e.toExternal ? "url(#arrow-ext)" : "url(#arrow-peer)");
      gEdges.appendChild(path);
    }
    ensurePeerFlowTimer();

    for (const n of nodes) {
      const peer = n.kind === "peer";
      const kids = childCount(n.nodeId, full.edges);
      const isCollapsed = collapsed.has(n.nodeId);
      const g = document.createElementNS(NS, "g");
      g.setAttribute(
        "class",
        (peer ? "node node--peer" : "node node--ext") +
          (isCollapsed ? " is-collapsed" : "") +
          (selectedId === n.nodeId ? " is-selected" : "") +
          (peer && isOfflineNode(n) ? " is-offline" : "")
      );
      g.setAttribute("data-id", n.nodeId);
      g.style.cursor = "grab";
      moveNodeGroup(g, n);

      if (peer) {
        // Expanding sonar waves. Phase is derived from the node id so each server
        // pulses on its own rhythm and keeps it across re-renders.
        let seed = 0;
        for (let i = 0; i < n.nodeId.length; i++) seed = (seed * 31 + n.nodeId.charCodeAt(i)) >>> 0;
        for (let w = 0; w < 2; w++) {
          const wave = document.createElementNS(NS, "circle");
          wave.setAttribute("class", "wave");
          wave.setAttribute("r", "13");
          wave.style.animationDelay = "-" + ((seed % 4600) / 1000 + w * 2.3).toFixed(2) + "s";
          g.appendChild(wave);
        }
      }

      const halo = document.createElementNS(NS, "circle");
      halo.setAttribute("class", "halo");
      halo.setAttribute("r", peer ? "22" : "18");
      g.appendChild(halo);

      const ring = document.createElementNS(NS, "circle");
      ring.setAttribute("class", "ring");
      ring.setAttribute("r", peer ? "16" : "13");
      g.appendChild(ring);

      const core = document.createElementNS(NS, "circle");
      core.setAttribute("class", "core");
      core.setAttribute("r", peer ? "11" : "9");
      if (peer) core.setAttribute("filter", "url(#node-glow)");
      g.appendChild(core);

      if (peer && n.hasIcon && n.iconUrl) {
        const clipId = "clip-" + n.nodeId.replace(/[^a-zA-Z0-9_-]/g, "_");
        const clip = document.createElementNS(NS, "clipPath");
        clip.setAttribute("id", clipId);
        const clipC = document.createElementNS(NS, "circle");
        clipC.setAttribute("r", "9");
        clip.appendChild(clipC);
        defs.appendChild(clip);
        const img = document.createElementNS(NS, "image");
        img.setAttribute("href", n.iconUrl);
        img.setAttributeNS("http://www.w3.org/1999/xlink", "href", n.iconUrl);
        img.setAttribute("x", "-9");
        img.setAttribute("y", "-9");
        img.setAttribute("width", "18");
        img.setAttribute("height", "18");
        img.setAttribute("clip-path", `url(#${clipId})`);
        img.setAttribute("preserveAspectRatio", "xMidYMid slice");
        g.appendChild(img);
      }

      if (peer && kids > 0) {
        const badge = document.createElementNS(NS, "g");
        badge.setAttribute("class", "badge");
        badge.setAttribute("transform", "translate(12,-12)");
        const bc = document.createElementNS(NS, "circle");
        bc.setAttribute("r", "8");
        badge.appendChild(bc);
        const bt = document.createElementNS(NS, "text");
        bt.setAttribute("text-anchor", "middle");
        bt.setAttribute("dominant-baseline", "central");
        bt.textContent = isCollapsed ? String(kids) : "−";
        badge.appendChild(bt);
        g.appendChild(badge);
      }

      const label = document.createElementNS(NS, "text");
      label.setAttribute("class", "label");
      label.setAttribute("y", "34");
      label.setAttribute("text-anchor", "middle");
      label.textContent = shortLabel(n.displayName || n.serverId || n.nodeId, peer ? 22 : 18);
      g.appendChild(label);

      if (peer && n.description) {
        const desc = document.createElementNS(NS, "text");
        desc.setAttribute("class", "desc");
        desc.setAttribute("y", "48");
        desc.setAttribute("text-anchor", "middle");
        desc.textContent = shortLabel(n.description, 26);
        g.appendChild(desc);
      }

      const sub = document.createElementNS(NS, "text");
      sub.setAttribute("class", "sub");
      sub.setAttribute("y", peer && n.description ? "62" : "50");
      sub.setAttribute("text-anchor", "middle");
      sub.textContent = peer
        ? (n.publicHost || "") + (n.publicPort ? ":" + n.publicPort : "")
        : (n.publicHost || "") + ":" + n.publicPort;
      g.appendChild(sub);

      g.addEventListener("pointerdown", (evt) => {
        if (evt.button != null && evt.button !== 0) return;
        evt.preventDefault();
        evt.stopPropagation();
        g.setPointerCapture(evt.pointerId);
        const p = clientToWorld(evt);
        const live = full.nodes.find((x) => x.nodeId === n.nodeId) || n;
        dragState = {
          id: n.nodeId,
          g,
          offsetX: p.x - (live.x ?? n.x),
          offsetY: p.y - (live.y ?? n.y),
          startX: p.x,
          startY: p.y,
          moved: false,
        };
        g.style.cursor = "grabbing";
        g.classList.add("is-dragging");
      });

      gNodes.appendChild(g);
    }

    if (selectedId) {
      const still = full.nodes.find((x) => x.nodeId === selectedId);
      if (still) showDetail(still);
      else showDetail(null);
    }

    empty.hidden = full.nodes.length > 0;
    empty.setAttribute("aria-hidden", full.nodes.length > 0 ? "true" : "false");
    applySearchHighlight({ focus: false });
  }

  svg.addEventListener("wheel", (evt) => {
    evt.preventDefault();
    const factor = evt.deltaY < 0 ? 1.12 : 1 / 1.12;
    zoomAt(factor, evt.clientX, evt.clientY);
  }, { passive: false });

  svg.addEventListener("pointerdown", (evt) => {
    if (dragState) return;
    if (evt.button != null && evt.button !== 0) return;
    if (evt.target !== svg && evt.target !== viewport && !evt.target.classList?.contains?.("edges")) {
      // allow pan from empty areas / edges background
      const t = evt.target;
      if (t.closest && t.closest(".node")) return;
    }
    if (evt.target.closest && evt.target.closest(".node")) return;
    evt.preventDefault();
    svg.setPointerCapture(evt.pointerId);
    const p = svgPoint(evt);
    panState = {
      startX: p.x,
      startY: p.y,
      ox: view.x,
      oy: view.y,
    };
    svg.classList.add("is-panning");
  });

  svg.addEventListener("pointermove", (evt) => {
    if (dragState) {
      const p = clientToWorld(evt);
      if (Math.hypot(p.x - dragState.startX, p.y - dragState.startY) > 4 / view.k) {
        dragState.moved = true;
      }
      const n = lastModel.nodes.find((x) => x.nodeId === dragState.id);
      if (!n) return;
      n.x = clamp(p.x - dragState.offsetX, -200, W + 200);
      n.y = clamp(p.y - dragState.offsetY, -200, H + 200);
      moveNodeGroup(dragState.g, n);
      syncEdges();
      return;
    }
    if (panState) {
      const p = svgPoint(evt);
      view.x = panState.ox + (p.x - panState.startX);
      view.y = panState.oy + (p.y - panState.startY);
      applyView();
    }
  });

  function endPointer(evt) {
    if (dragState) {
      const { g, id, moved } = dragState;
      try {
        g.releasePointerCapture(evt.pointerId);
      } catch (_) {
        /* ignore */
      }
      g.style.cursor = "grab";
      g.classList.remove("is-dragging");
      dragState = null;
      if (!moved) {
        const n = lastModel.nodes.find((x) => x.nodeId === id);
        if (n && n.kind === "peer" && childCount(id, lastModel.edges) > 0) {
          if (collapsed.has(id)) collapsed.delete(id);
          else collapsed.add(id);
          renderGraph(lastServers, lastPortals);
        }
        showDetail(n);
        if (viewport) {
          viewport.querySelectorAll(".node").forEach((el) => {
            el.classList.toggle("is-selected", el.getAttribute("data-id") === selectedId);
          });
        }
      }
      return;
    }
    if (panState) {
      try {
        svg.releasePointerCapture(evt.pointerId);
      } catch (_) {
        /* ignore */
      }
      panState = null;
      svg.classList.remove("is-panning");
    }
  }

  svg.addEventListener("pointerup", endPointer);
  svg.addEventListener("pointercancel", endPointer);

  document.getElementById("zoom-in")?.addEventListener("click", () => zoomBy(1.2));
  document.getElementById("zoom-out")?.addEventListener("click", () => zoomBy(1 / 1.2));
  document.getElementById("zoom-reset")?.addEventListener("click", () => resetView());

  const mapSection = document.getElementById("map");
  const mapExpandBtn = document.getElementById("map-expand");
  function setMapExpanded(expanded) {
    if (!mapSection || !mapExpandBtn) return;
    expanded = !!expanded;
    mapSection.classList.toggle("is-expanded", expanded);
    document.body.classList.toggle("map-expanded", expanded);
    mapExpandBtn.setAttribute("aria-pressed", expanded ? "true" : "false");
    const titleKey = expanded ? "collapseMapTitle" : "expandMapTitle";
    mapExpandBtn.setAttribute("data-i18n-title", titleKey);
    mapExpandBtn.setAttribute(
      "title",
      (window.mvpI18n && window.mvpI18n.t(titleKey)) || (expanded ? "Exit expanded map" : "Expand map")
    );
  }
  if (mapExpandBtn) {
    mapExpandBtn.addEventListener("click", (evt) => {
      evt.preventDefault();
      evt.stopPropagation();
      setMapExpanded(!document.body.classList.contains("map-expanded"));
    });
  }
  document.addEventListener("keydown", (evt) => {
    if (evt.key === "Escape" && document.body.classList.contains("map-expanded")) {
      setMapExpanded(false);
    }
  });
  window.addEventListener("mvp:lang", () => {
    if (!mapExpandBtn) return;
    const expanded = document.body.classList.contains("map-expanded");
    const titleKey = expanded ? "collapseMapTitle" : "expandMapTitle";
    mapExpandBtn.setAttribute("data-i18n-title", titleKey);
    if (window.mvpI18n) {
      mapExpandBtn.setAttribute("title", window.mvpI18n.t(titleKey));
    }
  });

  document.getElementById("fold-all")?.addEventListener("click", () => {
    for (const n of lastModel.nodes) {
      if (n.kind === "peer" && childCount(n.nodeId, lastModel.edges) > 0) {
        collapsed.add(n.nodeId);
      }
    }
    renderGraph(lastServers, lastPortals);
  });
  document.getElementById("unfold-all")?.addEventListener("click", () => {
    collapsed.clear();
    renderGraph(lastServers, lastPortals);
  });

  function setSearchOpen(open) {
    if (!searchBox || !searchToggle || !searchInput) return;
    searchBox.classList.toggle("is-open", open);
    searchToggle.setAttribute("aria-expanded", open ? "true" : "false");
    if (open) {
      searchInput.focus();
      searchInput.select();
    } else {
      searchQuery = "";
      searchInput.value = "";
      searchFocusedOnce = false;
      applySearchHighlight({ focus: false });
      if (selectedId == null) showDetail(null);
    }
  }

  searchToggle?.addEventListener("click", () => {
    const open = !searchBox.classList.contains("is-open");
    setSearchOpen(open);
  });

  searchInput?.addEventListener("input", () => {
    searchQuery = String(searchInput.value || "").trim().toLowerCase();
    const shouldFocus = searchQuery.length > 0;
    applySearchHighlight({ focus: shouldFocus && !searchFocusedOnce });
    if (shouldFocus) searchFocusedOnce = true;
    if (!searchQuery) searchFocusedOnce = false;
  });

  searchInput?.addEventListener("keydown", (evt) => {
    if (evt.key === "Escape") {
      evt.preventDefault();
      setSearchOpen(false);
      searchToggle?.focus();
    } else if (evt.key === "Enter") {
      evt.preventDefault();
      applySearchHighlight({ focus: true });
    }
  });

  function renderList(servers, portals) {
    list.innerHTML = "";
    const { nodes, edges } = buildGraphModel(servers, portals);
    const outs = new Map();
    for (const e of edges) outs.set(e.from, (outs.get(e.from) || 0) + 1);

    const peers = nodes.filter((n) => n.kind === "peer");
    const sorted = [...peers].sort(
      (a, b) => (a.lastPingAgeSec ?? 1e9) - (b.lastPingAgeSec ?? 1e9)
    );
    for (const s of sorted) {
      const li = document.createElement("li");
      li.setAttribute("data-id", s.nodeId);
      li.style.cursor = "pointer";
      const left = document.createElement("div");
      const name = document.createElement("div");
      name.className = "name";
      if (s.hasIcon && s.iconUrl) {
        const img = document.createElement("img");
        img.className = "icon";
        img.src = s.iconUrl;
        img.alt = "";
        img.width = 24;
        img.height = 24;
        name.appendChild(img);
      }
      name.appendChild(document.createTextNode(s.displayName || s.serverId));
      const addr = document.createElement("div");
      addr.className = "addr";
      const linkCount = outs.get(s.nodeId) || 0;
      const desc = s.description ? s.description + " · " : "";
      addr.textContent =
        desc +
        `${s.serverId} · ${s.publicHost}:${s.publicPort}` +
        (s.mcVersion ? ` · ${s.mcVersion}` : "") +
        (linkCount ? ` · ${linkCount} link${linkCount === 1 ? "" : "s"}` : "");
      left.appendChild(name);
      left.appendChild(addr);
      const ping = document.createElement("div");
      ping.className = "ping";
      ping.textContent = s.lastPingAgo || formatAgo(s.lastPingAgeSec);
      li.appendChild(left);
      li.appendChild(ping);
      li.addEventListener("click", () => {
        const live = lastModel.nodes.find((n) => n.nodeId === s.nodeId) || s;
        focusNode(live);
        showDetail(live);
        if (viewport) {
          viewport.querySelectorAll(".node").forEach((el) => {
            el.classList.toggle("is-selected", el.getAttribute("data-id") === selectedId);
          });
        }
      });
      list.appendChild(li);
    }
    applySearchHighlight({ focus: false });
  }

  // Point every download link at the versioned jar (MultiversePortals-<ver>.jar) so the
  // saved file always carries the release version. Static texts ship the plain name;
  // this rewrites them after load and after each language switch.
  let jarVersionName = null;
  async function refreshJarLinks() {
    try {
      if (!jarVersionName) {
        const res = await fetch("/version.json", { cache: "no-store" });
        if (!res.ok) return;
        const ver = (await res.json()).version;
        if (!ver) return;
        jarVersionName = "MultiversePortals-" + ver + ".jar";
      }
      document.querySelectorAll('a[href*="/download/MultiversePortals"]').forEach((a) => {
        a.setAttribute("href", "/download/" + jarVersionName);
        a.setAttribute("download", jarVersionName);
      });
      document.querySelectorAll("code, pre.path-tree").forEach((el) => {
        if (el.textContent.includes("MultiversePortals.jar")) {
          el.textContent = el.textContent.replaceAll("MultiversePortals.jar", jarVersionName);
        }
      });
    } catch (_) {
      /* ignore */
    }
  }

  async function refreshJarMeta() {
    try {
      const res = await fetch("/download/MultiversePortals.jar?v=20260722x", { method: "HEAD", cache: "no-store" });
      if (!res.ok) return;
      const len = res.headers.get("content-length");
      const mod = res.headers.get("last-modified");
      const mb = len ? (Number(len) / (1024 * 1024)).toFixed(1) + " MB" : "";
      const locale =
        window.mvpI18n?.lang === "zh" ? "zh-CN" : window.mvpI18n?.lang === "ru" ? "ru-RU" : "en-US";
      let ver = "";
      try {
        const vr = await fetch("/version.json", { cache: "no-store" });
        if (vr.ok) ver = String((await vr.json()).version || "").trim();
      } catch (_) {
        /* ignore */
      }
      const pluginWord =
        window.mvpI18n?.lang === "ru" ? "Плагин" : window.mvpI18n?.lang === "zh" ? "插件" : "Plugin";
      const head = [pluginWord, ver && ("v" + ver), mb].filter(Boolean).join(" ");
      jarMeta.textContent = [
        head,
        mod && (t("jarUpdated") + " " + new Date(mod).toLocaleString(locale)),
      ]
        .filter(Boolean)
        .join(" · ");
    } catch (_) {
      /* ignore */
    }
  }

  let lastStat = null;
  let lastError = null;
  let lastPlayers = { mvp: 0, network: 0 };
  /** network (green) by default; click number → mvp (teal) */
  let playersMode = "network";

  function playersLocale() {
    try {
      const lang = (window.mvpI18n && window.mvpI18n.lang) || "en";
      if (lang === "zh") return "zh-CN";
      if (lang === "ru") return "ru-RU";
      return "en-US";
    } catch (_) {
      return "en-US";
    }
  }

  function formatPlayers(n) {
    const v = Math.max(0, Number(n) || 0);
    try {
      return v.toLocaleString(playersLocale());
    } catch (_) {
      return String(v);
    }
  }

  function renderStatLine() {
    if (lastError) {
      stat.textContent = t("catalogDown");
      return;
    }
    if (!lastStat) {
      stat.textContent = t("loading");
      return;
    }
    const count = playersMode === "mvp" ? lastPlayers.mvp : lastPlayers.network;
    const modeClass = playersMode === "mvp" ? "is-mvp" : "is-network";
    const base = t("stat", lastStat);
    const unit = t("playersUnit");
    stat.innerHTML =
      '<span class="stat-base">' +
      base +
      '</span><span class="stat-sep" aria-hidden="true"> · </span>' +
      '<button type="button" class="stat-players ' +
      modeClass +
      '" id="stat-players-toggle">' +
      '<span class="stat-players__dot" aria-hidden="true"></span>' +
      '<span class="stat-players__n">' +
      formatPlayers(count) +
      "</span>" +
      '<span class="stat-players__unit">' +
      unit +
      "</span></button>";
    const toggle = document.getElementById("stat-players-toggle");
    if (toggle) {
      toggle.addEventListener("click", (e) => {
        e.preventDefault();
        e.stopPropagation();
        playersMode = playersMode === "network" ? "mvp" : "network";
        renderStatLine();
      });
    }
  }

  async function refresh() {
    try {
      const data = await loadCatalog();
      const retainDays = Number(data.mapRetainDays) > 0 ? Number(data.mapRetainDays) : 30;
      const retainSec = retainDays * 86400;
      // Drop MVP servers absent longer than retain window (hub also filters; belt-and-suspenders)
      let servers = (Array.isArray(data.servers) ? data.servers : []).filter((s) => {
        const age = s.lastPingAgeSec;
        if (age == null || age < 0) return true;
        return age < retainSec;
      });
      const keepIds = new Set(servers.map((s) => s.serverId).filter(Boolean));
      const portals = (Array.isArray(data.portals) ? data.portals : []).filter(
        (p) => !p.serverId || keepIds.has(p.serverId)
      );
      const { nodes, edges } = buildGraphModel(servers, portals);
      const peers = nodes.filter((n) => n.kind === "peer").length;
      const ext = nodes.filter((n) => n.kind === "external").length;
      const live = servers.filter((s) => s.online !== false && (s.lastPingAgeSec ?? 99999) < 5400);
      lastStat = { peers, ext, live: live.length, edges: edges.length };
      const p = data.players && typeof data.players === "object" ? data.players : null;
      let mvp = p && Number.isFinite(Number(p.mvp)) ? Math.max(0, Number(p.mvp)) : 0;
      let network = p && Number.isFinite(Number(p.network)) ? Math.max(0, Number(p.network)) : mvp;
      if (!p) {
        mvp = servers.reduce((sum, s) => {
          if (s.online === false) return sum;
          return sum + Math.max(0, Number(s.onlinePlayers) || 0);
        }, 0);
        network = mvp;
      }
      lastPlayers = { mvp, network: Math.max(mvp, network) };
      lastError = null;
      renderStatLine();
      renderGraph(servers, portals);
      renderList(servers, portals);
      if (empty.hidden) empty.textContent = t("empty");
    } catch (e) {
      lastError = String(e.message || e);
      lastStat = null;
      renderStatLine();
      empty.hidden = false;
      empty.setAttribute("aria-hidden", "false");
      empty.textContent = lastError;
    }
  }

  window.addEventListener("mvp:lang", () => {
    renderStatLine();
    if (lastError) {
      empty.textContent = lastError;
    }
    if (selectedId) {
      const n = lastModel.nodes.find((x) => x.nodeId === selectedId);
      if (n) showDetail(n);
    }
    refreshJarMeta();
    refreshJarLinks();
    if (lastServers.length || lastPortals.length) {
      renderGraph(lastServers, lastPortals);
      renderList(lastServers, lastPortals);
    }
  });

  const dockerCopyBtn = document.getElementById("docker-cmd-copy");
  if (dockerCopyBtn) {
    dockerCopyBtn.addEventListener("click", async () => {
      const text = dockerCopyBtn.getAttribute("data-copy") || "";
      if (!text) return;
      const ok = await copyText(text);
      dockerCopyBtn.textContent = ok ? t("copiedCmd") : t("copyCmd");
      if (ok) {
        window.setTimeout(() => {
          dockerCopyBtn.textContent = t("copyCmd");
        }, 1600);
      }
    });
  }

  refreshJarMeta();
  refreshJarLinks();
  refresh();
  setInterval(refresh, 60000);
})();
