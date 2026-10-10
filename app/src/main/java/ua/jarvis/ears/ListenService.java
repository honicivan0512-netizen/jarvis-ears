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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern WAKE = Pattern.compile(
            "(джарв[іиi]с|джарвис|жарвіс|жарвис|чарвіс|jarvis)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean want = false;
    private boolean busy = false;
    private long awaitUntil = 0;
    private PowerManager.WakeLock wl;
    private String base = "";
    private String token = "";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String chan = "jarvis_ears";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(chan, "Джарвіс слухає", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, chan)
                .setContentTitle("Джарвіс слухає")
                .setContentText("Скажи: «Джарвіс, ...»")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
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
        muteBeeps(true);
        want = true;
        say("Слухаю. Скажи: «Джарвіс, ...»");
        main.post(this::startListening);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        want = false;
        main.removeCallbacksAndMessages(null);
        if (sr != null) { try { sr.destroy(); } catch (Exception e) { } sr = null; }
        if (tts != null) { try { tts.shutdown(); } catch (Exception e) { } tts = null; }
        if (wl != null && wl.isHeld()) wl.release();
        muteBeeps(false);
        super.onDestroy();
    }

    private void muteBeeps(boolean on) {
        if (!getSharedPreferences("j", MODE_PRIVATE).getBoolean("mute", false)) return;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int dir = on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0);
        } catch (Exception e) {
            // без дозволу «Не турбувати» система не дає вимкнути звуки, це не страшно
        }
    }

    private void startListening() {
        if (!want || busy) return;
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
            if (!want || busy) return;
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
            if (!busy) main.postDelayed(ListenService.this::startListening, 200);
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
        if (text.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now < awaitUntil) {
            awaitUntil = 0;
            ask(text);
            return;
        }
        Matcher m = WAKE.matcher(text);
        if (m.find()) {
            String rest = text.substring(m.end()).replaceFirst("^[\\s,.!?:;\\-—]+", "").trim();
            if (!rest.isEmpty()) {
                ask(rest);
            } else {
                beep();
                awaitUntil = now + 8000;
                say("Слухаю команду...");
            }
        } else {
            say("(почула, але не до мене: " + text + ")");
        }
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
        main.postDelayed(this::startListening, 300);
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
