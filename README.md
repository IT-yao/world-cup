# 球友猜猜

微信群世界杯竞猜 H5：近期比赛、真实阵容、赔率参考、胜平负/比分竞猜、排行榜、个人竞猜记录。

## Local Run

```powershell
cd D:\idea-work\world-cup
mvn clean package
java -cp "target/classes;target/dependency/*" WatchPartyServer
```

Open:

```text
http://localhost:8080
```

本地没有 `DATABASE_URL` 时会使用 `data/predictions.tsv` 保存竞猜。

## API-Football Live Data

默认会优先尝试从中国体育彩票公开页面对应的 Sporttery 接口同步足球胜平负数据：

- 赛场/赛事
- 开赛时间
- 胜平负赔率
- 让球胜平负赔率
- 竞猜支持率
- 自动生成赛前分析
- 胜平负计算器：选择多场结果、倍数、注数、成本和最高预计回报

相关环境变量：

```text
SPORTTERY_ENABLED=true
SPORTTERY_CHANNEL=c
SPORTTERY_POOL_CODE=had,hhad
LIVE_DATA_REFRESH_MINUTES=15
```

如果 Sporttery 接口被 WAF 或网络策略拦截，应用会自动回退到 API-Football；只有两个真实数据源都未配置时，才会使用演示数据。

配置 `FOOTBALL_API_KEY` 后，服务也可以从 API-Football 同步近期比赛，并尝试获取：

- 真实赛程
- 比赛状态
- 首发阵容
- 胜平负赔率
- 大小球赔率

阵容通常在开赛前约 60 分钟才公布；赔率仅用于群友娱乐讨论，不构成投注建议。

环境变量：

```text
FOOTBALL_API_KEY=你的 API-Football key
FOOTBALL_LEAGUE_ID=1
FOOTBALL_SEASON=2026
FOOTBALL_NEXT_MATCHES=6
LIVE_DATA_REFRESH_MINUTES=15
APP_ZONE=Asia/Shanghai
```

没有 `FOOTBALL_API_KEY` 时，应用会继续使用演示数据。

## Free Deploy: Render + Neon

1. Create a free Neon Postgres project.
2. Copy the connection string:

```text
postgresql://user:password@host/database?sslmode=require
```

3. Push this repository to GitHub.
4. In Render, create a Web Service from the GitHub repository.
5. Render will read `render.yaml` and use the Dockerfile.
6. Add environment variables:

```text
DATABASE_URL=你的 Neon Postgres 连接串
SPORTTERY_ENABLED=true
FOOTBALL_API_KEY=你的 API-Football key（可选）
```

Render sets `PORT` automatically. The app creates the `predictions` table automatically on startup, so `schema.sql` is only for reference.

## API

- `GET /api/state?nickname=Aqiang`
- `GET /api/matches`
- `GET /api/leaderboard`
- `GET /api/live-status`
- `POST /api/sync-live`
- `POST /api/predictions`

Example:

```json
{
  "nickname": "Aqiang",
  "matchId": "m1",
  "winner": "HOME",
  "homeScore": 2,
  "awayScore": 1
}
```
