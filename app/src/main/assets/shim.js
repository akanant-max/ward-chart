/* ===================================================================
   Ward Chart — offline store and Drive sync
   ===================================================================

   The chart page is shared verbatim between claude.ai and this app. It
   asks for `window.claude.use("db")` and `use("downloads")`. On
   claude.ai those resolve to the artifact's server-side store. Here
   this file answers with the same call shapes, backed by localStorage,
   so the page's own code never changes between the two.

   It also adds `window.WardSync`, which merges this phone's data with a
   JSON file in Google Drive. Kotlin owns the file; this file owns the
   data and the merge.

   Record layout in localStorage, all keyed under `wardchart.v1.`:

     config/main      { version, cap, intervals, subjects, bands,
                        blocks[], chores[] }
     state/chores     { done: { <choreId>: "YYYY-MM-DD" } }
     state/days       { log:  { "YYYY-MM-DD": [blockId, ...] } }
     topics/<id>      { subject, title, taught, due, stage, reviews,
                        lastReviewed, example }

   Two keys belong to this file alone and are invisible to the page:

     __meta           { "<stamp key>": <epoch ms> }   last local change
     __tombstones     { "<topicId>":   <epoch ms> }   deleted topics

   Stamp keys are deliberately finer than records, so two phones that
   both went offline can each keep what they did:

     "config"            the whole config object
     "chores:<choreId>"  one chore's last-done date
     "days:<date>"       one day's ticked blocks
     "topic:<topicId>"   one topic

   =================================================================== */

(function () {
  "use strict";

  var NS = "wardchart.v1.";
  var META = NS + "__meta";
  var TOMB = NS + "__tombstones";

  /* ---------- raw storage ---------- */

  function rawGet(key) {
    try {
      var r = localStorage.getItem(key);
      return r ? JSON.parse(r) : null;
    } catch (e) {
      return null;
    }
  }
  function rawSet(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
      return true;
    } catch (e) {
      return false;
    }
  }
  function rawDel(key) {
    try {
      localStorage.removeItem(key);
    } catch (e) {}
  }

  function read(path) {
    var d = rawGet(NS + path);
    if (d && typeof d === "object") delete d._ts; // legacy field, never shown to the page
    return d;
  }
  function store(path, value) {
    return rawSet(NS + path, value);
  }

  function meta() {
    return rawGet(META) || {};
  }
  function stamp(keys, when) {
    var m = meta(), t = when || Date.now(), i;
    for (i = 0; i < keys.length; i++) m[keys[i]] = t;
    rawSet(META, m);
  }
  function tombstones() {
    return rawGet(TOMB) || {};
  }

  function topicIds() {
    var out = [], pre = NS + "topics/";
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k && k.lastIndexOf(pre, 0) === 0) {
          var rest = k.slice(pre.length);
          if (rest && rest.indexOf("/") < 0) out.push(rest);
        }
      }
    } catch (e) {}
    return out.sort();
  }

  /* ---------- change stamping ----------
     The page writes whole records. We diff against what is already
     stored so only the parts that actually changed get a new stamp —
     otherwise every save would claim every day and every chore, and a
     merge would always favour whichever phone saved last. */

  function stampWrite(path, next) {
    var prev = read(path), changed = [], k;

    if (path === "config/main") {
      if (JSON.stringify(prev) !== JSON.stringify(next)) changed.push("config");

    } else if (path === "state/chores") {
      var pd = (prev && prev.done) || {}, nd = (next && next.done) || {};
      for (k in nd) if (nd[k] !== pd[k]) changed.push("chores:" + k);
      for (k in pd) if (!(k in nd)) changed.push("chores:" + k);

    } else if (path === "state/days") {
      var pl = (prev && prev.log) || {}, nl = (next && next.log) || {};
      for (k in nl)
        if (JSON.stringify((nl[k] || []).slice().sort()) !==
            JSON.stringify((pl[k] || []).slice().sort())) changed.push("days:" + k);
      for (k in pl) if (!(k in nl)) changed.push("days:" + k);

    } else if (path.lastIndexOf("topics/", 0) === 0) {
      var id = path.slice("topics/".length);
      if (JSON.stringify(prev) !== JSON.stringify(next)) changed.push("topic:" + id);
      var tb = tombstones();
      if (id in tb) { delete tb[id]; rawSet(TOMB, tb); } // re-created, so un-delete
    }

    if (changed.length) stamp(changed);
    return changed.length > 0;
  }

  function stampDelete(path) {
    if (path.lastIndexOf("topics/", 0) !== 0) return false;
    var id = path.slice("topics/".length);
    var tb = tombstones();
    tb[id] = Date.now();
    rawSet(TOMB, tb);
    stamp(["topic:" + id]);
    return true;
  }

  /* ---------- the db surface the page expects ---------- */

  var docL = Object.create(null), collL = Object.create(null);

  function docSnap(p) {
    var d = read(p);
    return {
      id: p.slice(p.lastIndexOf("/") + 1),
      exists: d !== null,
      data: function () { return d === null ? undefined : d; },
      metadata: { fromCache: false, hasPendingWrites: false }
    };
  }
  function collSnap(coll) {
    var ids = coll === "topics" ? topicIds() : [];
    var docs = ids.map(function (id) { return docSnap(coll + "/" + id); });
    return {
      docs: docs, size: docs.length, empty: docs.length === 0,
      docChanges: function () {
        return docs.map(function (d, i) {
          return { type: "added", doc: d, oldIndex: -1, newIndex: i };
        });
      },
      metadata: { fromCache: false, hasPendingWrites: false }
    };
  }

  function fireDoc(p) {
    var ls = docL[p];
    if (ls) ls.slice().forEach(function (fn) { try { fn(docSnap(p)); } catch (e) {} });
    var i = p.lastIndexOf("/");
    if (i > 0) fireColl(p.slice(0, i));
  }
  function fireColl(coll) {
    var ls = collL[coll];
    if (ls) ls.slice().forEach(function (fn) { try { fn(collSnap(coll)); } catch (e) {} });
  }
  /** Re-deliver everything — used after a merge lands from Drive. */
  function fireAll() {
    Object.keys(docL).forEach(function (p) {
      docL[p].slice().forEach(function (fn) { try { fn(docSnap(p)); } catch (e) {} });
    });
    Object.keys(collL).forEach(fireColl);
  }

  function listen(bag, key, fn, build) {
    (bag[key] = bag[key] || []).push(fn);
    setTimeout(function () { try { fn(build(key)); } catch (e) {} }, 0);
    return function () {
      bag[key] = (bag[key] || []).filter(function (f) { return f !== fn; });
    };
  }
  function rid() {
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  }

  var FULL = { code: "quota_exceeded", message: "Phone storage is full." };

  function docRef(p) {
    return {
      id: p.slice(p.lastIndexOf("/") + 1),
      path: p,
      get: function () { return Promise.resolve(docSnap(p)); },
      set: function (data) {
        var changed = stampWrite(p, data);
        if (!store(p, data)) return Promise.reject(FULL);
        fireDoc(p);
        if (changed) Sync.schedulePush();
        return Promise.resolve();
      },
      update: function (data) {
        var cur = read(p);
        if (cur === null)
          return Promise.reject({ code: "invalid_argument", message: "No such record." });
        Object.keys(data).forEach(function (k) { cur[k] = data[k]; });
        var changed = stampWrite(p, cur);
        if (!store(p, cur)) return Promise.reject(FULL);
        fireDoc(p);
        if (changed) Sync.schedulePush();
        return Promise.resolve();
      },
      "delete": function () {
        stampDelete(p);
        rawDel(NS + p);
        fireDoc(p);
        Sync.schedulePush();
        return Promise.resolve();
      },
      acquire: function () { return Promise.resolve({ acquired: true }); },
      onSnapshot: function (next) { return listen(docL, p, next, docSnap); },
      collection: function (sub) { return collRef(p + "/" + sub); }
    };
  }

  function collRef(coll) {
    var q = {
      path: coll,
      doc: function (id) { return docRef(coll + "/" + (id || rid())); },
      add: function (data) {
        var r = q.doc();
        return r.set(data).then(function () { return r; });
      },
      where: function () { return q; },
      orderBy: function () { return q; },
      limit: function () { return q; },
      get: function () { return Promise.resolve(collSnap(coll)); },
      onSnapshot: function (next) { return listen(collL, coll, next, collSnap); }
    };
    return q;
  }

  var DB = { doc: docRef, collection: collRef };

  var DOWNLOADS = {
    save: function (req) {
      try {
        if (window.WardBridge && window.WardBridge.saveFile) {
          var text = typeof req.data === "string" ? req.data : String(req.data);
          if (window.WardBridge.saveFile(String(req.filename || "ward-chart.json"), text))
            return Promise.resolve({ status: "saved" });
          return Promise.reject({ code: "unavailable", message: "Could not write the file." });
        }
      } catch (e) {}
      /* No Android bridge: a browser or an iPad home-screen app. Hand the
         file to the browser as a normal download (iOS shows its save sheet). */
      try {
        var blob = new Blob([typeof req.data === "string" ? req.data : String(req.data)],
                            { type: "application/json" });
        var url = URL.createObjectURL(blob);
        var a = document.createElement("a");
        a.href = url; a.download = String(req.filename || "ward-chart.json");
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 4000);
        return Promise.resolve({ status: "saved" });
      } catch (e2) {}
      return Promise.reject({ code: "unavailable", message: "Saving is not available here." });
    }
  };

  window.claude = {
    use: function (name) {
      if (name === "db") return Promise.resolve(DB);
      if (name === "downloads") return Promise.resolve(DOWNLOADS);
      return Promise.resolve(null);
    }
  };

  /* ===================================================================
     Sync
     =================================================================== */

  var Sync = (function () {
    var pushTimer = null;
    var state = { wired: false, file: "", busy: false, last: "", error: "" };

    function bridge() {
      return (window.WardBridge && window.WardBridge.syncRead) ? window.WardBridge : null;
    }

    /** Everything this phone knows, in the shape written to Drive. */
    function snapshot() {
      var topics = {};
      topicIds().forEach(function (id) { topics[id] = read("topics/" + id); });
      return {
        kind: "ward-chart-sync",
        version: 1,
        config: read("config/main"),
        chores: (read("state/chores") || {}).done || {},
        days: (read("state/days") || {}).log || {},
        topics: topics,
        meta: meta(),
        tombstones: tombstones()
      };
    }

    function ts(m, key) {
      var v = m && m[key];
      return typeof v === "number" ? v : 0;
    }

    /**
     * Field-level three-way-ish merge. For each stamp key, whichever
     * side changed it more recently wins that key alone — so one phone
     * ticking blocks and another logging a topic both survive.
     * Returns the merged snapshot plus whether the local copy moved.
     */
    function merge(local, remote) {
      var lm = local.meta || {}, rm = remote.meta || {};
      var out = {
        kind: "ward-chart-sync", version: 1,
        config: null, chores: {}, days: {}, topics: {},
        meta: {}, tombstones: {}
      };
      var k;

      // config — whole object, newest wins
      var lc = ts(lm, "config"), rc = ts(rm, "config");
      out.config = rc > lc ? remote.config : local.config;
      if (!out.config) out.config = local.config || remote.config;
      out.meta.config = Math.max(lc, rc);

      // chores — per chore; a later last-done date is always the truth,
      // and the stamp only breaks ties.
      var chore = {};
      Object.keys(local.chores || {}).forEach(function (x) { chore[x] = 1; });
      Object.keys(remote.chores || {}).forEach(function (x) { chore[x] = 1; });
      Object.keys(chore).forEach(function (id) {
        var lv = (local.chores || {})[id], rv = (remote.chores || {})[id];
        var lt = ts(lm, "chores:" + id), rt = ts(rm, "chores:" + id);
        var take;
        if (lv && rv) take = (rv > lv) ? rv : (lv > rv ? lv : (rt > lt ? rv : lv));
        else take = lv || rv;
        if (take) out.chores[id] = take;
        out.meta["chores:" + id] = Math.max(lt, rt);
      });

      // days — per date
      var day = {};
      Object.keys(local.days || {}).forEach(function (x) { day[x] = 1; });
      Object.keys(remote.days || {}).forEach(function (x) { day[x] = 1; });
      Object.keys(day).forEach(function (d) {
        var lt = ts(lm, "days:" + d), rt = ts(rm, "days:" + d);
        var lv = (local.days || {})[d], rv = (remote.days || {})[d];
        if (lv && rv) out.days[d] = (rt > lt) ? rv : lv;
        else out.days[d] = lv || rv;
        out.meta["days:" + d] = Math.max(lt, rt);
      });

      // topics — per topic, with deletions honoured by timestamp
      var ltb = local.tombstones || {}, rtb = remote.tombstones || {};
      var ids = {};
      Object.keys(local.topics || {}).forEach(function (x) { ids[x] = 1; });
      Object.keys(remote.topics || {}).forEach(function (x) { ids[x] = 1; });
      Object.keys(ltb).forEach(function (x) { ids[x] = 1; });
      Object.keys(rtb).forEach(function (x) { ids[x] = 1; });
      Object.keys(ids).forEach(function (id) {
        var lt = ts(lm, "topic:" + id), rt = ts(rm, "topic:" + id);
        var deletedAt = Math.max(ts(ltb, id), ts(rtb, id));
        var liveAt = Math.max(
          (local.topics || {})[id] ? lt : 0,
          (remote.topics || {})[id] ? rt : 0
        );
        if (deletedAt && deletedAt >= liveAt) {
          out.tombstones[id] = deletedAt;
        } else {
          var rec = (rt > lt && (remote.topics || {})[id])
            ? remote.topics[id]
            : ((local.topics || {})[id] || (remote.topics || {})[id]);
          if (rec) out.topics[id] = rec;
        }
        out.meta["topic:" + id] = Math.max(lt, rt);
      });

      return out;
    }

    /** Write a merged snapshot back into localStorage. */
    function apply(m) {
      if (m.config) store("config/main", m.config);
      store("state/chores", { done: m.chores || {} });
      store("state/days", { log: m.days || {} });

      var have = topicIds(), keep = m.topics || {};
      have.forEach(function (id) { if (!(id in keep)) rawDel(NS + "topics/" + id); });
      Object.keys(keep).forEach(function (id) { store("topics/" + id, keep[id]); });

      rawSet(META, m.meta || {});
      rawSet(TOMB, m.tombstones || {});
      fireAll();
    }

    function paintStatus() {
      var el = document.getElementById("sync-status");
      if (!el) return;
      if (!bridge()) { el.textContent = "Sync is only available in the Android app."; return; }
      if (state.error) { el.textContent = "Last attempt failed: " + state.error; return; }
      if (!state.wired) { el.textContent = "Not syncing. Choose a file in your Drive to start."; return; }
      el.textContent = (state.busy ? "Syncing…" : "Syncing to " + (state.file || "a file in Drive")) +
        (state.last ? "  ·  last synced " + state.last : "");
    }

    function refreshState() {
      var b = bridge();
      if (!b) return;
      try {
        var s = JSON.parse(b.syncStatus() || "{}");
        state.wired = !!s.wired;
        state.file = s.file || "";
        state.last = s.last || "";
      } catch (e) {}
      paintStatus();
    }

    /** Pull, merge, push. Safe to call at any time; no-op when unwired. */
    function syncNow(silent) {
      var b = bridge();
      if (!b) return Promise.resolve(false);
      refreshState();
      if (!state.wired) return Promise.resolve(false);

      state.busy = true; state.error = ""; paintStatus();

      return new Promise(function (resolve) {
        var remoteText = "";
        try {
          remoteText = b.syncRead() || "";
        } catch (e) {
          state.busy = false; state.error = "could not read the file"; paintStatus();
          return resolve(false);
        }

        var local = snapshot(), remote = null;
        if (remoteText) {
          try { remote = JSON.parse(remoteText); } catch (e) { remote = null; }
        }
        if (remote && remote.kind !== "ward-chart-sync") remote = null;

        var merged = remote ? merge(local, remote) : local;
        if (remote) apply(merged);

        var ok = false;
        try { ok = !!b.syncWrite(JSON.stringify(merged)); } catch (e) { ok = false; }

        state.busy = false;
        if (!ok) state.error = "could not write the file";
        refreshState();
        resolve(ok);
      });
    }

    function schedulePush() {
      if (!bridge()) return;
      clearTimeout(pushTimer);
      pushTimer = setTimeout(function () { syncNow(true); }, 1500);
    }

    return {
      snapshot: snapshot,
      merge: merge,
      apply: apply,
      syncNow: syncNow,
      schedulePush: schedulePush,
      paintStatus: paintStatus,
      refreshState: refreshState,
      /** Called from Kotlin once the user has picked (or cleared) a file. */
      onFileChosen: function () { refreshState(); syncNow(); },
      _state: state
    };
  })();

  window.WardSync = Sync;

  // Pull on launch, and again whenever the app comes back to the front.
  function bootSync() { Sync.refreshState(); Sync.syncNow(); }
  if (document.readyState === "complete" || document.readyState === "interactive") {
    setTimeout(bootSync, 400);
  } else {
    document.addEventListener("DOMContentLoaded", function () { setTimeout(bootSync, 400); });
  }
  document.addEventListener("visibilitychange", function () {
    if (!document.hidden) Sync.syncNow();
  });
})();
