package example.deronbrown.castvolumecontrolservicetest;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.ext.cast.CastPlayer;
import com.google.android.exoplayer2.ext.mediasession.DefaultPlaybackController;
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class AudioService extends MediaBrowserServiceCompat
        implements CastPlayer.SessionAvailabilityListener, AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = AudioService.class.getSimpleName();
    private static final String EMPTY_MEDIA_ROOT_ID = "empty_root_id";

    private AudioManager audioManager;
    private EventListener eventListener;
    private SimpleExoPlayer exoPlayer;
    private CastPlayer castPlayer;
    private MediaSessionCompat mediaSessionCompat;
    private MediaSessionConnector mediaSessionConnector;

    private Player currentPlayer;
    private boolean castMediaQueueCreationPending;
    private int currentItemIndex = C.INDEX_UNSET;
    private int currentWindowIndex;

    private MediaSource mainMediaSource;

    private boolean userStopped;
    private boolean serviceInStartedState;

    @Override
    public void onCreate() {
        super.onCreate();

        audioManager = (AudioManager) getApplicationContext().getSystemService(AUDIO_SERVICE);

        eventListener = new EventListener();

        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(new AdaptiveTrackSelection.Factory(bandwidthMeter));
        exoPlayer = ExoPlayerFactory.newSimpleInstance(renderersFactory, trackSelector);

        // Initialize player
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .build();
        exoPlayer.setAudioAttributes(audioAttributes);
        exoPlayer.addListener(eventListener);

        // Initialize cast player
        castPlayer = new CastPlayer(CastContext.getSharedInstance(this));

        // Initialize media session
        ComponentName mediaButtonReceiver = new ComponentName(getApplicationContext(), MediaButtonReceiver.class);
        mediaSessionCompat = new MediaSessionCompat(getApplicationContext(), TAG, mediaButtonReceiver, null);
        setSessionToken(mediaSessionCompat.getSessionToken());

        mediaSessionConnector = new MediaSessionConnector(mediaSessionCompat, new PlaybackController(), false, null);
        mediaSessionConnector.setPlayer(exoPlayer, new PlaybackPreparer());

        setCurrentPlayer(castPlayer.isCastSessionAvailable() ? castPlayer : exoPlayer);
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        return new BrowserRoot(EMPTY_MEDIA_ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(null);
    }

    @Override
    public void onCastSessionAvailable() {
        setCurrentPlayer(castPlayer);
    }

    @Override
    public void onCastSessionUnavailable() {
        setCurrentPlayer(exoPlayer);
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        // TODO
    }

    private void setCurrentPlayer(Player newPlayer) {
        if (currentPlayer == newPlayer) {
            return;
        }

        mediaSessionConnector.setPlayer(null, null);

        // Player state management.
        long playbackPositionMs = C.TIME_UNSET;
        int windowIndex = C.INDEX_UNSET;
        boolean playWhenReady = false;
        if (currentPlayer != null) {
            int playbackState = currentPlayer.getPlaybackState();
            if (playbackState != Player.STATE_ENDED) {
                playbackPositionMs = currentPlayer.getCurrentPosition();
                playWhenReady = currentPlayer.getPlayWhenReady();
                windowIndex = currentPlayer.getCurrentWindowIndex();
                if (windowIndex != currentItemIndex) {
                    playbackPositionMs = C.TIME_UNSET;
                    windowIndex = currentItemIndex;
                }
            }
            currentPlayer.stop(true);
        }

        currentPlayer = newPlayer;
        mediaSessionConnector.setPlayer(newPlayer, new PlaybackPreparer());

        // Media queue management.
        castMediaQueueCreationPending = newPlayer == castPlayer;
        if (newPlayer == exoPlayer && mainMediaSource != null) {
            exoPlayer.prepare(mainMediaSource);
        }

        // Playback transition.
        if (windowIndex != C.INDEX_UNSET) {
            setCurrentItem(windowIndex, playbackPositionMs, playWhenReady);
        }
    }

    private void updateCurrentItemIndex() {
        int playbackState = currentPlayer.getPlaybackState();
        maybeSetCurrentItem(
                playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED
                        ? currentPlayer.getCurrentWindowIndex() : C.INDEX_UNSET);
    }

    private void setCurrentItem(int itemIndex, long positionMs, boolean playWhenReady) {
        maybeSetCurrentItem(itemIndex);
        if (castMediaQueueCreationPending) {
            castMediaQueueCreationPending = false;

            MediaInfo info = new MediaInfo.Builder("https://html5demos.com/assets/dizzy.mp4")
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(MimeTypes.VIDEO_MP4)
                    .setMetadata(convertMetadata())
                    .build();
            castPlayer.loadItem(new MediaQueueItem.Builder(info).build(), positionMs);
            setMediaSessionActive(false);
        } else {
            currentPlayer.seekTo(itemIndex, positionMs);
            currentPlayer.setPlayWhenReady(playWhenReady);
            setMediaSessionActive(true);
        }
    }

    private MediaMetadata convertMetadata() {
        MediaMetadataCompat from = getMediaSessionMetadata();
        MediaMetadata data = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        data.putString(MediaMetadata.KEY_TITLE, from.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
        data.putString(MediaMetadata.KEY_ARTIST, from.getString(MediaMetadataCompat.METADATA_KEY_ARTIST));
        data.putString(MediaMetadata.KEY_SUBTITLE, from.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE));
        return data;
    }

    private void maybeSetCurrentItem(int currentItemIndex) {
        if (this.currentItemIndex != currentItemIndex) {
            this.currentItemIndex = currentItemIndex;
        }
    }

    private MediaMetadataCompat getMediaSessionMetadata() {
        return new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "test-id")
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, TimeUnit.MINUTES.toMillis(5))
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Test Title")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Test Artist")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Test Title")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Test Artist")
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, 1)
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, 1).build();
    }

    private void moveServiceToStartedState() {
        NotificationCompat.Builder builder = NotificationsHelper.getAudioNotificationBuilder(this, getSessionToken(), true, getMediaSessionMetadata().getDescription());
        Notification notification = builder.build();

        if (!serviceInStartedState) {
            ContextCompat.startForegroundService(
                    AudioService.this,
                    new Intent(AudioService.this, AudioService.class));
            serviceInStartedState = true;
        }

        startForeground(NotificationsHelper.AUDIO_NOTIFICATION_ID, notification);
    }

    private void updateNotificationForPause() {
        if (serviceInStartedState) {
            stopForeground(false);
            NotificationCompat.Builder builder = NotificationsHelper.getAudioNotificationBuilder(this, getSessionToken(), false, getMediaSessionMetadata().getDescription());
            Notification notification = builder.build();

            NotificationManagerCompat.from(this).notify(NotificationsHelper.AUDIO_NOTIFICATION_ID, notification);
        }
    }

    private void moveServiceOutOfStartedState() {
        stopForeground(true);
        stopSelf();
        serviceInStartedState = false;
    }

    private void prepareMediaSource() {
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            setCurrentPlayer(castPlayer.isCastSessionAvailable() ? castPlayer : exoPlayer);
            setupCastListeners();

            DefaultHttpDataSourceFactory dataSourceFactory = new DefaultHttpDataSourceFactory("test-agent");
            mainMediaSource = new ExtractorMediaSource.Factory(dataSourceFactory).createMediaSource(Uri.parse("https://html5demos.com/assets/dizzy.mp4"));
            if (currentPlayer == exoPlayer) {
                exoPlayer.prepare(mainMediaSource);
                setMediaSessionActive(true);
            } else {
                castMediaQueueCreationPending = true;
                setCurrentItem(C.INDEX_UNSET, 0, exoPlayer.getPlayWhenReady());
                setMediaSessionActive(false);
            }
        }
    }

    private void setupCastListeners() {
        castPlayer.addListener(eventListener);
        castPlayer.setSessionAvailabilityListener(this);
    }

    // An active MediaSession will handle volume controls. When CastPlayer is playing,
    // we can allow receiver volume control by setting the MediaSession to inactive
    private void setMediaSessionActive(boolean active) {
        if (mediaSessionCompat.isActive() != active) {
            mediaSessionCompat.setActive(active);
        }
    }

    private class PlaybackPreparer implements MediaSessionConnector.PlaybackPreparer {

        @Override
        public long getSupportedPrepareActions() {
            return PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;
        }

        @Override
        public void onPrepare() {
        }

        @Override
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
            mediaSessionCompat.setMetadata(getMediaSessionMetadata());
            prepareMediaSource();
        }

        @Override
        public void onPrepareFromSearch(String query, Bundle extras) {
        }

        @Override
        public void onPrepareFromUri(Uri uri, Bundle extras) {
        }

        @Override
        public String[] getCommands() {
            return new String[0];
        }

        @Override
        public void onCommand(Player player, String command, Bundle extras, ResultReceiver cb) {
        }
    }

    private class PlaybackController extends DefaultPlaybackController {

        @Override
        public void onStop(Player player) {
            userStopped = true;
            exoPlayer.setPlayWhenReady(false);
            super.onStop(player);
        }
    }

    private class EventListener extends Player.DefaultEventListener {

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            updateCurrentItemIndex();
            switch (playbackState) {
                case Player.STATE_IDLE:
                    if (userStopped) {
                        moveServiceOutOfStartedState();
                        userStopped = false;
                    }

                    if (currentPlayer == castPlayer) {
                        SessionManager sessionManager = CastContext.getSharedInstance().getSessionManager();
                        CastSession castSession = sessionManager.getCurrentCastSession();
                        if (castSession != null) {
                            RemoteMediaClient remoteMediaClient = castSession.getRemoteMediaClient();
                            if (remoteMediaClient != null) {
                                moveServiceOutOfStartedState();
                            }
                        }
                    }
                    break;
                case Player.STATE_READY:
                    if (playWhenReady) {
                        moveServiceToStartedState();
                    } else {
                        updateNotificationForPause();
                    }
                    break;
                case Player.STATE_ENDED:
                    moveServiceOutOfStartedState();
                    break;
            }
        }

        @Override
        public void onPositionDiscontinuity(@Player.DiscontinuityReason int reason) {
            updateCurrentItemIndex();
            if (currentWindowIndex != currentPlayer.getCurrentWindowIndex()) {
                currentWindowIndex = currentPlayer.getCurrentWindowIndex();
            }
        }

        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest, @Player.TimelineChangeReason int reason) {
            currentWindowIndex = currentPlayer.getCurrentWindowIndex();
            updateCurrentItemIndex();
            if (timeline.isEmpty()) {
                castMediaQueueCreationPending = true;
            }
        }
    }
}
