package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional crypto prices (Binance, every 5 min) and weather/sunrise/sunset (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * Neither source needs a key. Location and currency come from config.properties.
 * Also optional: news headlines from an RSS feed (news_feed, every 30 min) and, in Brazil (state), the next election
 * from the TSE's public calendar (every 6 h).
 */
final class HomeData {
    static final String[] COINS = {"BTC", "ETH", "SOL", "USDT"};
    static final String[] COIN_NAMES = {"Bitcoin", "Ethereum", "Solana", "Dollar"};
    private static final String CURRENCY = Config.CURRENCY.trim().toUpperCase(Locale.ROOT);
    private static final String CURRENCY_CODE = CURRENCY;
    /** Binance has no pairs in some currencies (INR...) and then refuses the whole request: prices go in dollars instead. */
    private static volatile boolean inDollars;

    /** Binance quotes dollars as USDT. */
    private static String quote() {
        return CURRENCY.equals("USD") || inDollars ? "USDT" : CURRENCY;
    }
    private static final String WEATHER_QUERY = "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2";

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude() + "&longitude=" + Prefs.longitude() + WEATHER_QUERY;
    }
    private static final long PRICES_EVERY = 5 * 60_000L, WEATHER_EVERY = 30 * 60_000L;
    private static final long NEWS_EVERY = 30 * 60_000L, ELECTION_EVERY = 6 * 3_600_000L;
    /** The TSE's list of elections (dates, rounds, and the states each one covers). */
    private static final String ELECTIONS_URL = "https://resultados.tse.jus.br/oficial/comum/config/ele-c.json";
    private static final String NEWS_FEED = Config.NEWS_FEED.trim();
    private static final String STATE = Config.STATE.trim().toLowerCase(Locale.ROOT);
    private static final TimeZone BRASILIA = TimeZone.getTimeZone("America/Sao_Paulo");
    private static final int MAX_HEADLINES = 12;
    /** Exchange rates against the configured currency (AwesomeAPI, no key; every 15 min): exchange_rates=USD,EUR,GBP. */
    static final String[] FX = fxCodes();
    private static final long FX_EVERY = 15 * 60_000L;

    private static String[] fxCodes() {
        List<String> out = new ArrayList<>();
        for (String code : Config.EXCHANGE_RATES.split(",")) {
            String c = code.trim().toUpperCase(Locale.ROOT);
            if (c.matches("[A-Z]{3}") && !c.equals(CURRENCY_CODE) && !out.contains(c)) out.add(c);
        }
        return out.toArray(new String[0]);
    }
    interface Listener { void onData(); }

    static boolean newsOn() {
        return !NEWS_FEED.isEmpty();
    }

    static boolean electionsOn() {
        return STATE.length() == 2;
    }

    /** BTC, ETH and SOL; in Brazilian reais also the dollar (USDT/BRL), which Binance doesn't list for most currencies. */
    static int coinCount() {
        return Prefs.quotes() ? active().length : 0;
    }

    private static volatile int[] activeCoins;
    private static volatile int activeVersion = -1;
    private static volatile boolean activeInDollars;

    /** Indexes in COINS of the coins the user has on (and that Binance lists in this currency). */
    static int[] active() {
        int[] a = activeCoins;
        boolean dollars = inDollars;
        if (a != null && activeVersion == Prefs.version && activeInDollars == dollars) return a;
        int n = 0;
        int[] tmp = new int[COINS.length];
        // The dollar only in reais, not in dollars: USDTUSDT isn't a pair, and Binance would refuse the whole request.
        for (int i = 0; i < COINS.length; i++) if (Prefs.coin(i) && (i < 3 || CURRENCY.equals("BRL") && !dollars)) tmp[n++] = i;
        a = java.util.Arrays.copyOf(tmp, n);
        activeCoins = a;
        activeVersion = Prefs.version;
        activeInDollars = dollars;
        return a;
    }

    /** Settings changed (city, coins): fetch again now. */
    void settingsChanged() {
        pricesAt = 0;
        newsAt = 0;
        weatherAt = 0;
        pricesFailedAt = 0;
        weatherFailedAt = 0;
    }

    static String coinName(int i) {
        return i == 3 ? Ui.tr("Dollar") : COIN_NAMES[i];
    }

    /** Binance.com refuses the US (HTTP 451); Binance.US answers the same call in dollars. */
    private static final String[] PRICE_HOSTS = {"https://api.binance.com", "https://api.binance.us"};

    private static String pricesPath() {
        StringBuilder symbols = new StringBuilder();
        int[] coins = active();
        for (int i = 0; i < coins.length; i++) symbols.append(i == 0 ? "" : ",").append("%22").append(COINS[coins[i]]).append(quote()).append("%22");
        return "/api/v3/ticker/24hr?type=MINI&symbols=%5B" + symbols + "%5D";
    }

    private static String getPrices() throws IOException {
        IOException failed = null;
        for (String host : PRICE_HOSTS) {
            try {
                return get(host + pricesPath());
            } catch (IOException e) {
                // Binance.US has only dollar pairs: its error counts only where Binance.com refuses the US (HTTP 451).
                // After a timeout or a 5xx there, its HTTP 400 would put the prices in dollars for good.
                if (failed == null || "HTTP 451".equals(failed.getMessage())) failed = e;
                // A pair Binance doesn't list (HTTP 400) isn't a refusal: the caller falls back to dollars instead.
                if ("HTTP 400".equals(e.getMessage())) break;
            }
        }
        throw failed;
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    final double[] prices = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    /** 24 h change (%), in the same order. */
    final double[] changes = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    volatile long pricesAt, weatherAt;
    volatile Weather weather = new Weather();
    volatile String[] headlines = new String[0];
    /** FX's rates (in the configured currency) and their change today (%), NaN until fetched. */
    final double[] fxRate = new double[FX.length], fxChange = new double[FX.length];
    volatile long fxAt;
    /** Each headline's subtitle ("" when the feed has none), in the same order. */
    volatile String[] summaries = new String[0];
    volatile long newsAt, electionAt;
    /** The next election here: its day (0:00 in Brasília; 0 = none), round (1 or 2) and the office on the ballot. */
    volatile long electionDay;
    volatile int electionRound;
    volatile String electionOffice = "";

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingPrices, fetchingWeather;
    private volatile long pricesFailedAt, weatherFailedAt;
    private volatile boolean fetchingNews, fetchingElection, fetchingFx;
    private volatile long fxFailedAt;
    private volatile long newsFailedAt, electionFailedAt;
    /** The city of the forecast in memory: another process (ScreensaverAlone) can see the city change under it. */
    private volatile String weatherFor = "";

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        inDollars = CURRENCY.equals(cache.getString("dollarsFor", ""));
        try {
            if (Prefs.quotes()) {
                String p = cache.getString("prices", null);
                if (p != null) parsePrices(p);
                pricesAt = cache.getLong("pricesAt", 0);
            }
            String w = cache.getString("weather", null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                weatherAt = cache.getLong("weatherAt", 0);
                weatherFor = weatherPlace();
            }
        } catch (Exception ignored) {}
        java.util.Arrays.fill(fxRate, Double.NaN);
        java.util.Arrays.fill(fxChange, Double.NaN);
        try {
            String f = cache.getString("fx", null);
            if (FX.length > 0 && f != null) {
                parseFx(f);
                fxAt = cache.getLong("fxAt", 0);
            }
        } catch (Exception ignored) {}
        try {
            String n = cache.getString("news", null);
            if (newsOn() && n != null && NEWS_FEED.equals(cache.getString("newsFeed", ""))) {
                parseNews(n);
                newsAt = cache.getLong("newsAt", 0);
            }
            String e = cache.getString("elections", null);
            if (electionsOn() && e != null) {
                parseElections(e);
                electionAt = cache.getLong("electionAt", 0);
            }
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingPrices && coinCount() > 0 && (now - pricesAt > PRICES_EVERY || now < pricesAt) && !failedRecently(pricesFailedAt, now)) {
            fetchingPrices = true;
            io.execute(() -> {
                try {
                    String body;
                    try {
                        body = getPrices();
                    } catch (IOException e) {
                        if (inDollars || CURRENCY.equals("USD") || !"HTTP 400".equals(e.getMessage())) throw e;
                        inDollars = true;
                        cache.edit().putString("dollarsFor", CURRENCY).apply();
                        AppLog.w("prices: Binance has no " + CURRENCY + " pairs, using dollars");
                        body = getPrices();
                    }
                    parsePrices(body);
                    pricesAt = System.currentTimeMillis();
                    cache.edit().putString("prices", body).putLong("pricesAt", pricesAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    pricesFailedAt = System.currentTimeMillis();
                    AppLog.w("prices: " + e.getMessage());
                } finally {
                    fetchingPrices = false;
                }
            });
        }
        refreshNews(now);
        boolean moved = !weatherPlace().equals(weatherFor);
        if (!fetchingWeather && (moved || now - weatherAt > WEATHER_EVERY || now < weatherAt) && !failedRecently(weatherFailedAt, now)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String place = weatherPlace();
                    String body = get(weatherUrl());
                    weather = parseWeather(body);
                    weatherAt = System.currentTimeMillis();
                    weatherFor = place;
                    cache.edit().putString("weather", body).putLong("weatherAt", weatherAt).putString("weatherPlace", place).apply();
                    notifyListeners();
                } catch (Exception e) {
                    weatherFailedAt = System.currentTimeMillis();
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    /** News and the election calendar: the same rules as above, on the same thread. */
    private void refreshNews(long now) {
        if (newsOn() && !fetchingNews && (now - newsAt > NEWS_EVERY || now < newsAt) && !failedRecently(newsFailedAt, now)) {
            fetchingNews = true;
            io.execute(() -> {
                try {
                    String body = get(NEWS_FEED);
                    parseNews(body);
                    newsAt = System.currentTimeMillis();
                    cache.edit().putString("news", body).putLong("newsAt", newsAt).putString("newsFeed", NEWS_FEED).apply();
                    notifyListeners();
                } catch (Exception e) {
                    newsFailedAt = System.currentTimeMillis();
                    AppLog.w("news: " + e.getMessage());
                } finally {
                    fetchingNews = false;
                }
            });
        }
        if (FX.length > 0 && !fetchingFx && (now - fxAt > FX_EVERY || now < fxAt) && !failedRecently(fxFailedAt, now)) {
            fetchingFx = true;
            io.execute(() -> {
                try {
                    StringBuilder pairs = new StringBuilder();
                    for (String code : FX) pairs.append(pairs.length() == 0 ? "" : ",").append(code).append('-').append(CURRENCY);
                    String body = get("https://economia.awesomeapi.com.br/json/last/" + pairs);
                    parseFx(body);
                    fxAt = System.currentTimeMillis();
                    cache.edit().putString("fx", body).putLong("fxAt", fxAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    fxFailedAt = System.currentTimeMillis();
                    AppLog.w("exchange rates: " + e.getMessage());
                } finally {
                    fetchingFx = false;
                }
            });
        }
        if (electionsOn() && !fetchingElection && (now - electionAt > ELECTION_EVERY || now < electionAt)
                && !failedRecently(electionFailedAt, now)) {
            fetchingElection = true;
            io.execute(() -> {
                try {
                    String body = get(ELECTIONS_URL);
                    parseElections(body);
                    electionAt = System.currentTimeMillis();
                    cache.edit().putString("elections", body).putLong("electionAt", electionAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    electionFailedAt = System.currentTimeMillis();
                    AppLog.w("elections: " + e.getMessage());
                } finally {
                    fetchingElection = false;
                }
            });
        }
    }

    /** AwesomeAPI: {"USDBRL": {"bid": "4.96", "pctChange": "-1.72"}, ...}. */
    private void parseFx(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        for (int i = 0; i < FX.length; i++) {
            JSONObject r = o.optJSONObject(FX[i] + CURRENCY);
            if (r == null) continue;
            fxRate[i] = Double.parseDouble(r.optString("bid", "NaN"));
            fxChange[i] = Double.parseDouble(r.optString("pctChange", "NaN"));
        }
    }

    /** The items of an RSS feed, in its order: each title and its subtitle (atom:subtitle, plain text; g1 has it). */
    private void parseNews(String body) throws Exception {
        XmlPullParser x = android.util.Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        x.setInput(new StringReader(body));
        List<String> titles = new ArrayList<>(), subtitles = new ArrayList<>();
        String title = null, subtitle = "";
        boolean inItem = false;
        for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT && titles.size() < MAX_HEADLINES; ev = x.next()) {
            if (ev == XmlPullParser.START_TAG && "item".equals(x.getName())) {
                inItem = true;
                title = null;
                subtitle = "";
            } else if (ev == XmlPullParser.END_TAG && "item".equals(x.getName())) {
                inItem = false;
                if (title != null && !title.isEmpty()) {
                    titles.add(title);
                    subtitles.add(subtitle);
                }
            } else if (ev == XmlPullParser.START_TAG && inItem && x.getNamespace().isEmpty() && "title".equals(x.getName())) {
                title = x.nextText().replaceAll("\\s+", " ").trim();
            } else if (ev == XmlPullParser.START_TAG && inItem && "subtitle".equals(x.getName())) {
                subtitle = x.nextText().replaceAll("\\s+", " ").trim();
            }
        }
        summaries = subtitles.toArray(new String[0]);
        headlines = titles.toArray(new String[0]);
    }

    /**
     * The first ordinary election from yesterday on that votes here: a federal one (President) anywhere, a state one
     * (Governor) where its list of states has ours, a municipal one (Mayor) anywhere.
     */
    private void parseElections(String body) throws Exception {
        JSONArray pleitos = new JSONObject(body).getJSONArray("pl");
        SimpleDateFormat f = new SimpleDateFormat("dd/MM/yyyy", Locale.US);
        f.setTimeZone(BRASILIA);
        long from = System.currentTimeMillis() - 2 * 86_400_000L, best = 0;
        int round = 0;
        String office = "";
        for (int i = 0; i < pleitos.length(); i++) {
            JSONObject pl = pleitos.getJSONObject(i);
            long day = f.parse(pl.getString("dt")).getTime();
            if (day < from || (best != 0 && day >= best)) continue;
            JSONArray es = pl.getJSONArray("e");
            String found = "";
            int t = 0;
            for (int j = 0; j < es.length(); j++) {
                JSONObject e = es.getJSONObject(j);
                if (!e.optString("nm").contains("Ordinária")) continue;
                String tp = e.optString("tp");
                String o = tp.equals("8") ? "President" : tp.equals("3") ? "Mayor" : tp.equals("1") && covers(e, STATE) ? "Governor" : "";
                // President wins over Governor: on the same ballot, the line names the one everybody votes for.
                if (o.isEmpty() || found.equals("President")) continue;
                found = o;
                t = e.optInt("t", 1);
            }
            if (found.isEmpty()) continue;
            best = day;
            round = t;
            office = found;
        }
        electionDay = best;
        electionRound = round;
        electionOffice = office;
    }

    private static boolean covers(JSONObject election, String state) {
        JSONArray abr = election.optJSONArray("abr");
        for (int i = 0; abr != null && i < abr.length(); i++) {
            String cd = abr.optJSONObject(i) == null ? "" : abr.optJSONObject(i).optString("cd");
            if (cd.equals(state) || cd.equals("br")) return true;
        }
        return false;
    }

    /**
     * The election line, from 30 days before to the day after; "" otherwise. Polls are open 8:00 to 17:00, Brasília
     * time, everywhere in Brazil; after that, the counting (the results come in the news).
     */
    String electionLine(long now) {
        long day = electionDay;
        if (day == 0 || electionOffice.isEmpty() || now < day - 30 * 86_400_000L || now >= day + 2 * 86_400_000L) return "";
        String round = electionRound == 2 ? Ui.tr("Second round") : Ui.tr("First round");
        String office = electionOffice.equals("President") ? Ui.tr("President")
                : electionOffice.equals("Governor") ? Ui.tr("Governor") : Ui.tr("Mayor");
        if (now >= day + 17 * 3_600_000L) return Ui.trf("Counting the votes: {round} for {office}", "round", round, "office", office);
        if (now >= day) return Ui.trf("Election day: {round} for {office}, polls close at 5 PM", "round", round, "office", office);
        String when = now >= day - 86_400_000L ? Ui.tr("tomorrow") : Ui.datePattern("format|EEEE, MMMM d").format(day);
        return Ui.trf("{round} for {office}: {when}", "round", round, "office", office, "when", when);
    }

    private static boolean failedRecently(long at, long now) {
        return at != 0 && now >= at && now - at < 2 * 60_000L;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    private void parsePrices(String body) throws Exception {
        JSONArray list = new JSONArray(body);
        String quote = quote();
        int found = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            String symbol = o.getString("symbol");
            for (int k = 0; k < COINS.length; k++) {
                if (!symbol.equals(COINS[k] + quote)) continue;
                found++;
                double last = Double.parseDouble(o.optString("lastPrice", o.optString("price", "NaN")));
                double open = Double.parseDouble(o.optString("openPrice", "NaN"));
                prices[k] = last;
                changes[k] = open > 0 ? (last - open) / open * 100 : Double.NaN;
            }
        }
        if (found == 0) pricesAt = 0;   // cached in another currency (config changed): fetch again
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
            }
        }
        return w;
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    static List<String[]> findCity(String name) throws Exception {
        String lang = Ui.language.replaceAll("-.*", "");   // Open-Meteo takes the language without the country
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // places with the same name, state and country read the same
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude"))});
        }
        return out;
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        return isNight(Prefs.theme(), now, weather.sunrise, weather.sunset);
    }

    /** The same from what it depends on (SleepOverlay gets these in its Intent). */
    static boolean isNight(String theme, long now, long sunrise, long sunset) {
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    private static Locale locale() {
        return Ui.locale();
    }

    /** 4.15M · 580.4k · 21.0k · 1.04k · 5.61 (as short as possible; the dollar always with cents). */
    static String price(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 1_000_000) return String.format(locale(), "%.2fM", v / 1_000_000);
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        return String.format(locale(), "%.2f", v);
    }

    /** 4.15M · 416.3k · 12.2k · 1.04k · 531 · 5.05: abbreviated, for the price row on the home screen. */
    static String compact(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 1_000_000) return String.format(locale(), "%.2fM", v / 1_000_000);   // Bitcoin in rubles…
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        if (v >= 100) return String.format(locale(), "%.0f", v);
        return String.format(locale(), "%.2f", v);
    }

    /**
     * $108,234 · $185.20 · R$ 581.234 · 74 264 € (cents only below a thousand), in the configured currency, with the
     * symbol and spaces where the language puts them.
     */
    static String brl(double v) {
        String symbol = currencySymbol();
        if (Double.isNaN(v)) return symbol + "—";
        return Ui.once("brl", v, () -> {
            DecimalFormat f = (DecimalFormat) NumberFormat.getCurrencyInstance(locale());
            DecimalFormatSymbols s = f.getDecimalFormatSymbols();
            s.setCurrencySymbol(symbol.trim());   // our symbols: ICU would write "US$" outside the US
            f.setDecimalFormatSymbols(s);
            f.setMinimumFractionDigits(v >= 1000 ? 0 : 2);
            f.setMaximumFractionDigits(v >= 1000 ? 0 : 2);
            return f.format(v);
        });
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (code == 0) return day ? Ui.tr("Clear") : Ui.tr("night|Clear");
        if (code == 1) return day ? Ui.tr("Mostly Sunny") : Ui.tr("Mostly Clear");
        if (code == 2) return Ui.tr("Partly Cloudy");
        if (code == 3) return Ui.tr("Cloudy");
        if (code == 45 || code == 48) return Ui.tr("Fog");
        if (code >= 51 && code <= 57) return Ui.tr("Drizzle");
        if (code == 65 || code == 67 || code == 82) return Ui.tr("Heavy Rain");
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return Ui.tr("Snow");
        if (code >= 95) return Ui.tr("Thunderstorms");
        return Ui.tr("Rain");
    }

    /** "H:24°  L:15°" (in Portuguese, "Máx. 24°  Mín. 15°"), with the given space between the two. */
    static String highLow(Weather wx, String space) {
        return Ui.trf("H:{max}", "max", degrees(wx.max)) + space + Ui.trf("L:{min}", "min", degrees(wx.min));
    }

    private static String currencySymbol() {
        switch (inDollars ? "USD" : CURRENCY) {
            case "USD": return "$";
            case "BRL": return "R$ ";
            case "EUR": return "€";
            case "GBP": return "£";
            case "JPY": return "¥";
            default: return CURRENCY + " ";
        }
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    /** "+1.2%" / "−0.8%" (a real minus sign); "+1,2 %" in the languages that space the percent sign. */
    static String change(double v) {
        if (Double.isNaN(v)) return "";
        return Ui.once("change", v, () -> {
            NumberFormat f = NumberFormat.getPercentInstance(locale());
            f.setMinimumFractionDigits(1);
            f.setMaximumFractionDigits(1);
            return (v >= 0 ? "+" : "−") + f.format(Math.abs(v) / 100);
        });
    }

    static String hour(long at) {
        return Ui.once("hour", at, () -> Ui.datePattern("format|h a").format(at));
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        return minutes < 60 ? Ui.trf("{n} min ago", "n", minutes) : Ui.trf("{n} h ago", "n", minutes / 60);
    }
}
