package android.media;
public final class AudioAttributes {
    final int usage;
    public AudioAttributes(int usage) { this.usage=usage; }
    public int getUsage() { return usage; }
}
