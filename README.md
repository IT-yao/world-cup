# World Cup Watch Party

微信群世界杯竞猜 H5：赛程、AI 赛前分析、胜平负/比分竞猜、排行榜、个人竞猜记录。

## Local Run

```powershell
cd C:\Users\T\Documents\Codex\2026-06-11\new-chat\work\worldcup-watch-party
mvn clean package
java -cp "target/classes;target/dependency/*" WatchPartyServer
```

Open:

```text
http://localhost:8080
```

本地没有 `DATABASE_URL` 时会使用 `data/predictions.tsv` 保存竞猜。

## Free Deploy: Render + Neon

1. Create a free Neon Postgres project.
2. Copy the pooled or normal connection string. It should look like:

```text
postgresql://user:password@host/database?sslmode=require
```

3. Push this folder to GitHub.
4. In Render, create a new Web Service from the GitHub repository.
5. Render will read `render.yaml` and use the Dockerfile.
6. Add environment variable:

```text
DATABASE_URL=你的 Neon Postgres 连接串
```

7. Deploy and open the Render URL.

Render sets `PORT` automatically. The app creates the `predictions` table automatically on startup, so `schema.sql` is only for reference.

## API

- `GET /api/state?nickname=Aqiang`
- `GET /api/matches`
- `GET /api/leaderboard`
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
