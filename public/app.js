const state = {
  matches: [],
  leaderboard: [],
  mine: [],
  live: {},
  nickname: localStorage.getItem("watchPartyName") || "",
  calc: JSON.parse(localStorage.getItem("watchPartyCalc") || "{}")
};

const $ = (selector) => document.querySelector(selector);
const nicknameInput = $("#nickname");
const toast = $("#toast");

nicknameInput.value = state.nickname;

$("#saveNameBtn").addEventListener("click", () => {
  state.nickname = nicknameInput.value.trim();
  localStorage.setItem("watchPartyName", state.nickname);
  loadState();
  showToast("昵称已保存");
});

$("#refreshBtn").addEventListener("click", async () => {
  await fetch("/api/sync-live", { method: "POST" });
  await loadState();
  showToast("已刷新赛场数据");
});

$("#calcTimes").addEventListener("input", renderCalculator);

async function loadState() {
  const url = `/api/state?nickname=${encodeURIComponent(state.nickname)}`;
  const response = await fetch(url);
  const data = await response.json();
  state.matches = data.matches || [];
  state.leaderboard = data.leaderboard || [];
  state.mine = data.mine || [];
  state.live = data.live || {};
  render();
}

function render() {
  renderLiveStatus();
  renderMatches();
  renderLeaderboard();
  renderMine();
  renderCalculator();
}

function renderLiveStatus() {
  const names = {
    sporttery: "中国体育彩票",
    "api-football": "API-Football",
    demo: "演示数据"
  };
  const provider = names[state.live.provider] || "未知数据源";
  const sync = state.live.lastSync ? `，上次同步 ${formatDate(new Date(state.live.lastSync))}` : "";
  const error = state.live.error ? `，同步异常：${state.live.error}` : "";
  $("#liveStatus").textContent = `数据源：${provider}${sync}${error}`;
}

function renderMatches() {
  $("#matchCount").textContent = `${state.matches.length} 场`;
  const list = $("#matches");
  const template = $("#matchTemplate");
  list.innerHTML = "";

  if (!state.matches.length) {
    list.innerHTML = `<div class="empty">暂无赛场数据。请点右上角刷新，或检查 Sporttery 数据源是否可访问。</div>`;
    return;
  }

  state.matches.forEach((match) => {
    const node = template.content.cloneNode(true);
    const card = node.querySelector(".match-card");
    const kickoff = new Date(match.kickoff);
    const locked = kickoff <= new Date();
    const mine = state.mine.find((item) => item.matchId === match.id);

    card.dataset.matchId = match.id;
    node.querySelector(".stage").textContent = `${match.stage} · ${match.status || "未开始"}`;
    node.querySelector(".kickoff").textContent = `${formatDate(kickoff)} ${locked ? "已锁定" : countdown(kickoff)}`;
    node.querySelector(".home").textContent = match.home;
    node.querySelector(".away").textContent = match.away;
    node.querySelector(".homeWin").textContent = `${match.homeWin || 0}%`;
    node.querySelector(".draw").textContent = `${match.draw || 0}%`;
    node.querySelector(".awayWin").textContent = `${match.awayWin || 0}%`;
    node.querySelector(".analysis").textContent = match.analysis || "暂无分析。";

    renderLineups(node, match);
    renderOdds(node, match);
    bindCalculatorPicks(node, match);

    const form = node.querySelector(".predict-form");
    if (mine) {
      form.querySelector(`[value="${mine.winner}"]`).checked = true;
      form.homeScore.value = mine.homeScore;
      form.awayScore.value = mine.awayScore;
    }

    if (locked) {
      form.querySelectorAll("input, button").forEach((item) => item.disabled = true);
    }

    form.addEventListener("submit", (event) => submitPrediction(event, match.id));

    const result = node.querySelector(".result");
    if (match.result) {
      result.textContent = `赛果：${match.home} ${match.result.homeScore}:${match.result.awayScore} ${match.away}`;
    } else if (locked) {
      result.textContent = "已开赛，竞猜锁定。";
    } else if (mine) {
      result.textContent = `你已猜：${winnerText(mine.winner)}，比分 ${mine.homeScore}:${mine.awayScore}`;
    }

    list.appendChild(node);
  });
}

function renderLineups(node, match) {
  const box = node.querySelector(".lineups");
  if (!match.lineups?.available) {
    box.innerHTML = `<p class="muted">阵容未公布，通常开赛前约 60 分钟更新。</p>`;
    return;
  }
  box.innerHTML = `
    <div class="lineup-grid">
      <div>
        <strong>${escapeHtml(match.home)} ${escapeHtml(match.lineups.homeFormation || "")}</strong>
        <p>${escapeHtml((match.lineups.homePlayers || []).slice(0, 11).join("、"))}</p>
      </div>
      <div>
        <strong>${escapeHtml(match.away)} ${escapeHtml(match.lineups.awayFormation || "")}</strong>
        <p>${escapeHtml((match.lineups.awayPlayers || []).slice(0, 11).join("、"))}</p>
      </div>
    </div>
  `;
}

function renderOdds(node, match) {
  const odds = match.odds || {};
  node.querySelector(".bookmaker").textContent = odds.available
    ? `${odds.bookmaker || "竞彩平台"}${odds.updatedAt ? ` · ${odds.updatedAt}` : ""}`
    : "暂无竞彩数据";
  node.querySelector(".oddsHome").textContent = odds.home || "-";
  node.querySelector(".oddsDraw").textContent = odds.draw || "-";
  node.querySelector(".oddsAway").textContent = odds.away || "-";
  node.querySelector(".supportHome").textContent = odds.supportHome ? `主胜支持 ${odds.supportHome}` : "主胜支持 -";
  node.querySelector(".supportDraw").textContent = odds.supportDraw ? `平局支持 ${odds.supportDraw}` : "平局支持 -";
  node.querySelector(".supportAway").textContent = odds.supportAway ? `客胜支持 ${odds.supportAway}` : "客胜支持 -";
  node.querySelector(".overUnder").textContent = odds.overUnder ? `让球胜平负：${odds.overUnder}` : "让球胜平负：暂无";
}

function bindCalculatorPicks(node, match) {
  const odds = match.odds || {};
  const oddsMap = { HOME: odds.home, DRAW: odds.draw, AWAY: odds.away };
  node.querySelectorAll(".calc-pick").forEach((button) => {
    const outcome = button.dataset.outcome;
    const odd = Number(oddsMap[outcome]);
    const selected = Boolean(state.calc[match.id]?.[outcome]);
    button.disabled = !odd;
    button.classList.toggle("selected", selected);
    button.addEventListener("click", () => {
      state.calc[match.id] ||= {
        title: `${match.home} vs ${match.away}`,
        picks: {}
      };
      state.calc[match.id].title = `${match.home} vs ${match.away}`;
      state.calc[match.id].picks[outcome] = state.calc[match.id].picks[outcome]
        ? undefined
        : { label: winnerText(outcome), odd };
      if (!state.calc[match.id].picks[outcome]) {
        delete state.calc[match.id].picks[outcome];
      }
      if (!Object.keys(state.calc[match.id].picks).length) {
        delete state.calc[match.id];
      }
      localStorage.setItem("watchPartyCalc", JSON.stringify(state.calc));
      renderMatches();
      renderCalculator();
    });
  });
}

function renderCalculator() {
  const times = Math.max(1, Number($("#calcTimes").value) || 1);
  let pickCount = 0;
  let tickets = 1;
  let maxOdd = 1;
  const rows = [];

  Object.entries(state.calc).forEach(([matchId, entry]) => {
    const picks = Object.values(entry.picks || {});
    if (!picks.length) return;
    pickCount += picks.length;
    tickets *= picks.length;
    maxOdd *= Math.max(...picks.map((pick) => Number(pick.odd) || 1));
    rows.push(`
      <div class="calc-row">
        <strong>${escapeHtml(entry.title)}</strong>
        <span>${picks.map((pick) => `${escapeHtml(pick.label)} ${pick.odd}`).join(" / ")}</span>
      </div>
    `);
  });

  if (!rows.length) {
    tickets = 0;
    maxOdd = 0;
  }

  const cost = tickets * 2 * times;
  const maxReturn = maxOdd * 2 * times;
  $("#calcPickCount").textContent = `${pickCount} 项`;
  $("#calcTickets").textContent = tickets;
  $("#calcCost").textContent = `${cost.toFixed(0)} 元`;
  $("#calcReturn").textContent = `${maxReturn.toFixed(2)} 元`;
  $("#calcSelections").innerHTML = rows.length ? rows.join("") : `<div class="empty">点击任意赛场的主胜/平局/客胜加入计算器</div>`;
}

async function submitPrediction(event, matchId) {
  event.preventDefault();
  state.nickname = nicknameInput.value.trim();
  if (!state.nickname) {
    showToast("先填一个群昵称");
    nicknameInput.focus();
    return;
  }
  localStorage.setItem("watchPartyName", state.nickname);

  const form = event.currentTarget;
  const body = {
    nickname: state.nickname,
    matchId,
    winner: form.winner.value,
    homeScore: Number(form.homeScore.value),
    awayScore: Number(form.awayScore.value)
  };

  const response = await fetch("/api/predictions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  const data = await response.json();
  if (!response.ok) {
    showToast(data.error || "提交失败");
    return;
  }
  showToast(data.message || "已保存");
  loadState();
}

function renderLeaderboard() {
  const box = $("#leaderboard");
  if (!state.leaderboard.length) {
    box.innerHTML = `<div class="empty">还没有已结算竞猜</div>`;
    return;
  }
  box.innerHTML = state.leaderboard.map((item) => `
    <div class="rank-row">
      <div class="rank ${item.rank <= 3 ? "top" : ""}">${item.rank}</div>
      <div>
        <strong>${escapeHtml(item.nickname)}</strong>
        <div class="meta">命中 ${item.hits}/${item.played}，准确比分 ${item.exact}</div>
      </div>
      <div class="points">${item.points} 分</div>
    </div>
  `).join("");
}

function renderMine() {
  $("#mineCount").textContent = `${state.mine.length} 条`;
  const box = $("#mine");
  if (!state.mine.length) {
    box.innerHTML = `<div class="empty">输入昵称后提交第一场竞猜</div>`;
    return;
  }
  box.innerHTML = state.mine.map((item) => {
    const match = state.matches.find((entry) => entry.id === item.matchId);
    const title = match ? `${match.home} vs ${match.away}` : item.matchId;
    return `
      <div class="mine-row">
        <div>球</div>
        <div>
          <strong>${escapeHtml(title)}</strong>
          <div class="meta">${winnerText(item.winner)}，比分 ${item.homeScore}:${item.awayScore}</div>
        </div>
        <div class="points">${formatTime(new Date(item.createdAt))}</div>
      </div>
    `;
  }).join("");
}

function formatDate(date) {
  if (Number.isNaN(date.getTime())) return "";
  return `${String(date.getMonth() + 1).padStart(2, "0")}/${String(date.getDate()).padStart(2, "0")} ${formatTime(date)}`;
}

function formatTime(date) {
  if (Number.isNaN(date.getTime())) return "";
  return `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
}

function countdown(date) {
  const minutes = Math.max(0, Math.round((date - new Date()) / 60000));
  if (minutes < 60) return `${minutes} 分钟后`;
  return `${Math.floor(minutes / 60)} 小时后`;
}

function winnerText(value) {
  return { HOME: "主胜", DRAW: "平局", AWAY: "客胜" }[value] || value;
}

function showToast(message) {
  toast.textContent = message;
  toast.hidden = false;
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.hidden = true, 1800);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#039;");
}

loadState();
