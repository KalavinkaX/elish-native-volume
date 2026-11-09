package android.media;
import java.util.*;
import android.os.IBinder;
public interface IAudioService {
    List<AudioPlaybackConfiguration> getActivePlaybackConfigurations();
    public static class Stub {
        public static IAudioService asInterface(IBinder binder) {
            return new IAudioService() { public List<AudioPlaybackConfiguration> getActivePlaybackConfigurations() {
                return AudioPlaybackConfiguration.items;
            }};
        }
    }
}
