import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WatchPartyServer {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Path PUBLIC_DIR = Path.of("public").toAbsolutePath().normalize();
    private static final Path DATA_FILE = Path.of("data", "predictions.tsv").toAbsolutePath().normalize();
    private static final List<Match> MATCHES = new ArrayList<>();
    private static final List<Prediction> PREDICTIONS = new ArrayList<>();
    private static final ZoneId APP_ZONE = ZoneId.of(System.getenv().getOrDefault("APP_ZONE", "Asia/Shanghai"));
    private static final int LIVE_REFRESH_MINUTES = intEnv("LIVE_DATA_REFRESH_MINUTES", 15);
    private static PredictionStore store;
    private static FootballDataClient footballDataClient;
    private static LocalDateTime lastLiveSync;

    public static void main(String[] args) throws Exception {
        store = createStore();
        footballDataClient = FootballDataClient.fromEnv();
        seedData();
        refreshLiveDataIfNeeded(true);

        String envPort = System.getenv("PORT");
        int port = Integer.parseInt(envPort == null || envPort.isBlank() ? System.getProperty("port", "8080") : envPort);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", WatchPartyServer::route);
        server.setExecutor(null);
        server.start();
        System.out.println("QiuYou CaiCai is running at http://localhost:" + port);
    }

    private static void route(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                routeApi(exchange, path);
                return;
            }
            serveStatic(exchange, path);
        } catch (Exception ex) {
            sendJson(exchange, 500, "{\"error\":\"" + escape(ex.getMessage()) + "\"}");
        }
    }

    private static void routeApi(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if ("GET".equals(method) && "/api/state".equals(path)) {
            refreshLiveDataIfNeeded(false);
            sendJson(exchange, 200, stateJson(query(exchange.getRequestURI()).getOrDefault("nickname", "")));
            return;
        }
        if ("GET".equals(method) && "/api/matches".equals(path)) {
            refreshLiveDataIfNeeded(false);
            sendJson(exchange, 200, matchesJson());
            return;
        }
        if ("GET".equals(method) && "/api/leaderboard".equals(path)) {
            sendJson(exchange, 200, leaderboardJson());
            return;
        }
        if ("GET".equals(method) && "/api/live-status".equals(path)) {
            sendJson(exchange, 200, liveStatusJson());
            return;
        }
        if ("POST".equals(method) && "/api/sync-live".equals(path)) {
            refreshLiveDataIfNeeded(true);
            sendJson(exchange, 200, liveStatusJson());
            return;
        }
        if ("POST".equals(method) && "/api/predictions".equals(path)) {
            savePrediction(exchange);
            return;
        }
        sendJson(exchange, 404, "{\"error\":\"Not found\"}");
    }

    private static void savePrediction(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String nickname = field(body, "nickname").trim();
        String matchId = field(body, "matchId").trim();
        String winner = field(body, "winner").trim();
        int homeScore = intField(body, "homeScore");
        int awayScore = intField(body, "awayScore");

        if (nickname.isBlank() || matchId.isBlank() || winner.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"nickname, match and winner are required\"}");
            return;
        }
        Optional<Match> match = MATCHES.stream().filter(item -> item.id.equals(matchId)).findFirst();
        if (match.isEmpty()) {
            sendJson(exchange, 404, "{\"error\":\"match not found\"}");
            return;
        }
        if (match.get().kickoff.isBefore(LocalDateTime.now())) {
            sendJson(exchange, 409, "{\"error\":\"match already started\"}");
            return;
        }

        PREDICTIONS.removeIf(item -> item.matchId.equals(matchId) && item.nickname.equalsIgnoreCase(nickname));
        PREDICTIONS.add(new Prediction(UUID.randomUUID().toString(), nickname, matchId, winner, homeScore, awayScore, LocalDateTime.now()));
        store.save(PREDICTIONS);
        sendJson(exchange, 200, "{\"ok\":true,\"message\":\"saved\"}");
    }

    private static void refreshLiveDataIfNeeded(boolean force) {
        if (footballDataClient == null) return;
        if (!force && lastLiveSync != null && Duration.between(lastLiveSync, LocalDateTime.now()).toMinutes() < LIVE_REFRESH_MINUTES) {
            return;
        }
        try {
            List<Match> liveMatches = footballDataClient.fetchUpcomingMatches();
            if (!liveMatches.isEmpty()) {
                MATCHES.clear();
                MATCHES.addAll(liveMatches);
                lastLiveSync = LocalDateTime.now();
                System.out.println("Synced " + liveMatches.size() + " football matches from API-Football.");
            }
        } catch (Exception ex) {
            System.err.println("Failed to sync football data: " + ex.getMessage());
            lastLiveSync = LocalDateTime.now();
        }
    }

    private static String stateJson(String nickname) {
        return "{\"matches\":" + matchesJson()
                + ",\"leaderboard\":" + leaderboardJson()
                + ",\"mine\":" + myPredictionsJson(nickname)
                + ",\"live\":" + liveStatusJson()
                + "}";
    }

    private static String liveStatusJson() {
        return "{\"provider\":\"" + (footballDataClient == null ? "demo" : "api-football") + "\","
                + "\"configured\":" + (footballDataClient != null) + ","
                + "\"lastSync\":" + (lastLiveSync == null ? "null" : "\"" + lastLiveSync.format(ISO) + "\"")
                + "}";
    }

    private static String matchesJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < MATCHES.size(); i++) {
            Match match = MATCHES.get(i);
            if (i > 0) json.append(',');
            json.append('{')
                    .append("\"id\":\"").append(match.id).append("\",")
                    .append("\"externalId\":").append(match.externalId == null ? "null" : "\"" + escape(match.externalId) + "\"").append(',')
                    .append("\"stage\":\"").append(escape(match.stage)).append("\",")
                    .append("\"status\":\"").append(escape(match.status)).append("\",")
                    .append("\"home\":\"").append(escape(match.home)).append("\",")
                    .append("\"away\":\"").append(escape(match.away)).append("\",")
                    .append("\"kickoff\":\"").append(match.kickoff.format(ISO)).append("\",")
                    .append("\"homeWin\":").append(match.homeWin).append(',')
                    .append("\"draw\":").append(match.draw).append(',')
                    .append("\"awayWin\":").append(match.awayWin).append(',')
                    .append("\"analysis\":\"").append(escape(match.analysis)).append("\",")
                    .append("\"lineups\":").append(lineupsJson(match)).append(',')
                    .append("\"odds\":").append(oddsJson(match)).append(',')
                    .append("\"result\":").append(match.resultJson())
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static String lineupsJson(Match match) {
        return "{\"homeFormation\":\"" + escape(match.homeFormation) + "\","
                + "\"awayFormation\":\"" + escape(match.awayFormation) + "\","
                + "\"homePlayers\":" + stringArrayJson(match.homePlayers) + ","
                + "\"awayPlayers\":" + stringArrayJson(match.awayPlayers) + ","
                + "\"available\":" + (!match.homePlayers.isEmpty() || !match.awayPlayers.isEmpty())
                + "}";
    }

    private static String oddsJson(Match match) {
        return "{\"home\":\"" + escape(match.oddsHome) + "\","
                + "\"draw\":\"" + escape(match.oddsDraw) + "\","
                + "\"away\":\"" + escape(match.oddsAway) + "\","
                + "\"overUnder\":\"" + escape(match.oddsOverUnder) + "\","
                + "\"bookmaker\":\"" + escape(match.oddsBookmaker) + "\","
                + "\"updatedAt\":\"" + escape(match.oddsUpdatedAt) + "\","
                + "\"available\":" + match.hasOdds()
                + "}";
    }

    private static String stringArrayJson(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(escape(values.get(i))).append('"');
        }
        return json.append(']').toString();
    }

    private static String leaderboardJson() {
        Map<String, ScoreLine> scores = new LinkedHashMap<>();
        for (Prediction prediction : PREDICTIONS) {
            Match match = MATCHES.stream()
                    .filter(item -> item.id.equals(prediction.matchId))
                    .findFirst()
                    .orElse(null);
            if (match == null || !match.finished()) continue;
            ScoreLine line = scores.computeIfAbsent(prediction.nickname, ScoreLine::new);
            line.played++;
            int points = points(prediction, match);
            line.points += points;
            if (points > 0) line.hits++;
            if (points >= 3) line.exact++;
        }

        List<ScoreLine> sorted = new ArrayList<>(scores.values());
        sorted.sort(Comparator.comparingInt((ScoreLine item) -> item.points).reversed()
                .thenComparing(Comparator.comparingInt((ScoreLine item) -> item.exact).reversed())
                .thenComparing(item -> item.nickname));

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < sorted.size(); i++) {
            ScoreLine line = sorted.get(i);
            if (i > 0) json.append(',');
            json.append('{')
                    .append("\"rank\":").append(i + 1).append(',')
                    .append("\"nickname\":\"").append(escape(line.nickname)).append("\",")
                    .append("\"points\":").append(line.points).append(',')
                    .append("\"hits\":").append(line.hits).append(',')
                    .append("\"exact\":").append(line.exact).append(',')
                    .append("\"played\":").append(line.played)
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static String myPredictionsJson(String nickname) {
        if (nickname == null || nickname.isBlank()) return "[]";
        StringBuilder json = new StringBuilder("[");
        int count = 0;
        for (Prediction prediction : PREDICTIONS) {
            if (!prediction.nickname.equalsIgnoreCase(nickname)) continue;
            if (count++ > 0) json.append(',');
            json.append('{')
                    .append("\"matchId\":\"").append(prediction.matchId).append("\",")
                    .append("\"winner\":\"").append(escape(prediction.winner)).append("\",")
                    .append("\"homeScore\":").append(prediction.homeScore).append(',')
                    .append("\"awayScore\":").append(prediction.awayScore).append(',')
                    .append("\"createdAt\":\"").append(prediction.createdAt.format(ISO)).append("\"")
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static int points(Prediction prediction, Match match) {
        if (!match.finished()) return 0;
        int points = expectedWinner(prediction.homeScore, prediction.awayScore).equals(actualWinner(match)) ? 1 : 0;
        if (prediction.homeScore == match.homeScore && prediction.awayScore == match.awayScore) {
            points += 3;
        }
        return points;
    }

    private static String expectedWinner(int homeScore, int awayScore) {
        if (homeScore > awayScore) return "HOME";
        if (homeScore < awayScore) return "AWAY";
        return "DRAW";
    }

    private static String actualWinner(Match match) {
        return expectedWinner(match.homeScore, match.awayScore);
    }

    private static void serveStatic(HttpExchange exchange, String path) throws IOException {
        String cleanPath = "/".equals(path) ? "/index.html" : path;
        Path file = PUBLIC_DIR.resolve(cleanPath.substring(1)).normalize();
        if (!file.startsWith(PUBLIC_DIR) || !Files.exists(file) || Files.isDirectory(file)) {
            sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        sendBytes(exchange, 200, Files.readAllBytes(file), contentType(file));
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) return values;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(decode(parts[0]), parts.length > 1 ? decode(parts[1]) : "");
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String field(String json, String name) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"") : "";
    }

    private static int intField(String json, String name) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        sendText(exchange, status, json, "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int status, String text, String type) throws IOException {
        sendBytes(exchange, status, text.getBytes(StandardCharsets.UTF_8), type);
    }

    private static void sendBytes(HttpExchange exchange, int status, byte[] bytes, String type) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        return "application/octet-stream";
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static void seedData() {
        LocalDateTime now = LocalDateTime.now();
        MATCHES.add(new Match("m0", null, "演示赛", "已结束", "群友队", "熬夜队", now.minusHours(4), 46, 27, 27,
                "这场是排行榜演示赛：群友队节奏更稳，熬夜队后程体能可能掉线。", 2, 1)
                .withLineups("4-3-3", "4-2-3-1",
                        List.of("阿强", "小林", "老王", "小陈"),
                        List.of("夜猫", "咖啡", "闹钟", "加班人"))
                .withOdds("1.95", "3.25", "3.80", "2.5球 大1.88 / 小1.92", "DemoBook", now.format(ISO)));
        MATCHES.add(new Match("m1", null, "小组赛", "未开始", "墨西哥", "南非", now.plusHours(2), 48, 28, 24,
                "墨西哥主场氛围和控球更占优，南非反击速度是最大变数。推荐看边路推进和定位球。", null, null)
                .withOdds("2.10", "3.10", "3.55", "2.5球 大1.90 / 小1.90", "DemoBook", now.format(ISO)));
        MATCHES.add(new Match("m2", null, "小组赛", "未开始", "美国", "加拿大", now.plusHours(6), 41, 31, 28,
                "双方身体对抗会很满，美国中前场冲击更强，加拿大如果先稳住节奏会制造麻烦。", null, null));
        MATCHES.add(new Match("m3", null, "小组赛", "未开始", "西班牙", "日本", now.plusDays(1).plusHours(1), 52, 25, 23,
                "西班牙传控优势明显，日本的高位逼抢和转换速度会让比赛更好看。谨防冷门。", null, null));

        PREDICTIONS.addAll(store.load());
        if (!PREDICTIONS.isEmpty()) return;
        PREDICTIONS.add(new Prediction(UUID.randomUUID().toString(), "阿强", "m0", "HOME", 2, 1, now.minusHours(5)));
        PREDICTIONS.add(new Prediction(UUID.randomUUID().toString(), "小林", "m0", "DRAW", 1, 1, now.minusHours(5)));
        PREDICTIONS.add(new Prediction(UUID.randomUUID().toString(), "老王", "m0", "HOME", 1, 0, now.minusHours(5)));
    }

    private static PredictionStore createStore() throws Exception {
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl != null && !databaseUrl.isBlank()) {
            return new PostgresPredictionStore(databaseUrl);
        }
        return new FilePredictionStore();
    }

    private static int intEnv(String name, int fallback) {
        try {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private interface PredictionStore {
        List<Prediction> load();

        void save(List<Prediction> predictions);
    }

    private static class FilePredictionStore implements PredictionStore {
        @Override
        public List<Prediction> load() {
            List<Prediction> predictions = new ArrayList<>();
            if (!Files.exists(DATA_FILE)) return predictions;
            try {
                for (String line : Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8)) {
                    String[] parts = line.split("\t", -1);
                    if (parts.length != 7) continue;
                    predictions.add(new Prediction(parts[0], parts[1], parts[2], parts[3],
                            Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), LocalDateTime.parse(parts[6], ISO)));
                }
            } catch (Exception ex) {
                System.err.println("Failed to load predictions: " + ex.getMessage());
            }
            return predictions;
        }

        @Override
        public void save(List<Prediction> predictions) {
            try {
                Files.createDirectories(DATA_FILE.getParent());
                List<String> lines = new ArrayList<>();
                for (Prediction prediction : predictions) {
                    lines.add(String.join("\t",
                            prediction.id,
                            prediction.nickname.replace("\t", " "),
                            prediction.matchId,
                            prediction.winner,
                            String.valueOf(prediction.homeScore),
                            String.valueOf(prediction.awayScore),
                            prediction.createdAt.format(ISO)));
                }
                Files.write(DATA_FILE, lines, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                System.err.println("Failed to save predictions: " + ex.getMessage());
            }
        }
    }

    private static class PostgresPredictionStore implements PredictionStore {
        private final String jdbcUrl;

        private PostgresPredictionStore(String databaseUrl) throws Exception {
            this.jdbcUrl = toJdbcUrl(databaseUrl);
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.execute("""
                        create table if not exists predictions (
                            id text primary key,
                            nickname text not null,
                            match_id text not null,
                            winner text not null,
                            home_score int not null,
                            away_score int not null,
                            created_at timestamp not null
                        )
                        """);
                statement.execute("create unique index if not exists predictions_nickname_match_idx on predictions (lower(nickname), match_id)");
            }
        }

        @Override
        public List<Prediction> load() {
            List<Prediction> predictions = new ArrayList<>();
            try (Connection connection = connect();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select id, nickname, match_id, winner, home_score, away_score, created_at from predictions")) {
                while (rows.next()) {
                    predictions.add(new Prediction(
                            rows.getString("id"),
                            rows.getString("nickname"),
                            rows.getString("match_id"),
                            rows.getString("winner"),
                            rows.getInt("home_score"),
                            rows.getInt("away_score"),
                            rows.getTimestamp("created_at").toLocalDateTime()));
                }
            } catch (Exception ex) {
                System.err.println("Failed to load predictions from Postgres: " + ex.getMessage());
            }
            return predictions;
        }

        @Override
        public void save(List<Prediction> predictions) {
            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement("""
                         insert into predictions (id, nickname, match_id, winner, home_score, away_score, created_at)
                         values (?, ?, ?, ?, ?, ?, ?)
                         on conflict (id) do update set
                             nickname = excluded.nickname,
                             match_id = excluded.match_id,
                             winner = excluded.winner,
                             home_score = excluded.home_score,
                             away_score = excluded.away_score,
                             created_at = excluded.created_at
                         """)) {
                try (Statement cleanup = connection.createStatement()) {
                    cleanup.execute("delete from predictions");
                }
                for (Prediction prediction : predictions) {
                    statement.setString(1, prediction.id);
                    statement.setString(2, prediction.nickname);
                    statement.setString(3, prediction.matchId);
                    statement.setString(4, prediction.winner);
                    statement.setInt(5, prediction.homeScore);
                    statement.setInt(6, prediction.awayScore);
                    statement.setTimestamp(7, Timestamp.valueOf(prediction.createdAt));
                    statement.addBatch();
                }
                statement.executeBatch();
            } catch (Exception ex) {
                System.err.println("Failed to save predictions to Postgres: " + ex.getMessage());
            }
        }

        private Connection connect() throws Exception {
            return DriverManager.getConnection(jdbcUrl);
        }

        private static String toJdbcUrl(String databaseUrl) {
            if (databaseUrl.startsWith("jdbc:postgresql://")) return databaseUrl;
            if (databaseUrl.startsWith("postgres://") || databaseUrl.startsWith("postgresql://")) {
                try {
                    URI uri = new URI(databaseUrl);
                    StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
                    if (uri.getPort() > 0) jdbc.append(':').append(uri.getPort());
                    jdbc.append(uri.getPath());

                    Map<String, String> params = new LinkedHashMap<>();
                    if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                        for (String pair : uri.getRawQuery().split("&")) {
                            String[] parts = pair.split("=", 2);
                            params.put(decode(parts[0]), parts.length > 1 ? decode(parts[1]) : "");
                        }
                    }
                    if (uri.getUserInfo() != null) {
                        String[] userInfo = uri.getUserInfo().split(":", 2);
                        params.put("user", decode(userInfo[0]));
                        if (userInfo.length > 1) params.put("password", decode(userInfo[1]));
                    }

                    if (!params.isEmpty()) {
                        jdbc.append('?');
                        int index = 0;
                        for (Map.Entry<String, String> entry : params.entrySet()) {
                            if (index++ > 0) jdbc.append('&');
                            jdbc.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
                        }
                    }
                    return jdbc.toString();
                } catch (URISyntaxException ex) {
                    throw new IllegalArgumentException("Invalid DATABASE_URL", ex);
                }
            }
            return databaseUrl;
        }

        private static String urlEncode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }

    private static class FootballDataClient {
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final String apiKey;
        private final String baseUrl;
        private final String leagueId;
        private final String season;
        private final int nextCount;

        private FootballDataClient(String apiKey, String baseUrl, String leagueId, String season, int nextCount) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.leagueId = leagueId;
            this.season = season;
            this.nextCount = nextCount;
        }

        static FootballDataClient fromEnv() {
            String apiKey = System.getenv("FOOTBALL_API_KEY");
            if (apiKey == null || apiKey.isBlank()) return null;
            return new FootballDataClient(
                    apiKey,
                    env("FOOTBALL_API_BASE_URL", "https://v3.football.api-sports.io"),
                    env("FOOTBALL_LEAGUE_ID", "1"),
                    env("FOOTBALL_SEASON", String.valueOf(LocalDate.now().getYear())),
                    intEnv("FOOTBALL_NEXT_MATCHES", 6));
        }

        List<Match> fetchUpcomingMatches() throws Exception {
            String json = get("/fixtures?league=" + url(leagueId) + "&season=" + url(season) + "&next=" + nextCount);
            JsonArray response = GSON.fromJson(json, JsonObject.class).getAsJsonArray("response");
            List<Match> matches = new ArrayList<>();
            if (response == null) return matches;

            for (JsonElement element : response) {
                JsonObject item = element.getAsJsonObject();
                JsonObject fixture = object(item, "fixture");
                JsonObject league = object(item, "league");
                JsonObject teams = object(item, "teams");
                JsonObject goals = object(item, "goals");
                String fixtureId = text(fixture, "id");
                String home = text(object(teams, "home"), "name");
                String away = text(object(teams, "away"), "name");
                LocalDateTime kickoff = parseKickoff(text(fixture, "date"));
                String status = text(object(fixture, "status"), "long", "未开始");
                Integer homeScore = intOrNull(goals, "home");
                Integer awayScore = intOrNull(goals, "away");
                Match match = new Match(
                        "api-" + fixtureId,
                        fixtureId,
                        text(league, "round", "赛程"),
                        status,
                        home,
                        away,
                        kickoff,
                        0,
                        0,
                        0,
                        "真实赛程来自 API-Football。胜率会优先使用赛前赔率折算；阵容通常在开赛前约一小时公布。",
                        homeScore,
                        awayScore);
                attachLineups(match, fixtureId);
                attachOdds(match, fixtureId);
                matches.add(match);
            }
            return matches;
        }

        private void attachLineups(Match match, String fixtureId) {
            try {
                String json = get("/fixtures/lineups?fixture=" + url(fixtureId));
                JsonArray response = GSON.fromJson(json, JsonObject.class).getAsJsonArray("response");
                if (response == null || response.size() < 2) return;
                applyLineup(match, response.get(0).getAsJsonObject(), true);
                applyLineup(match, response.get(1).getAsJsonObject(), false);
            } catch (Exception ex) {
                System.err.println("Lineup unavailable for fixture " + fixtureId + ": " + ex.getMessage());
            }
        }

        private void attachOdds(Match match, String fixtureId) {
            try {
                String json = get("/odds?fixture=" + url(fixtureId));
                JsonArray response = GSON.fromJson(json, JsonObject.class).getAsJsonArray("response");
                if (response == null || response.isEmpty()) return;
                JsonArray bookmakers = response.get(0).getAsJsonObject().getAsJsonArray("bookmakers");
                if (bookmakers == null || bookmakers.isEmpty()) return;
                JsonObject bookmaker = bookmakers.get(0).getAsJsonObject();
                match.oddsBookmaker = text(bookmaker, "name", text(bookmaker, "id", "Bookmaker"));
                match.oddsUpdatedAt = LocalDateTime.now().format(ISO);
                JsonArray bets = bookmaker.getAsJsonArray("bets");
                if (bets == null) return;
                for (JsonElement betElement : bets) {
                    JsonObject bet = betElement.getAsJsonObject();
                    String betName = text(bet, "name", "");
                    JsonArray values = bet.getAsJsonArray("values");
                    if (values == null) continue;
                    if (betName.equalsIgnoreCase("Match Winner") || betName.equalsIgnoreCase("Home/Away")) {
                        applyWinnerOdds(match, values);
                    }
                    if (betName.toLowerCase(Locale.ROOT).contains("over/under")) {
                        match.oddsOverUnder = summarizeValues(values);
                    }
                }
                match.applyProbabilitiesFromOdds();
            } catch (Exception ex) {
                System.err.println("Odds unavailable for fixture " + fixtureId + ": " + ex.getMessage());
            }
        }

        private void applyLineup(Match match, JsonObject lineup, boolean homeSide) {
            String formation = text(lineup, "formation", "");
            List<String> players = new ArrayList<>();
            JsonArray startXi = lineup.getAsJsonArray("startXI");
            if (startXi != null) {
                for (JsonElement playerElement : startXi) {
                    JsonObject player = object(playerElement.getAsJsonObject(), "player");
                    String name = text(player, "name", "");
                    if (!name.isBlank()) players.add(name);
                }
            }
            if (homeSide) {
                match.homeFormation = formation;
                match.homePlayers = players;
            } else {
                match.awayFormation = formation;
                match.awayPlayers = players;
            }
        }

        private void applyWinnerOdds(Match match, JsonArray values) {
            for (JsonElement valueElement : values) {
                JsonObject value = valueElement.getAsJsonObject();
                String label = text(value, "value", "");
                String odd = text(value, "odd", "");
                if (label.equalsIgnoreCase("Home") || label.equalsIgnoreCase(match.home)) match.oddsHome = odd;
                if (label.equalsIgnoreCase("Draw")) match.oddsDraw = odd;
                if (label.equalsIgnoreCase("Away") || label.equalsIgnoreCase(match.away)) match.oddsAway = odd;
            }
        }

        private String summarizeValues(JsonArray values) {
            List<String> parts = new ArrayList<>();
            for (JsonElement valueElement : values) {
                JsonObject value = valueElement.getAsJsonObject();
                String label = text(value, "value", "");
                String odd = text(value, "odd", "");
                if (!label.isBlank() && !odd.isBlank()) parts.add(label + " " + odd);
                if (parts.size() >= 4) break;
            }
            return String.join(" / ", parts);
        }

        private String get(String path) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("x-apisports-key", apiKey)
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("API-Football returned HTTP " + response.statusCode());
            }
            return response.body();
        }

        private LocalDateTime parseKickoff(String value) {
            try {
                return OffsetDateTime.parse(value).atZoneSameInstant(APP_ZONE).toLocalDateTime();
            } catch (Exception ex) {
                return LocalDateTime.now().plusHours(2);
            }
        }

        private String url(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    private static JsonObject object(JsonObject object, String name) {
        JsonElement element = object == null ? null : object.get(name);
        return element == null || !element.isJsonObject() ? new JsonObject() : element.getAsJsonObject();
    }

    private static String text(JsonObject object, String name) {
        return text(object, name, "");
    }

    private static String text(JsonObject object, String name, String fallback) {
        JsonElement element = object == null ? null : object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static Integer intOrNull(JsonObject object, String name) {
        JsonElement element = object == null ? null : object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsInt();
    }

    private static class Match {
        private final String id;
        private final String externalId;
        private final String stage;
        private final String status;
        private final String home;
        private final String away;
        private final LocalDateTime kickoff;
        private int homeWin;
        private int draw;
        private int awayWin;
        private final String analysis;
        private final Integer homeScore;
        private final Integer awayScore;
        private String homeFormation = "";
        private String awayFormation = "";
        private List<String> homePlayers = new ArrayList<>();
        private List<String> awayPlayers = new ArrayList<>();
        private String oddsHome = "";
        private String oddsDraw = "";
        private String oddsAway = "";
        private String oddsOverUnder = "";
        private String oddsBookmaker = "";
        private String oddsUpdatedAt = "";

        private Match(String id, String externalId, String stage, String status, String home, String away,
                      LocalDateTime kickoff, int homeWin, int draw, int awayWin, String analysis,
                      Integer homeScore, Integer awayScore) {
            this.id = id;
            this.externalId = externalId;
            this.stage = stage;
            this.status = status;
            this.home = home;
            this.away = away;
            this.kickoff = kickoff;
            this.homeWin = homeWin;
            this.draw = draw;
            this.awayWin = awayWin;
            this.analysis = analysis;
            this.homeScore = homeScore;
            this.awayScore = awayScore;
        }

        private Match withLineups(String homeFormation, String awayFormation, List<String> homePlayers, List<String> awayPlayers) {
            this.homeFormation = homeFormation;
            this.awayFormation = awayFormation;
            this.homePlayers = new ArrayList<>(homePlayers);
            this.awayPlayers = new ArrayList<>(awayPlayers);
            return this;
        }

        private Match withOdds(String home, String draw, String away, String overUnder, String bookmaker, String updatedAt) {
            this.oddsHome = home;
            this.oddsDraw = draw;
            this.oddsAway = away;
            this.oddsOverUnder = overUnder;
            this.oddsBookmaker = bookmaker;
            this.oddsUpdatedAt = updatedAt;
            applyProbabilitiesFromOdds();
            return this;
        }

        private boolean hasOdds() {
            return !oddsHome.isBlank() || !oddsDraw.isBlank() || !oddsAway.isBlank() || !oddsOverUnder.isBlank();
        }

        private boolean finished() {
            return homeScore != null && awayScore != null;
        }

        private String resultJson() {
            if (!finished()) return "null";
            return "{\"homeScore\":" + homeScore + ",\"awayScore\":" + awayScore + "}";
        }

        private void applyProbabilitiesFromOdds() {
            double homeProbability = impliedProbability(oddsHome);
            double drawProbability = impliedProbability(oddsDraw);
            double awayProbability = impliedProbability(oddsAway);
            double total = homeProbability + drawProbability + awayProbability;
            if (total <= 0) return;
            homeWin = (int) Math.round(homeProbability / total * 100);
            draw = (int) Math.round(drawProbability / total * 100);
            awayWin = Math.max(0, 100 - homeWin - draw);
        }

        private double impliedProbability(String odd) {
            try {
                double value = Double.parseDouble(odd);
                return value <= 0 ? 0 : 1 / value;
            } catch (Exception ex) {
                return 0;
            }
        }
    }

    private record Prediction(String id, String nickname, String matchId, String winner,
                              int homeScore, int awayScore, LocalDateTime createdAt) {
    }

    private static class ScoreLine {
        private final String nickname;
        private int points;
        private int hits;
        private int exact;
        private int played;

        private ScoreLine(String nickname) {
            this.nickname = nickname;
        }
    }
}
