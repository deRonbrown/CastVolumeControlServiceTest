package example.deronbrown.castvolumecontrolservicetest;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.ArrayList;
import java.util.List;

public class NotificationsHelper {

    public static final int AUDIO_NOTIFICATION_ID = 1001;
    public static final String AUDIO_NOTIFICATION_CHANNEL_ID = "audio_playback";
    private static boolean hasCreatedChannel = false;

    public static NotificationCompat.Builder getAudioNotificationBuilder(@NonNull Context context,
                                                                         @NonNull MediaSessionCompat.Token token,
                                                                         boolean isPlaying,
                                                                         @NonNull MediaDescriptionCompat description) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, AUDIO_NOTIFICATION_CHANNEL_ID);
        builder
                .setContentTitle(description.getTitle())
                .setContentText(description.getSubtitle())
                .setSubText(description.getDescription())
                .setSmallIcon(R.mipmap.ic_launcher)
                .setColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))
                .setColorized(false)
                .setDeleteIntent(
                        MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_STOP))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent meditationPendingIntent = PendingIntent.getActivity(context, 1000, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        builder.setContentIntent(meditationPendingIntent);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            builder.setLargeIcon(description.getIconBitmap());
        }

        builder.setStyle(new android.support.v4.media.app.NotificationCompat.MediaStyle(builder)
                .setMediaSession(token)
                .setShowActionsInCompactView(0)
                // For backwards compatibility with Android L and earlier.
                .setShowCancelButton(true)
                .setCancelButtonIntent(
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                                context,
                                PlaybackStateCompat.ACTION_STOP)));

        if (isPlaying) {
            builder.addAction(new NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY_PAUSE)));
        } else {
            builder.addAction(new NotificationCompat.Action(
                    android.R.drawable.ic_media_play,
                    "Play",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY_PAUSE)));
        }
        builder.setSmallIcon(R.mipmap.ic_launcher);

        return builder;
    }

    public static void initChannels(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !hasCreatedChannel) {
            List<NotificationChannel> channels = new ArrayList<>();

            NotificationChannel audioChannel = new NotificationChannel(
                    AUDIO_NOTIFICATION_CHANNEL_ID,
                    "Audio",
                    NotificationManager.IMPORTANCE_LOW);
            audioChannel.setShowBadge(false);
            audioChannel.enableLights(false);
            audioChannel.enableVibration(false);
            audioChannel.setSound(null, null);
            audioChannel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            channels.add(audioChannel);

            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannels(channels);

            hasCreatedChannel = true;
        }
    }
}
