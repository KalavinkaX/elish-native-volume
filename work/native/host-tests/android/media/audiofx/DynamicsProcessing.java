package android.media.audiofx;
/** Host test double only. Never included in the Android DEX. */
public final class DynamicsProcessing {
    public static boolean controlled=true,enabledAllowed=true,mismatch=false;
    public boolean released,enabled;
    public float gain,gainWhenEnabled;
    public static DynamicsProcessing last;
    public DynamicsProcessing(int priority,int session,Config config){
        if(session<=0)throw new IllegalArgumentException("session zero");last=this;gain=config.gain;
    }
    public boolean hasControl(){return controlled;}
    public int setEnabled(boolean enabled){gainWhenEnabled=gain;this.enabled=enabled&&enabledAllowed;return enabledAllowed?0:-1;}
    public boolean getEnabled(){return enabled;}
    public void release(){released=true;}
    public void setInputGainAllChannelsTo(float gain){this.gain=gain;}
    public float getInputGainByChannelIndex(int channel){return mismatch?999:gain;}
    public static class Config {
        float gain;
        public static class Builder {
            float gain;
            public Builder(int variant,int channels,boolean eq,int eqBands,boolean mbc,int mbcBands,boolean post,int postBands,boolean limiter){}
            public Builder setInputGainAllChannelsTo(float value){gain=value;return this;}
            public Config build(){Config c=new Config();c.gain=gain;return c;}
        }
    }
}
