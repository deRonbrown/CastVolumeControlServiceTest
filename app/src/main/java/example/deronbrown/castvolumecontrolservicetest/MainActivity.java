package example.deronbrown.castvolumecontrolservicetest;

import android.content.ComponentName;
import android.os.RemoteException;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;

import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;

public class MainActivity extends AppCompatActivity {

    private MediaBrowserCompat mediaBrowser;
    private MediaBrowserConnectionCallback mediaBrowserConnectionCallback = new MediaBrowserConnectionCallback();
    private MediaControllerCompat mediaController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        NotificationsHelper.initChannels(this);

        // Initialize for MediaRouteButton
        CastContext.getSharedInstance(this);
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (mediaBrowser == null) {
            ComponentName componentName = new ComponentName(this, AudioService.class);
            mediaBrowser = new MediaBrowserCompat(this, componentName, mediaBrowserConnectionCallback, null);
            mediaBrowser.connect();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (mediaBrowser != null && mediaBrowser.isConnected()) {
            mediaBrowser.disconnect();
            mediaBrowser = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), menu, R.id.action_cast);
        return true;
    }

    public void playAudio(View view) {
        if (mediaController != null) {
            MediaControllerCompat.TransportControls transportControls = mediaController.getTransportControls();
            if (transportControls != null) {
                transportControls.play();
            }
        }
    }

    public void pauseAudio(View view) {
        if (mediaController != null) {
            MediaControllerCompat.TransportControls transportControls = mediaController.getTransportControls();
            if (transportControls != null) {
                transportControls.pause();
            }
        }
    }

    public void startAudio(View view) {
        if (mediaController != null) {
            MediaControllerCompat.TransportControls transportControls = mediaController.getTransportControls();
            if (transportControls != null) {
                transportControls.playFromMediaId("test", null);
            }
        }
    }

    private final class MediaBrowserConnectionCallback extends MediaBrowserCompat.ConnectionCallback {

        @Override
        public void onConnected() {
            super.onConnected();

            try {
                MediaSessionCompat.Token sessionToken = mediaBrowser.getSessionToken();
                mediaController = new MediaControllerCompat(MainActivity.this, sessionToken);
            } catch (RemoteException e) {
            }
        }
    }
}
