
package com.example.calloraai;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

public class AIHelper {

    private static final String TAG = "CalloraAI";

    private TextToSpeech textToSpeech;
    private boolean ready = false;
    private Locale selectedLocale = Locale.forLanguageTag("en-IN");

    public AIHelper(Context context) {
        textToSpeech = new TextToSpeech(
                context.getApplicationContext(),
                status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        setLanguage("en-IN");
                        Log.i(TAG, "AI voice initialized");
                    } else {
                        ready = false;
                        Log.e(TAG, "Text-to-speech initialization failed");
                    }
                }
        );
    }

    public void setLanguage(String languageTag) {
        if (textToSpeech == null) {
            return;
        }

        Locale locale = Locale.forLanguageTag(languageTag);
        int result = textToSpeech.setLanguage(locale);

        if (result != TextToSpeech.LANG_MISSING_DATA
                && result != TextToSpeech.LANG_NOT_SUPPORTED) {
            selectedLocale = locale;
            ready = true;
            Log.i(TAG, "Voice language set to " + languageTag);
        } else {
            ready = false;
            Log.e(TAG, "Voice language unavailable: " + languageTag);
        }
    }

    public boolean isReady() {
        return ready;
    }

    public void speak(String message) {
        if (textToSpeech == null || !ready) {
            Log.w(TAG, "AI voice is not ready");
            return;
        }

        if (message == null || message.trim().isEmpty()) {
            return;
        }

        textToSpeech.setSpeechRate(1.0f);
        textToSpeech.setPitch(1.0f);

        textToSpeech.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "CALLORAAI_SPEECH"
        );
    }

    public void stop() {
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
    }

    public void release() {
        ready = false;

        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
    }
}
