import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final Path PUBLIC_DIR = Path.of("public").toAbsolutePath().normalize();
    private static final Path DATA_FILE = Path.of("data", "predictions.tsv").toAbsolutePath().normalize();
    private static final List<Match> MATCHES = new ArrayList<>();
    private static final List<Prediction> PREDICTIONS = new ArrayList<>();
    private static PredictionStore store;

    public static void main(String[] args) throws Exception {
        store = createStore();
        seedData();

        String envPort = System.getenv("PORT");
        int port = Integer.parseInt(envPort == null || envPort.isBlank() ? System.getProperty("port", "8080") : envPort);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", WatchPartyServer::route);
        server.setExecutor(null);
        server.start();
        System.out.println("World Cup Watch Party is running at http://localhost:" + port);
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
            sendJson(exchange, 200, stateJson(query(exchange.getRequestURI()).getOrDefault("nickname", "")));
            return;
        }
        if ("GET".equals(method) && "/api/matches".equals(path)) {
            sendJson(exchange, 200, matchesJson());
            return;
        }
        if ("GET".equals(method) && "/api/leaderboard".equals(path)) {
            sendJson(exchange, 200, leaderboardJson());
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
        Prediction prediction = new Prediction(UUID.randomUUID().toString(), nickname, matchId, winner, homeScore, awayScore, LocalDateTime.now());
        PREDICTIONS.add(prediction);
        store.save(PREDICTIONS);
        sendJson(exchange, 200, "{\"ok\":true,\"message\":\"saved\"}");
    }

    private static String stateJson(String nickname) {
        return "{\"matches\":" + matchesJson()
                + ",\"leaderboard\":" + leaderboardJson()
                + ",\"mine\":" + myPredictionsJson(nickname)
                + "}";
    }

    private static String matchesJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < MATCHES.size(); i++) {
            Match match = MATCHES.get(i);
            if (i > 0) json.append(',');
            json.append('{')
                    .append("\"id\":\"").append(match.id).append("\",")
                    .append("\"stage\":\"").append(escape(match.stage)).append("\",")
                    .append("\"home\":\"").append(escape(match.home)).append("\",")
                    .append("\"away\":\"").append(escape(match.away)).append("\",")
                    .append("\"kickoff\":\"").append(match.kickoff.format(ISO)).append("\",")
                    .append("\"homeWin\":").append(match.homeWin).append(',')
                    .append("\"draw\":").append(match.draw).append(',')
                    .append("\"awayWin\":").append(match.awayWin).append(',')
                    .append("\"analysis\":\"").append(escape(match.analysis)).append("\",")
                    .append("\"result\":").append(match.resultJson())
                    .append('}');
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
            String key = decode(parts[0]);
            String value = parts.length > 1 ? decode(parts[1]) : "";
            values.put(key, value);
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
        MATCHES.add(new Match("m0", "Demo", "Group Friends", "Night Watchers", now.minusHours(4), 46, 27, 27,
                "Demo match for the leaderboard. Group Friends are steadier, while Night Watchers may fade late.", 2, 1));
        MATCHES.add(new Match("m1", "Group Stage", "Mexico", "South Africa", now.plusHours(2), 48, 28, 24,
                "Mexico have the stronger home atmosphere and possession game. South Africa's counterattack speed is the main variable.", null, null));
        MATCHES.add(new Match("m2", "Group Stage", "United States", "Canada", now.plusHours(6), 41, 31, 28,
                "This should be physical. The United States have more forward thrust, while Canada need to control the tempo early.", null, null));
        MATCHES.add(new Match("m3", "Group Stage", "Spain", "Japan", now.plusDays(1).plusHours(1), 52, 25, 23,
                "Spain's control is obvious, but Japan's pressing and transition speed make this a fun upset watch.", null, null));

        PREDICTIONS.addAll(store.load());
        if (!PREDICTIONS.isEmpty()) return;
        PREDICTIONS.add(new Prediction(UUID.randomUUID().toString(), "Aqiang", "m0", "HOME", 2, 1, now.minusHours(5)));
        PREDICTIONS.add(new Prediction(UUID.randomUUID().toString(), "Xiaolin", "m0", "DRAW", 1, 1, now.minusHours(5)));
        PREDICTIONS.add(new Prediction(UUID.randomUUID().toString(), "Laowang", "m0", "HOME", 1, 0, now.minusHours(5)));
    }

    private static PredictionStore createStore() throws Exception {
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl != null && !databaseUrl.isBlank()) {
            return new PostgresPredictionStore(databaseUrl);
        }
        return new FilePredictionStore();
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
                    StringBuilder jdbc = new StringBuilder("jdbc:postgresql://")
                            .append(uri.getHost());
                    if (uri.getPort() > 0) {
                        jdbc.append(':').append(uri.getPort());
                    }
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
                        if (userInfo.length > 1) {
                            params.put("password", decode(userInfo[1]));
                        }
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

    private record Match(String id, String stage, String home, String away, LocalDateTime kickoff,
                         int homeWin, int draw, int awayWin, String analysis, Integer homeScore, Integer awayScore) {
        boolean finished() {
            return homeScore != null && awayScore != null;
        }

        String resultJson() {
            if (!finished()) return "null";
            return "{\"homeScore\":" + homeScore + ",\"awayScore\":" + awayScore + "}";
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
