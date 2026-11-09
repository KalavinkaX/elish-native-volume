package android.media;
import java.util.*;
public final class PlayerProxy {
    public final List<VolumeShaper.Configuration> configurations=new ArrayList<VolumeShaper.Configuration>();
    public final List<VolumeShaper.Operation> operations=new ArrayList<VolumeShaper.Operation>();
    public void applyVolumeShaper(VolumeShaper.Configuration c,VolumeShaper.Operation o) {
        configurations.add(c);operations.add(o);
    }
    // Deliberately no setVolume: tests must use an independent shaper.
}
