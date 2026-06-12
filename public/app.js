const state = {
  matches: [],
  leaderboard: [],
  mine: [],
  live: {},
  nickname: localStorage.getItem("watchPartyName") || ""
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
  showToast("已刷新赛事情报");
});

async function loadState() {
  const url = `/api/state?nickname=${encodeURIComponent(state.nickname)}`;
  const response = await fetch(url);
  const data = await response.json();
  state.matches = data.matches;
  state.leaderboard = data.leaderboard;
  state.mine = data.mine;
  state.live = data.live || {};
  render();
}

function render() {
  renderLiveStatus();
  renderMatches();
  renderLeaderboard();
  renderMine();
}

function renderLiveStatus() {
  const provider = state.live.configured ? "API-Football" : "演示数据";
  const sync = state.live.lastSync ? `，上次同步 ${formatDate(new Date(state.live.lastSync))}` : "";
  $("#liveStatus").textContent = `数据源：${provider}${sync}`;
}

function renderMatches() {
  $("#matchCount").textContent = `${state.matches.length} 场`;
  const list = $("#matches");
  const template = $("#matchTemplate");
  list.innerHTML = "";

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
    node.querySelector(".analysis").textContent = match.analysis;

    renderLineups(node, match);
    renderOdds(node, match);

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
    ? `${odds.bookmaker || "赔率平台"}${odds.updatedAt ? ` · ${formatDate(new Date(odds.updatedAt))}` : ""}`
    : "暂无赔率数据";
  node.querySelector(".oddsHome").textContent = odds.home ? `主胜 ${odds.home}` : "主胜 -";
  node.querySelector(".oddsDraw").textContent = odds.draw ? `平局 ${odds.draw}` : "平局 -";
  node.querySelector(".oddsAway").textContent = odds.away ? `客胜 ${odds.away}` : "客胜 -";
  node.querySelector(".supportHome").textContent = odds.supportHome ? `主胜支持 ${odds.supportHome}` : "主胜支持 -";
  node.querySelector(".supportDraw").textContent = odds.supportDraw ? `平局支持 ${odds.supportDraw}` : "平局支持 -";
  node.querySelector(".supportAway").textContent = odds.supportAway ? `客胜支持 ${odds.supportAway}` : "客胜支持 -";
  node.querySelector(".overUnder").textContent = odds.overUnder ? `扩展盘口：${odds.overUnder}` : "扩展盘口：暂无";
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
  return `${String(date.getMonth() + 1).padStart(2, "0")}/${String(date.getDate()).padStart(2, "0")} ${formatTime(date)}`;
}

function formatTime(date) {
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
