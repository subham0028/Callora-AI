
package com.example.calloraai;

import android.Manifest;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CALL_SCREENING = 100;
    private static final int REQUEST_SPEECH = 200;
    private static final int REQUEST_MICROPHONE = 201;
    private static final int REQUEST_CALL_MICROPHONE = 202;

    private static final String DATABASE_URL =
            "https://calloraai-default-rtdb.firebaseio.com";

    private Button setupButton;
    private AIHelper aiHelper;

    private TextView screeningStatus;
    private TextView totalCallsText;
    private TextView spamCallsText;
    private TextView callStatusText;
    private EditText roomIdInput;

    private LinearLayout recentActivityContainer;

    private VoiceCallManager voiceCallManager;
    private FirebaseSignalingManager signalingManager;

    private boolean receiverRegistered = false;
    private boolean pendingCreateCall = false;
    private boolean pendingJoinCall = false;

    private String pendingRoomId;
    private String selectedLanguage = "en-IN";

    private final BroadcastReceiver callUpdateReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateCallStats();
                    updateRecentActivity();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        aiHelper = new AIHelper(this);
        SpamDatabaseSync.updateDatabase(this);

        View mainView = findViewById(R.id.main);

        ViewCompat.setOnApplyWindowInsetsListener(
                mainView, (view, insets) -> {

                    Insets bars = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars());

                    view.setPadding(
                            bars.left,
                            bars.top,
                            bars.right,
                            bars.bottom
                    );

                    return insets;
                });

        setupButton = findViewById(R.id.btnEnableScreening);
        Button btnTestAI = findViewById(R.id.btnTestAI);

        screeningStatus = findViewById(R.id.txtScreeningStatus);
        totalCallsText = findViewById(R.id.txtTotalCalls);
        spamCallsText = findViewById(R.id.txtSpamCalls);

        recentActivityContainer =
                findViewById(R.id.recentActivityContainer);

        roomIdInput = findViewById(R.id.editRoomId);
        callStatusText = findViewById(R.id.txtCallStatus);

        Button createCallButton = findViewById(R.id.btnCreateCall);
        Button joinCallButton = findViewById(R.id.btnJoinCall);
        Button endCallButton = findViewById(R.id.btnEndCall);

        setupButton.setOnClickListener(
                v -> setupCallScreening());

        btnTestAI.setOnClickListener(
                v -> showLanguageSelector());

        createCallButton.setOnClickListener(
                v -> requestCallMicrophone(true, null));

        joinCallButton.setOnClickListener(v -> {

            String roomId = roomIdInput.getText()
                    .toString()
                    .trim();

            if (roomId.isEmpty()) {
                roomIdInput.setError("Enter Room ID");
                return;
            }

            requestCallMicrophone(false, roomId);
        });

        endCallButton.setOnClickListener(
                v -> endVoiceCall());

        updateScreeningStatus();
        updateCallStats();
        updateRecentActivity();

        testFirebaseConnection();
    }

    // ---------------- FIREBASE CONNECTION ----------------

    private void testFirebaseConnection() {

        try {
            DatabaseReference reference = FirebaseDatabase
                    .getInstance(DATABASE_URL)
                    .getReference("connection_test");

            reference.setValue("CalloraAI Connected")
                    .addOnSuccessListener(unused ->
                            Toast.makeText(
                                    MainActivity.this,
                                    "Firebase Connected Successfully!",
                                    Toast.LENGTH_LONG
                            ).show()
                    )
                    .addOnFailureListener(error ->
                            Toast.makeText(
                                    MainActivity.this,
                                    "Firebase Error: " +
                                            error.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show()
                    );

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Firebase initialization failed: " +
                            e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // ---------------- WEBRTC CALLING ----------------

    private void requestCallMicrophone(
            boolean createCall,
            String roomId) {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            pendingCreateCall = createCall;
            pendingJoinCall = !createCall;
            pendingRoomId = roomId;

            requestPermissions(
                    new String[]{
                            Manifest.permission.RECORD_AUDIO
                    },
                    REQUEST_CALL_MICROPHONE
            );

            return;
        }

        if (createCall) {
            createVoiceCall();
        } else {
            joinVoiceCall(roomId);
        }
    }

    private void initializeCalling() {

        if (voiceCallManager != null &&
                signalingManager != null) {
            return;
        }

        voiceCallManager = new VoiceCallManager();

        boolean initialized =
                voiceCallManager.initialize(this);

        if (!initialized) {
            throw new IllegalStateException(
                    "WebRTC initialization failed");
        }

        boolean peerCreated =
                voiceCallManager.createPeerConnection();

        if (!peerCreated) {
            voiceCallManager.release();
            voiceCallManager = null;

            throw new IllegalStateException(
                    "PeerConnection creation failed");
        }

        signalingManager = new FirebaseSignalingManager();

        voiceCallManager.setSignalingListener(
                new VoiceCallManager.SignalingListener() {

                    @Override
                    public void onLocalDescription(
                            SessionDescription description) {

                        if (signalingManager != null) {
                            signalingManager.sendLocalDescription(
                                    description
                            );
                        }
                    }

                    @Override
                    public void onLocalIceCandidate(
                            IceCandidate candidate) {

                        if (signalingManager != null) {
                            signalingManager.sendLocalIceCandidate(
                                    candidate
                            );
                        }
                    }
                }
        );

        signalingManager.setCallback(
                new FirebaseSignalingManager.SignalingCallback() {

                    @Override
                    public void onRemoteDescription(
                            SessionDescription.Type type,
                            String sdp) {

                        if (voiceCallManager == null) {
                            return;
                        }

                        if (type == SessionDescription.Type.OFFER) {
                            voiceCallManager.setRemoteOffer(sdp);
                        } else if (
                                type == SessionDescription.Type.ANSWER) {
                            voiceCallManager.setRemoteAnswer(sdp);
                        }
                    }

                    @Override
                    public void onRemoteIceCandidate(
                            IceCandidate candidate) {

                        if (voiceCallManager != null) {
                            voiceCallManager.addRemoteIceCandidate(
                                    candidate.sdpMid,
                                    candidate.sdpMLineIndex,
                                    candidate.sdp
                            );
                        }
                    }

                    @Override
                    public void onError(String message) {

                        runOnUiThread(() -> {
                            callStatusText.setText(
                                    "Call Error: " + message);

                            Toast.makeText(
                                    MainActivity.this,
                                    message,
                                    Toast.LENGTH_LONG
                            ).show();
                        });
                    }
                }
        );
    }

    // Create a new call room
    private void createVoiceCall() {

        try {
            initializeCalling();

            String roomId = UUID.randomUUID()
                    .toString()
                    .substring(0, 8);

            roomIdInput.setText(roomId);

            signalingManager.createRoom(roomId);
            signalingManager.startListening();

            callStatusText.setText(
                    "Creating call... Room ID: " + roomId);

            voiceCallManager.createOffer();

            Toast.makeText(
                    this,
                    "Share this Room ID with the other phone: " +
                            roomId,
                    Toast.LENGTH_LONG
            ).show();

        } catch (Exception e) {
            endVoiceCall();

            callStatusText.setText(
                    "Unable to create call");

            Toast.makeText(
                    this,
                    e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // Join an existing call room
    private void joinVoiceCall(String roomId) {

        try {
            initializeCalling();

            signalingManager.joinRoom(roomId);
            signalingManager.startListening();

            callStatusText.setText(
                    "Joining Room: " + roomId);

            Toast.makeText(
                    this,
                    "Connecting to call room...",
                    Toast.LENGTH_SHORT
            ).show();

        } catch (Exception e) {
            endVoiceCall();

            callStatusText.setText(
                    "Unable to join call");

            Toast.makeText(
                    this,
                    e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // End call
    private void endVoiceCall() {

        if (signalingManager != null) {
            signalingManager.closeRoom();
            signalingManager = null;
        }

        if (voiceCallManager != null) {
            voiceCallManager.release();
            voiceCallManager = null;
        }

        callStatusText.setText("Call ended");

        Toast.makeText(
                this,
                "Call disconnected",
                Toast.LENGTH_SHORT
        ).show();
    }

    // ---------------- AI LANGUAGE ----------------

    private void showLanguageSelector() {

        new AlertDialog.Builder(this)
                .setTitle("Choose Speech Language")
                .setSingleChoiceItems(
                        new String[]{
                                "English (India)",
                                "Hindi"
                        },
                        selectedLanguage.equals("hi-IN") ? 1 : 0,
                        (dialog, which) -> {

                            selectedLanguage =
                                    (which == 0)
                                            ? "en-IN"
                                            : "hi-IN";

                            if (aiHelper != null) {
                                aiHelper.setLanguage(
                                        selectedLanguage);
                            }

                            dialog.dismiss();
                            checkMicrophonePermission();
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkMicrophonePermission() {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.RECORD_AUDIO
                    },
                    REQUEST_MICROPHONE
            );

        } else {
            startSpeechRecognition();
        }
    }

    private void startSpeechRecognition() {

        Intent intent = new Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                selectedLanguage);

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                selectedLanguage);

        intent.putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                selectedLanguage.equals("hi-IN")
                        ? "Callora AI se Hindi mein baat karein"
                        : "Speak to Callora AI in English");

        try {
            startActivityForResult(
                    intent,
                    REQUEST_SPEECH);

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Speech recognition unavailable",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults);

        if (requestCode == REQUEST_MICROPHONE) {

            if (grantResults.length > 0 &&
                    grantResults[0]
                            == PackageManager.PERMISSION_GRANTED) {

                startSpeechRecognition();

            } else {
                Toast.makeText(
                        this,
                        "Microphone permission is required",
                        Toast.LENGTH_SHORT
                ).show();
            }

        } else if (requestCode == REQUEST_CALL_MICROPHONE) {

            if (grantResults.length > 0 &&
                    grantResults[0]
                            == PackageManager.PERMISSION_GRANTED) {

                if (pendingCreateCall) {
                    createVoiceCall();

                } else if (pendingJoinCall) {
                    joinVoiceCall(pendingRoomId);
                }

            } else {
                Toast.makeText(
                        this,
                        "Microphone permission is required for calling",
                        Toast.LENGTH_LONG
                ).show();
            }

            pendingCreateCall = false;
            pendingJoinCall = false;
            pendingRoomId = null;
        }
    }

    // ---------------- CONVERSATION HISTORY ----------------

    private void saveConversation(
            String userMessage,
            String aiReply) {

        SharedPreferences prefs =
                getSharedPreferences(
                        "CalloraChat",
                        MODE_PRIVATE);

        String oldHistory =
                prefs.getString("history", "");

        String newEntry =
                "You: " + userMessage
                        + "\nCallora AI: " + aiReply
                        + "\n--------------------\n";

        prefs.edit()
                .putString(
                        "history",
                        oldHistory + newEntry)
                .apply();
    }

    private void showChatHistory() {

        SharedPreferences prefs =
                getSharedPreferences(
                        "CalloraChat",
                        MODE_PRIVATE);

        String history =
                prefs.getString("history", "");

        if (history.isEmpty()) {
            history = "No conversations saved yet.";
        }

        TextView historyView = new TextView(this);
        historyView.setText(history);
        historyView.setTextSize(16);
        historyView.setPadding(32, 24, 32, 24);

        new AlertDialog.Builder(this)
                .setTitle("Conversation History")
                .setView(historyView)
                .setPositiveButton("Close", null)
                .setNegativeButton(
                        "Clear History",
                        (dialog, which) -> {

                            new AlertDialog.Builder(this)
                                    .setTitle("Clear History?")
                                    .setMessage(
                                            "Delete all saved conversations?")
                                    .setPositiveButton(
                                            "Delete",
                                            (d, w) -> {

                                                prefs.edit()
                                                        .remove("history")
                                                        .apply();

                                                Toast.makeText(
                                                        this,
                                                        "History cleared",
                                                        Toast.LENGTH_SHORT
                                                ).show();
                                            })
                                    .setNegativeButton(
                                            "Cancel", null)
                                    .show();
                        })
                .show();
    }

    // ---------------- AI REPLY ----------------

    private String getAIReply(String message) {

        String text = message
                .toLowerCase(Locale.ROOT)
                .trim();

        if (text.contains("नमस्ते") ||
                text.contains("नमस्कार") ||
                text.contains("हेलो")) {

            return "नमस्ते! कृपया अपना नाम बताइए।";

        } else if (text.contains("मेरा नाम")) {

            return "आपसे मिलकर अच्छा लगा। आप किस काम से फोन कर रहे हैं?";

        } else if (text.contains("जरूरी") ||
                text.contains("आपातकाल")) {

            return "मैं समझता हूँ कि यह जरूरी है। मैं आपका संदेश यूजर तक पहुँचाऊँगा।";

        } else if (text.contains("धन्यवाद")) {

            return "आपका स्वागत है। आपका दिन शुभ हो.";
        }

        if (text.contains("hello") ||
                text.equals("hi") ||
                text.startsWith("hi ")) {

            return "Hello! May I know your name, please?";

        } else if (text.contains("my name is") ||
                text.contains("i am ") ||
                text.contains("this is ")) {

            return "Nice to meet you. May I know why you are calling?";

        } else if (text.contains("appointment") ||
                text.contains("meeting")) {

            return "Thank you. I will let the user know about your call.";

        } else if (text.contains("urgent") ||
                text.contains("emergency")) {

            return "I understand this is urgent. I will pass your message to the user.";

        } else if (text.contains("spam")) {

            return "I can help identify suspicious calls.";

        } else if (text.contains("your name") ||
                text.contains("who are you")) {

            return "My name is Callora AI, your call assistant.";

        } else if (text.contains("how are you")) {

            return "I am fine. Thank you for asking.";

        } else if (text.contains("bye")) {

            return "Thank you for the conversation. Goodbye!";
        }

        if (selectedLanguage.equals("hi-IN")) {
            return "जानकारी देने के लिए धन्यवाद। मैं आपका संदेश यूजर तक पहुँचाऊँगा।";
        }

        return "Thank you for the information. I will pass your message to the user.";
    }

    private void showConversation(
            String spokenText,
            String reply) {

        new AlertDialog.Builder(this)
                .setTitle("Callora AI Conversation")
                .setMessage(
                        "You said:\n" + spokenText
                                + "\n\nCallora AI:\n" + reply)
                .setPositiveButton(
                        "Speak Again",
                        (dialog, which) ->
                                startSpeechRecognition())
                .setNeutralButton(
                        "View History",
                        (dialog, which) ->
                                showChatHistory())
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------------- CALL SCREENING ----------------

    private void setupCallScreening() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {

            Toast.makeText(
                    this,
                    "Android 10 or above is required",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        RoleManager roleManager =
                getSystemService(RoleManager.class);

        if (roleManager == null ||
                !roleManager.isRoleAvailable(
                        RoleManager.ROLE_CALL_SCREENING)) {

            Toast.makeText(
                    this,
                    "Call screening is not available",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        if (roleManager.isRoleHeld(
                RoleManager.ROLE_CALL_SCREENING)) {

            updateScreeningStatus();

            Toast.makeText(
                    this,
                    "Call screening is already enabled",
                    Toast.LENGTH_SHORT
            ).show();

            return;
        }

        Intent intent =
                roleManager.createRequestRoleIntent(
                        RoleManager.ROLE_CALL_SCREENING);

        startActivityForResult(
                intent,
                REQUEST_CALL_SCREENING);
    }

    private void updateScreeningStatus() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {

            screeningStatus.setText(
                    "Android 10 or above is required");

            return;
        }

        RoleManager roleManager =
                getSystemService(RoleManager.class);

        boolean enabled =
                roleManager != null &&
                        roleManager.isRoleHeld(
                                RoleManager.ROLE_CALL_SCREENING);

        if (enabled) {

            screeningStatus.setText(
                    "Call screening is enabled");

            setupButton.setText(
                    "Call Screening Enabled");

        } else {

            screeningStatus.setText(
                    "Call screening is not configured");

            setupButton.setText(
                    "Set Up Call Screening");
        }
    }

    // ---------------- CALL STATISTICS ----------------

    private void updateCallStats() {

        totalCallsText.setText(
                String.valueOf(
                        CallHistoryManager.getTotalCalls(this)));

        spamCallsText.setText(
                String.valueOf(
                        CallHistoryManager.getSpamCalls(this)));
    }

    // ---------------- RECENT ACTIVITY ----------------

    private void updateRecentActivity() {

        if (recentActivityContainer == null) {
            return;
        }

        recentActivityContainer.removeAllViews();

        JSONArray history =
                CallHistoryManager.getHistory(this);

        if (history.length() == 0) {

            TextView emptyView = new TextView(this);

            emptyView.setText(
                    "☎\nNo calls yet\n"
                            + "Your recent call activity will appear here.");

            emptyView.setTextColor(
                    getColor(R.color.text_secondary));

            emptyView.setTextSize(15);
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setPadding(24, 32, 24, 32);

            recentActivityContainer.addView(emptyView);

            return;
        }

        for (int i = 0; i < history.length(); i++) {

            JSONObject record =
                    history.optJSONObject(i);

            if (record == null) {
                continue;
            }

            String number =
                    record.optString("number", "Unknown");

            String status =
                    record.optString("status", "NOT FLAGGED");

            long time =
                    record.optLong("time", 0);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(8, 12, 8, 12);

            TextView numberView = new TextView(this);
            numberView.setText(number);

            numberView.setTextColor(
                    getColor(R.color.text_primary));

            numberView.setTextSize(16);

            numberView.setTypeface(
                    null,
                    android.graphics.Typeface.BOLD);

            TextView statusView = new TextView(this);

            if ("SPAM".equalsIgnoreCase(status)) {

                statusView.setText("🛡 Spam detected");

                statusView.setTextColor(
                        getColor(android.R.color.holo_red_dark));

            } else if ("UNKNOWN".equalsIgnoreCase(status)) {

                statusView.setText(
                        "❔ Unknown caller - Unverified");

                statusView.setTextColor(
                        getColor(android.R.color.holo_orange_dark));

            } else {

                statusView.setText(
                        "✓ Not flagged as spam");

                statusView.setTextColor(
                        getColor(R.color.dark_green));
            }

            statusView.setTextSize(13);

            TextView timeView = new TextView(this);

            timeView.setText(
                    new SimpleDateFormat(
                            "dd MMM, hh:mm a",
                            Locale.getDefault())
                            .format(new Date(time)));

            timeView.setTextColor(
                    getColor(R.color.text_secondary));

            timeView.setTextSize(12);

            item.addView(numberView);
            item.addView(statusView);
            item.addView(timeView);

            recentActivityContainer.addView(item);

            View divider = new View(this);
            divider.setBackgroundColor(
                    getColor(R.color.background));

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1);

            recentActivityContainer.addView(
                    divider,
                    params);
        }
    }

    // ---------------- ACTIVITY LIFECYCLE ----------------

    @Override
    protected void onStart() {

        super.onStart();

        if (!receiverRegistered) {

            IntentFilter filter = new IntentFilter(
                    CallHistoryManager.ACTION_HISTORY_UPDATED);

            ContextCompat.registerReceiver(
                    this,
                    callUpdateReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);

            receiverRegistered = true;
        }

        updateScreeningStatus();
        updateCallStats();
        updateRecentActivity();
    }

    @Override
    protected void onStop() {

        if (receiverRegistered) {
            unregisterReceiver(callUpdateReceiver);
            receiverRegistered = false;
        }

        super.onStop();
    }

    @Override
    protected void onResume() {

        super.onResume();

        updateScreeningStatus();
        updateCallStats();
        updateRecentActivity();
    }

    // ---------------- ACTIVITY RESULT ----------------

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data);

        if (requestCode == REQUEST_SPEECH &&
                resultCode == RESULT_OK &&
                data != null) {

            ArrayList<String> results =
                    data.getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS);

            if (results != null && !results.isEmpty()) {

                String spokenText = results.get(0);

                String reply =
                        getAIReply(spokenText);

                saveConversation(
                        spokenText,
                        reply);

                showConversation(
                        spokenText,
                        reply);

                if (aiHelper != null) {
                    aiHelper.speak(reply);
                }
            }

            return;
        }

        if (requestCode == REQUEST_CALL_SCREENING) {

            updateScreeningStatus();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                RoleManager roleManager =
                        getSystemService(RoleManager.class);

                boolean enabled =
                        roleManager != null &&
                                roleManager.isRoleHeld(
                                        RoleManager.ROLE_CALL_SCREENING);

                Toast.makeText(
                        this,
                        enabled
                                ? "Call screening enabled!"
                                : "Call screening was not enabled",
                        Toast.LENGTH_SHORT
                ).show();
            }
        }
    }

    // ---------------- DESTROY ----------------

    @Override
    protected void onDestroy() {

        if (signalingManager != null) {
            signalingManager.closeRoom();
            signalingManager = null;
        }

        if (voiceCallManager != null) {
            voiceCallManager.release();
            voiceCallManager = null;
        }

        if (aiHelper != null) {
            aiHelper.release();
        }

        super.onDestroy();
    }
}
