package ua.jarvis.ears;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ListenService extends Service {
    public interface Sink { void show(String text); }

    public static volatile Sink sink;
    private static final StringBuilder LOG = new StringBuilder();

    public static synchronized void say(String line) {
        LOG.append(line).append("\n");
        if (LOG.length() > 6000) LOG.delete(0, LOG.length() - 5000);
        Sink s = sink;
        if (s != null) s.show(LOG.toString());
    }

    public static synchronized String logText() { return LOG.toString(); }

    private static final long IDLE_MS = 5 * 60 * 1000L;
    private static final String MODEL_NAME = "vosk-model-small-en-us-0.15";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final String GRAMMAR = "[\"jarvis\", \"jervis\", \"gervais\", \"[unk]\"]";
    private static final Pattern WAKE_EN = Pattern.compile("jarvis|jervis|gervais");
    private static final Pattern WAKE_UA = Pattern.compile(
            "^\\s*(джарв[іиi]с|джарвис|жарвіс|жарвис|чарвіс|jarvis)[\\s,.!?:;\\-—]*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STOP_WORDS = Pattern.compile(
            "^\\s*(відбій|відбой|досить слухати|вимкнись|до побачення|все дякую|стоп слухати)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean want = false;
    private boolean active = false;
    private boolean waking = false;
    private boolean busy = false;
    private PowerManager.WakeLock wl;
    private String base = "";
    private String token = "";

    private Model model;
    private SpeechService wakeSvc;
    private Recognizer wakeRec;

    private boolean silenced = false;
    private boolean silencedByFilter = false;
    private int prevFilter = NotificationManager.INTERRUPTION_FILTER_ALL;

    private final Runnable idleTask = () -> endSession("5 хвилин тиші: перестаю слухати. Скажи «Джарвіс», щоб розбудити.");

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification notif(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "jarvis_ears")
                .setContentTitle("Джарвіс")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void setNotif(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(1, notif(text));
        } catch (Exception e) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("jarvis_ears", "Джарвіс", NotificationManager.IMPORTANCE_LOW));
        Notification n = notif("Чекаю на слово «Джарвіс»");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            say("Не вдалося стати фоновою службою: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        base = getSharedPreferences("j", MODE_PRIVATE).getString("base", "");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        token = getSharedPreferences("j", MODE_PRIVATE).getString("token", "");
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (wl == null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:ears");
            wl.acquire();
        }
        if (tts == null) {
            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    int r = tts.setLanguage(new Locale("uk", "UA"));
                    if (r < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(new Locale("ru", "RU"));
                    tts.setPitch(0.6f);
                    tts.setSpeechRate(0.95f);
                    ttsReady = true;
                } else {
                    say("Голос (TTS) не запустився, відповіді будуть лише текстом.");
                }
            });
        }
        want = true;
        if (model == null) {
            prepareModel();
        } else if (!active) {
            main.post(this::startWake);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        want = false;
        main.removeCallbacksAndMessages(null);
        stopWake();
        if (sr != null) { try { sr.destroy(); } catch (Exception e) { } sr = null; }
        if (tts != null) { try { tts.shutdown(); } catch (Exception e) { } tts = null; }
        silenceOff();
        if (model != null) { try { model.close(); } catch (Exception e) { } model = null; }
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    // ---------- модель слова-будильника ----------
    private void prepareModel() {
        new Thread(() -> {
            try {
                File dir = new File(getFilesDir(), MODEL_NAME);
                if (!new File(dir, "conf").exists()) {
                    say("Завантажую голосову модель (40 МБ), це один раз. Потерпи...");
                    File zip = new File(getFilesDir(), "model.zip");
                    download(MODEL_URL, zip);
                    File tmp = new File(getFilesDir(), "unz");
                    deleteAll(tmp);
                    unzip(zip, tmp);
                    deleteAll(dir);
                    if (!new File(tmp, MODEL_NAME).renameTo(dir)) throw new Exception("не вдалося розпакувати модель");
                    deleteAll(tmp);
                    zip.delete();
                    say("Модель готова.");
                }
                Model m = new Model(dir.getAbsolutePath());
                model = m;
                main.post(this::startWake);
            } catch (Throwable e) {
                say("Не вдалося підготувати модель слова: " + e + ". Натисни «Запустити» ще раз.");
            }
        }).start();
    }

    private void deleteAll(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }

    private void download(String url, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (c.getResponseCode() != 200) throw new Exception("сервер моделі відповів " + c.getResponseCode());
        long total = c.getContentLengthLong();
        long got = 0, nextLog = 10L * 1024 * 1024;
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                got += n;
                if (got >= nextLog) {
                    say("  завантажено " + (got / 1048576) + " МБ" + (total > 0 ? " з " + (total / 1048576) : ""));
                    nextLog += 10L * 1024 * 1024;
                }
            }
        }
    }

    private void unzip(File zip, File dest) throws Exception {
        dest.mkdirs();
        String root = dest.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new java.io.FileInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                File f = new File(dest, e.getName());
                if (!f.getCanonicalPath().startsWith(root)) throw new Exception("поганий архів");
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
    }

    // ---------- режим «чекаю слово» (тихий, без писку) ----------
    private final org.vosk.android.RecognitionListener wakeListener = new org.vosk.android.RecognitionListener() {
        @Override public void onPartialResult(String h) { check(h); }
        @Override public void onResult(String h) { check(h); }
        @Override public void onFinalResult(String h) { check(h); }
        @Override public void onError(Exception e) {
            say("Помилка слуху: " + e.getMessage());
            main.postDelayed(ListenService.this::startWake, 4000);
        }
        @Override public void onTimeout() { }

        private void check(String h) {
            if (active || waking || h == null) return;
            if (WAKE_EN.matcher(h.toLowerCase()).find()) {
                waking = true;
                main.post(ListenService.this::beginSession);
            }
        }
    };

    private void startWake() {
        if (!want || model == null || active) return;
        stopWake();
        try {
            wakeRec = new Recognizer(model, 16000.0f, GRAMMAR);
            wakeSvc = new SpeechService(wakeRec, 16000.0f);
            wakeSvc.startListening(wakeListener);
            setNotif("Чекаю на слово «Джарвіс»");
            say("Чекаю на слово «Джарвіс».");
        } catch (Throwable e) {
            say("Не вдалося увімкнути слух: " + e + ". Пробую ще раз...");
            main.postDelayed(this::startWake, 5000);
        }
    }

    private void stopWake() {
        SpeechService s = wakeSvc;
        Recognizer r = wakeRec;
        wakeSvc = null;
        wakeRec = null;
        if (s != null) {
            try { s.stop(); } catch (Throwable e) { }
            try { s.shutdown(); } catch (Throwable e) { }
        }
        if (r != null) { try { r.close(); } catch (Throwable e) { } }
    }

    // ---------- сесія розмови ----------
    private void beginSession() {
        if (!want) return;
        stopWake();
        active = true;
        waking = false;
        busy = false;
        silenceOn();
        beep();
        setNotif("Слухаю тебе (вимкнусь після 5 хв тиші)");
        say("Слухаю тебе. Скажи команду. «Відбій», щоб завершити.");
        resetIdle();
        main.postDelayed(this::startListening, 400);
    }

    private void endSession(String why) {
        main.removeCallbacks(idleTask);
        active = false;
        waking = false;
        busy = false;
        if (sr != null) { try { sr.destroy(); } catch (Exception e) { } sr = null; }
        if (tts != null) { try { tts.stop(); } catch (Exception e) { } }
        silenceOff();
        say(why);
        main.postDelayed(this::startWake, 700);
    }

    private void resetIdle() {
        main.removeCallbacks(idleTask);
        main.postDelayed(idleTask, IDLE_MS);
    }

    private void silenceOn() {
        if (silenced) return;
        if (!getSharedPreferences("j", MODE_PRIVATE).getBoolean("mute", true)) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            if (nm.isNotificationPolicyAccessGranted()) {
                prevFilter = nm.getCurrentInterruptionFilter();
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
                silencedByFilter = true;
                silenced = true;
                return;
            }
        } catch (Exception e) { }
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0);
            silenced = true;
            silencedByFilter = false;
        } catch (Exception e) {
            say("(не вдалося вимкнути звуки сповіщень, дозволь «Не турбувати» у застосунку)");
        }
    }

    private void silenceOff() {
        if (!silenced) return;
        silenced = false;
        try {
            if (silencedByFilter) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                nm.setInterruptionFilter(prevFilter);
            } else {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0);
                am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
            }
        } catch (Exception e) { }
    }

    private void startListening() {
        if (!want || !active || busy) return;
        try {
            if (sr == null) {
                sr = SpeechRecognizer.createSpeechRecognizer(this);
                sr.setRecognitionListener(listener);
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uk-UA");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            sr.startListening(i);
        } catch (Exception e) {
            say("Помилка мікрофона: " + e.getMessage());
            resetRecognizer();
            main.postDelayed(this::startListening, 3000);
        }
    }

    private void resetRecognizer() {
        if (sr != null) { try { sr.destroy(); } catch (Exception e) { } sr = null; }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle b) { }
        @Override public void onEvent(int t, Bundle b) { }

        @Override
        public void onError(int error) {
            if (!want || !active || busy) return;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                say("Немає дозволу на мікрофон. Дозволь його в налаштуваннях застосунку.");
                stopSelf();
                return;
            }
            long delay = 300;
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                resetRecognizer();
                delay = 1000;
            } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || error == SpeechRecognizer.ERROR_SERVER) {
                delay = 4000;
            }
            main.postDelayed(ListenService.this::startListening, delay);
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) handleHeard(list.get(0));
            if (active && !busy) main.postDelayed(ListenService.this::startListening, 200);
        }
    };

    private void beep() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            main.postDelayed(tg::release, 400);
        } catch (Exception e) { }
    }

    private void handleHeard(String text) {
        text = text.trim();
        if (text.isEmpty() || !active) return;
        resetIdle();
        text = WAKE_UA.matcher(text).replaceFirst("").trim();
        if (text.isEmpty()) { beep(); return; }
        if (STOP_WORDS.matcher(text).matches()) {
            endSession("Добре, відбій. Скажи «Джарвіс», щоб розбудити.");
            return;
        }
        ask(text);
    }

    private void ask(final String text) {
        busy = true;
        if (sr != null) { try { sr.cancel(); } catch (Exception e) { } }
        say("Ти: " + text);
        new Thread(() -> {
            String reply;
            try {
                reply = post(text);
            } catch (Exception e) {
                reply = null;
                final String err = e.getMessage();
                main.post(() -> say("Не достукався до Джарвіса: " + err));
            }
            final String r = reply;
            main.post(() -> {
                if (r == null) { finishTurn(); return; }
                if (r.isEmpty()) { say("Джарвіс відповів порожньо."); finishTurn(); return; }
                say("Джарвіс: " + r);
                speak(r);
            });
        }).start();
    }

    private String post(String text) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/ask").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(8000);
        c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Token", token);
        byte[] body = new JSONObject().put("text", text).toString().getBytes("UTF-8");
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
        }
        if (code == 401) throw new Exception("неправильний токен, візьми новий командою /voice");
        if (code >= 400) {
            String e = sb.toString();
            try { e = new JSONObject(e).optString("error", e); } catch (Exception ignore) { }
            throw new Exception("помилка " + code + ": " + e);
        }
        return new JSONObject(sb.toString()).optString("reply", "").trim();
    }

    private void finishTurn() {
        busy = false;
        if (active) {
            resetIdle();
            main.postDelayed(this::startListening, 300);
        }
    }

    private void speak(String reply) {
        String t = reply.replaceAll("(?s)```.*?```", " ").replaceAll("[*_`#>]", "")
                .replaceAll("https?://\\S+", "посилання").replaceAll("\\s+", " ").trim();
        if (!ttsReady || t.isEmpty()) { finishTurn(); return; }
        List<String> chunks = new ArrayList<>();
        Matcher m = Pattern.compile("[^.!?]+[.!?]*").matcher(t);
        while (m.find()) {
            String s = m.group().trim();
            while (s.length() > 200) {
                int cut = s.lastIndexOf(' ', 200);
                if (cut < 50) cut = 200;
                chunks.add(s.substring(0, cut).trim());
                s = s.substring(cut).trim();
            }
            if (!s.isEmpty()) chunks.add(s);
        }
        if (chunks.isEmpty()) { finishTurn(); return; }
        final String lastId = "j" + (chunks.size() - 1);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { if (lastId.equals(id)) main.post(ListenService.this::finishTurn); }
            @Override public void onError(String id) { if (lastId.equals(id)) main.post(ListenService.this::finishTurn); }
        });
        for (int i = 0; i < chunks.size(); i++) {
            tts.speak(chunks.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "j" + i);
        }
    }
}
