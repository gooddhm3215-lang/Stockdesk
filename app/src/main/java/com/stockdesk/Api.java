package com.stockdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PC 버전 서버와 같은 /api/* 요청을 앱 안에서 처리한다. */
public class Api {
    static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    static final String DEFAULT_MODEL = "claude-sonnet-5-5";

    private final Context ctx;
    private final SharedPreferences prefs;
    private List<String[]> corps; // name, code, sector, market

    public Api(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("stockdesk", Context.MODE_PRIVATE);
    }

    static class UserErr extends Exception {
        UserErr(String m) { super(m); }
    }

    public static class Res {
        public final int code;
        public final String body;
        Res(int code, String body) { this.code = code; this.body = body; }
    }

    static Res ok(Object o) { return new Res(200, o.toString()); }

    static Res err(int code, String msg) {
        try {
            return new Res(code, new JSONObject().put("error", msg).toString());
        } catch (Exception e) {
            return new Res(code, "{\"error\":\"오류\"}");
        }
    }

    public Res handle(String method, String path, String body) {
        Uri u = Uri.parse("http://local" + path);
        String p = u.getPath() == null ? "" : u.getPath();
        try {
            switch (p) {
                case "/api/status": return ok(status());
                case "/api/ping": return ok(new JSONObject().put("ok", true));
                case "/api/resolve": return ok(resolve(nz(u.getQueryParameter("q"))));
                case "/api/prices": {
                    JSONObject o = new JSONObject();
                    o.put("stock", prices(nz(u.getQueryParameter("code"))));
                    JSONArray ks;
                    try { ks = prices("KOSPI"); } catch (IOException e) { ks = new JSONArray(); }
                    o.put("kospi", ks);
                    return ok(o);
                }
                case "/api/reports": return ok(new JSONObject().put("items", reports(nz(u.getQueryParameter("code")))));
                case "/api/config": return ok(saveConfig(new JSONObject(body.isEmpty() ? "{}" : body)));
                case "/api/analyze": {
                    JSONObject b = new JSONObject(body);
                    String txt = callClaude(b.optString("prompt"), prefs.getBoolean("search", true), 8000);
                    return ok(parseJson(txt));
                }
                case "/api/report_summary": return ok(summarize(new JSONObject(body)));
                default: return err(404, "not found");
            }
        } catch (UserErr e) {
            return err(400, e.getMessage());
        } catch (IOException e) {
            return err(502, "데이터를 가져오지 못했습니다 (" + e.getMessage() + "). 인터넷 연결을 확인해 주세요.");
        } catch (Exception e) {
            return err(500, "처리 중 오류: " + e.getMessage());
        }
    }

    static String nz(String s) { return s == null ? "" : s; }

    // ------------------------------------------------------------ 설정
    JSONObject status() throws Exception {
        String key = prefs.getString("apiKey", "");
        return new JSONObject()
                .put("hasKey", !key.isEmpty())
                .put("model", prefs.getString("model", DEFAULT_MODEL))
                .put("search", prefs.getBoolean("search", true))
                .put("pypdf", true)
                .put("keyHint", key.length() > 4 ? "…" + key.substring(key.length() - 4) : "");
    }

    JSONObject saveConfig(JSONObject b) throws Exception {
        SharedPreferences.Editor e = prefs.edit();
        String key = b.optString("apiKey", "").trim();
        if (!key.isEmpty()) e.putString("apiKey", key);
        if (b.optBoolean("clearKey", false)) e.putString("apiKey", "");
        String model = b.optString("model", "").trim();
        if (!model.isEmpty()) e.putString("model", model);
        if (b.has("search")) e.putBoolean("search", b.optBoolean("search", true));
        e.apply();
        return new JSONObject().put("ok", true);
    }

    // ------------------------------------------------------------ HTTP
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9");
        int code = c.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code);
        return readAll(c.getInputStream());
    }

    static String decode(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("EUC-KR"));
        }
    }

    static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    static final Pattern SPACE = Pattern.compile("\\s+");
    static final Pattern TR = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    static final Pattern TD = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>");
    static final Pattern A = Pattern.compile("(?is)<a[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>");
    static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style)[^>]*>.*?</(script|style)>");

    static String unescape(String s) {
        return s.replace("&nbsp;", " ").replace("&#160;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    static String clean(String s) {
        return SPACE.matcher(unescape(TAG.matcher(s).replaceAll(" "))).replaceAll(" ").trim();
    }

    /** 각 행: 셀 목록. 셀 = [텍스트, href1, 링크텍스트1, href2, ...] */
    static List<List<List<String>>> rows(String doc) {
        List<List<List<String>>> out = new ArrayList<>();
        Matcher mt = TR.matcher(doc);
        while (mt.find()) {
            List<List<String>> r = new ArrayList<>();
            Matcher md = TD.matcher(mt.group(1));
            while (md.find()) {
                List<String> c = new ArrayList<>();
                c.add(clean(md.group(1)));
                Matcher ma = A.matcher(md.group(1));
                while (ma.find()) {
                    c.add(unescape(ma.group(1)));
                    c.add(clean(ma.group(2)));
                }
                r.add(c);
            }
            if (!r.isEmpty()) out.add(r);
        }
        return out;
    }

    // ------------------------------------------------------------ 상장사
    static final String[][] FALLBACK = {{"삼성전자", "005930"}, {"SK하이닉스", "000660"}, {"HD현대일렉트릭", "267260"},
            {"효성중공업", "298040"}, {"HD한국조선해양", "009540"}, {"HD현대중공업", "329180"}, {"한화오션", "042660"},
            {"LG에너지솔루션", "373220"}, {"현대로템", "064350"}, {"NAVER", "035420"}, {"현대차", "005380"},
            {"기아", "000270"}, {"KB금융", "105560"}};

    synchronized List<String[]> loadCorps() {
        if (corps != null) return corps;
        File cache = new File(ctx.getFilesDir(), "corp_list.json");
        List<String[]> lst = new ArrayList<>();
        try {
            if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 7L * 86400000L) {
                JSONArray a = new JSONArray(new String(readAll(new java.io.FileInputStream(cache)), StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) {
                    JSONArray x = a.getJSONArray(i);
                    lst.add(new String[]{x.getString(0), x.getString(1), x.getString(2), x.getString(3)});
                }
            }
        } catch (Exception ignored) { lst.clear(); }
        if (lst.isEmpty()) {
            try {
                List<List<List<String>>> rs = rows(decode(get("https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13")));
                if (rs.size() > 1) {
                    int ci = 2, si = -1, mi = -1;
                    List<List<String>> h = rs.get(0);
                    for (int i = 0; i < h.size(); i++) {
                        String t = h.get(i).get(0);
                        if (t.equals("종목코드")) ci = i;
                        if (t.equals("업종")) si = i;
                        if (t.equals("시장구분")) mi = i;
                    }
                    for (int k = 1; k < rs.size(); k++) {
                        List<List<String>> r = rs.get(k);
                        if (r.size() <= ci) continue;
                        String code = r.get(ci).get(0);
                        if (!code.matches("[0-9A-Z]{1,6}")) continue;
                        while (code.length() < 6) code = "0" + code;
                        lst.add(new String[]{r.get(0).get(0), code,
                                si >= 0 && si < r.size() ? r.get(si).get(0) : "",
                                mi >= 0 && mi < r.size() ? r.get(mi).get(0) : ""});
                    }
                }
                if (!lst.isEmpty()) {
                    JSONArray a = new JSONArray();
                    for (String[] c : lst) a.put(new JSONArray().put(c[0]).put(c[1]).put(c[2]).put(c[3]));
                    try (OutputStream os = new FileOutputStream(cache)) {
                        os.write(a.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) { lst.clear(); }
        }
        if (lst.isEmpty()) for (String[] f : FALLBACK) lst.add(new String[]{f[0], f[1], "", ""});
        corps = lst;
        return lst;
    }

    static String norm(String s) { return SPACE.matcher(s).replaceAll("").toUpperCase(Locale.ROOT); }

    static JSONObject corpJson(String[] c) throws Exception {
        return new JSONObject().put("name", c[0]).put("code", c[1]).put("sector", c[2]).put("market", c[3]);
    }

    JSONObject resolve(String q) throws Exception {
        q = q.trim();
        List<String[]> lst = loadCorps();
        JSONObject out = new JSONObject();
        if (q.matches("\\d{6}")) {
            String[] hit = new String[]{q, q, "", ""};
            for (String[] c : lst) if (c[1].equals(q)) { hit = c; break; }
            return out.put("match", corpJson(hit)).put("candidates", new JSONArray());
        }
        String nq = norm(q);
        List<String[]> exact = new ArrayList<>(), starts = new ArrayList<>(), contains = new ArrayList<>();
        for (String[] c : lst) {
            String n = norm(c[0]);
            if (n.equals(nq)) exact.add(c);
            else if (n.startsWith(nq)) starts.add(c);
            else if (!nq.isEmpty() && n.contains(nq)) contains.add(c);
        }
        List<String[]> all = new ArrayList<>(exact);
        all.addAll(starts);
        all.addAll(contains);
        if (all.isEmpty()) return out.put("match", JSONObject.NULL).put("candidates", new JSONArray());
        JSONArray cands = new JSONArray();
        for (int i = 1; i < Math.min(all.size(), 8); i++) cands.put(corpJson(all.get(i)));
        return out.put("match", corpJson(all.get(0))).put("candidates", cands);
    }

    // ------------------------------------------------------------ 시세
    static final Pattern ITEM = Pattern.compile("data=\"([^\"]+)\"");

    static JSONArray prices(String symbol) throws Exception {
        String txt = new String(get("https://fchart.stock.naver.com/sise.nhn?symbol=" + URLEncoder.encode(symbol, "UTF-8")
                + "&timeframe=day&count=320&requestType=0"), StandardCharsets.ISO_8859_1);
        List<String[]> bars = new ArrayList<>();
        Matcher m = ITEM.matcher(txt);
        while (m.find()) {
            String[] p = m.group(1).split("\\|");
            if (p.length < 5 || !p[0].matches("\\d{8}")) continue;
            try {
                if (Double.parseDouble(p[4]) <= 0) continue;
            } catch (NumberFormatException e) { continue; }
            bars.add(p);
        }
        Collections.sort(bars, (a, b) -> a[0].compareTo(b[0]));
        JSONArray out = new JSONArray();
        for (String[] p : bars) {
            JSONObject o = new JSONObject();
            o.put("d", p[0].substring(0, 4) + "-" + p[0].substring(4, 6) + "-" + p[0].substring(6, 8));
            o.put("c", Double.parseDouble(p[4]));
            double v = 0;
            if (p.length > 5) try { v = Double.parseDouble(p[5]); } catch (NumberFormatException ignored) { }
            o.put("v", v);
            out.put(o);
        }
        return out;
    }

    // ------------------------------------------------------------ 리포트
    static JSONArray reports(String code) throws Exception {
        String doc = decode(get("https://finance.naver.com/research/company_list.naver?searchType=itemCode&itemCode="
                + URLEncoder.encode(code, "UTF-8") + "&page=1"));
        JSONArray out = new JSONArray();
        for (List<List<String>> r : rows(doc)) {
            String read = null, title = "", pdf = "", date = "";
            for (List<String> c : r) {
                for (int i = 1; i + 1 < c.size(); i += 2) {
                    String href = c.get(i);
                    if (read == null && href.contains("company_read")) { read = href; title = c.get(i + 1); }
                    if (pdf.isEmpty() && href.toLowerCase(Locale.ROOT).endsWith(".pdf")) pdf = href;
                }
                if (c.get(0).matches("\\d{2}\\.\\d{2}\\.\\d{2}")) date = c.get(0);
            }
            if (read == null) continue;
            if (!read.startsWith("http")) {
                read = read.startsWith("/") ? "https://finance.naver.com" + read : "https://finance.naver.com/research/" + read;
            }
            out.put(new JSONObject().put("title", title).put("broker", r.size() > 2 ? r.get(2).get(0) : "")
                    .put("date", date).put("pdf", pdf).put("read", read));
        }
        return out;
    }

    static final Pattern VIEW = Pattern.compile("(?is)<td[^>]*class=\"view_cnt\"[^>]*>(.*?)</td>");

    static String reportPageText(String readUrl) {
        try {
            String doc = decode(get(readUrl));
            Matcher m = VIEW.matcher(doc);
            String body = m.find() ? m.group(1) : doc;
            String t = clean(SCRIPT.matcher(body).replaceAll(" "));
            return t.length() > 12000 ? t.substring(0, 12000) : t;
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------ Claude API
    String callClaude(Object content, boolean search, int maxTokens) throws Exception {
        String key = prefs.getString("apiKey", "");
        if (key.isEmpty()) throw new UserErr("API 키가 없습니다. 오른쪽 위 '설정'에서 Claude API 키를 넣어 주세요.");
        JSONObject body = new JSONObject();
        body.put("model", prefs.getString("model", DEFAULT_MODEL));
        body.put("max_tokens", maxTokens);
        body.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));
        if (search) {
            body.put("tools", new JSONArray().put(new JSONObject().put("type", "web_search_20250305")
                    .put("name", "web_search").put("max_uses", 5)));
        }
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(360000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("x-api-key", key);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        c.setRequestProperty("content-type", "application/json");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        if (code != 200) {
            String msg = c.getErrorStream() != null ? new String(readAll(c.getErrorStream()), StandardCharsets.UTF_8) : "";
            try { msg = new JSONObject(msg).getJSONObject("error").optString("message", msg); } catch (Exception ignored) { }
            if (search && code == 400 && msg.toLowerCase(Locale.ROOT).contains("tool")) return callClaude(content, false, maxTokens);
            if (code == 401) throw new UserErr("API 키가 올바르지 않습니다. 설정에서 다시 확인해 주세요.");
            if (code == 429) throw new UserErr("요청 한도에 걸렸습니다. 잠시 후 다시 시도해 주세요.");
            if (msg.length() > 300) msg = msg.substring(0, 300);
            throw new UserErr("Claude API 오류 (" + code + "): " + msg);
        }
        JSONObject res = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
        JSONArray arr = res.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject b = arr.getJSONObject(i);
            if ("text".equals(b.optString("type"))) sb.append(b.optString("text"));
        }
        return sb.toString();
    }

    static final Pattern FENCE = Pattern.compile("(?s)```(?:json)?\\s*(.*?)```");

    static JSONObject parseJson(String t) throws UserErr {
        Matcher m = FENCE.matcher(t);
        if (m.find()) t = m.group(1);
        int i = t.indexOf('{'), j = t.lastIndexOf('}');
        if (i < 0 || j < i) throw new UserErr("AI 응답에서 결과를 읽지 못했습니다. 다시 시도해 주세요.");
        try {
            return new JSONObject(t.substring(i, j + 1));
        } catch (Exception e) {
            throw new UserErr("AI 응답 형식이 깨졌습니다. 다시 시도해 주세요.");
        }
    }

    JSONObject summarize(JSONObject r) throws Exception {
        String instr = "한국 증권사 애널리스트 리포트다 (" + r.optString("broker") + " / " + r.optString("date") + " / "
                + r.optString("title") + "). 감정 배제, 수치 중심으로 요약하라.\n"
                + "JSON 하나만 답하라: {\"opinion\":\"투자의견(없으면 null)\",\"target\":\"목표주가(없으면 null)\",\"summary\":\"핵심 2~3문장\","
                + "\"points\":[\"근거 수치·논리 3~5개\"],\"estimates\":\"실적 추정 변화(상향/하향/유지와 수치, 없으면 null)\","
                + "\"risks\":[\"리포트가 놓쳤을 수 있는 반대 논리 2개\"]}";
        String pdf = r.optString("pdf", "");
        if (!pdf.isEmpty()) {
            try {
                byte[] b = get(pdf);
                if (b.length > 1000 && b.length < 25 * 1024 * 1024 && b[0] == '%' && b[1] == 'P') {
                    JSONArray content = new JSONArray()
                            .put(new JSONObject().put("type", "document").put("source", new JSONObject()
                                    .put("type", "base64").put("media_type", "application/pdf")
                                    .put("data", Base64.encodeToString(b, Base64.NO_WRAP))))
                            .put(new JSONObject().put("type", "text").put("text", instr));
                    JSONObject out = parseJson(callClaude(content, false, 2000));
                    out.put("source", "pdf");
                    return out;
                }
            } catch (UserErr e) {
                if (e.getMessage().contains("API 키")) throw e;
            } catch (Exception ignored) { }
        }
        String t = reportPageText(r.optString("read", ""));
        if (t.isEmpty()) throw new UserErr("리포트 본문을 가져오지 못했습니다. 원문 링크로 확인해 주세요.");
        JSONObject out = parseJson(callClaude(instr + "\n\n본문(요약 페이지):\n" + t, false, 2000));
        out.put("source", "page");
        return out;
    }
}
