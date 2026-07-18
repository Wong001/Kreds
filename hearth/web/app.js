// Kreds client - journal-first shell (v3 reskin).
//
// Preserved from the pre-reskin client (re-homed into the new view
// containers, not deleted): DM chat (loadConversations/openThread/dm
// compose), stories strip + viewer, the friend-add ceremony, the profile
// editor, the revocation self-logout banner, and the theme toggle.
//
// People are destinations (Task 4, now a full page - Kreds Me/profile
// Slice A): openProfile is the one function every "open this person"
// click site calls (journal, chips, friends, circle nodes); it navigates
// to the #view-profile page. The circle (Task 5): buildCircle() renders
// the radial map from
// /api/kreds for both the compact rail (#circle-rail) and the full
// .overlay; the mobile tab bar's Circle tab shows the same rail.

let STATE = null;
let KREDS = [];       // /api/kreds rows: {identity_pub, name, ring, since}
let FEED = [];        // /api/feed rows
let CONVS = [];   // latest /api/conversations - fetched every refresh() for the nav badge
let ACTIVE_FILTER = "all";   // "all" | "inner" | an identity_pub
let CURRENT_DM = null;
let CURRENT_DM_NAME = "";
let PRIOR_VIEW = "journal";   // where the profile page's Back button returns
let CURRENT_PROFILE = null;   // identity of the profile page currently shown
// AUTHOR's resolved accent (hex) for the profile page currently rendered -
// same module-level idiom as CURRENT_PROFILE, needed because renderBlock
// only sees the per-block post `p`, not the profile object renderProfilePage
// has; set there (same place it computes `color` for --pcolor/avatar) so
// renderBlock's text-style color:"accent" resolves like the profile banner.
let CURRENT_PROFILE_ACCENT = null;
let ARRANGING = false;        // self-only Wall Arrange mode (Up/Down controls shown)
let BLOCK_SETTINGS_OPENER = null;   // #5: element to return focus to when the block-settings modal closes
// Whole-branch review IMPORTANT #3: a heal/action re-render used to always
// snap every deck back to photo 0 and re-collapse the mobile journal rail -
// jarring mid-browse. DECK_POS survives re-renders of the SAME profile;
// LAST_RENDERED_PROFILE (set only in renderProfilePage, so it also covers
// openBlockSettings' reopenAfterAction re-render, not just openProfile) is
// the one signal both fixes gate on: a genuine person-switch prunes/resets,
// a same-person re-render (heal, block-settings action, Arrange toggle) does not.
const DECK_POS = new Map();   // msg_id/album_id -> last flipped-to index
let LAST_RENDERED_PROFILE = null;
let NEEDS_WIZARD = false;   // set by boot() when onboarding_done is false; Task 3's bootData() consumes it
let UPDATE_BANNER_DISMISSED = false;   // session-only; returns next status push (Task 2, 0.3.15)
let LAST_SEEN_UPDATE_VERSION = null;   // re-nudge when update_status.version changes past a dismiss

async function j(url, opts) {
  const r = await fetch(url, opts);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

const DELETE_CONFIRM =
  "Delete for everyone? This removes it from your friends' devices " +
  "running Kreds. A modified app or a screenshot can still have kept a copy.";

// Honest copy for the Unfriend confirm dialog - verbatim, do not edit
// without updating the spec/plan copy it's sourced from.
function unfriendConfirm(name) {
  return "Remove " + name + " from your kreds? They leave your circle and "
    + "messages, and you both stop exchanging. Their Kreds app is sent a "
    + "signed removal notice and deletes what you shared as soon as it "
    + "receives it - we keep trying privately for up to 14 days. An honest "
    + "app deletes on receipt, but a modified client or a screenshot can still keep a copy, "
    + "and if their device is never reachable their copy may remain. "
    + "You can re-add them later if they share their code again.";
}

// A person present in STATE.disconnected has had the relationship end
// (either side unfriended) - {identity_pub, name} or undefined.
function disconnectedInfo(identityPub) {
  return (STATE && STATE.disconnected || [])
    .find(d => d.identity_pub === identityPub);
}

// Every deletion affordance must route through this helper so the honest
// boundary is always shown (spec: 2026-07-03 deletion hardening). When
// DMs/stories grow delete buttons, they call this too.
async function deleteEverywhere(msgId) {
  if (!confirm(DELETE_CONFIRM)) return false;
  await j("/api/delete", {method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({msg_id: msgId})});
  return true;
}

// -- identity color: the ONE place this derivation lives. Same formula on
// every device, so a person renders in the same hue everywhere (avatars,
// chips, and - Task 5 - the circle map). Not returned by any API; the
// client always derives it from the identity fingerprint.
function identityColor(fp) {
  const h = parseInt(fp.slice(0, 6), 16) % 360;
  return `hsl(${h} 55% 45%)`;
}

// -- unread watermark: an honest, per-device localStorage stopgap (NOT
// synced across devices, NOT backed by any server field). Key prefix
// "kreds_opened:" + either a chip's filter value ("inner") or an
// identity_pub.
function lastOpened(key) {
  return Number(localStorage.getItem("kreds_opened:" + key) || 0);
}
function markOpenedNow(key) {
  localStorage.setItem("kreds_opened:" + key, String(Date.now() / 1000));
}

// Bump a person's watermark to a specific post's created_at - never to
// "now", never backwards. Seeing Tuesday's post must NOT mark Thursday's
// unseen post below the fold as read: isFresh() compares against the
// person's LATEST post, so a created_at watermark honestly keeps the dot.
function bumpOpenedTo(key, t) {
  if (!key || !(t > lastOpened(key))) return;
  localStorage.setItem("kreds_opened:" + key, String(t));
  scheduleFreshDotRerender();
}

// Debounced dot refresh: one scroll can mark several posts seen; collapse
// into one chipbar+rail re-render. Deliberately NEVER re-renders the
// journal itself - that would rebuild the entries mid-scroll and
// re-trigger the seen observer.
let FRESH_RERENDER_TIMER = null;
function scheduleFreshDotRerender() {
  clearTimeout(FRESH_RERENDER_TIMER);
  FRESH_RERENDER_TIMER = setTimeout(() => {
    renderChipbar();
    renderCircleRail();
  }, 200);
}

// -- DM unread watermark: same per-device localStorage boundary as the
// post watermark above (never synced, never sent to the server), with
// its OWN prefix - a chip click marking posts read must not mark a DM
// thread read, and vice versa.
function dmOpened(identity) {
  return Number(localStorage.getItem("kreds_dm_opened:" + identity) || 0);
}
// `floor`: optional lower bound for the watermark, in epoch seconds. The
// journal watermark dodges sender clock skew by bumping to the post's own
// created_at rather than "now"; this is the DM-side equivalent - mirror
// the sender's clock forward, never lag behind it. openThread passes the
// newest message's created_at; the send-path call below stays floor-less
// (replying is always "now" - there's no older event to mirror).
function markDmOpenedNow(identity, floor) {
  localStorage.setItem("kreds_dm_opened:" + identity,
    String(Math.max(Date.now() / 1000, floor || 0)));
}

// A conversation is unread when the other side wrote last and it's newer
// than this device's watermark.
function convUnread(c) {
  if (!c.last_at) return false;
  if (c.last_from_me === true) return false;
  // Update-skew degrade (web payload ahead of core, allowed skew): an
  // older core omits last_from_me entirely - fall back to "any activity
  // since I opened", which can over-badge a thread my own OTHER device
  // answered last, but never silently under-badges.
  return c.last_at > dmOpened(c.identity_pub);
}

// Red count pill on the desktop Messages nav button: number of unread
// CONVERSATIONS (people, not messages), hidden at zero. Mobile has no
// Messages tab to badge - named follow-up, not silently included.
function renderDmBadge() {
  const badge = document.getElementById("nav-msg-badge");
  if (!badge) return;
  const n = CONVS.filter(convUnread).length;
  badge.textContent = String(n);
  badge.classList.toggle("hidden", n === 0);
  // The count is the only thing assistive tech should hear - a static
  // "Unread conversations" label would override the number entirely.
  badge.setAttribute("aria-label", n + " unread conversation" + (n === 1 ? "" : "s"));
  // The badge appearing/disappearing changes .appnav's height with no
  // resize event of its own - re-measure --nav-h so .journal-sticky's
  // offset never goes stale (a stale offset opens a see-through slit
  // between the pinned nav and the sticky header-group below it).
  measureNavHeight();
}

const SUN = '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" '
  + 'stroke="currentColor" stroke-width="2" stroke-linecap="round">'
  + '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2'
  + 'M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19"/></svg>';
const MOON = '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" '
  + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" '
  + 'stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3 '
  + 'a7 7 0 0 0 9.8 9.8z"/></svg>';
const ICONS = {
  attach: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" '
    + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" '
    + 'stroke-linejoin="round"><path d="M21 8l-9.5 9.5a3.5 3.5 0 0 1-5-5'
    + 'L15 3.5a2.5 2.5 0 0 1 3.5 3.5L9 16"/></svg>',
  send: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" '
    + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" '
    + 'stroke-linejoin="round"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4z"/></svg>',
  plus: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" '
    + 'stroke="currentColor" stroke-width="2" stroke-linecap="round">'
    + '<path d="M12 5v14M5 12h14"/></svg>',
  cog: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" '
    + 'stroke="currentColor" stroke-width="2" stroke-linecap="round" '
    + 'stroke-linejoin="round"><circle cx="12" cy="12" r="3"/>'
    + '<path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83'
    + 'l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0'
    + 'v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83'
    + 'l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09'
    + 'A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83'
    + 'l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09'
    + 'a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83'
    + 'l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4'
    + 'h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>',
};
const MARK_SVG = '<svg class="mark" viewBox="0 0 100 100" aria-hidden="true">'
  + '<path class="arc" d="M 58.32 10.88 A 40 40 0 0 1 88.04 62.36"/>'
  + '<path class="arc" d="M 79.72 76.76 A 40 40 0 0 1 20.28 76.76"/>'
  + '<path class="arc" d="M 11.96 62.36 A 40 40 0 0 1 41.68 10.88"/>'
  + '<circle class="dot" cx="50" cy="50" r="9"/></svg>';
const SCOPE_ICON = {
  inner: '<svg viewBox="0 0 20 20"><circle class="rf" cx="10" cy="10" r="3.4"/></svg>',
  kreds: '<svg viewBox="0 0 20 20"><circle class="rf" cx="10" cy="10" r="3.4"/>'
    + '<circle class="r" cx="10" cy="10" r="7"/></svg>',
};

// -- theme: apply/toggle/persist (unchanged behavior, renamed storage key).
function applyTheme(t) {
  document.documentElement.dataset.theme = t;
  const btn = document.getElementById("theme-toggle");
  if (btn) btn.innerHTML = (t === "dark") ? SUN : MOON;
}
function accentFg(hex) {
  const r = parseInt(hex.slice(1, 3), 16), g = parseInt(hex.slice(3, 5), 16),
        b = parseInt(hex.slice(5, 7), 16);
  return (r * 299 + g * 587 + b * 114) / 1000 > 150 ? "#17191e" : "#ffffff";
}
function setAccent(node, hex) {
  node.style.setProperty("--me", hex);
  node.style.setProperty("--me-fg", accentFg(hex));
}
function currentTheme() {
  return localStorage.getItem("kreds_theme")
    || (window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark" : "light");
}
function toggleTheme() {
  const next = (document.documentElement.dataset.theme === "dark")
    ? "light" : "dark";
  localStorage.setItem("kreds_theme", next);
  applyTheme(next);
}

function expiryLabel(expiresAt) {
  if (!expiresAt) return "";
  const s = expiresAt - Date.now() / 1000;
  if (s <= 0) return "expired";
  if (s < 3600) return "expires in " + Math.ceil(s / 60) + "m";
  if (s < 86400) return "expires in " + Math.ceil(s / 3600) + "h";
  return "expires in " + Math.ceil(s / 86400) + "d";
}

// ---------------------------------------------------------------------
// Journal (day-grouped feed)
// ---------------------------------------------------------------------

function dayLabel(date) {
  const startOfDay = d => new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const diffDays = Math.round(
    (startOfDay(new Date()) - startOfDay(date)) / 86400000);
  const weekday = date.toLocaleDateString(undefined, {weekday: "long"});
  const full = date.toLocaleDateString(undefined,
    {day: "numeric", month: "long"});
  if (diffDays === 0) return "Today · " + weekday + " " + full;
  if (diffDays === 1) return "Yesterday · " + weekday + " " + full;
  return weekday + " · " + full;
}

function groupByDay(rows) {
  // `rows` is already newest-first (as /api/feed returns it), so walking
  // it in order and starting a new bucket on each date change preserves
  // that ordering both across and within days.
  const groups = [];
  let cur = null;
  for (const p of rows) {
    const d = new Date(p.created_at * 1000);
    const key = d.toDateString();
    if (!cur || cur.key !== key) { cur = {key, date: d, rows: []}; groups.push(cur); }
    cur.rows.push(p);
  }
  return groups;
}

function buildEntry(p) {
  const color = (p.mine && STATE && STATE.accent)
    ? STATE.accent : identityColor(p.identity_pub);
  const article = el("article", "entry");
  article.dataset.author = p.identity_pub;
  article.dataset.created = p.created_at;

  const avatar = el("button", "eavatar");
  const letter = () => {
    avatar.replaceChildren();
    avatar.textContent = (p.author_name || "?").slice(0, 1).toUpperCase();
  };
  if (p.author_avatar) {
    const im = document.createElement("img");
    im.src = "/api/blob/" + p.author_avatar;
    im.alt = "";
    // blob may not have gossiped in yet (post row can arrive first):
    // fall back to the letter circle instead of an empty ring; the next
    // re-render after blob sync shows the photo (final review, 0.3.13)
    im.onerror = letter;
    avatar.append(im);
  } else {
    letter();
  }
  // ring + color stay unconditional: identity color remains visible over
  // photos, and IS the circle for the letter fallback (August 2026-07-15)
  avatar.style.background = color;
  avatar.style.setProperty("--ring", color);
  if (p.mine) {
    avatar.disabled = true;
    avatar.setAttribute("aria-label", "You");
  } else {
    avatar.setAttribute("aria-label", "Open " + p.author_name + "'s profile");
    avatar.onclick = () => openProfile(p.identity_pub);
  }

  const body = el("div", "ebody");
  const line = el("div", "eline");
  let nameNode;
  if (p.mine) {
    nameNode = el("span", "ename", p.author_name);
    nameNode.style.cursor = "default";
  } else {
    nameNode = el("button", "ename", p.author_name);
    nameNode.onclick = () => openProfile(p.identity_pub);
  }
  const time = el("span", "etime", new Date(p.created_at * 1000)
    .toLocaleTimeString([], {hour: "2-digit", minute: "2-digit"}));
  line.append(nameNode, time);
  const exp = expiryLabel(p.expires_at);
  if (exp) line.append(el("span", "etime", exp));
  const scope = el("span", "escope");
  // Guarded lookup: a gossip-supplied p.scope of e.g. "constructor" would
  // otherwise resolve via the prototype chain (Object.prototype.constructor
  // is truthy) instead of falling through to the kreds default.
  scope.innerHTML = Object.hasOwn(SCOPE_ICON, p.scope)
    ? SCOPE_ICON[p.scope] : SCOPE_ICON.kreds;
  scope.append(document.createTextNode(p.scope));
  line.append(scope);
  body.append(line, el("p", "etext", p.text));
  const eth = p.thumbs || [];
  // Journal photos (August 2026-07-18): the .epic class lets the FEED
  // cap their height (#view-journal-scoped - the profile rail's narrow
  // column already sizes right and keeps its look); the lightbox is the
  // full-size view, so the cap never hides pixels for good. blobImg's
  // pending placeholder drops the class+click - nothing to zoom yet.
  const eitems = p.blobs.map((h) => ({m: p.msg_id, h}));
  p.blobs.forEach((h, i) => {
    const im = blobImg(p.msg_id, h, eth[i] || null);
    im.classList.add("epic");
    im.style.cursor = "zoom-in";
    im.tabIndex = -1;   // scriptable focus target: lightbox close returns focus here
    im.onclick = () => openLightbox(eitems, i, im);
    body.append(im);
  });
  if (p.mine) {
    const acts = el("div", "eacts");
    const del = el("button", "pact del", "Delete everywhere");
    del.onclick = async () => {
      if (!await deleteEverywhere(p.msg_id)) return;
      await refresh();
      // refresh() re-renders journal/me/stories but not the profile page, and
      // a Wall post's only delete affordance is here - re-open the profile so
      // the deleted post actually disappears from the Wall/Journal.
      if (currentView() === "profile" && CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
    };
    acts.append(del);
    body.append(acts);
  }
  article.append(avatar, body);
  return article;
}

// Profile canvas block: a profile post rendered by inferred type - text, or
// photo (one big, several as a swipeable deck). Distinct from the compact
// journal buildEntry(). Delete-everywhere (self) routes through the shared
// helper. The Slice-3b grid layouts are retired - a synced record's grids
// map is deliberately ignored (spec 2026-07-13 decision 4).

// blobImg (spec 2026-07-18): every post image renders through this -
// prefers the tile-resolution thumb when the record carries one (old
// posts don't - full blob, exactly as before), and swaps itself for a
// neutral .img-pending placeholder instead of the browser's broken-image
// glyph while the blob hasn't gossiped in yet. The WS "changed" re-render
// (refresh) replaces the placeholder with a fresh <img> that retries.
function blobImg(msgId, hash, thumbHash) {
  const img = document.createElement("img");
  img.src = "/api/post-blob/" + msgId + "/" + (thumbHash || hash);
  img.alt = "";
  // IMPORTANT #4 (whole-branch review): a thumb can 404 (still gossiping
  // in) while the full blob already synced, or vice versa - one retry on
  // the OTHER hash before giving up, instead of placeholdering on the
  // thumb's failure alone.
  let retriedFull = false;
  img.onerror = () => {
    if (thumbHash && !retriedFull) {
      retriedFull = true;
      img.src = "/api/post-blob/" + msgId + "/" + hash;
      return;
    }
    const ph = el("div", "img-pending");
    ph.setAttribute("aria-hidden", "true");
    img.replaceWith(ph);
  };
  return img;
}

// Uniform photo list for a block: a plain post contributes its own
// msg_id per blob; an album pseudo-block (server-folded, Slice C) already
// carries cross-post {m, h} pairs. Either way the deck and lightbox see
// one shape. Thumbs ride along index-aligned (null for an old post/member
// with no thumb) - album rows gain .t server-side, in profile_view's
// album-fold loop (hearth/node.py).
function blockPhotoItems(p) {
  if (p.album) return p.photos;
  const th = p.thumbs || [];
  return (p.blobs || []).map((h, i) => ({m: p.msg_id, h, t: th[i] || null}));
}

// Stacked deck (Slice C, chrome per sketch 2026-07-18): the top photo
// fills the cells; stacked edges + bottom-center dots say "there's
// more"; invisible left/right tap zones or a swipe flip through, and a
// center tap (outside Arrange) opens the lightbox at the current photo.
// No photo is ever hidden - the 3rd, 5th, 12th are one swipe away at
// any size.
function renderDeck(p, items) {
  const deck = el("div", "block-deck");
  // IMPORTANT-3 (whole-branch review): resume wherever this deck was last
  // flipped to across a same-person re-render (heal/block-settings/Arrange
  // toggle - see DECK_POS at top), clamped in case the item count shrank
  // (e.g. an album lost a member).
  let i = Math.min(DECK_POS.get(p.msg_id) || 0, items.length - 1);
  const img = document.createElement("img");
  img.alt = ""; img.draggable = false; img.style.cursor = "zoom-in";
  img.tabIndex = -1;
  // Deck onerror/onload (spec 2026-07-18, delegated choice - deviates from
  // blobImg): this <img> is a single element reused across flips (show()
  // just swaps its src) - every prev/next/show closure in this function
  // holds a reference to it, so blobImg's replaceWith(placeholder) would
  // tear the element out from under them and break the flip. Minimal
  // variant instead: flag the element itself pending (same .img-pending
  // look) on error, and clear the flag on the next successful load (a
  // flip to a synced photo, or the WS-retry re-render building a fresh
  // deck/img anyway) - never a broken glyph, never a swapped-out element.
  // IMPORTANT #4: one retry on the full hash before pending (same reasoning
  // as blobImg) - `retriedFull` resets every show() so each photo shown
  // gets its own single retry.
  let retriedFull = false;
  img.onerror = () => {
    if (items[i].t && !retriedFull) {
      retriedFull = true;
      img.src = "/api/post-blob/" + items[i].m + "/" + items[i].h;
      return;
    }
    img.classList.add("img-pending");
  };
  img.onload = () => img.classList.remove("img-pending");
  // dots are position-only chrome - the zone buttons + lightbox carry
  // the accessible semantics, so the row is aria-hidden.
  const dots = el("div", "deck-dots");
  dots.setAttribute("aria-hidden", "true");
  items.forEach(() => dots.append(el("span", "deck-dot")));
  // Invisible tap zones, the arrow pills' successors: real <button>s so
  // the keyboard path survives (CSS keeps them transparent and draws a
  // :focus-visible ring) and so Arrange's whole-block pointerdown bails
  // out for them like any control - though Arrange also display:nones
  // them, keeping the full deck a drag surface there.
  const prev = el("button", "deck-tap deck-tap-prev");
  prev.type = "button";
  prev.setAttribute("aria-label", "Previous photo");
  const next = el("button", "deck-tap deck-tap-next");
  next.type = "button";
  next.setAttribute("aria-label", "Next photo");
  const show = () => {
    retriedFull = false;
    DECK_POS.set(p.msg_id, i);   // IMPORTANT-3: remember the flip across re-renders
    img.src = "/api/post-blob/" + items[i].m + "/" + (items[i].t || items[i].h);
    [...dots.children].forEach((d, k) =>
      d.classList.toggle("active", k === i));
  };
  // At either end a zone is a no-op (no wrap), matching the old disabled
  // arrow. `swiped` check: a mouse swipe's trailing click lands on
  // whatever sits under the pointer - over a zone it must not ALSO flip
  // (the same double-fire the lightbox tap below guards against).
  prev.onclick = (e) => {
    e.stopPropagation();
    if (swiped) { swiped = false; return; }
    if (i > 0) { i--; show(); }
  };
  next.onclick = (e) => {
    e.stopPropagation();
    if (swiped) { swiped = false; return; }
    if (i < items.length - 1) { i++; show(); }
  };
  // a mouse swipe's trailing click must not open the lightbox - pointer
  // events precede the click, so `swiped` is set in time to suppress it.
  img.onclick = () => {
    if (swiped) { swiped = false; return; }
    if (!ARRANGING) openLightbox(items, i, img);
  };
  // touch swipe, same 40px threshold as the lightbox; passive pointer
  // tracking only - Arrange mode's drag takes pointerdown before us via
  // the block handler, so gate on !ARRANGING.
  let sx = null;
  let swiped = false;
  deck.addEventListener("pointerdown", (e) => {
    swiped = false;                    // a stale flag must not eat a genuine later tap
    if (!ARRANGING) sx = e.clientX;
  });
  deck.addEventListener("pointerup", (e) => {
    if (sx == null) return;
    const dx = e.clientX - sx; sx = null;
    if (Math.abs(dx) > 40) {
      swiped = true;                   // even an end-of-deck swipe (no flip) is not a tap
      if (dx < 0 && i < items.length - 1) { i++; show(); }
      else if (dx > 0 && i > 0) { i--; show(); }
    }
  });
  deck.append(img, dots, prev, next);
  show();
  return deck;
}

// Add photos to an own photo block (Slice C): posts ONE new immutable
// photo post at the block's scope, then republishes the album record
// with it appended - growing a plain post-deck mints the album that
// wraps it (spec 2026-07-13 section 5). Post immutability untouched.
async function addPhotosToBlock(p, files) {
  if (!files || !files.length) return;
  const scope = p.album ? (p.scope_newest || "kreds") : (p.scope || "kreds");
  const fd = new FormData();
  fd.append("text", "");
  fd.append("scope", scope);
  fd.append("placement", "profile");
  // place=0: this post is album-bound - it becomes deck CONTENT, not a
  // wall block, so it must skip creation auto-place (spec 2026-07-14).
  // Without this, the new photo's own top-insert pushed the whole wall
  // (including the very album it was joining) down before /api/album
  // could fold it in - the deck lurched on every "+".
  fd.append("place", "0");
  for (const f of files) fd.append("photos", f);
  const r = await fetch("/api/post", {method: "POST", body: fd});
  if (!r.ok) { alert("Couldn't add: " + await r.text()); return; }
  const { msg_id } = await r.json();
  const members = p.album
    ? [...new Set(p.photos.map(ph => ph.m))].concat(msg_id)
    : [p.msg_id, msg_id];
  const ar = await fetch("/api/album", {method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(p.album
      ? {album_id: p.msg_id, members}
      : {members})});
  if (!ar.ok) alert("Added the photos, but couldn't grow the album: " + await ar.text());
  if (CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
}

// TEXT_COLORS: the ten ACCENTS hexes, copied verbatim from the server's
// source of truth (hearth/messages.py's ACCENTS) - a wall text block's
// color option is restricted to these plus "default"/"accent" (spec
// 2026-07-14, structured-options-never-user-CSS rule). Keep in sync by
// hand if messages.py's ACCENTS ever changes.
const TEXT_COLORS = ["#2743d6", "#c0563b", "#3e7c55", "#8a5cd0", "#17191e",
  "#1f8a8a", "#c79a2e", "#c0567e", "#4a5568", "#7a4e8a"];
// Cosmetic labels for AT only (positional, matching TEXT_COLORS order) -
// a screen reader announcing "Text color #17191e" names nothing a sighted
// user would recognize; these are just human-friendly names for the same
// swatches, not a new source of truth (messages.py's ACCENTS still is).
const TEXT_COLOR_NAMES = ["Blue", "Rust", "Green", "Purple", "Ink", "Teal",
  "Gold", "Rose", "Slate", "Plum"];

// Applies a wall text block's style (spec 2026-07-14) to `wrap` (the
// .block-text-wrap flex container around .block-text-body): h/v alignment
// via justify-content/align-items (+ text-align) on the flex container;
// size (when not "auto" - auto keeps the caller's text-w* span-scaled
// path) via a text-size-* class; font/weight/style via classes; color via
// the --text-color CSS var ("accent" resolves to authorAccent, the AUTHOR's
// own accent, like the profile banner; a raw hex is set as-is; "default"
// sets nothing, so .block-text-wrap's var(--text-color, inherit) falls
// through to theme ink - the dark-mode-honest monochrome default).
function applyTextStyle(wrap, ts, authorAccent) {
  const JUSTIFY = {left: "flex-start", center: "center", right: "flex-end"};
  const ALIGN = {top: "flex-start", middle: "center", bottom: "flex-end"};
  wrap.style.setProperty("justify-content", JUSTIFY[ts.h] || "flex-start");
  wrap.style.setProperty("align-items", ALIGN[ts.v] || "flex-start");
  wrap.style.textAlign = ts.h || "left";
  if (ts.size && ts.size !== "auto") wrap.classList.add("text-size-" + ts.size);
  if (ts.font === "disp") wrap.classList.add("text-font-disp");
  if (ts.weight === "bold") wrap.classList.add("text-bold");
  if (ts.style === "italic") wrap.classList.add("text-italic");
  if (ts.color === "accent") wrap.style.setProperty("--text-color", authorAccent || "");
  else if (ts.color && ts.color !== "default") wrap.style.setProperty("--text-color", ts.color);
}

function renderBlock(p) {
  const block = el("article", "block");
  block.dataset.msgId = p.msg_id;   // read back by Arrange mode's Done handler
  // Collage geometry: a pinned block sits at explicit cells; an unplaced
  // one spans its chosen size and flows. Pins/spans are plaintext geometry
  // over opaque ids (existence-disclosure class, same as the old order
  // list - see the spec's metadata honesty note).
  block.classList.add("block-cells");
  const geom = p.pin || null;
  const span = p.span || {w: 4, h: 1};
  if (geom) {
    block.style.gridColumn = (geom.x + 1) + " / span " + geom.w;
    block.style.gridRow = (geom.y + 1) + " / span " + geom.h;
  } else {
    block.style.gridColumn = "span " + span.w;
    block.style.gridRow = "span " + span.h;
  }
  // Auto size keeps the existing span-scaled text-w* look; an explicit
  // text_style.size (s/m/l/xl) opts out of it - applyTextStyle below adds
  // its own text-size-* class instead (spec 2026-07-14).
  if (p.text && !(p.blobs && p.blobs.length)
      && (!p.text_style || p.text_style.size === "auto"))
    block.classList.add("text-w" + span.w);
  if (p.media === "video" && p.blobs && p.blobs.length) {
    const wrap = el("div", "block-video");
    const v = document.createElement("video");
    v.controls = true; v.playsInline = true; v.preload = "metadata";
    // Thumb-first poster (spec 2026-07-18): a video's thumb is a
    // tile-resolution frame grab, cheaper than the full poster blob while
    // it's still gossiping in. Old posts (no thumbs) fall back exactly as
    // before.
    if (p.thumbs && p.thumbs[0]) v.poster = "/api/post-blob/" + p.msg_id + "/" + p.thumbs[0];
    else if (p.poster) v.poster = "/api/post-blob/" + p.msg_id + "/" + p.poster;
    const src = document.createElement("source");
    src.src = "/api/post-blob/" + p.msg_id + "/" + p.blobs[0];
    v.append(src); wrap.append(v); block.append(wrap);
  } else if (p.album || (p.blobs && p.blobs.length)) {
    const items = blockPhotoItems(p);
    if (items.length === 1) {
      const media = el("div", "block-photo");
      const img = blobImg(items[0].m, items[0].h, items[0].t);
      // whole-branch review IMPORTANT #3: images are draggable by default,
      // so a real-mouse block drag in Arrange mode would start a native
      // HTML5 image drag instead of our own pointer-drag reorder.
      img.draggable = false;
      img.style.cursor = "zoom-in";
      img.tabIndex = -1;   // scriptable focus target so the lightbox can return focus here on close
      img.onclick = () => { if (!ARRANGING) openLightbox(items, 0, img); };
      media.append(img);
      block.append(media);
    } else if (items.length) {
      block.append(renderDeck(p, items));   // 2+ photos: a swipeable .block-deck
      // has-deck: deterministic opt-out of .block's cell-crop overflow:
      // hidden (see .block.has-deck in style.css) - the deck's peek-out
      // stacked edges live a few px outside the card and would otherwise
      // be clipped away entirely. No :has() dependency.
      block.classList.add("has-deck");
    }
  }
  if (p.text) {
    // text_style is only annotated by the server for genuine text blocks
    // (never blobs/video, spec 2026-07-14) - a caption on a photo/video
    // post still renders plainly, no wrap-level style applied.
    const wrap = el("div", "block-text-wrap");
    wrap.append(el("p", "block-text-body", p.text));
    block.append(wrap);
    if (p.text_style) applyTextStyle(wrap, p.text_style, CURRENT_PROFILE_ACCENT);
  }
  if (p.mine) {
    // Album pseudo-blocks fold members at possibly-different scopes, so a
    // single Inner/Kreds badge would misrepresent the mix - skip it.
    if (!p.album) {
      const badge = el("span", "block-scope", p.scope === "inner" ? "Inner" : "Kreds");
      block.append(badge);
    }
  }
  if (ARRANGING && p.mine) {
    // Editing chrome lives HERE, not on the resting wall (sketch
    // 2026-07-18, superseding 2026-07-15's always-present gear): outside
    // Arrange an own block renders exactly as a friend sees it (plus the
    // scope tag). The delete path survives the re-gating - Arrange's
    // tap-to-open below and this gear both reach the settings modal.
    const cog = el("button", "block-settings-btn");
    cog.innerHTML = ICONS.cog;
    cog.type = "button";
    cog.setAttribute("aria-label", "Block settings");
    cog.onclick = () => openBlockSettings(p, block, cog);
    block.append(cog);
    // blockPhotoItems() doesn't itself discriminate media type - a video
    // post's own blob would otherwise satisfy .length > 0 too, so exclude
    // it explicitly (video posts are never album-eligible either way).
    if (p.media !== "video" && blockPhotoItems(p).length > 0) {
      const add = el("label", "block-add");
      // Silent-scope fix (review finding): Add-photos inherits the block's
      // (or album's newest member's) scope automatically - surface which
      // scope via tooltip/aria rather than a per-post picker (spec
      // Amendments 2026-07-14: picker is a named follow-up).
      const addScope = p.album ? (p.scope_newest || "kreds") : (p.scope || "kreds");
      add.title = "Add photos (posts at " + addScope + " scope)";
      add.setAttribute("aria-label", "Add photos (posts at " + addScope + " scope)");
      add.textContent = "+";
      const addInput = document.createElement("input");
      addInput.type = "file"; addInput.accept = "image/*"; addInput.multiple = true;
      addInput.className = "visually-hidden";
      addInput.onchange = () => addPhotosToBlock(p, [...addInput.files]);
      add.append(addInput);
      block.append(add);
    }
    block.classList.add("arranging");
    block.style.touchAction = "none";
    // #5(a): tabindex="-1" makes the block a valid programmatic focus
    // target (closeBlockSettings' opener.focus()) without adding it to
    // the normal Tab sequence - a tap-opened modal remembers the block
    // itself as its opener, since there's no more specific control to
    // credit for a whole-block tap.
    block.tabIndex = -1;
    if (p.pin) {
      const rz = el("div", "block-resize");
      rz.setAttribute("aria-hidden", "true");   // modal presets are the a11y path
      rz.addEventListener("pointerdown", (e) => {
        if ((e.button != null && e.button !== 0) || !e.isPrimary) return;
        e.preventDefault(); e.stopPropagation();   // never starts a move-drag
        startBlockResize(block, e, p);
      });
      block.append(rz);
    }
    // A small drag reorders; a tap (sub-threshold release) opens settings.
    block.addEventListener("pointerdown", (ev) => {
      if ((ev.button != null && ev.button !== 0) || !ev.isPrimary) return;
      // label included so .block-add's file-input trigger stays clickable
      // (it isn't a button/a/select/video, so it would otherwise start a tap/drag)
      if (ev.target.closest("button, a, select, video, label")) return;   // let controls work
      // IMPORTANT #3(c): preventDefault SYNCHRONOUSLY here, not inside the
      // eventual startBlockDrag handoff - by the time that call happens,
      // the browser's default action for this same event (native image
      // drag start, text-selection drag) has already committed, so calling
      // it there is a no-op.
      ev.preventDefault();
      const sx = ev.clientX, sy = ev.clientY;
      let handed = false;
      // MINOR #6: capture the pointer for the tap phase, not just the
      // eventual drag phase - without this, a sub-threshold press that
      // releases outside the window never delivers pointerup, and the
      // stale `up` closure below can later fire on some unrelated click.
      // Not fatal if unsupported/inactive - fall through uncaptured.
      try { block.setPointerCapture(ev.pointerId); } catch (e) { /* not fatal */ }
      const move = (e) => {
        if (e.pointerId !== ev.pointerId) return;   // ignore a concurrent second touch
        if (!handed && Math.hypot(e.clientX - sx, e.clientY - sy) > 6) {
          handed = true; teardown(); startBlockDrag(block, ev, p);    // hand off to the drag controller
        }
      };
      const up = (e) => {
        if (e.pointerId !== ev.pointerId) return;   // ignore a concurrent second touch
        teardown(); if (!handed) openBlockSettings(p, block, block);   // block itself is the opener (#5a)
      };
      // IMPORTANT #2: pointercancel is an ABORTED gesture, not a tap - it
      // must never open the modal. The old code reused `up` for both
      // events, so a cancelled gesture (e.g. the browser taking over for
      // a system gesture) popped the settings modal anyway. `cancel` only
      // tears down.
      const cancel = (e) => {
        if (e.pointerId !== ev.pointerId) return;   // ignore a concurrent second touch
        teardown();
      };
      const teardown = () => {
        // Release capture BEFORE startBlockDrag (called right after, on
        // the `move` handoff path) takes its own capture on the wall -
        // two elements holding capture on the same pointerId at once is
        // unspecified behavior we don't want to rely on (#6).
        try { block.releasePointerCapture(ev.pointerId); } catch (e) { /* already released/inactive */ }
        window.removeEventListener("pointermove", move);
        window.removeEventListener("pointerup", up);
        window.removeEventListener("pointercancel", cancel);
      };
      window.addEventListener("pointermove", move);
      window.addEventListener("pointerup", up);
      window.addEventListener("pointercancel", cancel);
    });
  }
  return block;
}

// ---- collage geometry (Arrange mode) --------------------------------
const WALL_GAP = 12;
function wallMetrics() {
  const wall = document.getElementById("profile-wall");
  const r = wall.getBoundingClientRect();
  const cell = (r.width - 3 * WALL_GAP) / 4;
  return {wall, r, cell};
}
// Top-left cell for a w-wide block whose pointer is at (px, py); y is
// unbounded downward (the canvas grows), x clamps into the 4 columns.
function cellFromPoint(px, py, w) {
  const {r, cell} = wallMetrics();
  const step = cell + WALL_GAP;
  const x = Math.max(0, Math.min(4 - w, Math.round((px - r.left) / step)));
  const y = Math.max(0, Math.round((py - r.top) / step));
  return {x, y};
}
function ghostAt(geom, ok) {
  let g = document.getElementById("pin-ghost");
  if (!g) {
    g = el("div", "pin-ghost"); g.id = "pin-ghost";
    document.getElementById("profile-wall").append(g);
  }
  g.style.gridColumn = (geom.x + 1) + " / span " + geom.w;
  g.style.gridRow = (geom.y + 1) + " / span " + geom.h;
  g.classList.toggle("invalid", !ok);
  return g;
}
function clearGhost() {
  const g = document.getElementById("pin-ghost");
  if (g) g.remove();
}

// Hand-rolled pointer drag (mouse + touch + pen), collage edition: the
// gesture no longer reorders siblings - it carries the block's w x h
// footprint to a cell target. Dynamic placement (spec 2026-07-14): the
// ghost is invalid ONLY out-of-bounds - cellFromPoint always clamps its
// result into bounds, so once the pointer is over the canvas the target
// is always valid; the client's overlap veto is gone (the server pushes
// whatever's in the way on drop, see set_block_pin). A drop off-canvas is
// a snap-back no-op (the tray/unpin drop zone died with the tray itself -
// /api/block-unpin has no UI caller left). Capture stays on the WALL (a
// reparented captured element loses capture in Chromium - found live in
// Slice 3a); finish() is idempotent and also wired to lostpointercapture
// (the autoscroll dropped-event fix from Slice 3a still applies).
function startBlockDrag(block, ev, p) {
  if ((ev.button != null && ev.button !== 0) || !ev.isPrimary) return;
  ev.preventDefault();
  const wall = document.getElementById("profile-wall");
  const w = p.span.w, h = p.span.h;
  try { wall.setPointerCapture(ev.pointerId); } catch (e) { return; }
  block.classList.add("dragging");
  let target = null;

  const onMove = (e) => {
    if (e.pointerId !== ev.pointerId) return;
    const {r} = wallMetrics();
    const over = e.clientX >= r.left && e.clientX <= r.right
              && e.clientY >= r.top - 40;   // small grace above row 0
    if (!over) { clearGhost(); target = null; }   // no #pin-ghost while off-canvas (out of bounds)
    else {
      const c = cellFromPoint(e.clientX, e.clientY, w);
      target = {x: c.x, y: c.y, w, h};
      ghostAt(target, true);   // paints/updates #pin-ghost at the target cell - always valid in-bounds
    }
    const M = 70;
    if (e.clientY < M) window.scrollBy(0, -12);
    else if (e.clientY > window.innerHeight - M) window.scrollBy(0, 12);
  };
  let done = false;
  const finish = async (commit) => {
    if (done) return;
    done = true;
    wall.removeEventListener("pointermove", onMove);
    wall.removeEventListener("pointerup", onUp);
    wall.removeEventListener("pointercancel", onCancel);
    wall.removeEventListener("lostpointercapture", onLost);
    try { wall.releasePointerCapture(ev.pointerId); } catch (e) { /* released */ }
    block.classList.remove("dragging");
    clearGhost();   // #pin-ghost never survives past the gesture
    if (commit && target)
      await postJSON("/api/block-pin", {msg_id: p.msg_id, ...target});
    // else: cancelled, or dropped out of bounds - snap-back no-op, nothing to post
    if (CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
  };
  const onUp = (e) => { if (e.pointerId === ev.pointerId) finish(true); };
  const onCancel = (e) => { if (e.pointerId === ev.pointerId) finish(false); };
  const onLost = (e) => {
    // Dropped-terminator safety net - but only the WALL losing its own
    // capture ends the gesture: the tap-phase handoff releases the BLOCK's
    // capture, whose lostpointercapture bubbles up here and must not kill
    // the drag before it starts (found live by the Task-8 smoke, 5/5
    // deterministic - it unpinned every dragged block on the first move).
    if (e.target === wall && e.pointerId === ev.pointerId) finish(true);
  };
  wall.addEventListener("pointermove", onMove);
  wall.addEventListener("pointerup", onUp);
  wall.addEventListener("pointercancel", onCancel);
  wall.addEventListener("lostpointercapture", onLost);
}

// Corner resize in cell steps: width/height derive from pointer distance
// past the block's top-left cell; clamped to the canvas and validated
// against neighbors like a move. Same capture/finish rules as the drag.
function startBlockResize(block, ev, p) {
  const wall = document.getElementById("profile-wall");
  try { wall.setPointerCapture(ev.pointerId); } catch (e) { return; }
  block.classList.add("dragging");
  const pin = p.pin;
  let target = null;
  const onMove = (e) => {
    if (e.pointerId !== ev.pointerId) return;
    const {r, cell} = wallMetrics();
    const step = cell + WALL_GAP;
    const w = Math.max(1, Math.min(4 - pin.x,
      Math.round((e.clientX - (r.left + pin.x * step)) / step)));
    const h = Math.max(1, Math.min(8,
      Math.round((e.clientY - (r.top + pin.y * step)) / step)));
    target = {x: pin.x, y: pin.y, w, h};
    // Dynamic placement (spec 2026-07-14): w/h are clamped into bounds
    // above, so the ghost is always valid - the server pushes on collision.
    ghostAt(target, true);
  };
  let done = false;
  const finish = async (commit) => {
    if (done) return;
    done = true;
    wall.removeEventListener("pointermove", onMove);
    wall.removeEventListener("pointerup", onUp);
    wall.removeEventListener("pointercancel", onCancel);
    wall.removeEventListener("lostpointercapture", onLost);
    try { wall.releasePointerCapture(ev.pointerId); } catch (e) { /* released */ }
    block.classList.remove("dragging");
    clearGhost();
    if (commit && target)
      await postJSON("/api/block-pin", {msg_id: p.msg_id, ...target});
    if (CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
  };
  const onUp = (e) => { if (e.pointerId === ev.pointerId) finish(true); };
  const onCancel = (e) => { if (e.pointerId === ev.pointerId) finish(false); };
  const onLost = (e) => {
    // Same scoping as startBlockDrag's onLost: only the wall's OWN capture
    // loss ends the gesture (a descendant's bubbled lostpointercapture
    // must not). Resize isn't reachable by the tap-handoff bug today, but
    // it shares the same latent exposure class.
    if (e.target === wall && e.pointerId === ev.pointerId) finish(true);
  };
  wall.addEventListener("pointermove", onMove);
  wall.addEventListener("pointerup", onUp);
  wall.addEventListener("pointercancel", onCancel);
  wall.addEventListener("lostpointercapture", onLost);
}

// Per-block settings modal (self+Arrange-only): opened by a TAP on a block
// (see the pointerdown handler in renderBlock) - replaces the old inline
// drag handle button, Up/Down arrows, and grid-<select> on the block face
// itself. The Phase-A Size picker, Slice-3b Photo-layout picker, and the
// reorder-era Move Up/Down pair are all retired (spec 2026-07-13, collage
// redesign) - Task 7 rebuilt the controls around pin/span geometry: Size
// presets and one-cell Nudge for a pinned block. The pinned/unpinned pair
// of actions that used to live below Nudge (send-to-holding-area / place-
// on-canvas) died with the holding area itself (spec 2026-07-14, dynamic
// placement) - a block is always on the canvas now; an unplaced one can
// still be sized here (/api/block-span) and gets placed by a direct drag
// or by /api/wall-autoplace's migration.
function closeBlockSettings() {
  document.getElementById("block-settings").classList.add("hidden");
  // IMPORTANT #5(e): return focus to whatever opened the modal (the gear
  // button, or the block itself for a tap-open) - without this, Esc/
  // backdrop/close-button all drop focus to <body>, losing the user's
  // place in Arrange mode.
  const opener = BLOCK_SETTINGS_OPENER;
  BLOCK_SETTINGS_OPENER = null;
  if (opener && opener.isConnected) opener.focus();
}
async function postJSON(url, body) {
  const r = await fetch(url, {method: "POST",
    headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});
  if (!r.ok) alert("Couldn't save: " + await r.text());
  return r.ok;
}
// `opener` (#5a, only passed on a fresh open - gear click or block tap)
// is remembered so closeBlockSettings can return focus to it. `focusSel`
// (only passed by this function's own post-pick refresh calls below) is a
// selector for the option to refocus after the body is rebuilt (#5d); a
// fresh open (no focusSel) focuses the close button (#5b) instead.
function openBlockSettings(p, block, opener, focusSel) {
  if (opener) BLOCK_SETTINGS_OPENER = opener;
  const body = document.getElementById("block-settings-body");
  body.textContent = "";

  // Refresh-after-action: every control below POSTs immediately (the
  // pin/span/unpin endpoints are the source of truth), then this re-fetches
  // the profile, re-renders the page (the same fetch-with-fallback +
  // renderProfilePage the rest of the app uses to reflect a POST - see
  // openProfile), relocates THIS block by its data-msg-id in the freshly
  // rendered DOM, and reopens the modal on it with focusSel (falling back
  // to the close button when focusSel doesn't resolve, same as any fresh
  // open - #5d below). If the block is no longer on the canvas at all
  // (deleted meanwhile), close the modal instead of reopening on nothing.
  // The re-render detaches EVERY node, including whatever opened the modal -
  // BLOCK_SETTINGS_OPENER would go stale, and #5(e)'s isConnected guard
  // would then silently drop focus to <body> on close (the exact failure it
  // exists to prevent). So the freshly-found block is handed in as the
  // opener, keeping focus-return on a live node; the close-fallback path
  // leaves the opener alone (nothing sensible left to focus).
  const reopenAfterAction = async (focusSel) => {
    let np;
    try {
      np = await j("/api/profile/" + CURRENT_PROFILE);
    } catch (e) {
      np = fallbackProfile(CURRENT_PROFILE);
    }
    renderProfilePage(np);
    const post = np.wall && np.wall.find(b => b.msg_id === p.msg_id);
    const nextBlock = document.querySelector('[data-msg-id="' + p.msg_id + '"]');
    if (!post || !nextBlock) { closeBlockSettings(); return; }
    openBlockSettings(post, nextBlock, nextBlock, focusSel);
  };

  // Size presets - the keyboard path for what corner-drag does by pointer.
  const sizes = el("div", "settings-group");
  sizes.append(el("div", "settings-label", "Size"));
  const srow = el("div", "settings-row");
  for (const [w, h] of [[1, 1], [2, 1], [2, 2], [4, 2], [4, 3]]) {
    const btn = el("button", "settings-opt", w + "x" + h);
    btn.type = "button";
    btn.dataset.sel = "size-" + w + "x" + h;
    if (p.pin && p.pin.w === w && p.pin.h === h) btn.classList.add("active");
    if (!p.pin && p.span && p.span.w === w && p.span.h === h) btn.classList.add("active");
    btn.onclick = async () => {
      if (p.pin) {
        // x-clamp keeps a resized block on the canvas; the overlap veto is
        // gone (spec 2026-07-14) - the server pushes whatever's in the way.
        const x = Math.min(p.pin.x, 4 - w);
        const g = {x, y: p.pin.y, w, h};
        await postJSON("/api/block-pin", {msg_id: p.msg_id, ...g});
      } else {
        await postJSON("/api/block-span", {msg_id: p.msg_id, w, h});
      }
      await reopenAfterAction('[data-sel="size-' + w + 'x' + h + '"]');
    };
    srow.append(btn);
  }
  sizes.append(srow); body.append(sizes);

  // Text group (spec 2026-07-14): text blocks only - p.text_style presence
  // is the gate (the server only annotates genuine text blocks, never
  // photo/video/deck/album ones - see profile_view). Immediate-apply like
  // every other control here: every button POSTs the COMPLETE current
  // selection (server drops defaults) then reopenAfterAction with the
  // control's own data-sel, same idiom as Size above.
  if (p.text_style) {
    const ts = p.text_style;
    const postStyle = async (patch, sel) => {
      await postJSON("/api/block-text", {
        msg_id: p.msg_id, h: ts.h, v: ts.v, size: ts.size, font: ts.font,
        weight: ts.weight, style: ts.style, color: ts.color, ...patch});
      await reopenAfterAction('[data-sel="' + sel + '"]');
    };
    const text = el("div", "settings-group");
    text.append(el("div", "settings-label", "Text"));

    const hrow = el("div", "settings-row");
    for (const [label, val] of [["Left", "left"], ["Center", "center"], ["Right", "right"]]) {
      const btn = el("button", "settings-opt", label);
      btn.type = "button";
      btn.dataset.sel = "text-h-" + val;
      if (ts.h === val) btn.classList.add("active");
      btn.onclick = () => postStyle({h: val}, "text-h-" + val);
      hrow.append(btn);
    }
    text.append(el("div", "settings-label", "Align"), hrow);

    const vrow = el("div", "settings-row");
    vrow.setAttribute("aria-label", "Vertical align");   // mirrors hrow's visible "Align" label - vrow has no preceding label div of its own
    for (const [label, val] of [["Top", "top"], ["Middle", "middle"], ["Bottom", "bottom"]]) {
      const btn = el("button", "settings-opt", label);
      btn.type = "button";
      btn.dataset.sel = "text-v-" + val;
      if (ts.v === val) btn.classList.add("active");
      btn.onclick = () => postStyle({v: val}, "text-v-" + val);
      vrow.append(btn);
    }
    text.append(vrow);

    const szrow = el("div", "settings-row");
    for (const [label, val] of [["Auto", "auto"], ["S", "s"], ["M", "m"], ["L", "l"], ["XL", "xl"]]) {
      const btn = el("button", "settings-opt", label);
      btn.type = "button";
      btn.dataset.sel = "text-size-" + val;
      if (ts.size === val) btn.classList.add("active");
      btn.onclick = () => postStyle({size: val}, "text-size-" + val);
      szrow.append(btn);
    }
    text.append(el("div", "settings-label", "Text size"), szrow);

    const frow = el("div", "settings-row");
    for (const [label, val] of [["Sans", "sans"], ["Display", "disp"]]) {
      const btn = el("button", "settings-opt", label);
      btn.type = "button";
      btn.dataset.sel = "text-font-" + val;
      if (ts.font === val) btn.classList.add("active");
      btn.onclick = () => postStyle({font: val}, "text-font-" + val);
      frow.append(btn);
    }
    const bold = el("button", "settings-opt", "B");
    bold.type = "button";
    bold.dataset.sel = "text-weight";
    bold.setAttribute("aria-label", "Bold");
    if (ts.weight === "bold") bold.classList.add("active");
    bold.onclick = () => postStyle({weight: ts.weight === "bold" ? "normal" : "bold"}, "text-weight");
    const italic = el("button", "settings-opt", "I");
    italic.type = "button";
    italic.dataset.sel = "text-style";
    italic.setAttribute("aria-label", "Italic");
    if (ts.style === "italic") italic.classList.add("active");
    italic.onclick = () => postStyle({style: ts.style === "italic" ? "normal" : "italic"}, "text-style");
    frow.append(bold, italic);
    text.append(frow);

    const crow = el("div", "settings-row");
    const colorBtn = (label, val, sel) => {
      const btn = el("button", "settings-opt", label);
      btn.type = "button";
      btn.dataset.sel = sel;
      if (ts.color === val) btn.classList.add("active");
      btn.onclick = () => postStyle({color: val}, sel);
      return btn;
    };
    crow.append(colorBtn("Default", "default", "text-color-default"));
    crow.append(colorBtn("Accent", "accent", "text-color-accent"));
    TEXT_COLORS.forEach((hex, i) => {
      const sw = el("button", "sw", "");
      sw.type = "button";
      sw.dataset.sel = "text-color-" + i;
      sw.setAttribute("aria-label", "Text color " + TEXT_COLOR_NAMES[i]);
      sw.style.background = hex;
      if (ts.color === hex) sw.classList.add("on");
      sw.onclick = () => postStyle({color: hex}, "text-color-" + i);
      crow.append(sw);
    });
    text.append(el("div", "settings-label", "Color"), crow);

    body.append(text);
  }

  if (p.pin) {
    // Nudge - one cell per press, disabled only at a canvas edge (spec
    // 2026-07-14: the overlap veto is gone, the server pushes on collision -
    // there's no bottom edge since the canvas grows downward).
    const move = el("div", "settings-group");
    move.append(el("div", "settings-label", "Move"));
    const mrow = el("div", "settings-row");
    for (const [label, dx, dy] of [["Left", -1, 0], ["Right", 1, 0],
                                   ["Up", 0, -1], ["Down", 0, 1]]) {
      const btn = el("button", "settings-opt", label);
      btn.type = "button";
      btn.dataset.sel = "nudge-" + label;
      const g = {x: p.pin.x + dx, y: p.pin.y + dy, w: p.pin.w, h: p.pin.h};
      btn.disabled = g.x < 0 || g.x + g.w > 4 || g.y < 0;
      btn.onclick = async () => {
        await postJSON("/api/block-pin", {msg_id: p.msg_id, ...g});
        await reopenAfterAction('[data-sel="nudge-' + label + '"]');
      };
      mrow.append(btn);
    }
    move.append(mrow); body.append(move);
  }

  if (p.album) {
    const ungroup = el("button", "settings-opt", "Ungroup");
    ungroup.type = "button";
    ungroup.onclick = async () => {
      // Members reappear standalone, top-inserted onto the canvas (empty members = ungroup).
      await postJSON("/api/album", {album_id: p.msg_id, members: []});
      await reopenAfterAction(null);
    };
    body.append(ungroup);
  }

  // Delete (spec 2026-07-15): re-homed from the block face. Albums keep
  // the ungroup-first rule - no one-tap delete on a folded deck.
  if (!p.album) {
    const grp = el("div", "settings-group");
    grp.append(el("div", "settings-label", "Delete"));
    const del = el("button", "settings-opt settings-del", "Delete everywhere");
    del.type = "button";
    del.onclick = async () => {
      if (!await deleteEverywhere(p.msg_id)) return;
      // The opener (gear) is doomed with its post - drop it BEFORE the
      // close so focus-restore can't re-arm a stale settings modal during
      // the refresh round-trip (same end state as reopenAfterAction's
      // deleted-meanwhile path: nothing sensible left to focus).
      BLOCK_SETTINGS_OPENER = null;
      closeBlockSettings();
      await refresh();
      if (currentView() === "profile" && CURRENT_PROFILE) openProfile(CURRENT_PROFILE);
    };
    grp.append(del);
    body.append(grp);
  }

  document.getElementById("block-settings").classList.remove("hidden");
  // IMPORTANT #5(b)/(d): move focus into the dialog. A fresh open (no
  // focusSel) goes to the close button; a post-pick rebuild (focusSel set)
  // goes back to the option just picked, falling back to the close button
  // if that selector doesn't resolve for some reason.
  const closeBtn = document.getElementById("block-settings-close");
  const target = (focusSel && body.querySelector(focusSel)) || closeBtn;
  target.focus();
}
document.getElementById("block-settings-close").onclick = closeBlockSettings;
document.getElementById("block-settings").addEventListener("click", (ev) => {
  if (ev.target.id === "block-settings") closeBlockSettings();   // backdrop
});
document.addEventListener("keydown", (ev) => {
  if (ev.key === "Escape") { closeBlockSettings(); closeFriendAdd(); }
});
// IMPORTANT #5(c): trap Tab within the card while the modal is open. Scoped
// naturally - this only fires when the Tab keydown bubbles up from a
// focused descendant of #block-settings, which can't happen while it's
// hidden (nothing inside it can hold focus).
document.getElementById("block-settings").addEventListener("keydown", (ev) => {
  if (ev.key !== "Tab") return;
  const card = document.querySelector("#block-settings .block-settings-card");
  if (!card) return;
  const focusables = [...card.querySelectorAll(
    'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])'
  )];
  if (!focusables.length) return;
  const first = focusables[0], last = focusables[focusables.length - 1];
  if (ev.shiftKey && document.activeElement === first) {
    ev.preventDefault(); last.focus();
  } else if (!ev.shiftKey && document.activeElement === last) {
    ev.preventDefault(); first.focus();
  }
});

function endState() {
  const wrap = el("div", "theend");
  wrap.innerHTML = MARK_SVG;
  wrap.append(
    el("div", "endtitle", "That's everything."),
    el("div", "endsub",
      "Your kreds is quiet. No algorithm, nothing more to scroll."));
  return wrap;
}

function filteredFeed() {
  if (ACTIVE_FILTER === "all") return FEED;
  if (ACTIVE_FILTER === "inner")
    return FEED.filter(p => p.scope === "inner");
  return FEED.filter(p => p.identity_pub === ACTIVE_FILTER);
}

// -- seen-state observer: a journal entry that has genuinely been on
// screen (>= SEEN_RATIO visible for SEEN_DWELL_MS) marks its author's
// watermark at that post's created_at. The dwell means a fast fling past
// a post does not count as reading it. localStorage-only - same honesty
// boundary as lastOpened above; no seen-data ever leaves this device.
const SEEN_RATIO = 0.6;
const SEEN_DWELL_MS = 700;
const SEEN_TIMERS = new Map();   // entry element -> pending dwell timer
function clearSeenTimers() {
  for (const t of SEEN_TIMERS.values()) clearTimeout(t);
  SEEN_TIMERS.clear();
}
const journalSeenObserver = new IntersectionObserver((entries) => {
  for (const en of entries) {
    const node = en.target;
    if (en.isIntersecting && en.intersectionRatio >= SEEN_RATIO) {
      if (!SEEN_TIMERS.has(node)) SEEN_TIMERS.set(node, setTimeout(() => {
        SEEN_TIMERS.delete(node);
        journalSeenObserver.unobserve(node);
        bumpOpenedTo(node.dataset.author, Number(node.dataset.created));
      }, SEEN_DWELL_MS));
    } else {
      clearTimeout(SEEN_TIMERS.get(node));
      SEEN_TIMERS.delete(node);
    }
  }
}, {threshold: SEEN_RATIO});

function renderJournal() {
  const root = document.getElementById("journal");
  // Entries are about to be rebuilt: drop observations AND pending dwell
  // timers (a timer surviving a re-render would mark a post the user may
  // have already scrolled away from).
  journalSeenObserver.disconnect();
  clearSeenTimers();
  root.replaceChildren();
  for (const day of groupByDay(filteredFeed())) {
    root.append(el("div", "dayhead", dayLabel(day.date)));
    for (const p of day.rows) {
      const entry = buildEntry(p);
      root.append(entry);
      if (!p.mine) journalSeenObserver.observe(entry);
    }
  }
  root.append(endState());
}

// ---------------------------------------------------------------------
// Chip bar (Everyone / Inner kreds / per-person, from /api/kreds)
// ---------------------------------------------------------------------

function renderChipbar() {
  const bar = document.getElementById("chipbar");
  bar.replaceChildren();
  const mkPlain = (label, filter) => {
    const b = el("button",
      "fchip plain" + (ACTIVE_FILTER === filter ? " active" : ""), label);
    b.dataset.filter = filter;
    return b;
  };
  bar.append(mkPlain("Everyone", "all"), mkPlain("Inner kreds", "inner"));
  for (const k of KREDS) {
    const chip = el("button",
      "fchip" + (ACTIVE_FILTER === k.identity_pub ? " active" : ""));
    chip.dataset.filter = k.identity_pub;
    const cav = el("span", "cav", (k.name || "?").slice(0, 1).toUpperCase());
    cav.style.background = identityColor(k.identity_pub);
    chip.append(cav, document.createTextNode(k.name));
    if (isFresh(k.identity_pub)) chip.append(el("span", "fdot"));
    bar.append(chip);
  }
  bar.querySelectorAll(".fchip").forEach(c => c.onclick = () => {
    ACTIVE_FILTER = c.dataset.filter;
    if (ACTIVE_FILTER !== "all" && ACTIVE_FILTER !== "inner")
      markOpenedNow(ACTIVE_FILTER);
    renderChipbar();
    renderJournal();
    renderCircleRail();   // clear the fresh dot for the person just opened
  });
}

// ---------------------------------------------------------------------
// Circle: radial map built from /api/kreds. Ported markup/CSS: docs/
// design/kreds_design_v3.html's .rail/.minimap/.ringguide/.mm-node
// (compact rail) and .overlay/.node/.younode/.ringlabel (full map).
//
// buildCircle() is the one place the SVG geometry is computed, shared by
// the compact rail and the full overlay: you at the center, inner-ring
// kreds on the inner radius, kreds-ring kreds on the outer radius, spread
// evenly by angle within each ring. Both callers pass live KREDS/FEED
// data - no invented people, no server-side layout.
//
// Freshness dot: honesty guard - it is computed ONLY from lastOpened()
// (localStorage, see above) vs this friend's FEED post times. There is no
// server "unread" field.
// ---------------------------------------------------------------------

const SVGNS = "http://www.w3.org/2000/svg";
function svgEl(tag, attrs) {
  const e = document.createElementNS(SVGNS, tag);
  if (attrs) for (const k in attrs) e.setAttribute(k, attrs[k]);
  return e;
}

function meInitial() {
  return ((STATE && STATE.profile_name) || "?").slice(0, 1).toUpperCase();
}

function isFresh(identityPub) {
  const latest = FEED.filter(p => p.identity_pub === identityPub)
    .reduce((m, p) => Math.max(m, p.created_at), 0);
  return latest > lastOpened(identityPub);
}

// Evenly distributes `items` by angle around (cx, cy) at radius `r`,
// starting just off the top and going clockwise. Not dead-center top: the
// ring label ("inner kreds" / "kreds") sits there, and with few members
// (e.g. exactly one) an even split from -90 would land a node right on
// top of that label.
function placeRing(items, cx, cy, r) {
  const n = items.length;
  return items.map((k, i) => {
    const angle = (-50 + (360 / n) * i) * Math.PI / 180;
    return {k, x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle)};
  });
}

// World-scaling constants (spec 2026-07-08-kreds-circle-zoom): the circle
// grows with friend count instead of packing nodes tighter. SPACING is the
// world-unit distance between adjacent node centers on a ring; RING_GAP the
// minimum inner->outer separation; MARGIN clears ring/name labels at the rim.
const CIRCLE_SPACING = 64;
const CIRCLE_RING_GAP = 78;
const CIRCLE_MARGIN = 60;

// Radius that gives `count` nodes CIRCLE_SPACING of arc each, never below
// the ring's cosmetic base radius (small circles keep today's proportions).
function ringRadius(count, baseR) {
  return Math.max(baseR, (count * CIRCLE_SPACING) / (2 * Math.PI));
}

// Renders the radial map into `svg` (an <svg> element, cleared first).
// `big` selects the full-overlay markup (.node/.younode/.ringlabel, with
// name labels and keyboard-focusable nodes) vs the compact rail's
// .mm-node/.mm-you markup (smaller, no labels).
function buildCircle(svg, kreds, opts) {
  let {size, innerR, outerR, youR, nodeR, big} = opts;
  if (opts.scaleWithCount) {
    const nInner = kreds.filter(k => k.ring === "inner").length;
    innerR = ringRadius(nInner, innerR);
    outerR = Math.max(ringRadius(kreds.length - nInner, outerR),
                      innerR + CIRCLE_RING_GAP);
    size = 2 * (outerR + CIRCLE_MARGIN);
  }
  svg.dataset.worldSize = size;
  svg.setAttribute("viewBox", "0 0 " + size + " " + size);
  svg.replaceChildren();
  const cx = size / 2, cy = size / 2;
  svg.append(
    svgEl("circle", {class: "ringguide", cx, cy, r: innerR}),
    svgEl("circle", {class: "ringguide", cx, cy, r: outerR}));
  if (big) {
    const inLbl = svgEl("text",
      {class: "ringlabel", x: cx, y: cy - innerR - 9, "text-anchor": "middle"});
    inLbl.textContent = "inner kreds";
    const outLbl = svgEl("text",
      {class: "ringlabel", x: cx, y: cy - outerR - 9, "text-anchor": "middle"});
    outLbl.textContent = "kreds";
    svg.append(inLbl, outLbl);
  }
  if (big) {
    const g = svgEl("g", {class: "younode"});
    const t = svgEl("text", {x: cx, y: cy + 1});
    t.textContent = meInitial();
    g.append(svgEl("circle", {cx, cy, r: youR}), t);
    svg.append(g);
  } else {
    const t = svgEl("text", {class: "mm-youtext", x: cx, y: cy + 0.5});
    t.textContent = meInitial();
    svg.append(svgEl("circle", {class: "mm-you", cx, cy, r: youR}), t);
  }
  const inner = kreds.filter(k => k.ring === "inner");
  const outer = kreds.filter(k => k.ring !== "inner");
  // Actual minimum node pitch (world units of arc between adjacent nodes on
  // the fullest ring). updateCircleLabels compares THIS, not the SPACING
  // floor: a small circle's real pitch is far above the floor, so its labels
  // stay visible at fit even on phone-sized viewports (whole-branch review,
  // Important #1).
  const pitches = [];
  if (inner.length) pitches.push(2 * Math.PI * innerR / inner.length);
  if (outer.length) pitches.push(2 * Math.PI * outerR / outer.length);
  svg.dataset.nodePitch = pitches.length ? Math.min(...pitches) : CIRCLE_SPACING;
  const placed = placeRing(inner, cx, cy, innerR).concat(placeRing(outer, cx, cy, outerR));
  for (const {k, x, y} of placed) {
    const color = identityColor(k.identity_pub);
    const initial = (k.name || "?").slice(0, 1).toUpperCase();
    const g = svgEl("g", {class: big ? "node" : "mm-node", "data-open": k.identity_pub});
    if (big) {
      g.tabIndex = 0;
      g.setAttribute("role", "button");
      g.setAttribute("aria-label", k.name);
    }
    const circle = svgEl("circle", big
      ? {class: "face", cx: x, cy: y, r: nodeR, fill: color}
      : {cx: x, cy: y, r: nodeR, fill: color});
    const text = svgEl("text", big
      ? {class: "init", x, y: y + 0.5}
      : {x, y: y + 0.5});
    text.textContent = initial;
    g.append(circle, text);
    if (isFresh(k.identity_pub))
      g.append(svgEl("circle",
        {class: "fresh", cx: x + nodeR * 0.75, cy: y - nodeR * 0.75, r: big ? 5 : 3.4}));
    if (big) {
      const label = svgEl("text", {class: "nlabel", x, y: y + nodeR + 16});
      label.textContent = k.name;
      g.append(label);
    }
    svg.append(g);
  }
}

function renderCircleRail() {
  const rail = document.getElementById("circle-rail");
  if (!rail) return;
  rail.replaceChildren();

  const title = el("div", "railtitle", "Your kreds · " + KREDS.length + " ");
  const expand = el("button", "expandbtn", "Expand ↗");
  expand.type = "button";
  expand.onclick = openCircleOverlay;
  title.append(expand);

  const mini = el("div", "minimap");
  mini.tabIndex = 0;
  mini.setAttribute("role", "button");
  mini.setAttribute("aria-label", "Open the circle");
  const svg = svgEl("svg", {viewBox: "0 0 200 200", "aria-hidden": "true"});
  buildCircle(svg, KREDS, {size: 200, innerR: 44, outerR: 78, youR: 13, nodeR: 11, big: false});
  mini.append(svg);
  // A click on a person node opens their profile; a click anywhere else on
  // the minimap (or the Expand button) opens the full overlay.
  mini.addEventListener("click", (ev) => {
    const node = ev.target.closest("[data-open]");
    if (node) openProfile(node.getAttribute("data-open"));
    else openCircleOverlay();
  });
  mini.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); openCircleOverlay(); }
  });

  const freshCount = KREDS.filter(k => isFresh(k.identity_pub)).length;
  const note = el("p", "railnote");
  if (!KREDS.length) {
    note.textContent = "Add a friend in Me to start your circle.";
  } else if (freshCount > 0) {
    note.append(el("b", "", freshCount + " new"), document.createTextNode(
      " since you last looked. Click the circle to expand — click a " +
      "person anywhere to open their profile."));
  } else {
    note.textContent = "Click the circle to expand — click a person " +
      "anywhere to open their profile.";
  }

  rail.append(title, mini, note);
}

function openCircleOverlay() {
  const svg = document.getElementById("circle-overlay-svg");
  buildCircle(svg, KREDS, {size: 440, innerR: 92, outerR: 170, youR: 24,
                           nodeR: 21, big: true, scaleWithCount: true});
  document.getElementById("circle-overlay").classList.add("open");
  circleCamera.fit();
}
function closeCircleOverlay() {
  document.getElementById("circle-overlay").classList.remove("open");
}

// ---------------------------------------------------------------------
// Circle camera (spec 2026-07-08-kreds-circle-zoom): zoom/pan by rewriting
// the overlay svg's viewBox - the drawn nodes never carry transforms, so
// click/keyboard/hover behavior is untouched. State is the viewBox origin
// (x, y) and width w (the viewport is square, height mirrors width).
// ---------------------------------------------------------------------
// Labels show only when nodes are far enough apart on SCREEN to carry
// them (spec: node pitch >= 56 css px). Pitch comes from dataset.nodePitch,
// the ACTUAL minimum world arc between adjacent nodes published by
// buildCircle - not the SPACING floor (a small circle's real pitch is far
// above that floor, so its labels stay visible on small viewports too).
function updateCircleLabels() {
  const svg = circleCamera.svg;
  if (!svg) return;
  const r = svg.getBoundingClientRect();
  if (!r.width) return;
  const pitchWorld = +svg.dataset.nodePitch || CIRCLE_SPACING;
  const pitchPx = pitchWorld * (r.width / circleCamera.w);
  svg.classList.toggle("labels-off", pitchPx < 56);
}

const circleCamera = {
  svg: null, x: 0, y: 0, w: 440, fitW: 440,
  init(svg) { this.svg = svg; },
  fit() {
    this.fitW = +this.svg.dataset.worldSize || 440;
    this.x = 0; this.y = 0; this.w = this.fitW;
    this.apply();
  },
  apply() {
    this.svg.setAttribute("viewBox",
      this.x + " " + this.y + " " + this.w + " " + this.w);
    updateCircleLabels();
  },
  clamp() {
    // zoom: between fit (whole world) and 8x magnification
    this.w = Math.min(this.fitW, Math.max(this.fitW / 8, this.w));
    // pan: keep >= 20% of the viewport showing world on each axis
    const lo = -0.8 * this.w, hi = this.fitW - 0.2 * this.w;
    this.x = Math.min(hi, Math.max(lo, this.x));
    this.y = Math.min(hi, Math.max(lo, this.y));
  },
  // Zoom by `factor` keeping the world point under (clientX, clientY) fixed.
  zoomAt(clientX, clientY, factor) {
    const r = this.svg.getBoundingClientRect();
    if (!r.width) return;
    const fx = (clientX - r.left) / r.width, fy = (clientY - r.top) / r.height;
    const wx = this.x + fx * this.w, wy = this.y + fy * this.w;
    this.w /= factor;
    this.clamp();
    this.x = wx - fx * this.w;
    this.y = wy - fy * this.w;
    this.clamp();
    this.apply();
  },
  panBy(dxPx, dyPx) {
    const r = this.svg.getBoundingClientRect();
    if (!r.width) return;
    this.x -= (dxPx / r.width) * this.w;
    this.y -= (dyPx / r.height) * this.w;
    this.clamp();
    this.apply();
  },
  // Pan (no zoom change) so a focused node sits inside a 10% margin.
  ensureVisible(nodeG) {
    const c = nodeG.querySelector("circle");
    if (!c) return;
    const nx = +c.getAttribute("cx"), ny = +c.getAttribute("cy");
    const pad = this.w * 0.1;
    if (nx < this.x + pad) this.x = nx - pad;
    if (nx > this.x + this.w - pad) this.x = nx - this.w + pad;
    if (ny < this.y + pad) this.y = ny - pad;
    if (ny > this.y + this.w - pad) this.y = ny - this.w + pad;
    this.clamp();
    this.apply();
  },
};

// One-shot flag: a pan/pinch gesture ends in a browser-generated click on
// whatever is under the pointer - swallow exactly that one click so a drag
// that started on a person node doesn't ALSO open their profile.
let CIRCLE_DRAGGED = false;

function wireCircleGestures(svg) {
  const pts = new Map();       // pointerId -> {x, y, sx, sy} (current + start)
  let moved = false;           // past the 6px tap threshold this gesture?
  let multi = false;           // did this gesture ever have two pointers?
  let pinchDist = 0;
  let lastTap = 0;             // for double-tap reset (touch)

  // Capture is DEFERRED until the gesture is definitely a pan/pinch: with
  // capture taken at pointerdown, the browser retargets the eventual click
  // to the svg itself, so a plain tap's click never reaches the person node
  // (found by the Task 5 live smoke - taps were silently dead). Capturing
  // only after the 6px threshold (or on a second pointer) leaves clean taps
  // uncaptured -> their click flows to the node as before.
  const capture = () => {
    for (const id of pts.keys()) {
      try { svg.setPointerCapture(id); } catch (e) { /* pointer already gone */ }
    }
  };

  const gone = (ev) => {
    // pointerup AND lostpointercapture both fire for a captured pointer -
    // process each pointer's release exactly once, or the second pass
    // re-runs the size-0 branch and clobbers CIRCLE_DRAGGED back to false
    // before the browser's click event consumes it (task review, Critical).
    if (!pts.has(ev.pointerId)) return;
    pts.delete(ev.pointerId);
    // dropping to exactly 2 must also re-seed (a 3rd finger lifting mid-pinch
    // left a stale pair distance)
    if (pts.size <= 2) pinchDist = 0;
    if (pts.size === 0) { CIRCLE_DRAGGED = moved; moved = false; multi = false; }
  };

  svg.addEventListener("pointerdown", (ev) => {
    // A pinch generates no click, so a stale CIRCLE_DRAGGED=true from the
    // previous gesture would swallow this gesture's tap - clear it at the
    // start of every fresh gesture (task review, Important).
    if (pts.size === 0) CIRCLE_DRAGGED = false;
    pts.set(ev.pointerId, {x: ev.clientX, y: ev.clientY,
                           sx: ev.clientX, sy: ev.clientY});
    if (pts.size === 2) {
      multi = true;
      capture();               // a second pointer = definitely a pinch, never a tap
      const [a, b] = [...pts.values()];
      pinchDist = Math.hypot(a.x - b.x, a.y - b.y);
    }
  });

  svg.addEventListener("pointermove", (ev) => {
    const p = pts.get(ev.pointerId);
    if (!p) return;
    // A swallowed right-button pointerup (context-menu flows on some
    // platforms) can leave a stale entry - a mouse moving with no buttons
    // held is not a gesture, tear it down.
    if (ev.pointerType === "mouse" && !ev.buttons) { gone(ev); return; }
    const prevX = p.x, prevY = p.y;
    p.x = ev.clientX; p.y = ev.clientY;
    if (!moved && Math.hypot(p.x - p.sx, p.y - p.sy) > 6) {
      moved = true;
      capture();               // definitely a pan now, never a tap
    }
    if (!moved) return;
    if (pts.size === 1) {
      circleCamera.panBy(p.x - prevX, p.y - prevY);
    } else if (pts.size === 2) {
      const [a, b] = [...pts.values()];
      const dist = Math.hypot(a.x - b.x, a.y - b.y);
      if (pinchDist > 0 && dist > 0) {
        circleCamera.zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, dist / pinchDist);
      }
      pinchDist = dist;
    }
  });

  svg.addEventListener("pointerup", (ev) => {
    // double-tap (touch) resets to fit - two clean taps within 300ms
    if (!moved && !multi && ev.pointerType === "touch" && pts.size === 1) {
      const now = performance.now();
      if (now - lastTap < 300) { circleCamera.fit(); lastTap = 0; }
      else lastTap = now;
    }
    gone(ev);
  });
  svg.addEventListener("pointercancel", gone);
  svg.addEventListener("lostpointercapture", gone);
  // Sub-threshold releases can land off-svg without capture (a down at the
  // very edge that exits before crossing 6px) - window-level teardown covers
  // them; gone()'s pts.has guard makes stray pointerups elsewhere a no-op.
  window.addEventListener("pointerup", gone);
  window.addEventListener("pointercancel", gone);
}

{
  const svg = document.getElementById("circle-overlay-svg");
  circleCamera.init(svg);
  wireCircleGestures(svg);
  svg.addEventListener("wheel", (ev) => {
    ev.preventDefault();
    circleCamera.zoomAt(ev.clientX, ev.clientY, ev.deltaY < 0 ? 1.15 : 1 / 1.15);
  }, {passive: false});
  svg.addEventListener("dblclick", () => circleCamera.fit());
  document.getElementById("circle-fit").onclick = () => circleCamera.fit();
  svg.addEventListener("focusin", (ev) => {
    const node = ev.target.closest("[data-open]");
    if (node) circleCamera.ensureVisible(node);
  });
}

document.getElementById("circle-overlay-svg").addEventListener("click", (ev) => {
  if (CIRCLE_DRAGGED) { CIRCLE_DRAGGED = false; return; }   // that "click" was a drag ending
  const node = ev.target.closest("[data-open]");
  if (node) openProfile(node.getAttribute("data-open"));
});
document.getElementById("circle-overlay-svg").addEventListener("keydown", (ev) => {
  if (ev.key !== "Enter" && ev.key !== " ") return;
  const node = ev.target.closest("[data-open]");
  if (node) { ev.preventDefault(); openProfile(node.getAttribute("data-open")); }
});
document.getElementById("close-overlay").onclick = closeCircleOverlay;
document.getElementById("circle-overlay").addEventListener("click", (ev) => {
  if (ev.target.id === "circle-overlay") closeCircleOverlay();
});

// ---------------------------------------------------------------------
// People: profile PAGE (identity-color banner, bio, ring status,
// Message + move-between-rings, scope-filtered posts). Every "open this
// person" click site in this file (journal avatar/name, friend rows in
// Me, circle map nodes) calls openProfile, which navigates to the
// #view-profile page - not a modal.
//
// Honesty guard: no "who holds this post" receipts popover here or
// anywhere else - Wall/Journal render only what the server already
// decrypt-filtered into p.wall / p.journal.
// ---------------------------------------------------------------------

function currentView() {
  // The mobile "Circle" tab is the journal view with a .show-circle modifier,
  // not its own container - report it as "circle" so Back restores it.
  const vj = document.getElementById("view-journal");
  if (!vj.classList.contains("hidden") && vj.classList.contains("show-circle"))
    return "circle";
  for (const v of ["journal", "messages", "profile", "settings"])
    if (!document.getElementById("view-" + v).classList.contains("hidden")) return v;
  return "journal";
}

async function openProfile(identityPub) {
  // Leaving a profile (or opening someone else's) exits Arrange mode. Compared
  // against the OLD CURRENT_PROFILE, before it's overwritten below - the
  // Arrange button's own handler re-renders the SAME profile on purpose
  // (ARRANGING = true; openProfile(CURRENT_PROFILE)) and must not have that
  // undone by this reset.
  if (identityPub !== CURRENT_PROFILE) ARRANGING = false;
  const from = currentView();
  if (from !== "profile") PRIOR_VIEW = from;   // where Back returns
  CURRENT_PROFILE = identityPub;
  // A person can be opened from the expanded circle overlay, which is an
  // absolute layer inside #app; close it so the profile page isn't hidden
  // behind it (the old modal escaped #app's stacking context; the page does
  // not).
  closeCircleOverlay();
  let p;
  try {
    p = await j("/api/profile/" + identityPub);
  } catch (e) {
    // Known friend, no synced profile record yet (e.g. freshly added) ->
    // /api/profile 404s. Show a minimal fallback (always a non-self profile)
    // instead of leaving the click silently do nothing.
    p = fallbackProfile(identityPub);
  }
  // Opening someone's profile is a deliberate visit: clear their new-post
  // dot outright (watermark to now - unlike the journal observer's
  // per-post bump, a visit means "caught up on this person").
  if (!p.mine) { markOpenedNow(identityPub); scheduleFreshDotRerender(); }
  // Final-review fix: setView BEFORE renderProfilePage - #view-profile still
  // carries .hidden (display:none !important) until setView clears it, and
  // renderProfilePage's renderWall/measureWallCell reads wall.clientWidth
  // synchronously; a display:none wall always measures 0, squashing --cell
  // on every profile visit (Journal/Messages -> profile is the most common
  // one). The view must be visible before anything inside it gets measured.
  setView("profile");
  renderProfilePage(p);
  applyProfileNav(p.mine);   // runs on BOTH the loaded and fallback paths, after setView per its own comment
}

// Your own profile keeps the "Me" nav context (desktop nav + mobile tab);
// anyone else's profile shows no active tab (Back is the way back). Called
// after setView("profile") so it isn't clobbered by setView clearing navlinks.
function applyProfileNav(mine) {
  const meTab = document.querySelector('.navlinks button[data-view="me"]');
  if (meTab) meTab.classList.toggle("active", !!mine);
  document.querySelectorAll(".tabbar-mobile button").forEach(b =>
    b.classList.toggle("active", !!mine && b.dataset.tab === "me"));
}

// Minimal fallback for when /api/profile 404s (see openProfile's catch
// above). Uses only client-known data: the friend/kreds name (or the
// identity's short fingerprint), the deterministic identity color, and a
// Message action - no bio, ring status, or posts to show.
function fallbackProfile(identityPub) {
  const disc = disconnectedInfo(identityPub);
  if (disc) {
    // No longer a known identity server-side (unfriended, either side) -
    // an inert profile: no bio/ring/posts/Message, just the marker.
    return {identity_pub: identityPub, name: disc.name, bio: "", mine: false,
            avatar: null, banner: null, banner_pos: 50, avatar_shape: "circle",
            avatar_size: "m", avatar_align: "left", ring: null, since: null,
            wall: [], journal: [], disconnected: true};
  }
  const known = (STATE && STATE.friends || []).find(f => f.identity_pub === identityPub)
    || KREDS.find(k => k.identity_pub === identityPub);
  return {identity_pub: identityPub, name: (known && known.name) || identityPub.slice(0, 8),
          bio: "", mine: false, avatar: null, banner: null, banner_pos: 50,
          avatar_shape: "circle", avatar_size: "m", avatar_align: "left",
          ring: null, since: null, wall: [], journal: [], unavailable: true};
}

// The collage: pinned blocks at explicit coordinates on #profile-wall
// (holes stay empty - a block this viewer can't decrypt leaves its gap);
// unplaced blocks flow newest-first below (#profile-wall-flow). The tray
// died with dynamic placement (spec 2026-07-14) - a legacy wall's unplaced
// blocks self-migrate below instead: opening YOUR OWN profile with any
// unplaced block fires one /api/wall-autoplace (a single layout write
// pinning them all at the top, push rule applied), then re-renders - but
// only when the response reports placed > 0, so a fully-migrated wall (or
// a visitor's, who never calls this at all) doesn't re-enter the loop.
function renderWall(p) {
  const wall = document.getElementById("profile-wall");
  const flow = document.getElementById("profile-wall-flow");
  wall.replaceChildren(); flow.replaceChildren();
  const pinned = p.wall.filter(b => b.pin);
  const unpinned = p.wall.filter(b => !b.pin);
  for (const post of pinned) wall.append(renderBlock(post));
  for (const post of unpinned) flow.append(renderBlock(post));
  if (!p.wall.length) flow.append(el("div", "hint",
    p.mine ? "Your profile is a blank canvas - post something above." : "Nothing here yet."));
  measureWallCell();
  if (p.mine && unpinned.length) {
    const who = p.identity_pub;   // snapshot: whose wall this migration was fired for
    fetch("/api/wall-autoplace", {method: "POST"}).then(async (r) => {
      if (!r.ok) return;
      const {placed} = await r.json();
      // This trigger is silent (not click-initiated like the file's other
      // unsnapshotted re-render sites), so the response must never yank a
      // user who navigated away back to the profile view - openProfile
      // unconditionally setView("profile")s. Re-render only if they're
      // still looking at THIS profile; the migration itself persisted
      // server-side either way (the next visit renders it placed).
      if (placed > 0 && CURRENT_PROFILE === who
          && currentView() === "profile") openProfile(who);
    });
  }
}

// --cell drives grid-auto-rows: CSS can't express "row height = column
// width" for spanning rows, so measure it (same idiom as --nav-h).
function measureWallCell() {
  const wall = document.getElementById("profile-wall");
  // Skip, don't clamp, when unmeasurable: a display:none wall (e.g.
  // #view-profile hidden mid-navigation) reads clientWidth 0 - leave --cell
  // at whatever it already was rather than poisoning it down to the 40px
  // floor. The floor below still applies to a genuinely tiny but VISIBLE
  // wall (real narrow-viewport case).
  if (!wall || !wall.clientWidth) return;
  const cell = Math.max(40, (wall.clientWidth - 3 * 12) / 4);
  document.documentElement.style.setProperty("--cell", cell.toFixed(2) + "px");
}
window.addEventListener("resize", measureWallCell);

function renderProfilePage(p) {
  // IMPORTANT-3 (whole-branch review): a re-render of the SAME person (heal,
  // an openBlockSettings reopenAfterAction, the Arrange toggle) must not
  // reset deck flips or re-collapse the mobile journal rail - only an
  // actual person-switch does. LAST_RENDERED_PROFILE is set only here so it
  // covers every renderProfilePage call site, not just openProfile's.
  const samePerson = p.identity_pub === LAST_RENDERED_PROFILE;
  if (!samePerson) DECK_POS.clear();   // a new person's decks start fresh, never at A's flip position
  LAST_RENDERED_PROFILE = p.identity_pub;
  const color = p.accent || identityColor(p.identity_pub);   // owner's chosen accent for all viewers
  CURRENT_PROFILE_ACCENT = color;   // renderWall -> renderBlock reads this for text-style color:"accent"
  const page = document.getElementById("profile-page");
  page.style.setProperty("--pcolor", color);

  const banner = document.getElementById("profile-banner");
  banner.style.backgroundImage = p.banner ? `url(/api/blob/${p.banner})` : "";
  // banner_pos (spec 2026-07-15): vertical crop percent, 0 = top of the
  // image, 100 = bottom; 50 = the old hardcoded center.
  banner.style.backgroundPosition =
    "center " + (Number.isInteger(p.banner_pos) ? p.banner_pos : 50) + "%";

  const head = document.getElementById("profile-head");
  head.className = "profile-head align-" + (p.avatar_align || "left");
  const av = document.getElementById("profile-avatar");
  av.className = "profile-avatar " + (p.avatar_shape || "circle") + " " + (p.avatar_size || "m");
  av.style.background = color;
  av.replaceChildren();
  if (p.avatar) { const img = document.createElement("img");
    img.src = "/api/blob/" + p.avatar; av.append(img); }
  else av.append(document.createTextNode((p.name || "?").slice(0, 1).toUpperCase()));

  document.getElementById("profile-name-view").textContent = p.name;
  document.getElementById("profile-hash").textContent =
    "identity " + p.identity_pub.slice(0, 8) + "…";
  document.getElementById("profile-bio").textContent = p.bio || "";

  // Cogwheel: self-only entry into the Settings page. Header/bio above
  // render identically for self and others (see comment above); the only
  // self-specific chrome is this button, not an inline dump.
  const cog = document.getElementById("profile-cog");
  cog.classList.toggle("hidden", !p.mine);
  cog.onclick = p.mine ? () => openSettings() : null;

  // Arrange/Done toggle: self-only, its click handler is wired once at load
  // (see below) and reads CURRENT_PROFILE - this just reflects state.
  const arrangeBtn = document.getElementById("profile-arrange");
  arrangeBtn.textContent = ARRANGING ? "Done" : "Arrange";
  arrangeBtn.classList.toggle("hidden", !p.mine);

  // Topbar "+" (self-only, spec 2026-07-15): quick add-friend without a
  // trip to Settings.
  document.getElementById("profile-addfriend").classList.toggle("hidden", !p.mine);

  // Right column: ONLY the Journal rail now, on every profile (spec
  // 2026-07-15) - the self-only panels live on the Settings page.
  document.getElementById("profile-back").classList.toggle("hidden", !!p.mine);
  document.getElementById("profile-layout").classList.add("has-side");   // always two-col on desktop

  const meta = document.getElementById("profile-meta");
  const acts = document.getElementById("profile-actions");
  meta.replaceChildren(); acts.replaceChildren();

  if (p.disconnected) {
    // Unfriended (either side) - an inert marker, no actions: Message is
    // disabled since there's no one left to message on this identity.
    meta.append(el("div", "profile-ring disconnected", "no longer connected"));
  } else if (p.unavailable) {
    meta.append(el("div", "hint", "Profile unavailable yet."));
    const msg = el("button", "btn-accent", "Message");
    msg.onclick = () => { goView("messages"); openThread(p.identity_pub, p.name); };
    acts.append(msg);
  } else if (!p.mine) {
    const ringLabel = p.ring === "inner" ? "Inner kreds" : "Kreds";
    const since = p.since ? new Date(p.since * 1000)
      .toLocaleDateString(undefined, {month: "long", year: "numeric"}) : "";
    const ring = el("div", "profile-ring", ringLabel + (since ? " · since " + since : ""));
    meta.append(ring);
    const msg = el("button", "btn-accent", "Message");
    msg.onclick = () => { goView("messages"); openThread(p.identity_pub, p.name); };
    const move = el("button", "", p.ring === "inner" ? "Move to kreds" : "Move to inner kreds");
    move.onclick = async () => {
      const next = p.ring === "inner" ? "kreds" : "inner";
      await j("/api/ring", {method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({identity_pub: p.identity_pub, ring: next})});
      await refresh();
      openProfile(p.identity_pub);
    };
    const unfriendBtn = el("button", "btn-danger", "Unfriend");
    unfriendBtn.onclick = async () => {
      if (!confirm(unfriendConfirm(p.name))) return;
      await j("/api/unfriend", {method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({identity_pub: p.identity_pub})});
      goView(PRIOR_VIEW);
      await refresh();
    };
    acts.append(msg, move, unfriendBtn);
  }
  // p.mine falls through with no meta/acts row: editing now lives behind
  // the cogwheel overlay, not an inline dump here.

  // Wall composer: self-only, posts with placement=profile.
  const compose = document.getElementById("profile-wall-compose");
  compose.replaceChildren();
  if (p.mine) compose.append(profilePostComposer());

  renderWall(p);

  const rail = document.getElementById("profile-journal-rail");
  rail.replaceChildren();
  // Reset the mobile disclosure per profile so B's rail isn't pre-expanded
  // just because A's was opened - but NOT on a same-person re-render
  // (IMPORTANT-3): a heal tick mid-scroll must not collapse a rail the
  // visitor just opened on THIS profile.
  if (!samePerson) {
    rail.classList.remove("open");
    document.getElementById("profile-journal-toggle").setAttribute("aria-expanded", "false");
  }
  if (!p.journal.length) rail.append(el("div", "hint", "No journal posts here."));
  for (const post of p.journal) rail.append(buildEntry(post));
}

// Settings page (spec 2026-07-15): the self-only side panels + the
// profile editor, re-homed - the cog opens this instead of the old edit
// overlay. Like the profile page, content refreshes on entry, not on
// every WS tick. Section collapse is a local UI preference
// (localStorage), deliberately not synced state.
async function openSettings() {
  let p;
  try {
    p = await j("/api/profile/" + STATE.identity_pub);
  } catch (e) {
    p = fallbackProfile(STATE.identity_pub);
  }
  document.getElementById("settings-editprofile")
    .replaceChildren(profileEditor(p));
  renderMeStrip();   // fills #friends / #devices / App-lock / Desktop / Updates
  setView("settings");
}
document.getElementById("settings-back").onclick = () => openMe();
function wireSettingsSections() {
  document.querySelectorAll("#view-settings .settings-section").forEach(d => {
    const key = "kreds_settings_open_" + d.id;
    const saved = localStorage.getItem(key);
    if (saved !== null) d.open = saved === "1";
    d.addEventListener("toggle", () =>
      localStorage.setItem(key, d.open ? "1" : "0"));
  });
}
wireSettingsSections();

// Wall composer (self only): same keeps-button (inner/kreds) pattern as the
// journal composer in index.html, but built in JS since this composer only
// exists on the self profile page, and posts with placement=profile.
function profilePostComposer() {
  const form = el("form", "composer profile-composer");
  // Stable hook (CRITICAL #1, whole-branch review): refresh()'s heal loop
  // needs to find THIS composer instance from outside to check dirtiness
  // before tearing it down mid-draft.
  form.id = "profile-composer-form";
  const input = document.createElement("input");
  input.type = "text"; input.autocomplete = "off";
  input.placeholder = "Post to your profile…";
  form.append(input);

  const bar = el("div", "composerbar");
  bar.append(el("span", "keeps-label", "keeps"));
  let scope = "kreds";                              // default kreds, per spec
  const keepBtns = [];
  for (const [val, label] of [["inner", "Inner"], ["kreds", "Kreds"]]) {
    const b = el("button", "keep" + (val === scope ? " active" : ""));
    b.type = "button";
    b.innerHTML = SCOPE_ICON[val] + "<span>" + label + "</span>";
    b.onclick = () => {
      scope = val;
      keepBtns.forEach(x => x.classList.remove("active"));
      b.classList.add("active");
    };
    keepBtns.push(b);
    bar.append(b);
  }
  form.append(el("div", "composer-note",
    "Inner posts reach only your Inner kreds, and moving someone into Inner reveals only future Inner posts. Kreds wall posts are visible to all your current kreds."));
  const photoLabel = el("label", "keep");
  photoLabel.textContent = "Photo";
  const photoInput = document.createElement("input");
  photoInput.type = "file"; photoInput.accept = "image/*"; photoInput.multiple = true;
  photoInput.className = "visually-hidden";   // keyboard-reachable (not display:none)
  photoLabel.append(photoInput);
  bar.append(photoLabel);
  const videoLabel = el("label", "keep");
  videoLabel.textContent = "Video";
  const videoInput = document.createElement("input");
  videoInput.type = "file"; videoInput.accept="video/*";
  videoInput.className = "visually-hidden";
  videoLabel.append(videoInput);
  bar.append(videoLabel);

  // -- live preview (spec 2026-07-13 section 4): the post as it will
  // render - one photo big, several as a stacked deck (the same
  // affordance Slice C's wall decks use), video as its first frame via a
  // local object URL. Honest boundary: the video preview is the RAW
  // file's frame - the server gate still transcodes and can still reject
  // on post; previewing is not acceptance.
  const preview = el("div", "compose-preview");
  preview.hidden = true;
  preview.setAttribute("aria-live", "polite");

  // -- video note: a SIBLING of the sized preview card, not a child of it -
  // appended inside, overflow:visible spilled it past the card's fixed
  // height onto the size chips below. aria-live so screen readers get the
  // same "attached" confirmation the visible note gives sighted users.
  const noteSlot = el("div", "preview-note");
  noteSlot.hidden = true;
  noteSlot.setAttribute("aria-live", "polite");

  // -- size chips: the block's starting w x h, previewed at true canvas
  // proportions via --cell (measureWallCell keeps it fresh on the
  // profile page, where this composer exclusively lives). Media defaults
  // to 2x2 and rides the chosen chip as w/h fields on /api/post; text-only
  // posts show no chips and send neither field (the server's 4x1 text
  // default applies).
  const chips = el("div", "size-chips");
  chips.setAttribute("role", "group");
  chips.setAttribute("aria-label", "Block size");
  chips.hidden = true;
  let span = {w: 2, h: 2};
  const chipBtns = [];
  for (const [w, h] of [[1, 1], [2, 2], [4, 2], [4, 3]]) {
    const c = el("button", "size-chip", w + "x" + h);
    c.type = "button";
    c.dataset.span = w + "x" + h;
    c.setAttribute("aria-pressed", String(w === 2 && h === 2));
    if (w === 2 && h === 2) c.classList.add("active");   // "2x2" default
    c.onclick = () => {
      span = {w, h};
      for (const x of chipBtns) {
        x.classList.toggle("active", x === c);
        x.setAttribute("aria-pressed", String(x === c));
      }
      sizePreview();
    };
    chipBtns.push(c);
    chips.append(c);
  }

  let objectUrls = [];
  let videoEdit = null;          // wire params from the editor, or null (raw)
  const dropUrls = () => {
    for (const u of objectUrls) URL.revokeObjectURL(u);
    objectUrls = [];
  };
  const sizePreview = () => {
    // True proportions: the same cell math the canvas renders with.
    preview.style.width = "calc(var(--cell, 120px) * " + span.w
      + " + " + (12 * (span.w - 1)) + "px)";
    preview.style.height = "calc(var(--cell, 120px) * " + span.h
      + " + " + (12 * (span.h - 1)) + "px)";
  };
  const clearPreview = () => {
    dropUrls();
    preview.replaceChildren();
    preview.hidden = true;
    noteSlot.hidden = true;
    chips.hidden = true;
  };
  const showPreview = () => {
    dropUrls();
    preview.replaceChildren();
    const files = [...photoInput.files];
    const videoFile = videoInput.files[0];
    if (videoFile) {
      const v = document.createElement("video");
      v.muted = true; v.playsInline = true; v.preload = "metadata";
      const u = URL.createObjectURL(videoFile);
      objectUrls.push(u);
      v.src = u;
      // WebKit won't paint a frame without an explicit seek.
      v.addEventListener("loadedmetadata", () => { v.currentTime = 0.01; });
      preview.append(v);
      // editor re-entry: the preview is the edit's handle
      v.style.cursor = "pointer";
      v.onclick = () => openVideoEditor(videoFile, videoEdit, (res) => {
        if (res.action === "cancel") return;     // keep current choices
        videoEdit = res.action === "done" ? res.edit : null;
        showPreview();
      });
      const parts = [];
      if (videoEdit) {
        parts.push("trimmed to " + videoEdit.duration.toFixed(1) + "s");
        if (videoEdit.crop) parts.push("cropped");
        if (videoEdit.poster_t > 0) parts.push("cover set");
      }
      // DRAFT copy (August may reword; update the asset pin with it)
      noteSlot.textContent = videoFile.name
        + (parts.length ? " - " + parts.join(" - ")
                        : " - tap the preview to trim or crop");
      noteSlot.hidden = false;
    } else if (files.length) {
      const wrap = el("div", files.length > 1 ? "preview-deck" : "preview-photo");
      const img = document.createElement("img");
      const u = URL.createObjectURL(files[0]);
      objectUrls.push(u);
      img.src = u;
      img.alt = files.length > 1
        ? files.length + " photos attached" : "1 photo attached";
      wrap.append(img);
      if (files.length > 1)
        wrap.append(el("span", "deck-count", String(files.length)));
      preview.append(wrap);
      noteSlot.hidden = true;
    } else { clearPreview(); return; }
    preview.hidden = false;
    chips.hidden = false;
    sizePreview();
  };
  photoInput.onchange = () => {
    if (photoInput.files.length) { videoInput.value = ""; videoEdit = null; }   // one medium, photos win
    showPreview();
  };
  videoInput.onchange = () => {
    if (!videoInput.files.length) { videoEdit = null; showPreview(); return; }
    photoInput.value = "";                       // one medium, video wins
    openVideoEditor(videoInput.files[0], null, (res) => {
      if (res.action === "cancel") {
        videoInput.value = ""; videoEdit = null; showPreview(); return;
      }
      videoEdit = res.action === "done" ? res.edit : null;
      showPreview();
    });
  };

  const btn = el("button", "postbtn", "Post to profile"); btn.type = "submit";
  bar.append(btn);
  // preview/chips must be appended WITH bar, not inserted before a bar that
  // isn't in the form yet (unconditional NotFoundError - live-smoke crash).
  form.append(preview, noteSlot, chips, bar);

  form.onsubmit = async (ev) => {
    ev.preventDefault();
    const fd = new FormData();
    fd.append("text", input.value);
    fd.append("scope", scope);
    fd.append("placement", "profile");
    const videoFile = videoInput.files[0];
    const hasMedia = !!videoFile || photoInput.files.length > 0;
    if (videoFile) fd.append("video", videoFile);
    else for (const f of photoInput.files) fd.append("photos", f);
    if (videoFile && videoEdit)
      fd.append("video_edit", JSON.stringify(videoEdit));
    // The chosen size rides straight on /api/post now (spec 2026-07-14 -
    // the separate span-seed call is gone): a new post auto-places at the
    // top at these proportions, push-place applied. Text-only posts send
    // no chips (none shown) - the server's 4x1 text default applies.
    if (hasMedia) { fd.append("w", span.w); fd.append("h", span.h); }
    const r = await fetch("/api/post", {method: "POST", body: fd});
    if (!r.ok) { alert("Post failed: " + await r.text()); return; }
    input.value = "";
    photoInput.value = "";
    videoInput.value = "";
    videoEdit = null;
    clearPreview();
    span = {w: 2, h: 2};
    for (const x of chipBtns) {
      const isDefault = x.dataset.span === "2x2";
      x.classList.toggle("active", isDefault);
      x.setAttribute("aria-pressed", String(isDefault));
    }
    openProfile(STATE.identity_pub);                // re-render the wall
  };
  return form;
}

document.getElementById("profile-back").onclick = () => goView(PRIOR_VIEW);
// Arrange/Done toggle (self-only Wall canvas): wired once here, like the
// other topbar handlers - it reads CURRENT_PROFILE to know which profile to
// re-render against. Collage edition (Task 6): every pin/resize/nudge POSTs
// itself (/api/block-pin) the moment it happens, so Done has nothing left
// to publish - it just exits arrange mode and re-renders.
async function toggleArrange() {
  if (ARRANGING) {
    ARRANGING = false;
    if (CURRENT_PROFILE) await openProfile(CURRENT_PROFILE);
  } else {
    ARRANGING = true;
    if (CURRENT_PROFILE) await openProfile(CURRENT_PROFILE);   // re-render with controls
  }
}
document.getElementById("profile-arrange").onclick = toggleArrange;
// Mobile journal disclosure: the rail is always open on desktop (CSS hides
// the toggle there); on narrow screens it starts collapsed behind this button.
document.getElementById("profile-journal-toggle").onclick = () => {
  const rail = document.getElementById("profile-journal-rail");
  const btn = document.getElementById("profile-journal-toggle");
  const open = rail.classList.toggle("open");
  btn.setAttribute("aria-expanded", String(open));
};
document.addEventListener("keydown", (ev) => {
  if (ev.key === "Escape") { closeCircleOverlay(); }
});

// ---- Video editor (spec 2026-07-18): trim + crop + cover ------------
// The client SIMULATES the edit against a local <video> preview and
// returns PARAMETERS only - the node's videogate executes the real
// ffmpeg cut. onClose({action, edit}): "done" carries {start, duration,
// crop|null, poster_t}; "raw" means the engine can't even read metadata
// (post unedited - the node validates, today's behavior); "cancel"
// discards the pick.
const VE_MAX_WINDOW = 15;
function openVideoEditor(file, existing, onClose) {
  const ov = el("div"); ov.id = "video-editor";
  ov.setAttribute("role", "dialog");
  ov.setAttribute("aria-modal", "true");
  ov.setAttribute("aria-label", "Edit video");
  const stage = el("div", "ve-stage");
  const frame = el("div", "ve-frame");
  const vid = document.createElement("video");
  vid.muted = true; vid.playsInline = true;
  const url = URL.createObjectURL(file);
  vid.src = url;
  frame.append(vid); stage.append(frame);
  const strip = el("div", "ve-strip");
  const track = el("div", "ve-track");
  const selWin = el("div", "ve-window");
  const hL = el("div", "ve-handle ve-h-left");
  const hR = el("div", "ve-handle ve-h-right");
  hL.setAttribute("aria-label", "Trim start");
  hR.setAttribute("aria-label", "Trim end");
  strip.append(track, selWin, hL, hR);
  const cover = el("div", "ve-cover");
  cover.title = "Cover frame";
  cover.setAttribute("aria-label", "Cover frame");
  strip.append(cover);
  const times = el("div", "ve-times");
  const chips = el("div", "ve-chips");
  const ASPECTS = {"orig": null, "1:1": 1, "9:16": 9 / 16, "16:9": 16 / 9};
  const chipBtns = {};
  for (const key of Object.keys(ASPECTS)) {
    const c = el("button", "ve-chip", key === "orig" ? "Original" : key);
    c.type = "button";
    c.onclick = () => setAspect(key);
    chipBtns[key] = c;
    chips.append(c);
  }
  const btns = el("div", "ve-btns");
  const cancelB = el("button", "ve-btn", "Cancel"); cancelB.type = "button";
  const doneB = el("button", "ve-btn ve-done", "Done"); doneB.type = "button";
  // disabled until loadedmetadata inits start/end/crop - Done before that
  // posted the degenerate {start:0,duration:0} and let handles drag state
  // that hadn't been set up yet.
  doneB.disabled = true;
  btns.append(cancelB, doneB);
  ov.append(stage, strip, times, chips, btns);
  document.body.append(ov);

  let dur = 0, vw = 0, vh = 0, degraded = false, closed = false;
  let start = 0, end = 0, coverAbs = 0;               // trim + cover state
  let aspect = "orig", zoom = 1, cx = 0.5, cy = 0.5;  // crop state

  const fmt = (t) => t.toFixed(1) + "s";
  const finish = (action, edit) => {
    if (closed) return;
    closed = true;
    window.removeEventListener("resize", onResize);
    URL.revokeObjectURL(url);
    ov.remove();
    if (action === "done") onClose({action: "done", edit});
    else if (action === "raw") onClose({action: "raw"});
    else onClose({action: "cancel"});
  };
  cancelB.onclick = () => finish("cancel");
  doneB.onclick = () => finish("done", buildEdit());
  ov.addEventListener("keydown", (e) => {
    if (e.key === "Escape") finish("cancel");
  });
  // viewport resize can change frame.clientWidth, which the crop transform
  // (applyCrop) is computed from - without a refresh here, a stale
  // width/height would letterbox or misalign the preview after a resize.
  const onResize = () => applyCrop();
  window.addEventListener("resize", onResize);

  function buildEdit() {
    return {start: Math.round(start * 1000) / 1000,
            duration: Math.round((end - start) * 1000) / 1000,
            crop: cropRect(),
            poster_t: Math.round((coverAbs - start) * 1000) / 1000};
  }
  // -- crop model: aspect presets + pan/zoom (spec 2026-07-18). The
  // crop rect is DERIVED (never stored): largest target-aspect rect in
  // the display-oriented frame, shrunk by zoom, centered on (cx, cy),
  // clamped inside. cropRect() is the single source of the wire value
  // AND the preview transform - they cannot disagree.
  function cropRect() {
    const r = ASPECTS[aspect];
    if (r == null || !vw || !vh) return null;
    const baseW = Math.min(1, (r * vh) / vw);
    const baseH = Math.min(1, vw / (r * vh));
    // zoomBy/restoreCrop already cap zoom so neither term below reaches
    // the floor - these 0.1s are just the validator's own min-crop
    // backstop (server rejects w/h < 0.1), not the aspect guard itself.
    const w = Math.max(0.1, baseW / zoom);
    const h = Math.max(0.1, baseH / zoom);
    const x = Math.min(Math.max(cx - w / 2, 0), 1 - w);
    const y = Math.min(Math.max(cy - h / 2, 0), 1 - h);
    return {x: Math.round(x * 1e6) / 1e6, y: Math.round(y * 1e6) / 1e6,
            w: Math.round(w * 1e6) / 1e6, h: Math.round(h * 1e6) / 1e6};
  }
  function applyCrop() {
    const c = cropRect();
    if (!c) {
      frame.style.aspectRatio = (vw || 16) + " / " + (vh || 9);
      vid.style.cssText = "";
      return;
    }
    frame.style.aspectRatio = (c.w * vw) + " / " + (c.h * vh);
    const W = frame.clientWidth;
    const dw = W / c.w;                       // displayed full-video width
    const dh = dw * vh / vw;
    vid.style.width = dw + "px";
    vid.style.height = dh + "px";
    vid.style.maxWidth = "none"; vid.style.maxHeight = "none";
    vid.style.objectFit = "fill";
    vid.style.left = (-c.x * dw) + "px";
    vid.style.top = (-c.y * dh) + "px";
  }
  function setAspect(key) {
    aspect = key;
    if (key === "orig") { zoom = 1; cx = 0.5; cy = 0.5; }
    for (const [k, b] of Object.entries(chipBtns))
      b.classList.toggle("active", k === aspect);
    applyCrop();
  }
  function initCrop() { setAspect(aspect); }
  function restoreCrop(c) {
    if (!c) { aspect = "orig"; return; }
    // guard against a degenerate stored rect (non-numeric, or a zero/negative
    // w/h) - without this, a corrupt existing.crop poisons zoom with NaN,
    // which then survives (uncorrected) into the next preset click, since
    // setAspect only resets zoom when switching TO "orig".
    if (![c.x, c.y, c.w, c.h].every(Number.isFinite) || !(c.w > 0) || !(c.h > 0)) {
      aspect = "orig"; return;
    }
    // recover the nearest preset from the rect's real aspect
    const ratio = (c.w * vw) / (c.h * vh);
    let best = "orig", err = Infinity;
    for (const [k, r] of Object.entries(ASPECTS)) {
      if (r == null) continue;
      if (Math.abs(r - ratio) < err) { err = Math.abs(r - ratio); best = k; }
    }
    aspect = best;
    const baseW = Math.min(1, (ASPECTS[best] * vh) / vw);
    const baseH = Math.min(1, vw / (ASPECTS[best] * vh));
    // same per-axis cap as zoomBy - a stored rect from a since-changed
    // zoom ceiling must not recover a zoom that would distort the aspect.
    // Math.max(1, ...) stays OUTSIDE the cap, same reason as zoomBy.
    zoom = Math.max(1, Math.min(10, baseW * 10, baseH * 10, baseW / c.w));
    if (!Number.isFinite(zoom)) zoom = 1;
    cx = c.x + c.w / 2; cy = c.y + c.h / 2;
  }

  // pan (single pointer) + pinch (two pointers) + wheel zoom
  const pointers = new Map();
  frame.addEventListener("pointerdown", (e) => {
    if (aspect === "orig" || degraded) return;
    e.preventDefault();
    frame.setPointerCapture(e.pointerId);
    pointers.set(e.pointerId, {x: e.clientX, y: e.clientY});
  });
  frame.addEventListener("pointermove", (e) => {
    if (!pointers.has(e.pointerId)) return;
    const prev = pointers.get(e.pointerId);
    const cur = {x: e.clientX, y: e.clientY};
    if (pointers.size === 2) {
      const other = [...pointers.entries()]
        .find(([id]) => id !== e.pointerId)[1];
      const d0 = Math.hypot(prev.x - other.x, prev.y - other.y);
      const d1 = Math.hypot(cur.x - other.x, cur.y - other.y);
      if (d0 > 0) zoomBy(d1 / d0);
    } else {
      const c = cropRect();
      if (c) {
        const dw = frame.clientWidth / c.w;
        cx = Math.min(1, Math.max(0, cx - (cur.x - prev.x) / dw));
        cy = Math.min(1, Math.max(0, cy - (cur.y - prev.y) / (dw * vh / vw)));
        applyCrop();
      }
    }
    pointers.set(e.pointerId, cur);
  });
  const dropPointer = (e) => pointers.delete(e.pointerId);
  frame.addEventListener("pointerup", dropPointer);
  frame.addEventListener("pointercancel", dropPointer);
  function zoomBy(f) {
    // cap per-axis, not just at 10x flat: cropRect's independent 0.1 floors
    // on w and h mean the SHORTER axis of a non-square aspect hits its floor
    // first, so past that point only the other axis kept shrinking and the
    // chosen aspect silently distorted. Capping zoom so neither baseW/zoom
    // nor baseH/zoom can reach the floor keeps the preset honest at max zoom.
    const r = ASPECTS[aspect];
    const baseW = (r != null && vw && vh) ? Math.min(1, (r * vh) / vw) : 1;
    const baseH = (r != null && vw && vh) ? Math.min(1, vw / (r * vh)) : 1;
    // Math.max(1, ...) OUTSIDE the cap: an extreme-aspect source can put
    // baseW*10 or baseH*10 below 1, and clamping zoom below 1 there would
    // let the OTHER axis inflate past the frame - cropRect would then emit
    // an out-of-bounds rect and Done's node-side validate would 400.
    zoom = Math.max(1, Math.min(10, baseW * 10, baseH * 10, zoom * f));
    applyCrop();
  }
  frame.addEventListener("wheel", (e) => {
    if (aspect === "orig" || degraded) return;
    e.preventDefault();
    zoomBy(e.deltaY < 0 ? 1.08 : 1 / 1.08);
  }, {passive: false});

  // -- cover marker: draggable within the trim window; seeks the
  // preview so the user sees the exact poster frame.
  function renderCover() {
    cover.style.left = px(coverAbs) + "px";
  }
  cover.addEventListener("pointerdown", (ev) => {
    if (!ev.isPrimary) return;
    ev.preventDefault();
    cover.setPointerCapture(ev.pointerId);
    vid.pause();
    const move = (e) => {
      const t = toT(e.clientX - strip.getBoundingClientRect().left);
      coverAbs = Math.min(Math.max(t, start), end);
      vid.currentTime = coverAbs;
      renderCover();
    };
    const up = () => {
      cover.removeEventListener("pointermove", move);
      cover.removeEventListener("pointerup", up);
      cover.removeEventListener("pointercancel", cancel);
    };
    // mirrors dragHandle's cancel (45455ab): without it, an interrupted
    // drag (e.g. OS gesture takeover) never removes move/up and leaks.
    const cancel = () => {
      cover.removeEventListener("pointermove", move);
      cover.removeEventListener("pointerup", up);
      cover.removeEventListener("pointercancel", cancel);
    };
    cover.addEventListener("pointermove", move);
    cover.addEventListener("pointerup", up);
    cover.addEventListener("pointercancel", cancel);
  });

  // -- trim geometry: strip x-position <-> time -----------------------
  const px = (t) => strip.clientWidth * t / dur;
  const toT = (x) => Math.min(dur, Math.max(0, x / strip.clientWidth * dur));
  function render() {
    selWin.style.left = px(start) + "px";
    selWin.style.width = Math.max(2, px(end) - px(start)) + "px";
    hL.style.left = px(start) + "px";
    hR.style.left = px(end) + "px";
    renderCover();
    times.textContent = fmt(start) + " - " + fmt(end)
      + "  (" + fmt(end - start) + " of " + fmt(dur) + ")";
  }

  function dragHandle(handle, isLeft) {
    handle.addEventListener("pointerdown", (ev) => {
      if (!ev.isPrimary) return;
      ev.preventDefault();
      handle.setPointerCapture(ev.pointerId);
      const move = (e) => {
        const t = toT(e.clientX - strip.getBoundingClientRect().left);
        if (isLeft) {
          start = Math.min(t, end - 0.5);
          start = Math.max(start, end - VE_MAX_WINDOW);
          start = Math.max(0, start);     // sub-0.5s source: end-0.5 goes negative
        } else {
          end = Math.max(t, start + 0.5);
          end = Math.min(end, start + VE_MAX_WINDOW);
        }
        coverAbs = Math.min(Math.max(coverAbs, start), end);
        vid.currentTime = isLeft ? start : Math.max(start, end - 0.1);
        render();
      };
      const up = () => {
        handle.removeEventListener("pointermove", move);
        handle.removeEventListener("pointerup", up);
        handle.removeEventListener("pointercancel", cancel);
        vid.currentTime = start; vid.play();
      };
      const cancel = () => {
        handle.removeEventListener("pointermove", move);
        handle.removeEventListener("pointerup", up);
        handle.removeEventListener("pointercancel", cancel);
      };
      handle.addEventListener("pointermove", move);
      handle.addEventListener("pointerup", up);
      handle.addEventListener("pointercancel", cancel);
    });
  }
  dragHandle(hL, true);
  dragHandle(hR, false);

  // loop playback inside the window
  vid.addEventListener("timeupdate", () => {
    if (vid.currentTime >= end - 0.03 || vid.currentTime < start - 0.25)
      vid.currentTime = start;
  });

  async function buildThumbs() {
    const tv = document.createElement("video");
    tv.muted = true; tv.preload = "auto"; tv.src = url;
    await new Promise((res, rej) => {
      tv.onloadeddata = res; tv.onerror = rej;
    });
    if (!tv.videoWidth) throw new Error("no frames");
    const N = 8, w = 88,
          h = Math.max(1, Math.round(w * tv.videoHeight / tv.videoWidth));
    for (let i = 0; i < N; i++) {
      const t = Math.min(dur - 0.05, (i + 0.5) * dur / N);
      // race the seek against a decode-error / stall timeout - without this,
      // a mid-stream decode error after earlier frames succeeded leaves
      // onseeked never firing and the loop (and degraded-mode fallback)
      // hung forever.
      await new Promise((res, rej) => {
        const to = setTimeout(() => rej(new Error("seek stall")), 4000);
        tv.onseeked = () => { clearTimeout(to); res(); };
        tv.onerror = () => { clearTimeout(to); rej(new Error("decode error")); };
        tv.currentTime = t;
      });
      const c = document.createElement("canvas");
      c.width = w; c.height = h; c.className = "ve-thumb";
      c.getContext("2d").drawImage(tv, 0, 0, w, h);
      track.append(c);
    }
  }

  vid.addEventListener("error", () => {
    // engine can't read the file at all -> today's behavior (raw post)
    if (!dur) finish("raw");
  });
  vid.addEventListener("loadedmetadata", () => {
    dur = vid.duration; vw = vid.videoWidth; vh = vid.videoHeight;
    if (!isFinite(dur) || dur <= 0) { finish("raw"); return; }
    if (existing) {
      // floor every restored value - existing comes from a prior save (or,
      // once Task 6 lands, arbitrary stored state) and must not be trusted
      // to already respect this video's own duration/window bounds. Fall
      // back non-finite fields (NaN/undefined/Infinity) to sane defaults
      // before the clamp math, so a corrupt existing edit can't poison
      // start/end/coverAbs with NaN.
      const exStart = Number.isFinite(existing.start) ? existing.start : 0;
      const exDuration = Number.isFinite(existing.duration) ? existing.duration : VE_MAX_WINDOW;
      const exPosterT = Number.isFinite(existing.poster_t) ? existing.poster_t : 0;
      start = Math.min(Math.max(0, exStart), Math.max(0, dur - 0.5));
      end = Math.min(dur, start + Math.min(Math.max(exDuration, 0.5), VE_MAX_WINDOW));
      coverAbs = start + Math.min(Math.max(exPosterT, 0), end - start);
      restoreCrop(existing.crop);
    } else {
      start = 0; end = Math.min(dur, VE_MAX_WINDOW); coverAbs = 0;
    }
    initCrop();
    render();
    doneB.disabled = false;   // state is real now - both happy and
                              // degraded-rung-1 (buildThumbs still pending)
                              // paths reach here; metadata-fail path never
                              // needs Done, it already finished "raw" above
    vid.currentTime = start;
    vid.play();
    buildThumbs().catch(() => {
      // metadata but no decodable frames (or a mid-stream decode failure
      // after some frames already landed): slider-only degraded mode -
      // trim still works blind against the times readout; crop stays
      // disabled (can't position what you can't see). Spec 2026-07-18.
      degraded = true;
      setAspect("orig");                     // neutralize crop too - a chip
                                              // picked before decode failed
                                              // must not leave a live pan/zoom
                                              // state the user can't see to fix
      track.replaceChildren();               // drop any partial thumbs
      track.classList.add("ve-blank");
      chips.classList.add("ve-disabled");
      frame.classList.add("ve-noframes");
    });
  });
  ov.tabIndex = -1;
  ov.focus();
}

// ---------------------------------------------------------------------
// Stories (unchanged behavior, re-homed into the journal view)
// ---------------------------------------------------------------------

function seenSet() {
  try { return new Set(JSON.parse(localStorage.getItem("kreds_seen") || "[]")); }
  catch (e) { return new Set(); }
}
function markSeen(ids) {
  const s = seenSet(); ids.forEach(i => s.add(i));
  localStorage.setItem("kreds_seen", JSON.stringify([...s]));
}

async function renderStories() {
  let strip = document.getElementById("stories");
  if (!strip) {
    strip = el("div"); strip.id = "stories";
    const journal = document.querySelector("#view-journal .journal");
    journal.insertBefore(strip, journal.firstChild);
  }
  const groups = await j("/api/stories");
  const liveIds = new Set(groups.flatMap(g => g.items.map(i => i.msg_id)));
  const pruned = [...seenSet()].filter(id => liveIds.has(id));
  localStorage.setItem("kreds_seen", JSON.stringify(pruned));
  const seen = seenSet();
  strip.replaceChildren();
  // own "add" tile always first
  const me = groups.find(g => g.mine);
  const addTile = el("div", "story-tile");
  const addRing = el("div", "story-ring add");
  addRing.textContent = "+";
  const addInput = document.createElement("input");
  addInput.type = "file"; addInput.accept = "image/*,video/*";
  addInput.style.display = "none";
  const addName = el("div", "story-name", "Your story");
  addInput.onchange = () => {
    const file = addInput.files[0];
    if (!file) return;
    const isVideo = !file.type.startsWith("image/");
    if (!isVideo) { uploadStory(file, null); return; }
    openVideoEditor(file, null, (res) => {
      if (res.action === "cancel") { addInput.value = ""; return; }
      uploadStory(file, res.action === "done" ? res.edit : null);
    });
  };
  async function uploadStory(file, edit) {
    const isVideo = !file.type.startsWith("image/");
    addRing.classList.add("busy");
    addRing.textContent = "";
    addName.textContent = isVideo ? "Processing video..." : "Uploading...";
    const fd = new FormData(); fd.append("media", file);
    fd.append("caption", "");
    if (edit) fd.append("video_edit", JSON.stringify(edit));
    try {
      const r = await fetch("/api/story", {method: "POST", body: fd});
      if (r.ok) { renderStories(); return; }
      const body = await r.text();
      if (r.status === 400 && /longer than 15/i.test(body))
        // only reachable on the raw/degraded path (no editor preview)
        alert("That video is longer than 15 seconds and could not be "
          + "previewed for trimming here. Please shorten it and try again.");
      else if (r.status === 413)
        // DRAFT copy - surface the node's own detail instead of a bare cap
        alert("That file is too large. " + body);
      else alert("Could not post story: " + body);
    } catch (e) {
      alert("Could not post story: " + e.message);
    }
    addInput.value = "";
    renderStories();
  }
  addRing.onclick = () => addInput.click();
  addTile.append(addRing, addInput, addName);
  strip.append(addTile);
  for (const g of groups) {
    if (g.mine && (!me || !me.items.length)) continue;
    const unseen = !g.mine && g.items.some(i => !seen.has(i.msg_id));
    const tile = el("div", "story-tile");
    const ring = el("div", "story-ring" + (unseen ? " unseen" : ""));
    if (g.avatar) { const im = document.createElement("img");
      im.src = "/api/blob/" + g.avatar; ring.append(im); }
    else ring.textContent = (g.name || "?").slice(0, 1).toUpperCase();
    ring.onclick = () => openStoryViewer(groups, g.identity_pub);
    tile.append(ring, el("div", "story-name", g.mine ? "You" : g.name));
    strip.append(tile);
  }
}

function openStoryViewer(groups, startIdentity) {
  const flat = [];
  for (const g of groups)
    for (const it of g.items) flat.push({...it, name: g.mine ? "You" : g.name});
  let idx = flat.findIndex(x =>
    groups.find(g => g.identity_pub === startIdentity).items
      .some(i => i.msg_id === x.msg_id));
  if (idx < 0) idx = 0;
  const ov = el("div"); ov.id = "story-viewer";
  let timer = null;
  function show() {
    if (idx < 0 || idx >= flat.length) { close(); return; }
    const it = flat[idx];
    markSeen([it.msg_id]);
    ov.replaceChildren();
    const bar = el("div", "sv-bars");
    flat.forEach((_, i) => bar.append(
      el("div", "sv-bar" + (i < idx ? " done" : i === idx ? " active" : ""))));
    const media = el("div", "sv-media");
    if (it.media_kind === "video") {
      const v = document.createElement("video");
      v.src = "/api/blob/" + it.media; v.autoplay = true; v.muted = true;
      v.playsInline = true; v.onended = next; v.onerror = next; media.append(v);
      v.onclick = () => { v.muted = !v.muted; };
    } else {
      const im = document.createElement("img"); im.src = "/api/blob/" + it.media;
      media.append(im); clearTimeout(timer); timer = setTimeout(next, 5000);
    }
    const cap = it.caption ? el("div", "sv-cap", it.caption) : null;
    const who = el("div", "sv-who", it.name);
    const x = el("div", "sv-close", "×"); x.onclick = close;
    const L = el("div", "sv-nav sv-left"); L.onclick = prev;
    const R = el("div", "sv-nav sv-right"); R.onclick = next;
    ov.append(bar, who, x, media, L, R);
    if (cap) ov.append(cap);
  }
  function next() { clearTimeout(timer); idx++; show(); }
  function prev() { clearTimeout(timer); idx = Math.max(0, idx - 1); show(); }
  function close() { clearTimeout(timer); ov.remove();
    document.body.classList.remove("sv-open"); renderStories(); }
  document.body.append(ov); document.body.classList.add("sv-open");
  show();
}

// Fullscreen photo lightbox: click a photo in a profile photo block to enlarge
// it and swipe/arrow through its items (generalized in Slice C: a plain
// post's own blobs, or an album's cross-post photos - both arrive as a
// uniform [{m, h}] list via blockPhotoItems). Fit-to-screen, no zoom;
// clamped at the ends; view-only (own + others). Mirrors #story-viewer.
function openLightbox(items, index, opener) {
  if (!items || !items.length) return;
  let i = Math.max(0, Math.min(index, items.length - 1));
  opener = opener || document.activeElement;          // restore focus on close (the clicked photo)
  const ov = el("div"); ov.id = "lightbox";
  ov.setAttribute("role", "dialog");
  ov.setAttribute("aria-modal", "true");
  ov.setAttribute("aria-label", "Photo");
  const img = document.createElement("img"); img.id = "lightbox-img"; img.alt = "";
  const prev = el("button", "lb-nav lb-prev", "‹"); prev.type = "button";
  prev.setAttribute("aria-label", "Previous photo");
  const next = el("button", "lb-nav lb-next", "›"); next.type = "button";
  next.setAttribute("aria-label", "Next photo");
  const count = el("div", "lb-count"); count.id = "lightbox-count";
  const x = el("button", "lb-close", "×"); x.type = "button";
  x.setAttribute("aria-label", "Close");
  ov.append(img, prev, next, count, x);

  function render() {
    // items may carry a precomputed src (DM photos live on /api/dm-blob);
    // post/wall callers keep the post-blob default.
    img.src = items[i].src ||
      ("/api/post-blob/" + items[i].m + "/" + items[i].h);
    count.textContent = (i + 1) + " / " + items.length;
    const multi = items.length > 1;
    prev.style.display = next.style.display = count.style.display = multi ? "" : "none";
    prev.disabled = i === 0;
    next.disabled = i === items.length - 1;
  }
  function go(d) { i = Math.max(0, Math.min(i + d, items.length - 1)); render(); }
  function close() {
    document.removeEventListener("keydown", onKey);
    ov.remove(); document.body.classList.remove("lb-open");
    if (opener && opener.isConnected && opener.focus) opener.focus();
  }
  function onKey(e) {
    if (e.key === "Escape") close();
    else if (e.key === "ArrowLeft") go(-1);
    else if (e.key === "ArrowRight") go(1);
  }
  prev.onclick = () => go(-1);
  next.onclick = () => go(1);
  x.onclick = close;
  ov.addEventListener("click", (e) => { if (e.target === ov) close(); });   // backdrop
  let sx = null;                                                            // touch swipe
  ov.addEventListener("pointerdown", (e) => { sx = e.clientX; });
  ov.addEventListener("pointerup", (e) => {
    if (sx == null) return;
    const dx = e.clientX - sx; sx = null;
    if (Math.abs(dx) > 40) go(dx < 0 ? 1 : -1);
  });
  document.addEventListener("keydown", onKey);
  document.body.append(ov); document.body.classList.add("lb-open");
  render(); x.focus();
}

// ---------------------------------------------------------------------
// Chrome (nav whoami + identity strip) + Me strip (friends, devices - now
// sections on the self-only Settings page (spec 2026-07-15); ceremony
// + profile-editor functions preserved below)
// ---------------------------------------------------------------------

function renderChrome() {
  document.getElementById("profile-name").textContent = STATE.profile_name;
  document.getElementById("device-name").textContent = STATE.device_name;
  document.getElementById("idstrip-fp").textContent =
    "identity " + STATE.identity_pub;
  const n = STATE.devices.length;
  document.getElementById("idstrip-devcount").textContent =
    n + (n === 1 ? " device" : " devices");
}

function renderMeStrip() {
  if (!STATE) return;
  const friends = document.getElementById("friends");
  friends.replaceChildren();
  if (!STATE.friends.length) friends.append(el("div", "hint",
    "No friends yet. Share a code below - in person or over a chat you already trust."));
  for (const f of STATE.friends) {
    const row = el("div", "friend");
    row.append(el("span", "", f.name),
               el("span", "dim tiny", f.identity_pub.slice(0, 8)));
    row.style.cursor = "pointer";
    row.onclick = () => openProfile(f.identity_pub);
    friends.append(row);
  }
  // People who left (either side unfriended): an inert row, no Message -
  // just the marker, matching the profile page's disconnected state.
  for (const d of (STATE.disconnected || [])) {
    const row = el("div", "friend disconnected");
    row.append(el("span", "", d.name), el("span", "dim tiny", "no longer connected"));
    friends.append(row);
  }

  const devices = document.getElementById("devices");
  devices.replaceChildren();
  for (const d of STATE.devices) {
    const row = el("div", "device" + (d.revoked ? " revoked" : ""));
    row.append(el("span", "",
      d.name + (d.this_device ? " (this device)" : "")));
    if (!d.revoked && !d.this_device) {
      const btn = el("button", "", "revoke");
      btn.onclick = async () => {
        await j("/api/device/revoke", {method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify({device_pub: d.device_pub})});
        refresh();
      };
      row.append(btn);
    }
    devices.append(row);
  }

  renderApplockSettings();   // self-only App-lock panel (not awaited - independent of the rest)
  renderDesktopSettings();   // self-only, desktop-only close-behavior toggle (Task 3)
  renderUpdateSettings();    // self-only Updates panel (Task 3)
}

// ---------------------------------------------------------------------
// First-run onboarding (Task 2): GET /api/bootstrap said
// initialized:false - this node has no enrolled identity yet (a brand
// new data dir, or one mid a Connect pairing). Builds Create New Node /
// Connect to Existing into the static #fr-create-panel / #fr-connect-panel
// shells (index.html), then drives the bootstrap app's endpoints and
// polls across the bootstrap->full-node handoff before reloading into the
// now-running full app. No secret (name, pairing code/package) is ever
// persisted client-side - each lives only in its input until submitted.
// ---------------------------------------------------------------------

// The bootstrap->full-app handoff closes the bootstrap uvicorn and starts
// the full node app on the SAME port (see runner.run_serve). The full app
// only binds after Tor bootstrap + onion publish, which can take minutes
// cold - so poll FOREVER with backoff (500ms -> 2s) and surface the
// desktop shell's startup stage when available (pywebview api; absent in
// a plain browser, where the text fallback carries it).
async function pollForFullApp(statusEl) {
  let delay = 500;
  while (true) {
    try {
      const r = await fetch("/api/state");
      if (r.ok) { location.reload(); return; }
    } catch (e) { /* mid-handoff / tor still bootstrapping - keep polling */ }
    if (statusEl) {
      let msg = "Starting your node - this can take a few minutes...";
      try {
        const s = await window.pywebview?.api?.get_startup_status?.();
        if (s && s.stage === "tor-bootstrap" && s.pct != null)
          msg = "Connecting to Tor - " + s.pct + "%";
        else if (s && s.stage === "tor-waiting")
          msg = "Waiting for a previous Kreds to finish closing...";
        else if (s && s.stage === "failed")
          msg = "Startup hit a problem - see app.log in the Kreds data folder.";
      } catch (e) { /* bridge absent (plain browser): keep the fallback text */ }
      statusEl.textContent = msg;
    }
    await new Promise(res => setTimeout(res, delay));
    delay = Math.min(delay * 1.5, 2000);
  }
}

function renderFirstRun() {
  // Mirrors renderLockScreen's hide pattern: #app + the mobile tabbar
  // (which lives outside #app) must be actually hidden, not just covered,
  // so Tab/AT can't reach controls behind the gate.
  document.getElementById("app").classList.add("hidden");
  const tabbar = document.querySelector(".tabbar-mobile");
  if (tabbar) tabbar.classList.add("hidden");
  document.getElementById("first-run").classList.remove("hidden");

  // -- Create New Node --
  const createPanel = document.getElementById("fr-create-panel");
  createPanel.replaceChildren();
  const createForm = document.createElement("form");
  const nameInput = document.createElement("input");
  nameInput.type = "text";
  nameInput.placeholder = "Your name";
  nameInput.autocomplete = "name";
  nameInput.required = true;
  nameInput.setAttribute("aria-label", "Your name");
  const createBtn = el("button", "btn-accent", "Create");
  createBtn.type = "submit";
  const createStatus = el("div", "hint");
  createForm.append(nameInput, createBtn, createStatus);
  createForm.onsubmit = async (ev) => {
    ev.preventDefault();
    const name = nameInput.value.trim();
    if (!name) return;
    nameInput.disabled = true;
    createBtn.disabled = true;
    createStatus.textContent = "Setting up...";
    try {
      const r = await fetch("/api/bootstrap/create", {method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({name, device: "desktop"})});
      if (!r.ok) throw new Error((await r.json().catch(() => ({}))).detail || r.statusText);
      await pollForFullApp(createStatus);
    } catch (e) {
      createStatus.textContent = "Could not create node: " + e.message;
      nameInput.disabled = false;
      createBtn.disabled = false;
    }
  };
  createPanel.append(createForm);

  // -- Connect to Existing Node --
  const connectPanel = document.getElementById("fr-connect-panel");
  connectPanel.replaceChildren();
  const startBtn = el("button", "btn-accent", "Get pairing code");
  const connectStatus = el("div", "hint");
  connectPanel.append(startBtn, connectStatus);
  startBtn.onclick = async () => {
    startBtn.disabled = true;
    try {
      const r = await fetch("/api/bootstrap/pair-request", {method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({device: "desktop"})});
      if (!r.ok) throw new Error((await r.json().catch(() => ({}))).detail || r.statusText);
      const {request} = await r.json();
      startBtn.remove();
      connectStatus.textContent = "On your other device: Settings -> add device, "
        + "paste this, paste the result back below.";
      const reqField = document.createElement("input");
      reqField.type = "text";
      reqField.readOnly = true;
      reqField.value = request;
      reqField.setAttribute("aria-label", "Pairing request - copy this to your other device");
      reqField.onfocus = () => reqField.select();
      const pkgForm = document.createElement("form");
      const pkgArea = document.createElement("textarea");
      pkgArea.rows = 4;
      pkgArea.placeholder = "Paste the result from your other device here";
      pkgArea.setAttribute("aria-label", "Pairing package from your other device");
      const installBtn = el("button", "btn-accent", "Connect");
      installBtn.type = "submit";
      const installStatus = el("div", "hint");
      pkgForm.append(pkgArea, installBtn, installStatus);
      pkgForm.onsubmit = async (ev) => {
        ev.preventDefault();
        const pkg = pkgArea.value.trim();
        if (!pkg) return;
        pkgArea.disabled = true;
        installBtn.disabled = true;
        installStatus.textContent = "Connecting...";
        const ir = await fetch("/api/bootstrap/pair-install", {method: "POST",
          headers: {"Content-Type": "application/json"},
          body: JSON.stringify({package: pkg})});
        if (ir.ok) {
          await pollForFullApp(installStatus);
        } else {
          const body = await ir.json().catch(() => ({}));
          alert("Could not connect: " + (body.detail || ir.statusText));
          installStatus.textContent = "";
          pkgArea.disabled = false;
          installBtn.disabled = false;
        }
      };
      connectPanel.append(reqField, pkgForm);
      reqField.focus();
    } catch (e) {
      connectStatus.textContent = "Could not start pairing: " + e.message;
      startBtn.disabled = false;
    }
  };

  nameInput.focus();   // keyboard-accessible: land in the primary (Create) field on show
}

// ---------------------------------------------------------------------
// App-lock (Task 4): lock screen + self-only settings. The node
// (hearth/node.py) is the sole source of truth for lock state - the
// client only reflects GET /api/applock and reacts when the node says
// locked (initial boot, an in-session 423 on any call, or the node's own
// idle/sleep autolock noticed via the hooks below). The credential itself
// is NEVER persisted client-side (no localStorage/sessionStorage) - it is
// read from the input and sent only in the one POST body that needs it.
// ---------------------------------------------------------------------

const IDLE_OPTIONS = [[0, "Off"], [5, "5 minutes"], [10, "10 minutes"], [15, "15 minutes"]];
let LOCK_THROTTLE_TIMER = null;     // countdown interval while unlock is throttled
let APPLOCK_HEARTBEAT_LAST = null;  // wall-clock reference for the sleep-jump heartbeat

async function getApplockStatus() {
  const r = await fetch("/api/applock");
  if (!r.ok) return {enabled: false, locked: false, cred_type: null,
                      settings: {idle_minutes: 0, lock_on_sleep: false}, throttle_wait: 0};
  return r.json();
}

function applyThrottle(seconds) {
  clearInterval(LOCK_THROTTLE_TIMER);
  LOCK_THROTTLE_TIMER = null;
  const submit = document.getElementById("lock-submit");
  const err = document.getElementById("lock-error");
  let remaining = Math.ceil(seconds || 0);
  const tick = () => {
    if (remaining <= 0) {
      submit.disabled = false;
      if (LOCK_THROTTLE_TIMER) { clearInterval(LOCK_THROTTLE_TIMER); LOCK_THROTTLE_TIMER = null; }
      return;
    }
    submit.disabled = true;
    err.textContent = "Too many attempts - try again in " + remaining + "s.";
    remaining--;
  };
  if (remaining > 0) { tick(); LOCK_THROTTLE_TIMER = setInterval(tick, 1000); }
  else submit.disabled = false;
}

// Renders/updates the full-screen gate. Safe to call repeatedly (the
// heartbeat/visibilitychange hooks and the 423 handler all call this) -
// only resets the input/focus/error on the FIRST show, so a repeated call
// (e.g. just to refresh the throttle countdown) doesn't yank focus or
// clobber an in-progress PIN entry.
function renderLockScreen(status) {
  const screen = document.getElementById("lock-screen");
  const wasHidden = screen.classList.contains("hidden");
  screen.classList.remove("hidden");
  // The overlay is opaque, but position:fixed does NOT remove #app's
  // controls from the Tab order or from a screen reader's tree - without
  // actually hiding #app (+ the mobile tabbar, which lives outside #app),
  // Tab/AT could still reach the composer/nav behind the gate. Mirrors the
  // existing revoked-banner treatment in refresh() below.
  document.getElementById("app").classList.add("hidden");
  const tabbar = document.querySelector(".tabbar-mobile");
  if (tabbar) tabbar.classList.add("hidden");
  // Whole-branch review, IMPORTANT #2: the one-time onboarding wizard
  // shares #app's z-index and is NOT hidden by the lines above (it lives
  // outside #app) - if App-lock got enabled at wizard Step A and the node
  // autolocks (idle/sleep) before the wizard finishes, the wizard would
  // otherwise paint over this lock screen with dead controls. Hide it too,
  // and drop its Esc listener (mirrors how renderOnboardingWizard adds it)
  // so a stray Esc while locked can't reach finishOnboardingWizard.
  document.getElementById("onboarding-wizard").classList.add("hidden");
  document.removeEventListener("keydown", onboardingWizardKeydown);
  const cred = document.getElementById("lock-cred");
  const isPin = status.cred_type === "pin";
  document.getElementById("lock-keypad").classList.toggle("hidden", !isPin);
  cred.inputMode = isPin ? "numeric" : "text";
  cred.placeholder = isPin ? "PIN" : "Passphrase";
  cred.setAttribute("aria-label", isPin ? "PIN" : "Passphrase");
  if (wasHidden) {
    cred.value = "";
    document.getElementById("lock-error").textContent = "";
  }
  applyThrottle(status.throttle_wait || 0);
  if (wasHidden) cred.focus();   // focus the field on show (keyboard-accessible)
}

function hideLockScreen() {
  document.getElementById("lock-screen").classList.add("hidden");
  // Restoring #app/.tabbar-mobile here is safe even in the revoked edge
  // case: bootAfterUnlock()'s refresh() runs right after this and will
  // re-hide them if STATE.revoked (a revoked node can't have App-lock
  // enabled anyway - enter_revoked_state deletes applock.json).
  document.getElementById("app").classList.remove("hidden");
  const tabbar = document.querySelector(".tabbar-mobile");
  if (tabbar) tabbar.classList.remove("hidden");
  clearInterval(LOCK_THROTTLE_TIMER);
  LOCK_THROTTLE_TIMER = null;
}

// Re-checks the node and shows the gate if it now says locked - shared by
// the 423 handler, the visibility hook, and the heartbeat below. Never
// hides an already-shown gate on its own (only a successful /api/unlock
// does that) - a stray non-423 response while locked must not race the
// gate away.
async function recheckApplock() {
  const status = await getApplockStatus();
  if (status.locked) renderLockScreen(status);
  return status;
}

// Any fetch getting a 423 means the node considers us locked right now
// (idle/sleep autolock decided server-side, or another surface locked it)
// - wrapping window.fetch ONCE here means no individual call site (post,
// DM send, block-size, etc.) needs its own 423 check.
(function installApplockGate() {
  const realFetch = window.fetch.bind(window);
  window.fetch = async function (...args) {
    const r = await realFetch(...args);
    // Not awaited (must not delay returning the response) and not allowed
    // to become an unhandled rejection if the status re-check itself
    // fails to reach the node (whole-branch review, minor #10).
    if (r.status === 423) recheckApplock().catch(() => {});
    return r;
  };
})();

document.getElementById("lock-keypad").addEventListener("click", (ev) => {
  const btn = ev.target.closest("button");
  if (!btn) return;
  const cred = document.getElementById("lock-cred");
  if (btn.id === "lock-backspace") cred.value = cred.value.slice(0, -1);
  else if (btn.id === "lock-clear") cred.value = "";
  else if (btn.dataset.digit !== undefined) cred.value += btn.dataset.digit;
  cred.focus();
});

document.getElementById("lock-form").onsubmit = async (ev) => {
  ev.preventDefault();
  const cred = document.getElementById("lock-cred");
  const err = document.getElementById("lock-error");
  const submit = document.getElementById("lock-submit");
  const credential = cred.value;
  if (!credential) { err.textContent = "Enter your credential."; return; }
  submit.disabled = true;
  try {
    const r = await fetch("/api/unlock", {method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({credential})});
    const body = await r.json();
    cred.value = "";
    if (r.ok) {
      err.textContent = "";
      hideLockScreen();
      await bootAfterUnlock();
    } else {
      err.textContent = body.detail === "throttled"
        ? "Too many attempts - please wait."
        : "Wrong credential.";
      applyThrottle(body.throttle_wait || 0);
      cred.focus();
    }
  } catch (e) {
    err.textContent = "Could not reach the node: " + e.message;
    submit.disabled = false;
  }
};

// Client hooks (node is the source of truth; these only prompt a re-check):
// (a) tab going to background - so the gate is already correct by the time
//     the user comes back, instead of flashing stale content first; and
// (b) a heartbeat that notices the wall clock jumped further than its own
//     tick could account for - the same "process was asleep" signal the
//     node's own maybe_autolock uses (hearth/node.py), just client-side so
//     a backgrounded/suspended TAB (not just the whole process) re-checks.
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") recheckApplock().catch(() => {});
});

function startApplockHeartbeat(intervalMs = 5000) {
  APPLOCK_HEARTBEAT_LAST = Date.now();
  setInterval(() => {
    const now = Date.now();
    const gap = now - APPLOCK_HEARTBEAT_LAST;
    APPLOCK_HEARTBEAT_LAST = now;
    if (gap > intervalMs + 30000) recheckApplock();
  }, intervalMs);
}

// Idle auto-lock's activity signal (whole-branch review, IMPORTANT #5,
// redone). The earlier approach touched last_activity server-side on
// every allowed request except a denylist of tagged/background paths -
// but a background WS-driven refresh() (see connectWs() above) and media
// <img>/<video> GETs (/api/blob/, /api/post-blob/, /api/dm-blob/ - see
// renderBlock/openThread/etc.) can't be tagged or excluded from a
// denylist, so an abandoned tab with an active friend graph never idled
// out. Inverted: the server now only touches on POST /api/activity (see
// hearth/api.py), and this client only calls that route when GENUINE
// user input - a pointerdown or keydown, tracked here at the document
// level so it fires regardless of which element the input landed on -
// has happened since the last ping.
let LAST_INPUT = 0;
document.addEventListener("pointerdown", () => { LAST_INPUT = Date.now(); });
document.addEventListener("keydown", () => { LAST_INPUT = Date.now(); });

function startActivityPing(intervalMs = 22000) {
  let lastPinged = 0;
  setInterval(() => {
    if (LAST_INPUT <= lastPinged) return;   // no real input since last ping - let it idle
    lastPinged = LAST_INPUT;
    // Fire-and-forget, and deliberately not gated on checking app-lock
    // status first: on a non-applock node this just touches a
    // last_activity nothing ever reads (harmless), and on a LOCKED node
    // it 423s (also harmless, and expected - see hearth/api.py's
    // _APPLOCK_ALLOWLIST) - installApplockGate()'s fetch wrapper already
    // reacts to that 423 the same way it does for any other route.
    fetch("/api/activity", {method: "POST"}).catch(() => {});
  }, intervalMs);
}

// Settings-page section (spec 2026-07-15): fills
// #applock-settings from the current GET /api/applock status. Rebuilt
// wholesale on every state change (enable/change/disable) rather than
// patched in place - this panel is small and rarely touched, unlike the
// live journal/circle.
async function renderApplockSettings() {
  const panel = document.getElementById("applock-settings");
  if (!panel) return;
  const status = await getApplockStatus();
  panel.replaceChildren();

  if (!status.enabled) {
    const hint = el("div", "hint", "Locks Kreds behind a PIN or passphrase on this device.");
    const typeSel = document.createElement("select");
    for (const [v, label] of [["pin", "PIN (numbers)"], ["passphrase", "Passphrase"]]) {
      const o = document.createElement("option"); o.value = v; o.textContent = label;
      typeSel.append(o);
    }
    const cred = document.createElement("input");
    cred.type = "password"; cred.autocomplete = "off"; cred.placeholder = "Choose a credential";
    const confirm = document.createElement("input");
    confirm.type = "password"; confirm.autocomplete = "off"; confirm.placeholder = "Confirm";
    const err = el("div", "hint");
    const btn = el("button", "btn-accent", "Enable App-lock"); btn.type = "button";
    btn.onclick = async () => {
      err.textContent = "";
      if (!cred.value) { err.textContent = "Enter a credential."; return; }
      if (cred.value !== confirm.value) { err.textContent = "Credentials don't match."; return; }
      const r = await fetch("/api/applock/setup", {method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({credential: cred.value, cred_type: typeSel.value})});
      if (!r.ok) { err.textContent = "Couldn't enable: " + await r.text(); return; }
      cred.value = ""; confirm.value = "";
      renderApplockSettings();
    };
    panel.append(hint, el("div", "lbl", "Lock type"), typeSel,
      el("div", "lbl", "Credential"), cred, confirm, btn, err);
    return;
  }

  // -- enabled: idle timeout, lock-on-sleep, change, disable, lock now
  const idleSel = document.createElement("select");
  idleSel.setAttribute("aria-label", "Auto-lock after idle");
  for (const [v, label] of IDLE_OPTIONS) {
    const o = document.createElement("option"); o.value = v; o.textContent = label;
    if (v === status.settings.idle_minutes) o.selected = true;
    idleSel.append(o);
  }
  const sleepChk = document.createElement("input");
  sleepChk.type = "checkbox"; sleepChk.id = "applock-lock-on-sleep";
  sleepChk.checked = !!status.settings.lock_on_sleep;
  const sleepLbl = document.createElement("label");
  sleepLbl.htmlFor = "applock-lock-on-sleep";
  sleepLbl.append(sleepChk, document.createTextNode(" Lock when the device sleeps"));
  const settingsErr = el("div", "hint");
  const saveSettings = async () => {
    const r = await fetch("/api/applock/settings", {method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({idle_minutes: Number(idleSel.value), lock_on_sleep: sleepChk.checked})});
    settingsErr.textContent = r.ok ? "" : "Couldn't save: " + await r.text();
  };
  idleSel.onchange = saveSettings;
  sleepChk.onchange = saveSettings;

  const lockNowBtn = el("button", "", "Lock now"); lockNowBtn.type = "button";
  lockNowBtn.id = "applock-lock-now";
  lockNowBtn.onclick = async () => {
    const r = await fetch("/api/lock", {method: "POST"});
    if (!r.ok) { alert("Couldn't lock: " + await r.text()); return; }
    renderLockScreen(await getApplockStatus());
  };

  const oldCred = document.createElement("input");
  oldCred.type = "password"; oldCred.autocomplete = "off"; oldCred.placeholder = "Current credential";
  const newCred = document.createElement("input");
  newCred.type = "password"; newCred.autocomplete = "off"; newCred.placeholder = "New credential";
  const changeErr = el("div", "hint");
  const changeBtn = el("button", "", "Change credential"); changeBtn.type = "button";
  changeBtn.onclick = async () => {
    changeErr.textContent = "";
    if (!oldCred.value || !newCred.value) { changeErr.textContent = "Enter both credentials."; return; }
    const r = await fetch("/api/applock/change", {method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({old: oldCred.value, new: newCred.value})});
    oldCred.value = ""; newCred.value = "";
    changeErr.textContent = r.ok ? "Changed." : "Couldn't change: " + await r.text();
  };

  const disableCred = document.createElement("input");
  disableCred.type = "password"; disableCred.autocomplete = "off";
  disableCred.placeholder = "Current credential";
  const disableErr = el("div", "hint");
  const disableBtn = el("button", "btn-danger", "Disable App-lock"); disableBtn.type = "button";
  disableBtn.onclick = async () => {
    disableErr.textContent = "";
    if (!disableCred.value) { disableErr.textContent = "Enter your credential."; return; }
    const r = await fetch("/api/applock/disable", {method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({credential: disableCred.value})});
    if (!r.ok) { disableErr.textContent = "Couldn't disable: " + await r.text(); return; }
    disableCred.value = "";
    renderApplockSettings();
  };

  panel.append(
    el("div", "lbl", "Auto-lock after idle"), idleSel,
    sleepLbl, settingsErr,
    lockNowBtn,
    el("div", "lbl", "Change credential"), oldCred, newCred, changeBtn, changeErr,
    el("div", "lbl", "Disable App-lock"), disableCred, disableBtn, disableErr);
}

// Self-only, desktop-only close-behavior toggle (Task 3, mirrors
// renderApplockSettings' rebuild-wholesale pattern): what closing the
// frameless window does. Gated on window.pywebview - in a plain browser
// this returns immediately with no fetch at all, and #profile-desktop-
// panel stays display:none via style.css's .desktop-only-panel default
// regardless. The same GET /api/settings the titlebar's close handler
// reads live, so the two stay in sync without any shared client state.
async function renderDesktopSettings() {
  const panel = document.getElementById("desktop-settings");
  if (!panel || !window.pywebview) return;
  const cur = (await j("/api/settings")).close_behavior;
  panel.replaceChildren();
  const sel = document.createElement("select");
  sel.setAttribute("aria-label", "When you close the window");
  for (const [v, label] of [["quit", "Quit the app"],
                            ["keep", "Keep running in the background (in the system tray)"]]) {
    const o = document.createElement("option"); o.value = v; o.textContent = label;
    if (v === cur) o.selected = true;
    sel.append(o);
  }
  const err = el("div", "hint");
  sel.onchange = async () => {
    const r = await fetch("/api/settings", {method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({close_behavior: sel.value})});
    err.textContent = r.ok ? "" : "Couldn't save: " + await r.text();
  };
  panel.append(el("div", "lbl", "When you close the window"), sel, err);
}

// Shared by the Settings panel (renderUpdateSettings) and the auto-update
// banner (renderUpdateBanner, Task 2, 0.3.15): POST the existing apply
// endpoint, then reload (web hot-swap - unambiguous, always immediate).
// A staged core update (restart_required) is NOT actioned here: the two
// callers' buttons make different promises to the user ("Restart to
// update" on the banner vs. "Apply update" in Settings), so each caller
// decides its own restart timing off the returned result (review finding,
// 0.3.15). errEl receives any failure text - callers own their own button
// disable/enable around this call. Returns the parsed apply result, or
// null/undefined on a fetch failure.
async function applyUpdateNow(errEl) {
  let out;
  try {
    const r = await fetch("/api/update/apply", {method: "POST"});
    if (!r.ok) { if (errEl) errEl.textContent = "Couldn't apply update: " + await r.text(); return null; }
    out = await r.json();
  } catch (e) {
    if (errEl) errEl.textContent = "Couldn't apply update: " + e.message;
    return null;
  }
  if (out.reload) { location.reload(); return out; }
  return out;   // restart_required is the caller's call
}

// Signed in-app updates (Task 3, mirrors renderApplockSettings/
// renderDesktopSettings' rebuild-wholesale pattern): a manual "Check for
// updates" button against GET /api/update/check, and - only once one's
// available - an Apply button against POST /api/update/apply (via the
// shared applyUpdateNow helper above, Task 2). A web hot-swap reloads the
// page; a staged core update just needs a restart, so it stays put and
// tells the user instead. No auto-check loop (Phase 2a is manual-only
// per spec).
async function renderUpdateSettings() {
  const panel = document.getElementById("update-settings");
  if (!panel) return;
  panel.replaceChildren();

  const status = el("div", "hint", "");
  const checkBtn = el("button", "", "Check for updates");
  checkBtn.type = "button";
  const result = el("div", "update-result");
  panel.append(checkBtn, status, result);

  checkBtn.onclick = async () => {
    checkBtn.disabled = true;
    status.textContent = "Checking...";
    result.replaceChildren();
    let info;
    try {
      const r = await fetch("/api/update/check");
      if (!r.ok) { status.textContent = "Couldn't check for updates: " + await r.text();
        checkBtn.disabled = false; return; }
      info = await r.json();
    } catch (e) {
      status.textContent = "Couldn't check for updates: " + e.message;
      checkBtn.disabled = false;
      return;
    }
    checkBtn.disabled = false;
    status.textContent = "App core " + info.current
      + "  ·  Web " + (info.web_version || info.current)
      + (info.available ? "" : " (up to date)");
    if (!info.available) return;

    const verLine = el("div", "lbl", "Update available: " + info.version);
    const notes = info.notes ? el("div", "hint update-notes", info.notes) : null;
    const applyBtn = el("button", "btn-accent", "Apply update");
    applyBtn.type = "button";
    const applyErr = el("div", "hint");
    // Fetch + reload logic is the shared applyUpdateNow helper (Task 2) -
    // also used by renderUpdateBanner. But THIS button says "Apply
    // update", not "Restart" - so a staged core update must not restart
    // silently on this click (review finding, 0.3.15). Render a
    // user-clicked "Restart now" affordance instead, same as before the
    // helper existed, and let the user pick their own restart timing.
    applyBtn.onclick = async () => {
      applyBtn.disabled = true;
      applyErr.textContent = "";
      const out = await applyUpdateNow(applyErr);
      if (out && out.restart_required) {
        // window.pywebview.api.restart (Task 3, hearth/desktop.py) only
        // exists in the desktop shell -- a plain browser (dev/demo) falls
        // back to the text-only notice, since there's no launcher to
        // relaunch there.
        const api = window.pywebview && window.pywebview.api;
        if (api && api.restart) {
          const restartBtn = el("button", "btn-accent", "Restart now");
          restartBtn.type = "button";
          restartBtn.onclick = () => api.restart();
          applyErr.replaceChildren(
            el("span", "", "Downloaded - "), restartBtn,
            el("span", "", " to finish."));
        } else {
          applyErr.textContent = "Downloaded - restart Kreds to finish.";
        }
      }
      applyBtn.disabled = false;
    };
    result.append(verLine, ...(notes ? [notes] : []), applyBtn, applyErr);
  };
}

// Auto-update nudge (Task 2, 0.3.15): a dismissible top banner rendered
// from STATE.update_status (Task 1's node-side auto-check), called every
// refresh(). Notify + one-click apply ONLY - it never applies on its own.
// Dismiss is session-only: refresh() resets UPDATE_BANNER_DISMISSED when
// a genuinely new version shows up, so a fresh update re-nudges even if
// an earlier one was dismissed.
function renderUpdateBanner() {
  const bar = document.getElementById("update-banner");
  if (!bar) return;
  const s = (STATE && STATE.update_status) || {available: false};
  if (!s.available || UPDATE_BANNER_DISMISSED) {
    bar.classList.add("hidden");
    bar.replaceChildren();
    return;
  }
  bar.replaceChildren();
  const msg = el("span", "ub-msg", "A Kreds update is ready");
  const act = el("button", "ub-act",
    s.kind === "core" ? "Restart to update" : "Update now");
  act.type = "button";
  const err = el("span", "ub-err", "");
  // The banner's core button literally says "Restart to update" - one
  // click means restart, unlike the Settings panel's "Apply update"
  // (review finding, 0.3.15): restart here as soon as the apply stages,
  // no separate confirmation step.
  act.onclick = async () => {
    act.disabled = true;
    const out = await applyUpdateNow(err);
    let navigating = false;    // page reload (web) or app restart (core)
    if (out && out.restart_required) {
      const api = window.pywebview && window.pywebview.api;
      if (api && api.restart) { api.restart(); navigating = true; }
      else { err.textContent = "Downloaded - restart Kreds to finish."; }
    }
    // Finding 2 (whole-branch review): a failed/errored apply (out falsy),
    // or a staged core update with no pywebview restart bridge to call,
    // never navigates away -- re-enable the button so the user can retry
    // instead of it staying dead until the next refresh() rebuild. A
    // successful web apply already reload()s inside applyUpdateNow, so
    // this line is moot there (the page is gone).
    if (!navigating && !(out && out.reload)) { act.disabled = false; }
  };
  const x = el("button", "ub-x", "✕");   // dismiss (returns next push)
  x.type = "button";
  x.setAttribute("aria-label", "Dismiss");
  x.onclick = () => { UPDATE_BANNER_DISMISSED = true; renderUpdateBanner(); };
  bar.append(msg, act, err, x);
  bar.classList.remove("hidden");
}

const ACCENTS = ["#2743d6","#c0563b","#3e7c55","#8a5cd0","#17191e",
  "#1f8a8a","#c79a2e","#c0567e","#4a5568","#7a4e8a",
  "#2f80ed","#c0392b","#e2725b","#b52d8c","#1e6b5a","#6a4bb0"];

// Profile editor (preserved from the pre-reskin client). It used to live
// inside the full-page "profile view" (`.prof-wrap`); re-homed into the
// Me view it now previews the accent directly on <html> (the same target
// `refresh()` uses after save) instead of a `.prof-wrap` ancestor that no
// longer exists.
function profileEditor(p) {
  const box = el("div", "editor");
  box.append(el("h2", "", "Edit your profile"));
  const name = document.createElement("input"); name.value = p.name;
  const bio = document.createElement("textarea"); bio.rows = 2; bio.value = p.bio || "";
  bio.placeholder = "Short bio";
  box.append(el("div","lbl","Name"), name, el("div","lbl","Bio"), bio);
  // accent
  box.append(el("div","lbl","Your color"));
  const sws = el("div","swatches"); let accent = p.accent;
  for (const c of ACCENTS) { const s = el("div","sw" + (c===accent?" on":""));
    s.style.background = c; s.onclick = () => { accent = c;
      [...sws.children].forEach(x=>x.classList.remove("on")); s.classList.add("on");
      setAccent(document.documentElement, c); }; sws.append(s); }
  const custom = document.createElement("input");
  custom.type = "color"; custom.value = /^#[0-9a-f]{6}$/.test(accent)
    ? accent : "#2743d6"; custom.className = "sw-custom";
  custom.oninput = () => { accent = custom.value.toLowerCase();
    [...sws.children].forEach(x => x.classList.remove("on"));
    setAccent(document.documentElement, accent); };
  sws.append(custom);
  box.append(sws);
  // shape / size / align
  const mk = (label, opts, cur) => { box.append(el("div","lbl",label));
    const row = el("div","shapes"); const st = {v: cur};
    for (const o of opts) { const b = el("div","opt"+(o===cur?" on":""), o);
      b.onclick = () => { st.v = o; [...row.children].forEach(x=>x.classList.remove("on"));
        b.classList.add("on"); }; row.append(b); } box.append(row); return st; };
  const shape = mk("Picture shape", ["circle","squircle","square","triangle"], p.avatar_shape);
  const size  = mk("Picture size", ["s","m","l"], p.avatar_size);
  const align = mk("Placement", ["left","center","right"], p.avatar_align);
  // uploads
  box.append(el("div","lbl","Picture"));
  const av = document.createElement("input"); av.type="file"; av.accept="image/*"; box.append(av);
  box.append(el("div","lbl","Banner"));
  const bn = document.createElement("input"); bn.type="file"; bn.accept="image/*"; box.append(bn);
  // Banner crop (spec 2026-07-15): drag the preview up/down to pick which
  // band of the image the banner strip shows; the range slider is the
  // same value's keyboard path. banner_pos = background-position-y
  // percent (0 top, 100 bottom). Shown only once a banner exists.
  let bannerPos = Number.isInteger(p.banner_pos) ? p.banner_pos : 50;
  const crop = el("div", "banner-crop hidden");
  const preview = el("div", "banner-crop-preview");
  preview.setAttribute("aria-hidden", "true");   // the slider is the accessible control
  const slider = document.createElement("input");
  slider.type = "range"; slider.min = "0"; slider.max = "100";
  slider.value = String(bannerPos);
  slider.setAttribute("aria-label", "Banner crop position");
  const applyPos = (v) => {
    bannerPos = Math.max(0, Math.min(100, Math.round(v)));
    slider.value = String(bannerPos);
    preview.style.backgroundPosition = "center " + bannerPos + "%";
  };
  slider.oninput = () => applyPos(Number(slider.value));
  preview.addEventListener("pointerdown", (ev) => {
    ev.preventDefault();                       // no text-selection smear
    const start = bannerPos, sy = ev.clientY;
    const rect = preview.getBoundingClientRect();
    try { preview.setPointerCapture(ev.pointerId); } catch (e) { /* not fatal */ }
    const move = (e) => {
      if (e.pointerId !== ev.pointerId) return;
      // dragging the image DOWN reveals more of its top = smaller percent
      applyPos(start - ((e.clientY - sy) / rect.height) * 100);
    };
    const stop = (e) => {
      if (e.pointerId !== ev.pointerId) return;
      preview.removeEventListener("pointermove", move);
      preview.removeEventListener("pointerup", stop);
      preview.removeEventListener("pointercancel", stop);   // aborted gesture: stop tracking, keep the last applied pos
    };
    preview.addEventListener("pointermove", move);
    preview.addEventListener("pointerup", stop);
    preview.addEventListener("pointercancel", stop);
  });
  const showCrop = (url) => {
    preview.style.backgroundImage = `url(${url})`;
    preview.style.backgroundPosition = "center " + bannerPos + "%";
    crop.classList.remove("hidden");
  };
  if (p.banner) showCrop("/api/blob/" + p.banner);
  let bannerObjUrl = null;   // re-pick revokes the prior URL (same blob-URL lifecycle rule as the composer's objectUrls/dropUrls)
  bn.onchange = () => {
    if (!bn.files[0]) return;
    if (bannerObjUrl) URL.revokeObjectURL(bannerObjUrl);
    bannerObjUrl = URL.createObjectURL(bn.files[0]);
    showCrop(bannerObjUrl);
  };
  crop.append(preview, slider);
  box.append(crop);
  const save = el("button","btn-accent","Save profile"); save.style.marginTop="14px";
  save.onclick = async () => {
    const fd = new FormData();
    fd.append("name", name.value); fd.append("bio", bio.value);
    fd.append("accent", accent.toLowerCase()); fd.append("avatar_shape", shape.v);
    fd.append("avatar_size", size.v); fd.append("avatar_align", align.v);
    fd.append("banner_pos", String(bannerPos));
    if (av.files[0]) fd.append("avatar", av.files[0]);
    if (bn.files[0]) fd.append("banner", bn.files[0]);
    const r = await fetch("/api/profile", {method:"POST", body:fd});
    if (r.ok) {
      if (bannerObjUrl) { URL.revokeObjectURL(bannerObjUrl); bannerObjUrl = null; }
      await refresh();
      // The editor lives on the Settings page now (spec 2026-07-15):
      // re-enter it so the saved values (and a fresh banner blob ref)
      // show immediately, staying on Settings.
      openSettings();
    } else alert("Save failed: " + await r.text());
  };
  box.append(save);
  return box;
}

// ---------------------------------------------------------------------
// Friend-add (Task 3, re-homed into the profile side strip's #ceremony):
// Share tab (POST /api/friend/invite -> code + live countdown) and Enter
// tab (POST /api/friend/add -> auto-connect over Tor, or a manual
// fallback code) are the DEFAULT two-mode flow. The original 4-box
// copy-paste ceremony (respond/finalize/complete, unchanged server-side)
// is kept reachable underneath as the offline fallback -- it's what the
// "manual" branch's response code above walks the user through.
// ---------------------------------------------------------------------

// Display the ~80-char invite compactly: kreds·invite·<FP>…<last4>. `fp`
// is the server-derived 4-char fingerprint (r.fp from /api/friend/invite);
// falls back to the code's own first 4 chars if a caller ever omits it.
// The Copy button always copies the RAW code (code.value / r.payload),
// never this truncated form.
function shortInvite(code, fp) {
  return "kreds·invite·" + (fp || code.slice(0, 4)) + "…" + code.slice(-4);
}

function wireCopyButton(btn, getValue) {
  const label = btn.textContent;
  btn.onclick = async () => {
    const text = getValue();
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
    } catch (e) {
      // Clipboard API can be unavailable (insecure origin, permission
      // denied) -- fall back to a hidden textarea + execCommand("copy").
      const tmp = document.createElement("textarea");
      tmp.value = text;
      tmp.style.position = "fixed";
      tmp.style.opacity = "0";
      document.body.append(tmp);
      tmp.select();
      try { document.execCommand("copy"); } catch (e2) { /* best effort */ }
      tmp.remove();
    }
    btn.textContent = "Copied";
    setTimeout(() => { btn.textContent = label; }, 1500);
  };
}

// Share tab: generate a code, show it with a copy button, and a live
// "expires in MM:SS" countdown (setInterval updating textContent) computed
// from the invite's expires_at. At 0 the countdown reads "Code expired"
// and a Regenerate button (re-POSTs the same endpoint) appears. While
// this is open, connectWs()'s ws.onmessage -> refresh() (the friends-
// changed signal) keeps re-fetching /api/state, so A's entry appears in
// the friends list the moment B auto-connects -- no polling of our own.
function buildShareTab(container) {
  container.replaceChildren();
  const hint = el("div", "hint",
    "Share this code with your friend. Once they connect, they'll appear " +
    "in your friends list automatically.");
  const getBtn = el("button", "btn-accent", "Get my code");
  getBtn.type = "button";
  const result = el("div", "friendadd-share-result hidden");
  // The truncated chip is the ONLY code shown - a pretty, readable label
  // (kreds·invite·<fingerprint>…<suffix>). The full ~80-char code never
  // appears on screen; Copy (or clicking the chip itself) puts it on the
  // clipboard so you paste it straight into a chat. Showing the raw code
  // alongside just confused people - it looked like a second, different code.
  let fullCode = "";
  const chip = el("div", "friendadd-chip");
  chip.title = "Click to copy";
  const copyBtn = el("button", "", "Copy code");
  copyBtn.type = "button";
  wireCopyButton(copyBtn, () => fullCode);
  chip.onclick = () => copyBtn.click();     // clicking the code copies it too
  const countdown = el("div", "hint friendadd-countdown");
  countdown.setAttribute("aria-live", "polite");
  const regenBtn = el("button", "hidden", "Regenerate");
  regenBtn.type = "button";
  let timer = null;

  const tickCountdown = (expiresAt) => {
    // Host-agnostic self-cleanup (review fix): if the panel this tab lives
    // in was torn down mid-countdown (popover closed, or any host rebuilt
    // its panel), the countdown node detaches - stop the interval instead
    // of ticking against dead DOM forever.
    if (!countdown.isConnected) {
      if (timer) { clearInterval(timer); timer = null; }
      return;
    }
    const remaining = Math.max(0, Math.floor(expiresAt - Date.now() / 1000));
    if (remaining <= 0) {
      if (timer) { clearInterval(timer); timer = null; }
      countdown.textContent = "Code expired";
      regenBtn.classList.remove("hidden");
      copyBtn.disabled = true;
      return;
    }
    const mm = String(Math.floor(remaining / 60)).padStart(2, "0");
    const ss = String(remaining % 60).padStart(2, "0");
    countdown.textContent = "expires in " + mm + ":" + ss;
  };

  const getCode = async () => {
    if (timer) { clearInterval(timer); timer = null; }
    getBtn.disabled = true;
    regenBtn.disabled = true;
    try {
      const r = await j("/api/friend/invite", {method: "POST"});
      fullCode = r.payload;
      chip.textContent = shortInvite(r.payload, r.fp);
      copyBtn.disabled = false;
      regenBtn.classList.add("hidden");
      result.classList.remove("hidden");
      tickCountdown(r.expires_at);
      timer = setInterval(() => tickCountdown(r.expires_at), 1000);
    } catch (e) {
      result.classList.remove("hidden");
      countdown.textContent = "Couldn't get a code: " + e.message;
    } finally {
      getBtn.disabled = false;
      regenBtn.disabled = false;
    }
  };
  getBtn.onclick = getCode;
  regenBtn.onclick = getCode;

  result.append(chip, copyBtn, countdown, regenBtn);
  container.append(hint, getBtn, result);
  container.friendaddFocus = () => getBtn.focus();
}

// Enter-code tab: paste A's code, Connect posts it to /api/friend/add.
// "connected" -> honest confirmation + refresh() so the new friend shows
// up immediately. "manual" (A unreachable right now) -> the honest
// "they seem offline" note plus B's own response code (copy button), the
// same code the manual box's step 2 would have produced -- A pastes it
// into their own manual box (step 3) and hands the resulting final back.
function buildEnterTab(container) {
  container.replaceChildren();
  const form = document.createElement("form");
  form.id = "friendadd-enter-form";
  const label = document.createElement("label");
  label.htmlFor = "friendadd-enter-code";
  label.className = "hint";
  label.textContent = "Their code";
  const ta = document.createElement("textarea");
  ta.id = "friendadd-enter-code";
  ta.rows = 4;
  ta.placeholder = "Paste their code here";
  // Enter submits (Shift+Enter still inserts a newline, since codes are
  // single-line JSON anyway this just gives keyboard users a submit path
  // without hunting for the button).
  ta.addEventListener("keydown", (ev) => {
    if (ev.key === "Enter" && !ev.shiftKey) {
      ev.preventDefault();
      if (form.requestSubmit) form.requestSubmit(); else submitBtn.click();
    }
  });
  const submitBtn = el("button", "btn-accent", "Connect");
  submitBtn.type = "submit";
  // Names the peer's fingerprint (server-derived from the pasted invite's
  // id_prefix) so there's a human check even on the auto-connect path --
  // filled right before the connected/manual result below.
  const fpLine = el("div", "hint friendadd-fp");
  fpLine.setAttribute("aria-live", "polite");
  const status = el("div", "hint");
  status.setAttribute("aria-live", "polite");
  const fallback = el("div", "friendadd-fallback hidden");
  const fbLabel = document.createElement("label");
  fbLabel.htmlFor = "friendadd-fallback-code";
  fbLabel.className = "hint";
  fbLabel.textContent = "They seem offline - send them this code:";
  const fbCode = document.createElement("textarea");
  fbCode.id = "friendadd-fallback-code";
  fbCode.className = "friendadd-code";
  fbCode.readOnly = true;
  fbCode.rows = 4;
  const fbCopy = el("button", "", "Copy code");
  fbCopy.type = "button";
  wireCopyButton(fbCopy, () => fbCode.value);
  fallback.append(fbLabel, fbCode, fbCopy);

  form.append(label, ta, submitBtn, fpLine, status);
  form.onsubmit = async (ev) => {
    ev.preventDefault();
    if (!ta.value.trim()) { status.textContent = "Paste a code first."; return; }
    submitBtn.disabled = true;
    fallback.classList.add("hidden");
    fpLine.textContent = "";
    status.textContent = "Connecting...";
    try {
      const r = await j("/api/friend/add", {method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({payload: ta.value})});
      if (r.fp) {
        fpLine.textContent = "Connecting to someone whose ID starts with " + r.fp;
      }
      if (r.status === "connected") {
        status.textContent = "You're now friends with " + r.friend;
        ta.value = "";
        refresh();
      } else {
        status.textContent = "";
        fbCode.value = r.response;
        fallback.classList.remove("hidden");
      }
    } catch (e) {
      status.textContent = "Couldn't connect: " + e.message;
    } finally {
      submitBtn.disabled = false;
    }
  };
  container.append(form, fallback);
  container.friendaddFocus = () => ta.focus();
}

// The original 4-box copy-paste ceremony, unchanged apart from living in
// its own builder so it can be re-homed under a "manual fallback"
// disclosure instead of being the default view.
function buildManualCeremony(container) {
  container.replaceChildren();
  const ta = document.createElement("textarea");
  ta.rows = 4;
  ta.placeholder = "Paste a code here, or click Show my code";
  const status = el("div", "hint",
    "Manual fallback: exchange codes directly, one paste at a time.");
  const step = async (url, sendPayload, nextText, clearAfter) => {
    try {
      const body = sendPayload
        ? {method: "POST", headers: {"Content-Type": "application/json"},
           body: JSON.stringify({payload: ta.value})}
        : {method: "POST"};
      const r = await j(url, body);
      ta.value = clearAfter ? "" : (r.payload || "");
      status.textContent = nextText;
      const qrWrap = document.getElementById("qr-wrap");
      if (qrWrap) qrWrap.remove();
      if (ta.value && !clearAfter) {
        const w = el("div"); w.id = "qr-wrap";
        const img = document.createElement("img");
        img.id = "qr-img";
        img.src = "/api/qr?text=" + encodeURIComponent(ta.value);
        w.append(el("div", "hint",
          "Show this QR (or copy the code):"), img);
        status.after(w);
      }
      refresh();
    } catch (e) { status.textContent = "Rejected: " + e.message; }
  };
  const mk = (label, fn) => {
    const b = el("button", "", label);
    b.type = "button";
    b.onclick = fn;
    return b;
  };
  container.append(ta,
    mk("1. Show my code", () => step("/api/friend/invite", false,
      "Give this code to your friend. They paste it and press 2.")),
    mk("2. Respond", () => step("/api/friend/respond", true,
      "Send this response back. They paste it and press 3.")),
    mk("3. Finalize", () => step("/api/friend/finalize", true,
      "Friend added on your side. Send this final code; they press 4.")),
    mk("4. Complete", () => step("/api/friend/complete", true,
      "Friend added.", true)),
    status);
}

// The Share-my-code / Enter-a-code panel, home-agnostic (spec
// 2026-07-15): ceremonyUI hosts it in Settings > Friends behind the
// "Add friend" toggle; openFriendAdd hosts the same panel in the topbar
// "+" dialog. Sets panel.friendaddFocus() for whichever host opens it.
function buildFriendAdd(panel) {
  const tabs = el("div", "friendadd-tabs");
  tabs.setAttribute("role", "tablist");
  const shareTabBtn = el("button", "friendadd-tab active", "Share my code");
  const enterTabBtn = el("button", "friendadd-tab", "Enter a code");
  for (const b of [shareTabBtn, enterTabBtn]) { b.type = "button"; b.setAttribute("role", "tab"); }
  tabs.append(shareTabBtn, enterTabBtn);

  const shareTab = el("div", "friendadd-body");
  const enterTab = el("div", "friendadd-body hidden");
  buildShareTab(shareTab);
  buildEnterTab(enterTab);
  let activeTab = shareTab;   // tracks which tab the toggle button should focus on reopen

  const showShare = () => {
    shareTabBtn.classList.add("active"); shareTabBtn.setAttribute("aria-selected", "true");
    enterTabBtn.classList.remove("active"); enterTabBtn.setAttribute("aria-selected", "false");
    shareTab.classList.remove("hidden"); enterTab.classList.add("hidden");
    activeTab = shareTab;
    shareTab.friendaddFocus();
  };
  const showEnter = () => {
    enterTabBtn.classList.add("active"); enterTabBtn.setAttribute("aria-selected", "true");
    shareTabBtn.classList.remove("active"); shareTabBtn.setAttribute("aria-selected", "false");
    enterTab.classList.remove("hidden"); shareTab.classList.add("hidden");
    activeTab = enterTab;
    enterTab.friendaddFocus();
  };
  shareTabBtn.onclick = showShare;
  enterTabBtn.onclick = showEnter;
  shareTabBtn.setAttribute("aria-selected", "true");
  enterTabBtn.setAttribute("aria-selected", "false");

  const fallbackToggle = el("button", "linklike", "Trouble connecting? Use the manual code exchange");
  fallbackToggle.type = "button";
  const manualPanel = el("div", "ceremony-manual hidden");
  buildManualCeremony(manualPanel);
  fallbackToggle.onclick = () => manualPanel.classList.toggle("hidden");

  panel.append(tabs, shareTab, enterTab, fallbackToggle, manualPanel);
  panel.friendaddFocus = () => activeTab.friendaddFocus();
}

function ceremonyUI() {
  const root = document.getElementById("ceremony");
  root.replaceChildren();
  const toggle = el("button", "btn-accent", "Add friend");
  toggle.innerHTML = ICONS.plus + "<span>Add friend</span>";
  const panel = el("div", "ceremony-panel hidden");
  buildFriendAdd(panel);
  toggle.onclick = () => {
    panel.classList.toggle("hidden");
    if (!panel.classList.contains("hidden")) panel.friendaddFocus();
  };
  root.append(toggle, panel);
}

// Topbar "+" (spec 2026-07-15): quick add-friend without leaving the
// profile. Rebuilt fresh per open so a prior invite's countdown state
// never leaks into a new session.
let FRIENDADD_OPENER = null;   // same shape as BLOCK_SETTINGS_OPENER
function openFriendAdd() {
  const body = document.getElementById("friendadd-body");
  body.replaceChildren();
  const panel = el("div", "ceremony-panel");
  buildFriendAdd(panel);
  body.append(panel);
  FRIENDADD_OPENER = document.getElementById("profile-addfriend");
  document.getElementById("friendadd-overlay").classList.remove("hidden");
  panel.friendaddFocus();
}
function closeFriendAdd() {
  document.getElementById("friendadd-overlay").classList.add("hidden");
  // Block-settings parity: return focus to the topbar "+" that opened the
  // dialog - without this, Esc/backdrop/close-button all drop focus to
  // <body>, losing the user's place on the profile.
  const opener = FRIENDADD_OPENER;
  FRIENDADD_OPENER = null;
  if (opener && opener.isConnected) opener.focus();
}
document.getElementById("profile-addfriend").onclick = openFriendAdd;
document.getElementById("friendadd-close").onclick = closeFriendAdd;
document.getElementById("friendadd-overlay").addEventListener("click", (ev) => {
  if (ev.target.id === "friendadd-overlay") closeFriendAdd();
});
// Block-settings parity: trap Tab within the card while the dialog is
// open (it declares aria-modal="true"). Scoped naturally - this only
// fires when the Tab keydown bubbles up from a focused descendant of
// #friendadd-overlay, which can't happen while it's hidden.
document.getElementById("friendadd-overlay").addEventListener("keydown", (ev) => {
  if (ev.key !== "Tab") return;
  const card = document.querySelector("#friendadd-overlay .block-settings-card");
  if (!card) return;
  const focusables = [...card.querySelectorAll(
    'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])'
  )];
  if (!focusables.length) return;
  const first = focusables[0], last = focusables[focusables.length - 1];
  if (ev.shiftKey && document.activeElement === first) {
    ev.preventDefault(); last.focus();
  } else if (!ev.shiftKey && document.activeElement === last) {
    ev.preventDefault(); first.focus();
  }
});

// ---------------------------------------------------------------------
// Messages / DM chat (unchanged, re-homed into #view-messages)
// ---------------------------------------------------------------------

async function loadConversations() {
  CONVS = await j("/api/conversations");
  renderConversations();
  renderDmBadge();
}

function renderConversations() {
  const root = document.getElementById("conversations");
  root.replaceChildren();
  const convs = CONVS.slice();
  const friends = (STATE ? STATE.friends : []);
  const seen = new Set(convs.map(c => c.identity_pub));
  for (const f of friends)
    if (!seen.has(f.identity_pub))
      convs.push({identity_pub: f.identity_pub, name: f.name, last_text: null});
  if (!convs.length) {
    root.append(el("div", "hint", "No friends yet. Add someone to chat."));
    return;
  }
  for (const c of convs) {
    const row = el("div", "conv"
      + (c.identity_pub === CURRENT_DM ? " active" : "")
      + (convUnread(c) ? " unread" : ""));
    const av = el("div", "conv-avatar");
    av.textContent = (c.name || "?").slice(0, 1).toUpperCase();
    const txt = el("div");
    txt.append(el("div", "name", c.name),
               el("div", "preview", c.last_text || "no messages yet"));
    row.append(av, txt);
    if (convUnread(c)) row.append(el("span", "cdot"));
    row.onclick = () => openThread(c.identity_pub, c.name);
    root.append(row);
  }
}

function dmPlaceholder() {
  const t = document.getElementById("thread");
  t.replaceChildren(el("div", "dm-empty", "Pick a conversation to start."));
  document.getElementById("dm-compose").classList.add("hidden");
  document.getElementById("thread-title").textContent = "Messages";
}

async function openThread(identity, name, keepScroll) {
  CURRENT_DM = identity;
  CURRENT_DM_NAME = name;
  localStorage.setItem("hearth_dm", identity);
  localStorage.setItem("hearth_dm_name", name);
  document.getElementById("thread-title").textContent = "Chat with " + name;
  document.getElementById("dm-compose").classList.remove("hidden");
  const root = document.getElementById("thread");
  const atBottom = root.scrollHeight - root.scrollTop - root.clientHeight < 40;
  const msgs = await j("/api/dm/" + identity);
  root.replaceChildren();
  for (const m of msgs) {
    const b = el("div", "bubble" + (m.from_me ? " me" : ""));
    if (m.undecryptable) b.append(el("div", "undec",
      "(cannot decrypt on this device)"));
    else {
      b.append(el("div", "", m.text || ""));
      // DM photos (August 2026-07-18): compact clickable previews - the
      // shared lightbox is the full-size view. src is precomputed per
      // item because DM blobs live on /api/dm-blob, not the lightbox's
      // post-blob default.
      const ditems = m.blobs.map(
        (bh) => ({src: "/api/dm-blob/" + m.msg_id + "/" + bh}));
      m.blobs.forEach((h, bi) => {
        const img = document.createElement("img");
        img.src = "/api/dm-blob/" + m.msg_id + "/" + h;
        img.className = "dmpic";
        img.style.cursor = "zoom-in";
        img.tabIndex = -1;   // lightbox close returns focus here
        img.onclick = () => openLightbox(ditems, bi, img);
        b.append(img);
      });
    }
    b.append(el("div", "bt", new Date(m.created_at * 1000).toLocaleString()));
    root.append(b);
  }
  if (!msgs.length) {
    const e = el("div", "dm-empty", "No messages yet - say hi.");
    root.append(e);
  }
  // Opening the thread is what "read" means for the badge - but ONLY when
  // the user could actually have seen it. refresh() re-invokes openThread
  // on every WS push while Messages is the active view, and that reaches
  // hidden windows too (a background tab, or the desktop app minimized to
  // tray): document.hidden guards against silently marking a friend's DM
  // read before anyone looked at it. Same standard as the journal's
  // IntersectionObserver, which gets this for free - browsers pause IO
  // callbacks in hidden tabs, so it never fires unseen.
  // The watermark itself is clamped to the newest message's own
  // created_at (mirroring the journal watermark's created_at-not-now
  // trick): local Date.now() alone would let a sender's fast clock keep
  // the thread "unread" forever after it's been opened.
  const newest = msgs.length ? msgs[msgs.length - 1].created_at : 0;
  if (!document.hidden) markDmOpenedNow(identity, newest);
  renderConversations();
  renderDmBadge();
  // Auto-scroll to newest on an explicit open, or on a live update only when
  // the reader was already at the bottom (don't yank them up mid-scroll).
  if (!keepScroll || atBottom) root.scrollTop = root.scrollHeight;
}

document.getElementById("dm-compose").onsubmit = async (ev) => {
  ev.preventDefault();
  if (!CURRENT_DM) return;
  const fd = new FormData();
  fd.append("to", CURRENT_DM);
  fd.append("text", document.getElementById("dm-text").value);
  fd.append("expires_seconds", "");
  for (const f of document.getElementById("dm-photos").files)
    fd.append("photos", f);
  const r = await fetch("/api/dm", {method: "POST", body: fd});
  if (r.ok) {
    document.getElementById("dm-text").value = "";
    document.getElementById("dm-photos").value = "";
    // Replying obviously means the thread is read - matters on the skew
    // path (no last_from_me), where the fallback is time-based.
    if (CURRENT_DM) markDmOpenedNow(CURRENT_DM);
    openThread(CURRENT_DM, CURRENT_DM_NAME);
  } else { alert("Send failed: " + await r.text()); }
};

// ---------------------------------------------------------------------
// Composer (keeps -> hidden #scope, submit -> /api/post)
// ---------------------------------------------------------------------

// Scope buttons only: the Photo button is a <label class="keep"> for pill
// styling but carries NO data-scope, so restrict the scope handler to
// .keep[data-scope] - otherwise clicking Photo set scope to undefined
// (bug found in the first two-machine test).
document.querySelectorAll("#composer .keep[data-scope]").forEach(btn => {
  btn.addEventListener("click", () => {
    document.querySelectorAll("#composer .keep[data-scope]").forEach(b => b.classList.remove("active"));
    btn.classList.add("active");
    document.getElementById("scope").value = btn.dataset.scope;
  });
});

document.getElementById("composer").onsubmit = async (ev) => {
  ev.preventDefault();
  const fd = new FormData();
  fd.append("text", document.getElementById("post-text").value);
  fd.append("scope", document.getElementById("scope").value);
  fd.append("expires_seconds", document.getElementById("post-expiry").value);
  for (const f of document.getElementById("post-photos").files)
    fd.append("photos", f);
  const r = await fetch("/api/post", {method: "POST", body: fd});
  // Fail loud, keep the user's work: a swallowed 400 silently discarded the
  // post (looked like a vanished orphan). Match the Wall composer's pattern.
  if (!r.ok) { alert("Post failed: " + await r.text()); return; }
  document.getElementById("post-text").value = "";
  document.getElementById("post-photos").value = "";
  refresh();
};

// ---------------------------------------------------------------------
// View switching: #view-journal / #view-messages / #view-profile, plus the
// mobile Circle/Journal/Me tab bar (Circle's real content is Task 5's;
// for now it just reveals the circle-rail stub in place of the journal).
// Me is not its own view - it opens the self profile page (openMe below).
// ---------------------------------------------------------------------

function setView(which) {
  if (which !== "profile") ARRANGING = false;   // leaving the profile exits arrange mode
  for (const v of ["journal", "messages", "profile", "settings"])
    document.getElementById("view-" + v).classList.toggle("hidden", v !== which);
  document.querySelectorAll(".navlinks button").forEach(b =>
    b.classList.toggle("active", b.dataset.view === which));
  if (which !== "profile" && which !== "settings") localStorage.setItem("hearth_view", which);
  if (which === "messages") { loadConversations(); if (!CURRENT_DM) dmPlaceholder(); }
}

function goView(tab) {
  const vj = document.getElementById("view-journal");
  if (tab === "circle") { setView("journal"); vj.classList.add("show-circle"); }
  else { vj.classList.remove("show-circle"); setView(tab); }
  document.querySelectorAll(".tabbar-mobile button").forEach(b =>
    b.classList.toggle("active", b.dataset.tab === tab));
}

// Me nav/tab entry point: opens your own profile page directly (Task 1)
// instead of a separate card-summary view. Records "me" as the remembered
// view so a reload lands back on your own profile (restoreView below).
function openMe() {
  localStorage.setItem("hearth_view", "me");   // remembered across reloads
  if (STATE) openProfile(STATE.identity_pub);
}

function restoreView() {
  const v = localStorage.getItem("hearth_view");
  if (v === "messages") {
    goView("messages");
    const dm = localStorage.getItem("hearth_dm");
    const name = localStorage.getItem("hearth_dm_name");
    if (dm) openThread(dm, name || dm.slice(0, 8)).catch(() => dmPlaceholder());
    else dmPlaceholder();
  } else if (v === "me") {
    openMe();
  }
}

// ---------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------

async function refresh() {
  // No longer tags/skips activity here (whole-branch review, IMPORTANT #5,
  // redone): idle auto-lock is now driven ONLY by explicit user input via
  // startActivityPing()'s POST /api/activity, not by inferring "was this
  // fetch background" from the call site. Every refresh() - WS-driven or
  // user-triggered - fetches the same way.
  STATE = await j("/api/state");
  // re-nudge on a genuinely new version even if a prior banner was dismissed
  if (STATE.update_status && STATE.update_status.available
      && STATE.update_status.version !== LAST_SEEN_UPDATE_VERSION) {
    UPDATE_BANNER_DISMISSED = false;
    LAST_SEEN_UPDATE_VERSION = STATE.update_status.version;
  }
  renderUpdateBanner();
  if (STATE.revoked) {
    document.getElementById("app").classList.add("hidden");
    document.getElementById("revoked-banner").classList.remove("hidden");
    // .tabbar-mobile lives outside #app, so hiding #app alone leaves it
    // visible on a revoked device - hide it too. The profile page lives
    // inside #app (unlike the old modal), so hiding #app covers it.
    document.querySelector(".tabbar-mobile").classList.add("hidden");
    return;
  }
  if (STATE.accent) setAccent(document.documentElement, STATE.accent);
  renderChrome();
  FEED = await j("/api/feed");
  KREDS = await j("/api/kreds");
  renderChipbar();
  renderJournal();
  renderCircleRail();
  renderMeStrip();
  renderStories();
  // Profile self-heal (spec 2026-07-18 Part 1): a friend's wall fills in
  // as blobs gossip in, instead of staying broken until re-navigation.
  // Guards: never re-render mid-Arrange (tears the drag surface from
  // under the pointer) or under the block-settings modal (it holds a
  // reference to its block element). Next tick heals after they close.
  // CRITICAL #1 (whole-branch review): also never re-render while the
  // video-editor overlay is open (its onClose closure writes into THIS
  // composer instance - a re-render swaps in a fresh one, orphaning the
  // edit) or while the profile composer has an unsaved draft (text typed,
  // a photo/video picked, or focus still inside it) - a heal tick must not
  // silently discard what someone is mid-way through writing/attaching.
  const composerForm = document.getElementById("profile-composer-form");
  const composerDirty = !!composerForm && (
    composerForm.querySelector("input[type=text]").value !== ""
    || [...composerForm.querySelectorAll("input[type=file]")]
      .some(f => f.files.length > 0)
    || composerForm.contains(document.activeElement));
  if (currentView() === "profile" && CURRENT_PROFILE && !ARRANGING
      && document.getElementById("block-settings").classList.contains("hidden")
      && !document.getElementById("video-editor")
      && !composerDirty)
    openProfile(CURRENT_PROFILE);
  // Conversations are fetched EVERY cycle now (not just with Messages
  // open): the nav badge lives outside that view. One fetch feeds both
  // the badge and the list.
  await loadConversations();
  if (!document.getElementById("view-messages").classList.contains("hidden")) {
    // Keep the open conversation live: a WS "changed" (e.g. an incoming DM
    // arriving over gossip) re-renders the thread in place, preserving the
    // reader's scroll position unless they're already at the bottom.
    if (CURRENT_DM) openThread(CURRENT_DM, CURRENT_DM_NAME, true);
  }
}

function connectWs() {
  const ws = new WebSocket("ws://" + location.host + "/ws");
  ws.onmessage = () => refresh();
  ws.onclose = () => setTimeout(connectWs, 2000);
}

applyTheme(currentTheme());
document.getElementById("theme-toggle").onclick = toggleTheme;

document.getElementById("nav-journal").onclick = () => goView("journal");
document.getElementById("nav-messages").onclick = () => goView("messages");
document.getElementById("nav-me").onclick = () => openMe();
document.querySelectorAll(".tabbar-mobile button").forEach(
  b => b.onclick = () => (b.dataset.tab === "me" ? openMe() : goView(b.dataset.tab)));

document.querySelectorAll(".file-btn").forEach(l => {
  l.insertAdjacentHTML("afterbegin", ICONS.attach);
});
const dmBtn = document.querySelector("#dm-compose button[type=submit]");
if (dmBtn) dmBtn.innerHTML = ICONS.send + "<span>Send</span>";

// ---------------------------------------------------------------------
// Desktop custom chrome (Kreds Windows app shell, Task 3): everything
// here is gated on window.pywebview - a plain browser (dev/demo) gets
// none of it: no body.desktop class, #titlebar stays display:none via
// its static .hidden class, and .desktop-only-panel (style.css) stays
// display:none regardless - the Desktop section lives on the cog-gated
// Settings page now (index.html), not behind a self-view hidden-class
// toggle.
// pywebview may not have injected window.pywebview by the time this
// script runs, so wireDesktopChrome is called on BOTH the "pywebviewready"
// event it fires on window AND a boot-time check right below in case
// it's already there.
// ---------------------------------------------------------------------

// --nav-h feeds .journal-sticky's sticky offset (style.css): measured,
// not hardcoded - the nav's real height depends on font metrics and can
// change if it ever wraps.
function measureNavHeight() {
  const nav = document.querySelector(".appnav");
  if (nav) document.documentElement.style.setProperty(
    "--nav-h", nav.offsetHeight + "px");
}
window.addEventListener("resize", measureNavHeight);
measureNavHeight();
// Nav height depends on font metrics, which can still settle after first
// paint (webfont swap) - re-measure once they're ready rather than only
// on boot + resize.
document.fonts.ready.then(measureNavHeight);

function wireDesktopChrome() {
  if (!window.pywebview) return;
  document.body.classList.add("desktop");
  const bar = document.getElementById("titlebar");
  if (bar) bar.classList.remove("hidden");
  const api = window.pywebview.api;
  const minBtn = document.getElementById("titlebar-min");
  const maxBtn = document.getElementById("titlebar-max");
  const closeBtn = document.getElementById("titlebar-close");
  if (minBtn) minBtn.onclick = () => api.minimize();
  if (maxBtn) maxBtn.onclick = () => api.toggle_maximize();
  if (closeBtn) closeBtn.onclick = async () => {
    // Read the live pref every time (never cached) so a change made in
    // Settings takes effect on the very next close, no reload needed.
    let closeBehavior = "quit";
    try { closeBehavior = (await j("/api/settings")).close_behavior; }
    catch (e) { /* unreadable pref - fail safe to quit, never trap the app open */ }
    if (closeBehavior === "keep") {
      // Newer shells hide to the system tray; an older frozen shell (web
      // payload ahead of core - allowed update skew) has no hide_to_tray,
      // so degrade to the old taskbar-minimize.
      if (api.hide_to_tray) api.hide_to_tray();
      else api.minimize();
    } else api.quit();
  };
  initResizeGrip();
}
window.addEventListener("pywebviewready", wireDesktopChrome);
if (window.pywebview) wireDesktopChrome();   // already ready before this script ran

// Corner resize grip (0.3.13): rebuilds the drag-resize a frameless
// window loses (no OS borders). Desktop only - the grip stays hidden in
// a browser. One bridge call per animation frame: unthrottled
// pointermoves flood the pywebview Invoke marshal. Called from
// wireDesktopChrome() so it shares that function's own retry idiom
// (pywebviewready event + boot-time check) for late bridge injection.
function initResizeGrip() {
  const grip = document.getElementById("win-resize");
  if (!grip || !window.pywebview || !window.pywebview.api) return;
  // wireDesktopChrome (its caller) can itself run twice under the same
  // late-bridge race its own comment documents (pywebviewready firing
  // after the boot-time check already saw window.pywebview) - guard the
  // same way the titlebar buttons do (idempotent re-wiring), just via
  // the "on" class already used as the reveal flag, so a second call
  // never stacks a second set of pointer listeners on the grip.
  if (grip.classList.contains("on")) return;
  grip.classList.add("on");
  let startX = 0, startY = 0, startW = 0, startH = 0, raf = 0;
  let nextW = 0, nextH = 0;
  const push = () => {
    raf = 0;
    window.pywebview.api.resize_to(nextW, nextH);
  };
  grip.addEventListener("pointerdown", (e) => {
    // primary button/touch only, matching the wall drag's house rule -
    // native grips don't respond to right-drag or a second finger
    if ((e.button != null && e.button !== 0) || !e.isPrimary) return;
    startX = e.screenX; startY = e.screenY;
    startW = window.innerWidth; startH = window.innerHeight;
    grip.setPointerCapture(e.pointerId);
    e.preventDefault();
  });
  grip.addEventListener("pointermove", (e) => {
    if (!grip.hasPointerCapture || !grip.hasPointerCapture(e.pointerId)) return;
    nextW = startW + (e.screenX - startX);
    nextH = startH + (e.screenY - startY);
    if (!raf) raf = requestAnimationFrame(push);
  });
  const drop = (e) => {
    if (grip.hasPointerCapture && grip.hasPointerCapture(e.pointerId))
      grip.releasePointerCapture(e.pointerId);
    // flush, don't discard: a pending frame holds the LAST computed size,
    // and dropping it would settle the window one throttle-frame short of
    // where the pointer was released (review finding)
    if (raf) { cancelAnimationFrame(raf); push(); }
  };
  grip.addEventListener("pointerup", drop);
  grip.addEventListener("pointercancel", drop);
}

// ---------------------------------------------------------------------
// One-time onboarding wizard (Task 3): shown once right after a fresh
// node finishes create/pair-install, gated by the NEEDS_WIZARD module
// flag (boot() below sets it from GET /api/bootstrap's onboarding_done -
// Task 2). Two steps - (A) an optional, genuinely skippable App-lock
// setup that posts to the same /api/applock/setup endpoint as the
// Settings panel (renderApplockSettings above), and (B) an honest
// "iPhone is in development" note - then POST /api/onboarding-done so it
// never shows again. Mirrors the first-run/lock-screen hide-#app pattern
// so nothing behind the gate is Tab/AT-reachable while it's up.
// ---------------------------------------------------------------------

function onboardingWizardKeydown(ev) {
  // One-time setup: Esc must not silently dismiss without marking done
  // (that would just re-show the wizard on the next boot) - it skips
  // straight to Done instead, the same net effect as declining every step.
  if (ev.key === "Escape") { ev.preventDefault(); finishOnboardingWizard(); }
}

async function finishOnboardingWizard() {
  try { await fetch("/api/onboarding-done", {method: "POST"}); }
  catch (e) { /* best-effort - a failed POST just means it may show again next boot */ }
  NEEDS_WIZARD = false;
  document.getElementById("onboarding-wizard").classList.add("hidden");
  document.removeEventListener("keydown", onboardingWizardKeydown);
  // Whole-branch review, IMPORTANT #2: if the node autolocked while the
  // wizard was open, renderLockScreen already hid #app/tabbar and is
  // showing #lock-screen right now - unconditionally unhiding #app/tabbar
  // here would restore gated content to the Tab/AT tree BEHIND the lock
  // overlay. Only unhide when the lock screen isn't the thing currently
  // shown; onboarding_done is already marked above either way, so the
  // wizard stays finished (once-only) once the node is unlocked.
  if (document.getElementById("lock-screen").classList.contains("hidden")) {
    document.getElementById("app").classList.remove("hidden");
    const tabbar = document.querySelector(".tabbar-mobile");
    if (tabbar) tabbar.classList.remove("hidden");
  }
}

// Toggles the two step <section>s + dot indicator, and focuses the new
// step's first control (keyboard-accessible, mirrors renderFirstRun/
// renderLockScreen landing focus in the primary field on show).
function showWizardStep(i) {
  document.getElementById("wiz-step-applock").classList.toggle("hidden", i !== 0);
  document.getElementById("wiz-step-phone").classList.toggle("hidden", i !== 1);
  document.getElementById("wiz-step-desktop").classList.toggle("hidden", i !== 2);
  document.getElementById("wiz-dot-0").classList.toggle("active", i === 0);
  document.getElementById("wiz-dot-1").classList.toggle("active", i === 1);
  // wiz-dot-2 (desktop-only close-behavior step) only exists once
  // renderOnboardingWizard has appended it (window.pywebview) - a plain
  // browser never creates it, so this step index is simply unreachable
  // there and the null-check is enough.
  const dot2 = document.getElementById("wiz-dot-2");
  if (dot2) dot2.classList.toggle("active", i === 2);
  const step = i === 0 ? document.getElementById("wiz-step-applock")
             : i === 1 ? document.getElementById("wiz-step-phone")
             : document.getElementById("wiz-step-desktop");
  const first = step.querySelector("select, input, button");
  if (first) first.focus();
}

async function renderOnboardingWizard() {
  document.getElementById("app").classList.add("hidden");
  const tabbar = document.querySelector(".tabbar-mobile");
  if (tabbar) tabbar.classList.add("hidden");
  document.getElementById("onboarding-wizard").classList.remove("hidden");
  document.addEventListener("keydown", onboardingWizardKeydown);

  // -- Step A: App-lock - genuinely skippable, Skip never calls setup --
  const stepLock = document.getElementById("wiz-step-applock");
  stepLock.replaceChildren();
  stepLock.append(el("h2", "wiz-title", "Protect this device"));
  const status = await getApplockStatus();
  if (status.enabled) {
    // Edge case: App-lock was already enabled before onboarding finished
    // (e.g. a prior session set it up but never reached Done) - nothing
    // to set up, just move on.
    stepLock.append(el("p", "hint", "App-lock is already set up on this device."));
    const nextBtn = el("button", "btn-accent", "Continue"); nextBtn.type = "button";
    nextBtn.onclick = () => showWizardStep(1);
    stepLock.append(nextBtn);
  } else {
    stepLock.append(el("p", "hint", "Protect this device with a PIN or passphrase."));
    const typeSel = document.createElement("select");
    typeSel.setAttribute("aria-label", "Lock type");
    for (const [v, label] of [["pin", "PIN (numbers)"], ["passphrase", "Passphrase"]]) {
      const o = document.createElement("option"); o.value = v; o.textContent = label;
      typeSel.append(o);
    }
    const cred = document.createElement("input");
    cred.type = "password"; cred.autocomplete = "off"; cred.placeholder = "Choose a credential";
    cred.setAttribute("aria-label", "Credential");
    const confirm = document.createElement("input");
    confirm.type = "password"; confirm.autocomplete = "off"; confirm.placeholder = "Confirm";
    confirm.setAttribute("aria-label", "Confirm credential");
    const err = el("div", "hint");
    const enableBtn = el("button", "btn-accent", "Enable App-lock"); enableBtn.type = "button";
    enableBtn.onclick = async () => {
      err.textContent = "";
      if (!cred.value) { err.textContent = "Enter a credential."; return; }
      if (cred.value !== confirm.value) { err.textContent = "Credentials don't match."; return; }
      const r = await fetch("/api/applock/setup", {method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({credential: cred.value, cred_type: typeSel.value})});
      if (!r.ok) { err.textContent = "Couldn't enable: " + await r.text(); return; }
      showWizardStep(1);
    };
    const skipBtn = el("button", "", "Skip"); skipBtn.type = "button";
    skipBtn.onclick = () => showWizardStep(1);   // genuinely skippable - no setup call at all
    const row = el("div", "wiz-actions");
    row.append(enableBtn, skipBtn);
    stepLock.append(typeSel, cred, confirm, err, row);
  }

  // -- Step B: Phone/iOS card - honest informational note, no other action --
  const stepPhone = document.getElementById("wiz-step-phone");
  stepPhone.replaceChildren();
  stepPhone.append(el("h2", "wiz-title", "Kreds for iPhone"));
  stepPhone.append(el("p", "hint",
    "Kreds for iPhone is in development. You'll pair your phone to this node when it ships; "
    + "you can pair any device anytime from Settings."));
  const doneBtn = el("button", "btn-accent", "Continue"); doneBtn.type = "button";
  // Desktop-only (Task 3): the close-behavior step comes next instead of
  // finishing straight away. A plain browser has no such step - Continue
  // keeps its original behavior of finishing the wizard directly.
  doneBtn.onclick = () => (window.pywebview ? showWizardStep(2) : finishOnboardingWizard());
  stepPhone.append(doneBtn);

  // -- Step C: close-behavior (desktop-only) - the Windows shell's Quit
  // vs Keep-running-in-the-background choice for the frameless window,
  // changeable later in Settings (renderDesktopSettings, same endpoint).
  // Only reachable from Step B's Continue when window.pywebview exists;
  // a plain browser never builds this step's content or its dot.
  if (window.pywebview) {
    let dot2 = document.getElementById("wiz-dot-2");
    if (!dot2) {
      dot2 = el("span", "wiz-dot"); dot2.id = "wiz-dot-2";
      document.querySelector(".wiz-dots").append(dot2);
    }
    const stepDesktop = document.getElementById("wiz-step-desktop");
    stepDesktop.replaceChildren();
    stepDesktop.append(el("h2", "wiz-title", "When you close the window"));
    stepDesktop.append(el("p", "hint",
      "Quit stops Kreds entirely. Keep running in the background lets it keep "
      + "syncing with your kreds while the window is hidden in the system tray."));
    const pickClose = async (v) => {
      await fetch("/api/settings", {method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({close_behavior: v})});
      finishOnboardingWizard();
    };
    const quitBtn = el("button", "btn-accent", "Quit the app"); quitBtn.type = "button";
    quitBtn.onclick = () => pickClose("quit");
    const keepBtn = el("button", "", "Keep running in the background (in the system tray)"); keepBtn.type = "button";
    keepBtn.onclick = () => pickClose("keep");
    const row = el("div", "wiz-actions");
    row.append(quitBtn, keepBtn);
    stepDesktop.append(row);
  }

  showWizardStep(0);
}

// APPLOCK_BOOTED guards connectWs/ceremonyUI/service-worker-registration
// (and the WS-reconnect / heartbeat they start) from running twice: boot()
// runs once at page load and may return early (locked); bootAfterUnlock()
// then runs this same one-time tail once the node actually unlocks.
let APPLOCK_BOOTED = false;

async function bootData() {
  if (APPLOCK_BOOTED) { await refresh(); return; }
  APPLOCK_BOOTED = true;
  // restoreView() (esp. the "me" case) needs STATE.identity_pub, which
  // only exists after refresh()'s /api/state fetch resolves - await it
  // rather than firing both at once.
  await refresh();
  restoreView();
  connectWs();
  ceremonyUI();
  startApplockHeartbeat();
  startActivityPing();
  // PWA: register the service worker at the app ROOT. A worker served from
  // under the static mount can only ever control paths under that mount;
  // serving it at / lets its scope cover the whole app - see hearth/api.py's
  // `GET /sw.js` route.
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js");
  // One-time wizard (Task 3): #app is already populated above but was kept
  // hidden by boot() below if NEEDS_WIZARD - render it now, on top of the
  // (invisible-until-dismissed) real app, so there's no flash of app
  // content before the wizard ever appears.
  if (NEEDS_WIZARD) renderOnboardingWizard();
}

async function bootAfterUnlock() {
  await bootData();
}

// Nothing else renders while locked: check GET /api/applock FIRST, before
// any content fetch - a locked node shows only #lock-screen. Unlocking
// (the lock-form submit handler above) calls bootAfterUnlock() to run the
// deferred data boot.
//
// Before even the applock check, GET /api/bootstrap: a node with no
// enrolled identity yet (first run, or mid a Connect pairing) answers
// {initialized:false} and the client shows Create/Connect instead of any
// app content. A fetch error here (node unreachable) defaults to
// initialized:true rather than stranding a working, already-enrolled node
// behind a screen meant only for brand-new installs.
async function boot() {
  let b;
  try {
    const r = await fetch("/api/bootstrap");
    // A non-2xx (whole-branch review, IMPORTANT #1: e.g. 410 "device
    // revoked" from revoked_gate - hearth/api.py - which does NOT
    // allowlist /api/bootstrap, only /api/state) must not be parsed as a
    // bootstrap body: its {"detail": "device revoked"} shape has no
    // "initialized" key, so unconditionally parsing it would make
    // !b.initialized true and wrongly render first-run over a revoked
    // device instead of falling through to the applock/state path below,
    // which already renders the revoked banner. Treat any non-2xx like
    // the network-error case below.
    b = r.ok ? await r.json() : {initialized: true, onboarding_done: true};
  }
  // A transient failure here defaults to initialized (never strand a
  // working node behind first-run) AND onboarding_done (never re-flash the
  // one-time wizard at an already-onboarded node over a flaky boot fetch).
  catch (e) { b = {initialized: true, onboarding_done: true}; }
  if (!b.initialized) { renderFirstRun(); return; }
  const status = await getApplockStatus();
  if (status.locked) { renderLockScreen(status); return; }
  if (!b.onboarding_done) NEEDS_WIZARD = true;   // module flag consumed by bootData (Task 3)
  hideLockScreen();
  // hideLockScreen() just unhid #app - if the one-time wizard is about to
  // run, re-hide it immediately (same task, before the browser paints) so
  // bootData() below can populate #app in the background without ever
  // flashing the real app underneath the wizard.
  if (NEEDS_WIZARD) {
    document.getElementById("app").classList.add("hidden");
    const tabbar = document.querySelector(".tabbar-mobile");
    if (tabbar) tabbar.classList.add("hidden");
  }
  await bootData();
}

boot().catch(err => console.error("boot failed", err));
