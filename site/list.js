(() => {
  const body = document.getElementById("rep-body");
  const empty = document.getElementById("list-empty");
  const countEl = document.getElementById("list-count");
  const search = document.getElementById("list-search");
  const table = document.getElementById("rep-table");

  function t(key, vars) {
    return window.mvpI18n ? window.mvpI18n.t(key, vars) : key;
  }

  function hostKey(host, port) {
    const h = String(host || "").trim().toLowerCase();
    const p = Number(port) > 0 ? Number(port) : 25565;
    return h + ":" + p;
  }

  function fmtAddr(host, port) {
    if (!host) return "—";
    const p = Number(port) > 0 ? Number(port) : 25565;
    return p === 25565 ? host : host + ":" + p;
  }

  function num(v) {
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
  }

  function merge(catalog, rep) {
    const byId = new Map();
    const byHost = new Map();

    function rowOf(id, host, port) {
      if (id && byId.has(id)) return byId.get(id);
      const hk = host ? hostKey(host, port) : "";
      if (hk && byHost.has(hk)) return byHost.get(hk);
      const key = id || hk;
      if (!key) return null;
      const row = {
        id: id || "",
        name: "",
        host: host || "",
        port: Number(port) > 0 ? Number(port) : 25565,
        reputation: 0,
        received: 0,
        sent: 0,
        online: null,
        kind: id ? "peer" : "ext",
      };
      if (id) byId.set(id, row);
      if (hk) byHost.set(hk, row);
      return row;
    }

    for (const s of catalog.servers || []) {
      const row = rowOf(s.serverId, s.publicHost, s.publicPort);
      if (!row) continue;
      row.id = s.serverId || row.id;
      row.name = s.displayName || s.motd || s.serverId || row.name;
      row.host = s.publicHost || row.host;
      row.port = s.publicPort || row.port;
      row.online = s.online !== false && !(s.lastPingAgeSec == null || s.lastPingAgeSec >= 5400);
      row.kind = "peer";
      if (s.serverId) byId.set(s.serverId, row);
      if (row.host) byHost.set(hostKey(row.host, row.port), row);
    }

    for (const p of catalog.portals || []) {
      const host = p.destHost;
      if (!host) continue;
      const port = p.destJavaPort > 0 ? p.destJavaPort : (p.destPort > 0 ? p.destPort : 25565);
      const row = rowOf(p.destServerId, host, port);
      if (!row) continue;
      if (!row.name) row.name = p.destLabel || host;
      if (!row.host) row.host = host;
      if (!row.port) row.port = port;
    }

    for (const e of rep.edges || []) {
      const from = rowOf(e.from, null, 0);
      if (from) {
        from.received += num(e.arrived != null ? e.arrived : e.receivedOk);
        from.sent += num(e.departed != null ? e.departed : e.sentOk);
      }
      const about = rowOf(e.to, e.host, e.port);
      if (about) {
        about.reputation += num(e.reputation);
        if (!about.host && e.host) about.host = e.host;
        if (!about.port && e.port) about.port = e.port;
        if (!about.name) about.name = e.to || e.host || about.name;
      }
    }

    for (const d of rep.dests || []) {
      const row = rowOf(d.serverId, d.host, d.port);
      if (!row) continue;
      if (!row.received) row.received = num(d.accepted);
      if (!row.reputation) {
        row.reputation = num(d.succeeded) - num(d.bounced) + num(d.accepted) - num(d.refused);
      }
      if (!row.host && d.host) row.host = d.host;
      if (!row.port && d.port) row.port = d.port;
    }

    const seen = new Set();
    const out = [];
    for (const row of [...byId.values(), ...byHost.values()]) {
      const k = row.id || hostKey(row.host, row.port);
      if (seen.has(k)) continue;
      seen.add(k);
      if (!row.name) row.name = row.id || fmtAddr(row.host, row.port);
      out.push(row);
    }
    return out;
  }

  let rows = [];
  let sortKey = "reputation";
  let sortDir = -1;
  let query = "";

  function cmp(a, b) {
    const dir = sortDir;
    const ka = a[sortKey];
    const kb = b[sortKey];
    if (sortKey === "name" || sortKey === "addr") {
      const sa = sortKey === "addr" ? fmtAddr(a.host, a.port) : a.name;
      const sb = sortKey === "addr" ? fmtAddr(b.host, b.port) : b.name;
      return dir * String(sa).localeCompare(String(sb), undefined, { sensitivity: "base" });
    }
    const na = num(ka);
    const nb = num(kb);
    if (na !== nb) return dir * (na - nb);
    return String(a.name).localeCompare(String(b.name), undefined, { sensitivity: "base" });
  }

  function matches(row, q) {
    if (!q) return true;
    const hay = [
      row.name,
      row.id,
      row.host,
      fmtAddr(row.host, row.port),
      row.host + ":" + row.port,
    ]
      .join("\n")
      .toLowerCase();
    return hay.includes(q);
  }

  function render() {
    const q = query.trim().toLowerCase();
    const shown = rows.filter((r) => matches(r, q)).sort(cmp);
    body.replaceChildren();
    table.querySelectorAll("th[data-sort]").forEach((th) => {
      th.classList.toggle("is-sort", th.getAttribute("data-sort") === sortKey);
    });
    if (!rows.length) {
      empty.hidden = false;
      empty.textContent = t("listEmpty");
      countEl.textContent = "";
      return;
    }
    if (!shown.length) {
      empty.hidden = false;
      empty.textContent = t("listNone");
      countEl.textContent = t("listCount", { n: 0 });
      return;
    }
    empty.hidden = true;
    countEl.textContent = t("listCount", { n: shown.length });
    const frag = document.createDocumentFragment();
    for (const r of shown) {
      const tr = document.createElement("tr");
      const nameTd = document.createElement("td");
      nameTd.className = "name";
      const dot = document.createElement("span");
      dot.className = "dot" + (r.online ? "" : " is-off");
      dot.title = r.online ? t("listOnline") : t("listOffline");
      nameTd.appendChild(dot);
      nameTd.appendChild(document.createTextNode(r.name));
      const addrTd = document.createElement("td");
      addrTd.className = "addr";
      addrTd.textContent = fmtAddr(r.host, r.port);
      const repTd = document.createElement("td");
      const rep = num(r.reputation);
      repTd.className = rep > 0 ? "rep-pos" : rep < 0 ? "rep-neg" : "rep-zero";
      repTd.textContent = (rep > 0 ? "+" : "") + rep;
      const inTd = document.createElement("td");
      inTd.textContent = String(num(r.received));
      const outTd = document.createElement("td");
      outTd.textContent = String(num(r.sent));
      tr.append(nameTd, addrTd, repTd, inTd, outTd);
      frag.appendChild(tr);
    }
    body.appendChild(frag);
  }

  async function load() {
    empty.hidden = false;
    empty.textContent = t("loading");
    try {
      const [catRes, repRes] = await Promise.all([
        fetch("/mvp/v1/catalog/export", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: "{}",
        }),
        fetch("/mvp/v1/reputation?limit=500", { cache: "no-store" }),
      ]);
      if (!catRes.ok) throw new Error("catalog " + catRes.status);
      const catalog = await catRes.json();
      const rep = repRes.ok ? await repRes.json() : { edges: [], dests: [] };
      rows = merge(catalog, rep);
      render();
    } catch (_) {
      empty.hidden = false;
      empty.textContent = t("catalogDown");
      countEl.textContent = "";
    }
  }

  search.addEventListener("input", () => {
    query = search.value;
    render();
  });

  table.querySelectorAll("th[data-sort]").forEach((th) => {
    th.addEventListener("click", () => {
      const key = th.getAttribute("data-sort");
      if (sortKey === key) {
        sortDir *= -1;
      } else {
        sortKey = key;
        sortDir = key === "name" || key === "addr" ? 1 : -1;
      }
      render();
    });
  });

  window.addEventListener("mvp:lang", () => render());
  load();
})();
