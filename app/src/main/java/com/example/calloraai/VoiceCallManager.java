
package com.example.calloraai;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.PeerConnection;
import org.webrtc.RtpReceiver;
import org.webrtc.MediaStream;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;
import org.webrtc.SdpObserver;

public class VoiceCallManager {

    private static final String TAG = "CalloraAI";

    private PeerConnectionFactory factory;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private PeerConnection peerConnection;

    private boolean initialized = false;
    private boolean remoteDescriptionSet = false;

    private final List<IceCandidate> pendingCandidates =
            new ArrayList<>();

    // Signaling callbacks
    public interface SignalingListener {

        void onLocalDescription(SessionDescription description);

        void onLocalIceCandidate(IceCandidate candidate);
    }

    private SignalingListener signalingListener;

    public void setSignalingListener(SignalingListener listener) {
        signalingListener = listener;
    }

    // Initialize WebRTC
    public boolean initialize(Context context) {

        if (initialized) {
            return true;
        }

        try {
            PeerConnectionFactory.InitializationOptions options =
                    PeerConnectionFactory.InitializationOptions
                            .builder(context.getApplicationContext())
                            .createInitializationOptions();

            PeerConnectionFactory.initialize(options);

            factory = PeerConnectionFactory.builder()
                    .createPeerConnectionFactory();

            MediaConstraints constraints = new MediaConstraints();

            audioSource = factory.createAudioSource(constraints);

            localAudioTrack = factory.createAudioTrack(
                    "callora_audio_track",
                    audioSource
            );

            localAudioTrack.setEnabled(true);

            initialized = true;

            Log.d(TAG, "WebRTC initialized");

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Initialization failed", e);
            release();
            return false;
        }
    }

    // Get local audio track
    public AudioTrack getLocalAudioTrack() {
        return localAudioTrack;
    }

    // Check initialization
    public boolean isInitialized() {
        return initialized;
    }

    // Create PeerConnection
    public boolean createPeerConnection() {

        if (!initialized || factory == null ||
                localAudioTrack == null) {
            return false;
        }

        if (peerConnection != null) {
            return true;
        }

        try {
            PeerConnection.RTCConfiguration configuration =
                    new PeerConnection.RTCConfiguration(
                            Collections.singletonList(
                                    PeerConnection.IceServer
                                            .builder(
                                                    "stun:stun.l.google.com:19302"
                                            )
                                            .createIceServer()
                            )
                    );

            peerConnection = factory.createPeerConnection(
                    configuration,
                    new PeerConnection.Observer() {

                        @Override
                        public void onSignalingChange(
                                PeerConnection.SignalingState state) {
                            Log.d(TAG, "Signaling: " + state);
                        }

                        @Override
                        public void onIceConnectionChange(
                                PeerConnection.IceConnectionState state) {
                            Log.d(TAG, "ICE: " + state);
                        }

                        @Override
                        public void onIceConnectionReceivingChange(
                                boolean receiving) {
                        }

                        @Override
                        public void onIceGatheringChange(
                                PeerConnection.IceGatheringState state) {
                        }

                        @Override
                        public void onIceCandidate(
                                IceCandidate candidate) {

                            if (signalingListener != null) {
                                signalingListener.onLocalIceCandidate(
                                        candidate
                                );
                            }
                        }

                        @Override
                        public void onIceCandidatesRemoved(
                                IceCandidate[] candidates) {
                        }

                        @Override
                        public void onAddStream(MediaStream stream) {
                        }

                        @Override
                        public void onRemoveStream(MediaStream stream) {
                        }

                        @Override
                        public void onDataChannel(
                                org.webrtc.DataChannel dataChannel) {
                        }

                        @Override
                        public void onRenegotiationNeeded() {
                        }

                        @Override
                        public void onAddTrack(
                                RtpReceiver receiver,
                                MediaStream[] streams) {
                        }
                    }
            );

            if (peerConnection == null) {
                return false;
            }

            peerConnection.addTrack(
                    localAudioTrack,
                    Collections.singletonList("callora_stream")
            );

            Log.d(TAG, "PeerConnection created");

            return true;

        } catch (Exception e) {
            Log.e(TAG, "PeerConnection creation failed", e);
            return false;
        }
    }

    // Create Offer
    public void createOffer() {

        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection unavailable");
            return;
        }

        MediaConstraints constraints = new MediaConstraints();

        peerConnection.createOffer(new SdpObserver() {

            @Override
            public void onCreateSuccess(SessionDescription description) {

                peerConnection.setLocalDescription(
                        new SimpleSdpObserver() {

                            @Override
                            public void onSetSuccess() {

                                Log.d(TAG, "Local Offer created");

                                if (signalingListener != null) {
                                    signalingListener.onLocalDescription(
                                            description
                                    );
                                }
                            }

                        },
                        description
                );
            }

            @Override
            public void onCreateFailure(String error) {
                Log.e(TAG, "Offer failed: " + error);
            }

            @Override
            public void onSetSuccess() {
            }

            @Override
            public void onSetFailure(String error) {
            }

        }, constraints);
    }

    // Receive incoming Offer and create Answer
    public void setRemoteOffer(String sdp) {

        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection unavailable");
            return;
        }

        SessionDescription offer = new SessionDescription(
                SessionDescription.Type.OFFER,
                sdp
        );

        peerConnection.setRemoteDescription(
                new SimpleSdpObserver() {

                    @Override
                    public void onSetSuccess() {

                        remoteDescriptionSet = true;
                        flushPendingCandidates();

                        createAnswer();
                    }

                },
                offer
        );
    }

    // Create Answer
    public void createAnswer() {

        if (peerConnection == null) {
            return;
        }

        MediaConstraints constraints = new MediaConstraints();

        peerConnection.createAnswer(new SdpObserver() {

            @Override
            public void onCreateSuccess(SessionDescription description) {

                peerConnection.setLocalDescription(
                        new SimpleSdpObserver() {

                            @Override
                            public void onSetSuccess() {

                                Log.d(TAG, "Local Answer created");

                                if (signalingListener != null) {
                                    signalingListener.onLocalDescription(
                                            description
                                    );
                                }
                            }

                        },
                        description
                );
            }

            @Override
            public void onCreateFailure(String error) {
                Log.e(TAG, "Answer failed: " + error);
            }

            @Override
            public void onSetSuccess() {
            }

            @Override
            public void onSetFailure(String error) {
            }

        }, constraints);
    }

    // Receive remote Answer
    public void setRemoteAnswer(String sdp) {

        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection unavailable");
            return;
        }

        SessionDescription answer = new SessionDescription(
                SessionDescription.Type.ANSWER,
                sdp
        );

        peerConnection.setRemoteDescription(
                new SimpleSdpObserver() {

                    @Override
                    public void onSetSuccess() {

                        remoteDescriptionSet = true;
                        flushPendingCandidates();

                        Log.d(TAG, "Remote Answer set");
                    }

                },
                answer
        );
    }

    // Receive remote ICE candidate
    public void addRemoteIceCandidate(
            String sdpMid,
            int sdpMLineIndex,
            String candidate
    ) {

        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection unavailable");
            return;
        }

        IceCandidate iceCandidate = new IceCandidate(
                sdpMid,
                sdpMLineIndex,
                candidate
        );

        if (!remoteDescriptionSet) {
            pendingCandidates.add(iceCandidate);
            return;
        }

        peerConnection.addIceCandidate(iceCandidate);
    }

    // Add candidates received before remote SDP
    private void flushPendingCandidates() {

        if (peerConnection == null) {
            return;
        }

        for (IceCandidate candidate : pendingCandidates) {
            peerConnection.addIceCandidate(candidate);
        }

        pendingCandidates.clear();
    }

    // Common SDP observer
    private static class SimpleSdpObserver implements SdpObserver {

        @Override
        public void onCreateSuccess(SessionDescription description) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String error) {
            Log.e(TAG, "SDP creation failed: " + error);
        }

        @Override
        public void onSetFailure(String error) {
            Log.e(TAG, "SDP setting failed: " + error);
        }
    }

    // Release resources
    public void release() {

        initialized = false;
        remoteDescriptionSet = false;

        pendingCandidates.clear();

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
            peerConnection = null;
        }

        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(false);
            localAudioTrack.dispose();
            localAudioTrack = null;
        }

        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }

        if (factory != null) {
            factory.dispose();
            factory = null;
        }
    }
}
