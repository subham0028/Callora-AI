
package com.example.calloraai;

import android.util.Log;

import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.HashMap;
import java.util.Map;

public class FirebaseSignalingManager {

    private static final String TAG = "FirebaseSignaling";

    private final DatabaseReference database;

    private DatabaseReference roomRef;
    private ChildEventListener candidateListener;
    private ValueEventListener descriptionListener;

    private String roomId;
    private boolean isCaller;
    private boolean listening = false;

    public interface SignalingCallback {

        void onRemoteDescription(
                SessionDescription.Type type,
                String sdp
        );

        void onRemoteIceCandidate(IceCandidate candidate);

        void onError(String message);
    }

    private SignalingCallback callback;

    public FirebaseSignalingManager() {

        database = FirebaseDatabase
                .getInstance(
                        "https://calloraai-default-rtdb.firebaseio.com"
                )
                .getReference("calls");
    }

    public void setCallback(SignalingCallback callback) {
        this.callback = callback;
    }

    // Caller creates a room
    public void createRoom(String roomId) {

        this.roomId = roomId;
        this.isCaller = true;

        roomRef = database.child(roomId);

        Map<String, Object> roomData = new HashMap<>();

        roomData.put("createdAt",
                System.currentTimeMillis());

        roomData.put("status", "waiting");

        roomRef.setValue(roomData)
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "Room created: " + roomId);
                })
                .addOnFailureListener(error -> {
                    notifyError(error.getMessage());
                });
    }

    // Callee joins an existing room
    public void joinRoom(String roomId) {

        this.roomId = roomId;
        this.isCaller = false;

        roomRef = database.child(roomId);

        roomRef.get()
                .addOnSuccessListener(snapshot -> {

                    if (!snapshot.exists()) {
                        notifyError("Room not found");
                        return;
                    }

                    roomRef.child("status")
                            .setValue("connected");

                    Log.d(TAG, "Joined room: " + roomId);

                })
                .addOnFailureListener(error -> {
                    notifyError(error.getMessage());
                });
    }

    // Send local Offer or Answer
    public void sendLocalDescription(
            SessionDescription description) {

        if (roomRef == null) {
            notifyError("Room is not initialized");
            return;
        }

        String key;

        if (description.type == SessionDescription.Type.OFFER) {
            key = "offer";
        } else {
            key = "answer";
        }

        Map<String, Object> data = new HashMap<>();

        data.put("type", description.type.canonicalForm());
        data.put("sdp", description.description);

        roomRef.child(key)
                .setValue(data)
                .addOnSuccessListener(unused ->
                        Log.d(TAG, key + " uploaded")
                )
                .addOnFailureListener(error ->
                        notifyError(error.getMessage())
                );
    }

    // Send local ICE Candidate
    public void sendLocalIceCandidate(
            IceCandidate candidate) {

        if (roomRef == null) {
            return;
        }

        String key = isCaller
                ? "callerCandidates"
                : "calleeCandidates";

        Map<String, Object> data = new HashMap<>();

        data.put("sdpMid", candidate.sdpMid);
        data.put("sdpMLineIndex", candidate.sdpMLineIndex);
        data.put("candidate", candidate.sdp);

        roomRef.child(key)
                .push()
                .setValue(data)
                .addOnFailureListener(error ->
                        notifyError(error.getMessage())
                );
    }

    // Listen for remote Offer/Answer and ICE Candidates
    public void startListening() {

        if (roomRef == null || listening) {
            return;
        }

        listening = true;

        String remoteDescriptionKey = isCaller
                ? "answer"
                : "offer";

        descriptionListener = new ValueEventListener() {

            @Override
            public void onDataChange(DataSnapshot snapshot) {

                if (!snapshot.exists()) {
                    return;
                }

                String sdp = snapshot.child("sdp")
                        .getValue(String.class);

                String type = snapshot.child("type")
                        .getValue(String.class);

                if (sdp == null || type == null) {
                    return;
                }

                SessionDescription.Type sessionType =
                        type.equals("offer")
                                ? SessionDescription.Type.OFFER
                                : SessionDescription.Type.ANSWER;

                if (callback != null) {
                    callback.onRemoteDescription(
                            sessionType,
                            sdp
                    );
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                notifyError(error.getMessage());
            }
        };

        roomRef.child(remoteDescriptionKey)
                .addValueEventListener(descriptionListener);

        String remoteCandidateKey = isCaller
                ? "calleeCandidates"
                : "callerCandidates";

        candidateListener = new ChildEventListener() {

            @Override
            public void onChildAdded(
                    DataSnapshot snapshot,
                    String previousChildName) {

                String sdpMid = snapshot.child("sdpMid")
                        .getValue(String.class);

                Integer index = snapshot.child("sdpMLineIndex")
                        .getValue(Integer.class);

                String candidate = snapshot.child("candidate")
                        .getValue(String.class);

                if (sdpMid == null ||
                        index == null ||
                        candidate == null) {
                    return;
                }

                IceCandidate iceCandidate = new IceCandidate(
                        sdpMid,
                        index,
                        candidate
                );

                if (callback != null) {
                    callback.onRemoteIceCandidate(iceCandidate);
                }
            }

            @Override
            public void onChildChanged(
                    DataSnapshot snapshot,
                    String previousChildName) {
            }

            @Override
            public void onChildRemoved(DataSnapshot snapshot) {
            }

            @Override
            public void onChildMoved(
                    DataSnapshot snapshot,
                    String previousChildName) {
            }

            @Override
            public void onCancelled(DatabaseError error) {
                notifyError(error.getMessage());
            }
        };

        roomRef.child(remoteCandidateKey)
                .addChildEventListener(candidateListener);

        Log.d(TAG, "Firebase listeners started");
    }

    // Remove listeners
    public void stopListening() {

        if (roomRef == null) {
            return;
        }

        if (descriptionListener != null) {
            roomRef.child(isCaller ? "answer" : "offer")
                    .removeEventListener(descriptionListener);

            descriptionListener = null;
        }

        if (candidateListener != null) {

            String key = isCaller
                    ? "calleeCandidates"
                    : "callerCandidates";

            roomRef.child(key)
                    .removeEventListener(candidateListener);

            candidateListener = null;
        }

        listening = false;
    }

    // Delete room
    public void closeRoom() {

        stopListening();

        if (roomRef != null) {
            roomRef.removeValue()
                    .addOnSuccessListener(unused ->
                            Log.d(TAG, "Room deleted")
                    )
                    .addOnFailureListener(error ->
                            notifyError(error.getMessage())
                    );

            roomRef = null;
        }
    }

    private void notifyError(String message) {

        Log.e(TAG, message);

        if (callback != null) {
            callback.onError(message);
        }
    }
}
